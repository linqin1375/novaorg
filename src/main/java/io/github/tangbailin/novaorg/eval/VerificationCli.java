package io.github.tangbailin.novaorg.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tangbailin.novaorg.knowledge.*;
import io.github.tangbailin.novaorg.llm.*;
import io.github.tangbailin.novaorg.metrics.CostModel;
import io.github.tangbailin.novaorg.org.ToolCatalog;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Headless explicit opt-in evaluation: no web server, accounts or production ledger. */
public final class VerificationCli {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] arguments) throws Exception {
        var args=new HashMap<String,String>();
        for(String item:arguments) {
            if(!item.startsWith("--")) throw new IllegalArgumentException("Use --name=value");
            var pair=item.substring(2).split("=",2);args.put(pair[0],pair.length==2?pair[1]:"true");
        }
        String mode=args.getOrDefault("mode","offline");
        if(!Set.of("offline","live","retrieval").contains(mode)) throw new IllegalArgumentException("mode must be offline, live or retrieval");
        boolean live=mode.equals("live"),retrievalOnly=mode.equals("retrieval"),semantic="api".equals(env("NOVAORG_EMBEDDING_MODE","local"));
        if((live || semantic) && !args.containsKey("allow-network"))
            throw new IllegalArgumentException("Network evaluation requires explicit --allow-network");
        int maxCases=number(args,"max-cases",5,1,50),trials=number(args,"trials",1,1,5);
        var limit=new Budget(live?realModel():new DemoLlmPort(),
                number(args,"max-requests",30,1,200),number(args,"max-tokens",50000,1000,500000));
        var provider=semantic?new OpenAiCompatEmbeddingModel(
                required("NOVAORG_EMBEDDING_BASE_URL"),env("NOVAORG_EMBEDDING_API_KEY",""),required("NOVAORG_EMBEDDING_MODEL"),
                Duration.ofSeconds(30),Integer.parseInt(env("NOVAORG_EMBEDDING_DIMENSIONS","1024"))):null;
        EmbeddingPort embedding=semantic?new EmbeddingBudget(provider):new SemanticEmbeddingModel();
        if(retrievalOnly && !semantic) throw new IllegalArgumentException("Retrieval-only provider acceptance requires NOVAORG_EMBEDDING_MODE=api");
        if(live && !limit.isLive()) throw new IllegalStateException("Live evaluation cannot run the rule backend");
        if(live && !semantic && !args.containsKey("allow-local-features"))
            throw new IllegalArgumentException("Live RAG verification requires learned embedding, or explicitly mark --allow-local-features");
        Path output=Path.of(args.getOrDefault("output","reports/"+mode+"-"+java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())));
        if(Files.exists(output)) throw new IllegalArgumentException("Output directory already exists; choose a new run ID");
        Files.createDirectories(output);
        var started=Instant.now();
        var runner=retrievalOnly?null:new EvalRunner(null,new ToolCatalog(),new ClasspathKnowledgeBase(embedding),limit,CostModel.defaultModel());
        String casesPath=args.getOrDefault("cases",live?"eval/agent-live-cases.json":"eval/cases.json");
        var source=retrievalOnly?List.<EvalCase>of():runner.loadCases(casesPath);
        if(args.containsKey("case-ids")) {
            var requested=Set.of(args.get("case-ids").split(","));
            source=source.stream().filter(c->requested.contains(c.id())).toList();
            if(source.size()!=requested.size()) throw new IllegalArgumentException("Unknown or duplicate case IDs");
        }
        var cases=source.subList(0,Math.min(maxCases,source.size()));
        boolean failed=false;
        for(int trial=1;!retrievalOnly && trial<=trials;trial++) {
            var report=runner.run(cases);
            runner.write(report,output.resolve("agent-trial-"+trial+".json"),output.resolve("agent-trial-"+trial+".md"));
            failed|=report.permissionViolations()>0 || report.passed()<report.total();
            System.out.println("Trial "+trial+": "+report.passed()+"/"+report.total()+", backend="+report.llm());
        }
        if(retrievalOnly || args.containsKey("retrieval")) {
            retrieval(embedding,"eval/retrieval-cases.json",output.resolve("retrieval-dev.json"));
            retrieval(embedding,"eval/retrieval-holdout.json",output.resolve("retrieval-holdout.json"));
        }
        var manifest=new LinkedHashMap<String,Object>();
        manifest.put("startedAt",started.toString());manifest.put("finishedAt",Instant.now().toString());
        manifest.put("mode",mode);manifest.put("llm",retrievalOnly?"NOT_CALLED: retrieval-only":limit.id());manifest.put("embedding",embedding.id());manifest.put("learnedEmbedding",embedding.isSemantic());
        manifest.put("dataset",retrievalOnly?List.of("eval/retrieval-cases.json","eval/retrieval-holdout.json"):casesPath);
        manifest.put("casesHash",retrievalOnly?Map.of("dev",hashResource("eval/retrieval-cases.json"),"holdout",hashResource("eval/retrieval-holdout.json")):hashResource(casesPath));
        manifest.put("knowledgeHash",knowledgeHash());manifest.put("promptHash",hashResourceOrFile("src/main/java/io/github/tangbailin/novaorg/llm/RolePromptFactory.java"));
        manifest.put("trials",trials);manifest.put("casesPerTrial",cases.size());manifest.put("modelRequests",limit.requests);
        manifest.put("caseIds",cases.stream().map(EvalCase::id).toList());
        manifest.put("inputTokens",limit.input);manifest.put("outputTokens",limit.output);manifest.put("usageEstimated",limit.estimated);
        manifest.put("requestLimit",limit.maxRequests);manifest.put("tokenLimit",limit.maxTokens);
        if(provider!=null) manifest.put("embeddingUsage",provider.usageStatistics());
        manifest.put("embeddingLimits",Map.of("maxUniqueTexts",200,"maxCharacters",200000));
        manifest.put("strictTools",env("NOVAORG_LLM_STRICT_TOOLS","false"));
        manifest.put("retrievalConfig",Map.of("rrfK",60,"rerankWeight",0,"candidateDepth",20));
        manifest.put("semanticAnswerSupport","NOT_EVALUATED: inspect references and business outcome; human review required");
        manifest.put("billing","Provider usage is recorded when available; configured cost estimates are not invoices");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("manifest.json").toFile(),manifest);
        System.out.println("Reports: "+output.toAbsolutePath());
        if(failed) System.exit(2);
    }
    private static LlmPort realModel() {
        return new OpenAiCompatLlmPort(required("NOVAORG_LLM_BASE_URL"),required("NOVAORG_LLM_API_KEY"),required("NOVAORG_LLM_MODEL"),
                Duration.ofSeconds(45),1,Boolean.parseBoolean(env("NOVAORG_LLM_STRICT_TOOLS","false")),
                Boolean.parseBoolean(env("NOVAORG_LLM_THINKING","false")));
    }
    private static void retrieval(EmbeddingPort embedding,String dataset,Path output) throws Exception {
        var cases=new RetrievalEvalRunner(new ClasspathKnowledgeBase(new HashingEmbeddingModel())).loadCases(dataset);
        var reports=new LinkedHashMap<String,Object>();
        for(var channels:List.of(EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL),
                EnumSet.of(InMemoryHybridIndex.Channel.DENSE),
                EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL,InMemoryHybridIndex.Channel.CONCEPT),
                EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL,InMemoryHybridIndex.Channel.DENSE),
                EnumSet.allOf(InMemoryHybridIndex.Channel.class))) {
            var port=channels.equals(EnumSet.of(InMemoryHybridIndex.Channel.LEXICAL))?new HashingEmbeddingModel():embedding;
            var kb=new ClasspathKnowledgeBase(port,ClasspathKnowledgeBase.DEFAULT_LOCATION,60,20,0,channels);
            reports.put(channels.toString(),new RetrievalEvalRunner(kb).run(cases));
        }
        var rerank=new ClasspathKnowledgeBase(embedding,ClasspathKnowledgeBase.DEFAULT_LOCATION,60,20,0.6,
                EnumSet.allOf(InMemoryHybridIndex.Channel.class));
        reports.put("all+rerank-weight-0.6",new RetrievalEvalRunner(rerank).run(cases));
        reports.put("metricDefinition","Hit@k: any expected document appears in first k distinct documents; legacy field names recallAtK");
        reports.put("split",dataset.contains("holdout")?"held out from tuning; AI-authored; human review pending":"development; previously used for weight tuning");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),reports);
    }
    private static String env(String key,String fallback) { var value=System.getenv(key);return value==null||value.isBlank()?fallback:value; }
    private static String required(String key) { var value=env(key,"");if(value.isBlank()) throw new IllegalArgumentException("Missing "+key);return value; }
    private static int number(Map<String,String> args,String key,int fallback,int min,int max) {
        int value=Integer.parseInt(args.getOrDefault(key,String.valueOf(fallback)));
        if(value<min||value>max) throw new IllegalArgumentException("Out of range: "+key);return value;
    }
    private static String hashResource(String path) throws Exception {
        try(var in=new org.springframework.core.io.ClassPathResource(path).getInputStream()) { return sha(in.readAllBytes()); }
    }
    private static String hashResourceOrFile(String path) throws Exception {
        return Files.exists(Path.of(path))?sha(Files.readAllBytes(Path.of(path))):"source-unavailable";
    }
    private static String knowledgeHash() throws Exception {
        var resources=new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources(ClasspathKnowledgeBase.DEFAULT_LOCATION);
        Arrays.sort(resources,Comparator.comparing(org.springframework.core.io.Resource::getFilename));
        var bytes=new java.io.ByteArrayOutputStream();
        for(var resource:resources) try(var in=resource.getInputStream()) { bytes.write(in.readAllBytes()); }
        return sha(bytes.toByteArray());
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static final class Budget implements LlmPort {
        private final LlmPort delegate;private final int maxRequests,maxTokens;
        private int requests,input,output;private boolean estimated;
        Budget(LlmPort delegate,int requests,int tokens) { this.delegate=delegate;maxRequests=requests;maxTokens=tokens;estimated=!delegate.isLive(); }
        @Override public String id() { return delegate.id(); }
        @Override public boolean isLive() { return delegate.isLive(); }
        @Override public LlmResponse complete(LlmRequest request) {
            if(requests>=maxRequests || input+output>=maxTokens) throw new IllegalStateException("Evaluation model budget exhausted");
            requests++;
            var r=delegate.complete(request.withMaxOutputTokens(Math.min(request.maxOutputTokens(),maxTokens-input-output)));
            input+=r.inputTokens();output+=r.outputTokens();estimated|=r.usageEstimated();return r;
        }
    }
    private static final class EmbeddingBudget implements EmbeddingPort {
        private final EmbeddingPort delegate;private final Set<String> texts=new HashSet<>();private long characters;
        EmbeddingBudget(EmbeddingPort port) { delegate=port; }
        @Override public String id() { return delegate.id(); }
        @Override public int dimensions() { return delegate.dimensions(); }
        @Override public boolean isSemantic() { return true; }
        @Override public float[] embed(String text) { return embedAll(List.of(text)).get(0); }
        @Override public List<float[]> embedAll(List<String> input) {
            for(String text:input) if(texts.add(text)) characters+=text.length();
            if(texts.size()>200 || characters>200000) throw new IllegalStateException("Embedding evaluation budget exhausted");
            return delegate.embedAll(input);
        }
    }
}
