package io.github.tangbailin.novaorg.llm;

import java.util.List;

/** Structured provider output. Estimated usage must not be presented as an API invoice. */
public record LlmResponse(String text, int inputTokens, int outputTokens, long latencyMs,
                          String provider, List<LlmToolCall> toolCalls, String finishReason,
                          String reasoningContent, boolean usageEstimated) {
    public LlmResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
    public LlmResponse(String text,int input,int output,long latency,String provider) {
        this(text,input,output,latency,provider,List.of(),"stop",null,true);
    }
    public static LlmResponse of(String text,int input,int output,long latency,String provider) {
        return new LlmResponse(text,input,output,latency,provider);
    }
    public int totalTokens() { return inputTokens+outputTokens; }
}
