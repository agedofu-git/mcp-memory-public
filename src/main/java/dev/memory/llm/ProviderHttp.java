package dev.memory.llm;

import dev.memory.service.MemoryException;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ProviderHttp implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ProviderHttp.class);
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 150;
    private static final long MAX_RETRY_DELAY_MILLIS = 5_000;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json;
    public ProviderHttp(JsonMapper json) { this.json = json; }

    public JsonNode post(URI base, String path, String key, Duration timeout, Object payload) {
        if (!java.util.Set.of("http", "https").contains(base.getScheme()) || base.getRawUserInfo() != null
                || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("Invalid AI provider configuration");
        }
        var builder = HttpRequest.newBuilder(URI.create(base.toString().replaceAll("/+$", "") + path))
                .timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (key != null && !key.isBlank()) builder.header("Authorization", "Bearer " + key);
        for (int attempt = 0; attempt < 2; attempt++) {
            CompletableFuture<HttpResponse<byte[]>> pending = null;
            try {
                // ofByteArray completes only after the whole body; the timeout covers slow response bodies too.
                pending = client.sendAsync(builder.build(), limitedBodyHandler());
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    try {
                        var result = json.readTree(response.body());
                        if (result == null || !result.isObject()) throw MemoryException.malformed();
                        return result;
                    }
                    catch (RuntimeException ignored) { throw MemoryException.malformed(); }
                }
                if ((status == 429 || status >= 500) && attempt == 0) {
                    Thread.sleep(retryDelayMillis(response.headers().firstValue("Retry-After"), timeout, Instant.now()));
                    continue;
                }


                log.warn("event=provider_failure status={} body={}",
                status,
                new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));

                throw unavailable();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (ExecutionException | TimeoutException exception) {
                if (attempt == 1) throw unavailable();
            } finally {
                if (pending != null && !pending.isDone()) pending.cancel(true);
            }
        }
        throw unavailable();
    }

    static long retryDelayMillis(Optional<String> retryAfter, Duration timeout, Instant now) {
        long requested = retryAfter.map(String::strip).filter(value -> !value.isEmpty()).map(value -> {
            try {
                return Math.max(0, Math.multiplyExact(Long.parseLong(value), 1_000L));
            } catch (NumberFormatException | ArithmeticException ignored) {
                try {
                    return Math.max(0, Duration.between(now,
                            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());
                } catch (DateTimeParseException invalidDate) {
                    return DEFAULT_RETRY_DELAY_MILLIS;
                }
            }
        }).orElse(DEFAULT_RETRY_DELAY_MILLIS);
        return Math.min(requested, Math.min(MAX_RETRY_DELAY_MILLIS, timeout.toMillis()));
    }

    @Override
    public void close() {
        client.close();
    }

    private static HttpResponse.BodyHandler<byte[]> limitedBodyHandler() {
        return info -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> result = new CompletableFuture<>();
            private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            private Flow.Subscription subscription;
            public CompletionStage<byte[]> getBody() { return result; }
            public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
            public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
                for (var buffer : buffers) {
                    if (output.size() + buffer.remaining() > 1048576) {
                        subscription.cancel(); result.completeExceptionally(new IOException("Response too large")); return;
                    }
                    byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
                }
                subscription.request(1);
            }
            public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
            public void onComplete() { result.complete(output.toByteArray()); }
        };
    }

    private static MemoryException unavailable() {
        log.warn("event=provider_failure");
        return new MemoryException(MemoryException.Kind.PROVIDER_UNAVAILABLE, "AI provider unavailable or request rejected.");
    }
}
