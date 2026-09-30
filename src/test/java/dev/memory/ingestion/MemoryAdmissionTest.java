package dev.memory.ingestion;

import dev.memory.TestSupport;
import dev.memory.domain.*;
import dev.memory.embedding.EmbeddingClient;
import dev.memory.repository.*;
import dev.memory.service.MemoryException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryAdmissionTest {
    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final MemoryWrites writes = mock(MemoryWrites.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final MemoryRelationDetector relations = mock(MemoryRelationDetector.class);
    private final MemoryAdmission admission = new MemoryAdmission(repository, writes, embeddings, relations,
            new DuplicateDetector(TestSupport.properties()));
    private final MemoryDraft candidate = TestSupport.draft("Server has 32 GB RAM");
    private final Memory a = TestSupport.memory(MemoryType.FACT, "Server has 16 GB RAM", Instant.parse("2026-01-01T00:00:00Z"), 0.7, 0);
    private final Memory b = TestSupport.memory(MemoryType.FACT, "Server memory capacity is 32 GB", Instant.parse("2026-01-02T00:00:00Z"), 0.7, 0);
    private final Memory created = TestSupport.memory(MemoryType.FACT, candidate.content(), Instant.now(), 0.7, 0);
    private final EmbeddingClient.Embedding vector = new EmbeddingClient.Embedding(new float[]{1, 0, 0}, "test", "test", 3, "1");

    @BeforeEach void setup() {
        when(writes.commit(anyLong(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        when(embeddings.embed(candidate.content())).thenReturn(vector);
        when(repository.insert(eq(candidate), eq(vector), any(), isNull(), isNull())).thenReturn(created);
        when(repository.replace(any(), any(), isNull(), eq(false))).thenAnswer(call -> call.getArgument(0));
    }

    @ParameterizedTest
    @CsvSource({"UPDATE,DUPLICATE,false", "UPDATE,DUPLICATE,true", "CONTRADICTION,DUPLICATE,false", "CONTRADICTION,DUPLICATE,true",
            "UPDATE,CONTRADICTION,false", "UPDATE,CONTRADICTION,true", "UPDATE,UPDATE,false", "UPDATE,UPDATE,true"})
    void allRelationsSurviveRegardlessOfCandidateOrder(MemoryRelation first, MemoryRelation second, boolean reversed) {
        prepare(first, second, reversed, 0.85);
        var result = admission.admit(candidate);
        UUID survivor = second == MemoryRelation.DUPLICATE ? b.id() : created.id();
        assertThat(result.memory().id()).isEqualTo(survivor);
        verifyRelation(a, first, survivor);
        verifyRelation(b, second, survivor);
        verify(writes, times(1)).commit(anyLong(), any());
        var order = inOrder(relations, writes);
        order.verify(relations, times(2)).classify(eq(candidate), any());
        order.verify(writes).commit(anyLong(), any());
        if (second == MemoryRelation.DUPLICATE) {
            verify(repository, never()).insert(any(), any(), any(), any(), any());
            verify(repository).evidence(b.id(), candidate);
        }
    }

    @Test void ignoredDuplicateAlsoKeepsEarlierUpdate() {
        prepare(MemoryRelation.UPDATE, MemoryRelation.DUPLICATE, false, 0.99);
        assertThat(admission.admit(candidate).memory().id()).isEqualTo(b.id());
        verify(repository).supersede(a, "SUPERSEDED");
        verify(repository).link(b.id(), a.id(), "SUPERSEDES");
        verify(repository, never()).replace(any(), any(), any(), anyBoolean());
    }

    @Test void multipleDuplicatesKeepOneStableSurvivorAndHistoricalLinks() {
        prepare(MemoryRelation.DUPLICATE, MemoryRelation.DUPLICATE, true, 0.85);
        assertThat(admission.admit(candidate).memory().id()).isEqualTo(a.id());
        verify(repository).supersede(b, "SUPERSEDED");
        verify(repository).link(a.id(), b.id(), "SUPERSEDES");
        verify(repository, never()).insert(any(), any(), any(), any(), any());
    }

    @Test void failureAfterAnUpdateDecisionDoesNotPartiallyWrite() {
        prepare(MemoryRelation.UPDATE, MemoryRelation.DUPLICATE, false, 0.85);
        when(relations.classify(candidate, b)).thenThrow(MemoryException.malformed());
        assertThatThrownBy(() -> admission.admit(candidate)).isInstanceOf(MemoryException.class);
        verify(writes, never()).commit(anyLong(), any());
        verify(repository, never()).supersede(any(), anyString());
    }

    @Test void exactDuplicateAvoidsEmbeddingAndRelationCalls() {
        when(repository.exact(candidate.type(), candidate.content(), null)).thenReturn(Optional.of(created));
        assertThat(admission.admit(candidate).action()).isEqualTo("IGNORE");
        verify(repository).evidence(created.id(), candidate);
        verifyNoInteractions(embeddings, relations);
    }

    @Test void mergeKeepsTheHighestConfidence() {
        var lowerConfidence = new Memory(a.id(), a.type(), a.content(), a.normalizedContent(), a.createdAt(), a.updatedAt(),
                a.lastAccessedAt(), a.source(), a.sourceConversationId(), a.importance(), 0.5, a.accessCount(), a.tags(),
                a.metadata(), a.active(), a.archived(), a.superseded(), a.stale(), a.supersedesMemoryId(), a.parentMemoryId(),
                a.consolidationGroupId(), a.embeddingProvider(), a.embeddingModel(), a.embeddingDimensions(), a.embeddingVersion(), a.version());
        when(repository.related(candidate, vector)).thenReturn(List.of(new MemoryRepository.Hit(lowerConfidence, 0.85, 0)));
        when(relations.classify(candidate, lowerConfidence))
                .thenReturn(new MemoryRelationDetector.Classification(MemoryRelation.DUPLICATE, "same fact"));

        admission.admit(candidate);

        verify(repository).replace(eq(lowerConfidence), argThat(draft -> draft.confidence() == candidate.confidence()), isNull(), eq(false));
    }

    @Test void concurrentCommitRetriesOnceAndReusesEmbedding() {
        when(repository.related(candidate, vector)).thenReturn(List.of());
        var commits = new AtomicInteger();
        doAnswer(call -> {
            if (commits.getAndIncrement() == 0) throw MemoryException.conflict();
            return call.<Supplier<?>>getArgument(1).get();
        }).when(writes).commit(anyLong(), any());

        assertThat(admission.admit(candidate).action()).isEqualTo("CREATE");

        verify(embeddings).embed(candidate.content());
        verify(repository, times(2)).related(candidate, vector);
        verify(writes, times(2)).commit(anyLong(), any());
    }

    private void prepare(MemoryRelation first, MemoryRelation second, boolean reversed, double similarity) {
        var hits = List.of(new MemoryRepository.Hit(a, similarity, 0), new MemoryRepository.Hit(b, similarity, 0));
        when(repository.related(candidate, vector)).thenReturn(reversed ? hits.reversed() : hits);
        when(relations.classify(candidate, a)).thenReturn(new MemoryRelationDetector.Classification(first, "test relation"));
        when(relations.classify(candidate, b)).thenReturn(new MemoryRelationDetector.Classification(second, "test relation"));
    }

    private void verifyRelation(Memory target, MemoryRelation relation, UUID survivor) {
        if (relation == MemoryRelation.UPDATE) {
            verify(repository).supersede(target, "SUPERSEDED");
            verify(repository).link(survivor, target.id(), "SUPERSEDES");
        } else if (relation == MemoryRelation.CONTRADICTION) {
            verify(repository).link(survivor, target.id(), "CONTRADICTS");
            verify(repository, never()).supersede(eq(target), anyString());
        }
    }
}
