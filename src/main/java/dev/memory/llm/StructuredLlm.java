package dev.memory.llm;

import dev.memory.service.MemoryException;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StructuredLlm {
    private final LlmClient client;
    private final Validator validator;
    private final JsonMapper json;
    private final Map<String, String> prompts;
    public StructuredLlm(LlmClient client, Validator validator, JsonMapper mapper) throws IOException {
        this.client = client; this.validator = validator;
        this.json = mapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        var loaded = new java.util.HashMap<String, String>();
        for (var name : java.util.List.of("memory-extraction", "memory-judge", "contradiction-detection", "memory-consolidation")) {
            loaded.put(name, new ClassPathResource("prompts/" + name + ".txt").getContentAsString(StandardCharsets.UTF_8));
        }
        prompts = Map.copyOf(loaded);
    }
    public <T> T call(String prompt, Object input, Class<T> resultType) {
        String template = Objects.requireNonNull(prompts.get(prompt), "Unknown structured prompt: " + prompt);
        String response = client.complete(template, json.writeValueAsString(input));
        try {
            T result = json.readValue(response, resultType);
            if (result == null || !validator.validate(result).isEmpty()) throw MemoryException.malformed();
            return result;
        } catch (MemoryException exception) { throw exception; }
        catch (RuntimeException ignored) { throw MemoryException.malformed(); }
    }
}
