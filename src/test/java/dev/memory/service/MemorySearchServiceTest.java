package dev.memory.service;

import dev.memory.TestSupport;
import dev.memory.config.MemoryProperties;
import dev.memory.controller.Requests;
import dev.memory.domain.MemoryType;
import dev.memory.embedding.EmbeddingClient;
import dev.memory.ranking.*;
import dev.memory.repository.MemoryRepository;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemorySearchServiceTest {
    private final Instant now = Instant.parse("2026-09-06T00:00:00Z");
    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final EmbeddingClient.Embedding vector = new EmbeddingClient.Embedding(new float[]{1, 0, 0}, "test", "test", 3, "1");

    @Test void relevanceThresholdsCanReturnNoResultsAndAreDisabledByDefault() {
        var unrelated = TestSupport.memory(MemoryType.FACT, "User prefers coffee", now, 1, 100);
        when(embeddings.embed(anyString())).thenReturn(vector);
        when(repository.search(any(), eq(vector), anyInt())).thenReturn(List.of(new MemoryRepository.Hit(unrelated, 0.1, 0)));
        assertThat(search(0, 0).search(query(false))).hasSize(1);
        assertThat(search(0, 0.5).search(query(false))).isEmpty();
        assertThat(search(0.9, 0).search(query(true))).isEmpty();
    }

    @Test void filtersBeforeLimitAndDoesNotDependOnDebugComponents() {
        var unrelated = TestSupport.memory(MemoryType.FACT, "User prefers coffee", now, 1, 100);
        var relevant = TestSupport.memory(MemoryType.FACT, "Server has 32 GB RAM", now, 0.3, 0);
        when(embeddings.embed(anyString())).thenReturn(vector);
        when(repository.search(any(), eq(vector), anyInt())).thenReturn(List.of(
                new MemoryRepository.Hit(unrelated, 0.1, 0), new MemoryRepository.Hit(relevant, 0.8, 0)));
        for (boolean debug : List.of(false, true)) {
            assertThat(search(0, 0.8).search(query(debug))).singleElement().satisfies(result -> {
                assertThat(result.memory().id()).isEqualTo(relevant.id());
                assertThat(result.components() != null).isEqualTo(debug);
            });
        }
    }

    private Requests.Search query(boolean debug) {
        return new Requests.Search("server RAM", 1, null, null, null, false, false, null, null, null, null, null, debug);
    }
    private MemorySearchService search(double minimumScore, double minimumSemantic) {
        var defaults = TestSupport.properties();
        var r = defaults.ranking();
        var settings = new MemoryProperties.Ranking(r.semanticWeight(), r.importanceWeight(), r.recencyWeight(), r.accessWeight(),
                r.confidenceWeight(), r.textWeight(), r.typeBoost(), r.candidatePool(), r.accessCap(), r.recencyHalfLifeDays(), minimumScore, minimumSemantic);
        var properties = new MemoryProperties(defaults.embedding(), defaults.llm(), settings, defaults.decay(), defaults.extraction(),
                defaults.duplicate(), defaults.consolidation(), false, defaults.maxRequestBytes());
        return new MemorySearchService(embeddings, repository,
                new MemoryRanker(properties, new MemoryDecayPolicy(properties), Clock.fixed(now, ZoneOffset.UTC)), properties);
    }
}
