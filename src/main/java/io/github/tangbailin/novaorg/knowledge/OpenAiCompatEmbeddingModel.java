package io.github.tangbailin.novaorg.knowledge;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Learned embeddings from a provider or local compatible server; no hash-vector fallback. */
public final class OpenAiCompatEmbeddingModel implements EmbeddingPort {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int BATCH=16;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl,key,model;
    private final Duration timeout;
    private final int dimensions;
    private final AtomicLong requests=new AtomicLong(),inputTexts=new AtomicLong(),inputCharacters=new AtomicLong(),inputTokens=new AtomicLong(),missingUsage=new AtomicLong();
    private final Map<String,float[]> cache=Collections.synchronizedMap(new LinkedHashMap<>(128,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,float[]> entry) { return size()>2048; }
    });
    public OpenAiCompatEmbeddingModel(String url,String key,String model,Duration timeout,int dimensions) {
        if(dimensions<=0) throw new IllegalArgumentException("Embedding dimensions must be positive");
        baseUrl=url.replaceAll("/+$","");this.key=key;this.model=model;this.timeout=timeout;this.dimensions=dimensions;
    }
    @Override public String id() { return "learned-embedding:"+model+"@"+URI.create(baseUrl).getHost()+"/"+dimensions+"d"; }
    @Override public boolean isSemantic() { return true; }
    @Override public int dimensions() { return dimensions; }
    public Map<String,Object> usageStatistics() {
        return Map.of("httpRequests",requests.get(),"inputTexts",inputTexts.get(),"inputCharacters",inputCharacters.get(),
                "inputTokens",inputTokens.get(),"usageComplete",missingUsage.get()==0);
    }
    @Override public float[] embed(String text) { return embedAll(List.of(text==null?"":text)).get(0); }
    @Override public List<float[]> embedAll(List<String> texts) {
        var normalized=texts.stream().map(t->t==null?"":t).toList();
        var pending=new ArrayList<>(new LinkedHashSet<>(normalized.stream().filter(t->!cache.containsKey(t)).toList()));
        // Keep this call's results separately; eviction must not turn a large batch into zero vectors.
        var current=new HashMap<String,float[]>();
        for(String text:normalized) { var value=cache.get(text);if(value!=null) current.put(text,value); }
        for(int i=0;i<pending.size();i+=BATCH) {
            var batch=pending.subList(i,Math.min(i+BATCH,pending.size()));
            var values=fetch(batch);current.putAll(values);cache.putAll(values);
        }
        return normalized.stream().map(t->current.get(t).clone()).toList();
    }
    private Map<String,float[]> fetch(List<String> batch) {
        var root=JSON.createObjectNode().put("model",model).put("dimensions",dimensions);var input=root.putArray("input");batch.forEach(input::add);
        var builder=HttpRequest.newBuilder(URI.create(baseUrl+"/embeddings")).timeout(timeout)
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(),StandardCharsets.UTF_8));
        if(key!=null && !key.isBlank()) builder.header("Authorization","Bearer "+key);
        try {
            requests.incrementAndGet();inputTexts.addAndGet(batch.size());inputCharacters.addAndGet(batch.stream().mapToLong(String::length).sum());
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()>=400) throw new IllegalStateException("Embedding HTTP "+response.statusCode()+"; no local fallback");
            var parsed=JSON.readTree(response.body());var usage=parsed.path("usage");
            var tokens=usage.has("prompt_tokens")?usage.path("prompt_tokens"):usage.path("total_tokens");
            if(tokens.isIntegralNumber() && tokens.asLong()>=0) inputTokens.addAndGet(tokens.asLong());
            else missingUsage.incrementAndGet();
            var data=parsed.path("data");
            if(!data.isArray() || data.size()!=batch.size()) throw new IllegalStateException("Embedding response count mismatch");
            var results=new HashMap<String,float[]>();var indexes=new HashSet<Integer>();
            for(var item:data) {
                int index=item.path("index").asInt(-1);
                if(index<0 || index>=batch.size() || !indexes.add(index)) throw new IllegalStateException("Invalid embedding response index");
                var raw=item.path("embedding");
                if(!raw.isArray() || raw.size()!=dimensions) throw new IllegalStateException("Embedding dimension mismatch; expected "+dimensions);
                float[] vector=new float[dimensions];double norm=0;
                for(int j=0;j<dimensions;j++) {
                    if(!raw.get(j).isNumber()) throw new IllegalStateException("Non-numeric embedding value");
                    double value=raw.get(j).asDouble();
                    if(!Double.isFinite(value) || !Float.isFinite((float)value)) throw new IllegalStateException("Non-finite embedding value");
                    vector[j]=(float)value;norm+=value*value;
                }
                if(norm==0) throw new IllegalStateException("Zero embedding vector");
                results.put(batch.get(index),HashingEmbeddingModel.normalize(vector));
            }
            return results;
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("Embedding interrupted");
        } catch(java.io.IOException e) { throw new IllegalStateException("Embedding transport or JSON error",e); }
    }
}
