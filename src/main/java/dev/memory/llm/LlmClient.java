package dev.memory.llm;

public interface LlmClient {
    String complete(String systemPrompt, String inputJson);
}
