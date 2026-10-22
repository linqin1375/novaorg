package io.github.tangbailin.novaorg.llm;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Native tool messages are preserved; protocol errors never fall back to the rule model. */
public final class OpenAiCompatLlmPort implements LlmPort {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl,apiKey,model;
    private final Duration timeout;
    private final int maxAttempts;
    private final boolean strictTools,thinking;
    public OpenAiCompatLlmPort(String url,String key,String model,Duration timeout,int attempts) {
        this(url,key,model,timeout,attempts,false,false);
    }
    public OpenAiCompatLlmPort(String url,String key,String model,Duration timeout,int attempts,boolean strict,boolean thinking) {
        baseUrl=url.replaceAll("/+$","");apiKey=key;this.model=model;this.timeout=timeout;
        maxAttempts=Math.max(1,Math.min(3,attempts));strictTools=strict;this.thinking=thinking;
        if(strict && "api.deepseek.com".equals(URI.create(url).getHost()) && !baseUrl.endsWith("/beta"))
            throw new IllegalArgumentException("DeepSeek strict tools require /beta");
    }
    @Override public boolean isLive() { return true; }
    @Override public String id() { return "openai-compatible:"+model+"@"+URI.create(baseUrl).getHost(); }
    @Override public LlmResponse complete(LlmRequest request) {
        long start=System.nanoTime();RuntimeException last=null;
        for(int attempt=1;attempt<=maxAttempts;attempt++) {
            try {
                var r=http.send(buildRequest(request),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if(r.statusCode()==429 || r.statusCode()>=500) throw new IllegalStateException("Retryable model HTTP "+r.statusCode());
                if(r.statusCode()>=400) throw new IllegalArgumentException("Model HTTP "+r.statusCode()+"; check endpoint/model/schema");
                return parse(r.body(),(System.nanoTime()-start)/1_000_000);
            } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("Model interrupted");
            } catch(IllegalArgumentException e) { throw e;
            } catch(IOException | RuntimeException e) { last=new IllegalStateException("Model call failed: "+e.getClass().getSimpleName(),e); }
            if(attempt<maxAttempts) try { Thread.sleep(Math.min(2000,200L*attempt)); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("Model interrupted"); }
        }
        throw last==null?new IllegalStateException("Model failed"):last;
    }
    HttpRequest buildRequest(LlmRequest request) {
        var root=MAPPER.createObjectNode().put("model",model).put("max_tokens",request.maxOutputTokens());
        if(!thinking) root.put("temperature",request.temperature());
        if("api.deepseek.com".equals(URI.create(baseUrl).getHost())) root.putObject("thinking").put("type",thinking?"enabled":"disabled");
        var messages=root.putArray("messages");
        if(request.systemPrompt()!=null && !request.systemPrompt().isBlank())
            messages.addObject().put("role","system").put("content",request.systemPrompt());
        var history=request.messages().isEmpty()?List.of(LlmMessage.user(request.userPrompt())):request.messages();
        for(var item:history) {
            var m=messages.addObject().put("role",item.role()).put("content",item.content()==null?"":item.content());
            if(item.reasoningContent()!=null) m.put("reasoning_content",item.reasoningContent());
            if(item.toolCallId()!=null) m.put("tool_call_id",item.toolCallId());
            if(!item.toolCalls().isEmpty()) {
                var calls=m.putArray("tool_calls");
                for(var call:item.toolCalls()) calls.addObject().put("id",call.id()).put("type","function")
                        .putObject("function").put("name",call.name().replace(".","__")).put("arguments",call.arguments());
            }
        }
        if(!request.tools().isEmpty()) {
            var tools=root.putArray("tools");
            for(String schema:request.tools()) try {
                ObjectNode tool=(ObjectNode)MAPPER.readTree(schema);
                ((ObjectNode)tool.get("function")).put("name",tool.path("function").path("name").asText().replace(".","__"));
                if(strictTools) { ((ObjectNode)tool.get("function")).put("strict",true);strictSchema((ObjectNode)tool.get("function").get("parameters")); }
                tools.add(tool);
            } catch(IOException e) { throw new IllegalArgumentException("Invalid tool schema",e); }
            root.put("tool_choice","auto");
        }
        var b=HttpRequest.newBuilder(URI.create(baseUrl+"/chat/completions")).timeout(timeout)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(root.toString(),StandardCharsets.UTF_8));
        if(apiKey!=null && !apiKey.isBlank()) b.header("Authorization","Bearer "+apiKey);
        return b.build();
    }
    private static void strictSchema(ObjectNode schema) {
        if(schema.path("properties").isObject()) {
            var required=new HashSet<String>();schema.path("required").forEach(v->required.add(v.asText()));
            var props=(ObjectNode)schema.get("properties");var all=MAPPER.createArrayNode();
            var names=new ArrayList<String>();props.fieldNames().forEachRemaining(names::add);
            for(String name:names) {
                var child=(ObjectNode)props.get(name);strictSchema(child);all.add(name);
                if(!required.contains(name)) {
                    var optional=MAPPER.createObjectNode();var any=optional.putArray("anyOf");any.add(child);any.addObject().put("type","null");props.set(name,optional);
                }
            }
            schema.set("required",all);schema.put("additionalProperties",false);
        }
        if(schema.path("items").isObject()) strictSchema((ObjectNode)schema.get("items"));
    }
    private LlmResponse parse(String body,long ms) throws IOException {
        var root=MAPPER.readTree(body);
        if(!root.path("choices").isArray() || root.path("choices").isEmpty()) throw new IllegalArgumentException("Model returned no choices");
        var choice=root.path("choices").get(0);var message=choice.path("message");
        var calls=new ArrayList<LlmToolCall>();
        if(message.path("tool_calls").isArray()) for(var call:message.path("tool_calls"))
            calls.add(new LlmToolCall(call.path("id").asText(""),call.path("function").path("name").asText("").replace("__","."),
                    call.path("function").path("arguments").asText("")));
        String text=message.path("content").asText("");
        if(text.isBlank() && calls.isEmpty()) throw new IllegalArgumentException("Empty model answer");
        boolean estimated=!root.path("usage").has("prompt_tokens") || !root.path("usage").has("completion_tokens");
        return new LlmResponse(text,root.path("usage").path("prompt_tokens").asInt(0),
                root.path("usage").path("completion_tokens").asInt(DemoLlmPort.estimateTokens(text)),ms,id(),calls,
                choice.path("finish_reason").asText("stop"),message.hasNonNull("reasoning_content")?message.get("reasoning_content").asText():null,estimated);
    }
    /** Legacy offline formatter only; never used to execute live model responses. */
    @Deprecated static String normalizeToolCalls(JsonNode calls) {
        if(calls==null || !calls.isArray()) return "";
        StringBuilder out=new StringBuilder();
        for(var c:calls) {
            String name=c.path("function").path("name").asText("");if(name.isBlank()) continue;
            var n=MAPPER.createObjectNode().put("name",name);
            try { String raw=c.path("function").path("arguments").asText("{}");n.set("arguments",MAPPER.readTree(raw.isBlank()?"{}":raw)); }
            catch(IOException e) { n.set("arguments",MAPPER.createObjectNode()); }
            out.append("\nTOOL_CALL ").append(n);
        }
        return out.toString();
    }
}
