package dev.memory.llm;

import com.sun.net.httpserver.HttpServer;
import dev.memory.TestSupport;
import dev.memory.config.MemoryProperties;
import dev.memory.embedding.OpenAiCompatibleEmbeddingClient;
import dev.memory.service.MemoryException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ProviderClientTest {
    private HttpServer server;
    private String response;
    private int status = 200;
    private String requestBody;
    private final AtomicInteger requests = new AtomicInteger();
    private final java.util.List<ProviderHttp> clients = new ArrayList<>();
    private MemoryProperties properties;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            requests.incrementAndGet();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var defaults = TestSupport.properties();
        var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties = new MemoryProperties(new MemoryProperties.Embedding(base, "", "test", "local", 3, "1", Duration.ofSeconds(2), true),
                new MemoryProperties.Llm(base, "", "chat", Duration.ofSeconds(2), 0, 256, true), defaults.ranking(), defaults.decay(),
                defaults.extraction(), defaults.duplicate(), defaults.consolidation(), false, defaults.maxRequestBytes());
    }
    @AfterEach void stop() {
        clients.forEach(ProviderHttp::close);
        server.stop(0);
    }
    private ProviderHttp http() {
        var client = new ProviderHttp(JsonMapper.builder().build());
        clients.add(client);
        return client;
    }
    @Test void sendsCompatibleEmbeddingPayloadAndParsesVector() {
        response = "{\"data\":[{\"embedding\":[1,0.5,-0.2]}]}";
        var vector = new OpenAiCompatibleEmbeddingClient(http(), properties).embed("Server has 32 GB RAM");
        assertThat(vector.values()).containsExactly(1, 0.5f, -0.2f);
        assertThat(requestBody).contains("\"dimensions\":3", "\"model\":\"test\"");
        assertThat(requests).hasValue(1);
    }
    @Test void rejectsWrongDimensionsZeroVectorAndMalformedJson() {
        for (String invalid : java.util.List.of("{\"data\":[{\"embedding\":[1,2]}]}", "{\"data\":[{\"embedding\":[0,0,0]}]}",
                "{\"data\":[{\"embedding\":[\"1\",2,3]}]}", "not JSON")) {
            response = invalid;
            assertThatThrownBy(() -> new OpenAiCompatibleEmbeddingClient(http(), properties).embed("query")).isInstanceOf(MemoryException.class);
        }
    }
    @Test void retriesTransientFailuresOnlyOnceWithoutExposingBody() {
        response = "secret provider diagnostic"; status = 503;
        assertThatThrownBy(() -> new OpenAiCompatibleEmbeddingClient(http(), properties).embed("query"))
                .isInstanceOf(MemoryException.class).hasMessageNotContaining("secret");
        assertThat(requests).hasValue(2);
    }
    @Test void retryAfterSupportsSecondsAndHttpDatesWithABoundedDelay() {
        var now = Instant.parse("2026-09-06T00:00:00Z");
        assertThat(ProviderHttp.retryDelayMillis(Optional.of("2"), Duration.ofSeconds(30), now)).isEqualTo(2_000);
        assertThat(ProviderHttp.retryDelayMillis(Optional.of("Sun, 06 Sep 2026 00:00:03 GMT"), Duration.ofSeconds(30), now)).isEqualTo(3_000);
        assertThat(ProviderHttp.retryDelayMillis(Optional.of("60"), Duration.ofSeconds(30), now)).isEqualTo(5_000);
        assertThat(ProviderHttp.retryDelayMillis(Optional.of("invalid"), Duration.ofSeconds(30), now)).isEqualTo(150);
    }
    @Test void doesNotRetryAuthenticationErrors() {
        response = "invalid secret"; status = 401;
        assertThatThrownBy(() -> new OpenAiCompatibleEmbeddingClient(http(), properties).embed("query")).isInstanceOf(MemoryException.class);
        assertThat(requests).hasValue(1);
    }
    @Test void parsesChatCompletionAndRejectsTruncatedOutput() {
        response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}";
        assertThat(new OpenAiCompatibleLlmClient(http(), properties).complete("prompt", "{}")).isEqualTo("{}");
        assertThat(requestBody).contains("\"response_format\"", "\"max_tokens\":256");
        response = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}";
        assertThatThrownBy(() -> new OpenAiCompatibleLlmClient(http(), properties).complete("prompt", "{}")).isInstanceOf(MemoryException.class);
    }
    @Test void acceptsLocalCompletionReasonsOnlyAfterStrictStructuredValidation() throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var validation = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var structured = new StructuredLlm(new OpenAiCompatibleLlmClient(http(), properties), validation.getValidator(), mapper);
            for (String reason : java.util.List.of("\"stop\"", "\"eos\"", "null", "missing")) {
                String prefix = reason.equals("missing") ? "" : "\"finish_reason\":" + reason + ",";
                for (String content : java.util.List.of("{\"memories\":[]}", "{", "{\"memories\":[]} {}", "{\"memories\":[null]}", "{\"memories\":[],\"extra\":1}")) {
                    response = "{\"choices\":[{" + prefix + "\"message\":{\"content\":" + mapper.writeValueAsString(content) + "}}]}";
                    if (content.equals("{\"memories\":[]}")) {
                        assertThat(structured.call("memory-extraction", java.util.Map.of(), dev.memory.ingestion.MemoryExtractor.Extraction.class).memories()).isEmpty();
                    } else {
                        assertThatThrownBy(() -> structured.call("memory-extraction", java.util.Map.of(), dev.memory.ingestion.MemoryExtractor.Extraction.class))
                                .isInstanceOf(MemoryException.class);
                    }
                }
            }
        }
    }
    @Test void rejectsFilteredToolAndUnknownCompletionReasonsEvenWithCompleteJson() {
        for (String reason : java.util.List.of("\"length\"", "\"content_filter\"", "\"tool_calls\"", "\"unknown\"", "1", "true", "{}")) {
            response = "{\"choices\":[{\"finish_reason\":" + reason + ",\"message\":{\"content\":\"{}\"}}]}";
            assertThatThrownBy(() -> new OpenAiCompatibleLlmClient(http(), properties).complete("prompt", "{}")).isInstanceOf(MemoryException.class);
        }
    }
}
