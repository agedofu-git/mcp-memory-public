package dev.memory.embedding;

import dev.memory.config.MemoryProperties;
import dev.memory.llm.ProviderHttp;
import dev.memory.service.MemoryException;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {
    private final ProviderHttp http;
    private final MemoryProperties.Embedding settings;
    public OpenAiCompatibleEmbeddingClient(ProviderHttp http, MemoryProperties properties) {
        this.http = http; settings = properties.embedding();
    }
    @Override public Embedding embed(String content) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", settings.model()); payload.put("input", content);
        payload.put("encoding_format", "float");
        if (settings.sendDimensions()) payload.put("dimensions", settings.dimensions());
        var body = http.post(settings.baseUrl(), "/embeddings", settings.apiKey(), settings.timeout(), payload);
        var data = body.path("data");
        if (!data.isArray() || data.size() != 1) throw MemoryException.malformed();
        var vector = data.get(0).path("embedding");
        if (!vector.isArray() || vector.size() != settings.dimensions())
            throw new MemoryException(MemoryException.Kind.INVALID_PROVIDER_OUTPUT, "Embedding dimensions do not match configuration.");
        float[] values = new float[vector.size()];
        double norm = 0;
        for (int i = 0; i < values.length; i++) {
            if (!vector.get(i).isNumber()) throw MemoryException.malformed();
            values[i] = (float) vector.get(i).asDouble();
            if (!Float.isFinite(values[i])) throw MemoryException.malformed();
            norm += (double) values[i] * values[i];
        }
        if (norm == 0) throw MemoryException.malformed();
        return new Embedding(values, settings.provider(), settings.model(), settings.dimensions(), settings.version());
    }
}
