package dev.memory.consolidation;

import dev.memory.TestSupport;
import dev.memory.domain.MemoryType;
import dev.memory.embedding.EmbeddingClient;
import dev.memory.llm.StructuredLlm;
import dev.memory.ranking.MemoryDecayPolicy;
import dev.memory.repository.MemoryRepository;
import dev.memory.repository.MemoryWrites;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryConsolidationServiceTest {
    @Test void usageAfterStaleSelectionPreventsAutomaticArchiving() {
        var now = Instant.parse("2026-09-06T00:00:00Z");
        var old = TestSupport.memory(MemoryType.EPISODIC, "Configured server once",
                Instant.parse("2024-01-01T00:00:00Z"), 0.05, 0);
        var recent = new dev.memory.domain.Memory(old.id(), old.type(), old.content(), old.normalizedContent(),
                old.createdAt(), old.updatedAt(), now, old.source(), old.sourceConversationId(), old.importance(), old.confidence(),
                1, old.tags(), old.metadata(), true, false, false, false, null, null, null,
                old.embeddingProvider(), old.embeddingModel(), old.embeddingDimensions(), old.embeddingVersion(), old.version());
        var repository = mock(MemoryRepository.class);
        var writes = mock(MemoryWrites.class);
        when(repository.staleBatch()).thenReturn(List.of(old));
        when(repository.lock(old.id())).thenReturn(recent);
        when(writes.commit(anyLong(), any())).thenAnswer(call -> call.<java.util.function.Supplier<?>>getArgument(1).get());
        var settings = TestSupport.properties();
        var service = new MemoryConsolidationService(repository, writes, mock(StructuredLlm.class), mock(EmbeddingClient.class),
                new MemoryDecayPolicy(settings), settings, Clock.fixed(now, java.time.ZoneOffset.UTC));

        assertThat(service.consolidate(true).archived()).isZero();
        verify(repository, never()).replace(any(), any(), any(), anyBoolean());
    }
    @Test void storedConflictPreventsAnUnsupportedSummaryBeforeEmbeddingOrWrites() {
        var first = TestSupport.memory(MemoryType.FACT, "Server has 16 GB RAM",
                Instant.parse("2026-08-01T00:00:00Z"), 0.7, 0);
        var second = TestSupport.memory(MemoryType.FACT, "Server has 32 GB RAM",
                Instant.parse("2026-08-02T00:00:00Z"), 0.7, 0);
        var repository = mock(MemoryRepository.class);
        var writes = mock(MemoryWrites.class);
        var embeddings = mock(EmbeddingClient.class);
        var llm = mock(StructuredLlm.class);
        when(repository.consolidationBatch()).thenReturn(List.of(first, second));
        when(repository.require(first.id())).thenReturn(first);
        when(repository.cluster(first)).thenReturn(List.of(second));
        when(repository.contradictions(anyCollection())).thenReturn(
                List.of(new MemoryRepository.Contradiction(second.id(), first.id())));
        var summary = new MemoryConsolidationService.Summary(MemoryType.FACT,
                "Server consistently has 32 GB RAM", 0.7, 0.95, List.of("server"), List.of(first.id(), second.id()));
        when(llm.call(eq("memory-consolidation"), any(), eq(MemoryConsolidationService.Plan.class)))
                .thenReturn(new MemoryConsolidationService.Plan(List.of(summary), List.of()));
        var settings = TestSupport.properties();
        var service = new MemoryConsolidationService(repository, writes, llm, embeddings,
                new MemoryDecayPolicy(settings), settings, Clock.systemUTC());

        var result = service.consolidate(false);

        assertThat(result.complete()).isFalse();
        assertThat(result.errors()).containsExactly("INVALID_PROVIDER_OUTPUT");
        assertThat(result.summaries()).isEmpty();
        verifyNoInteractions(embeddings);
        verify(writes, never()).commit(anyLong(), any());
    }
}
