package dev.memory.llm;

import dev.memory.config.MemoryProperties;
import dev.memory.service.MemoryException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleLlmClient implements LlmClient {
    private final ProviderHttp http;
    private final MemoryProperties.Llm settings;
    public OpenAiCompatibleLlmClient(ProviderHttp http, MemoryProperties properties) { this.http = http; settings = properties.llm(); }
    @Override public String complete(String prompt, String input) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", settings.model()); body.put("temperature", settings.temperature());
        body.put("max_tokens", settings.maxTokens());
        body.put("messages", List.of(Map.of("role", "system", "content", prompt), Map.of("role", "user", "content", input)));
        if (settings.jsonMode()) body.put("response_format", Map.of("type", "json_object"));
        var response = http.post(settings.baseUrl(), "/chat/completions", settings.apiKey(), settings.timeout(), body);
        var choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) throw MemoryException.malformed();
        var choice = choices.get(0);
        var content = choice.path("message").path("content");
        var finish = choice.path("finish_reason");
        // Local compatible servers may use eos or omit the reason. StructuredLlm still
        // requires complete, strictly typed JSON before any domain operation can use it.
        boolean completed = finish.isMissingNode() || finish.isNull()
                || (finish.isString() && Set.of("stop", "eos").contains(finish.asString()));
        if (!content.isString() || content.asString().isBlank() || !completed)
            throw MemoryException.malformed();
        return content.asString();
    }
}
