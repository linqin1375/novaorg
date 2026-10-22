package io.github.tangbailin.novaorg.llm;

import java.util.List;

/** Explicit model input, native conversation history, tools and output budget. */
public record LlmRequest(String systemPrompt, String userPrompt, int maxOutputTokens,
                         double temperature, List<String> stopHints, List<String> tools,
                         List<LlmMessage> messages) {
    public LlmRequest {
        stopHints = stopHints == null ? List.of() : List.copyOf(stopHints);
        tools = tools == null ? List.of() : List.copyOf(tools);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
    public LlmRequest(String system,String user,int tokens,double temperature,List<String> stops,List<String> tools) {
        this(system,user,tokens,temperature,stops,tools,List.of());
    }
    public static LlmRequest of(String system,String user) { return builder().system(system).user(user).build(); }
    public LlmRequest withMaxOutputTokens(int tokens) {
        return new LlmRequest(systemPrompt,userPrompt,tokens,temperature,stopHints,tools,messages);
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String system="",user="";
        private int tokens=512;
        private double temperature=0.2;
        private List<String> tools=List.of();
        private List<LlmMessage> messages=List.of();
        public Builder system(String value) { system=value;return this; }
        public Builder user(String value) { user=value;return this; }
        public Builder maxOutputTokens(int value) { tokens=value;return this; }
        public Builder temperature(double value) { temperature=value;return this; }
        public Builder tools(List<String> value) { tools=value;return this; }
        public Builder messages(List<LlmMessage> value) { messages=value;return this; }
        public LlmRequest build() { return new LlmRequest(system,user,tokens,temperature,List.of(),tools,messages); }
    }
}
