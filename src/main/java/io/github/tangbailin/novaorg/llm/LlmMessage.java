package io.github.tangbailin.novaorg.llm;

import java.util.List;

/** Native conversation history; reasoning content is transported, never exposed in the UI trace. */
public record LlmMessage(String role, String content, List<LlmToolCall> toolCalls,
                         String toolCallId, String reasoningContent) {
    public LlmMessage { toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls); }
    public static LlmMessage user(String text) { return new LlmMessage("user",text,List.of(),null,null); }
    public static LlmMessage assistant(LlmResponse response) {
        return new LlmMessage("assistant",response.text(),response.toolCalls(),null,response.reasoningContent());
    }
    public static LlmMessage tool(String callId,String result) {
        return new LlmMessage("tool",result,List.of(),callId,null);
    }
}
