package dev.memory.consolidation;

import dev.memory.TestSupport;
import dev.memory.domain.*;
import dev.memory.repository.MemoryRepository;
import dev.memory.service.MemoryException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConsolidationValidationTest {
    private final Memory a = TestSupport.memory(MemoryType.PROJECT, "Added pgvector", Instant.parse("2026-08-01T00:00:00Z"), 0.7, 0);
    private final Memory b = TestSupport.memory(MemoryType.PROJECT, "Added memory search", Instant.parse("2026-08-02T00:00:00Z"), 0.8, 3);
    private MemoryConsolidationService.Summary summary(List<UUID> ids) {
        return new MemoryConsolidationService.Summary(MemoryType.PROJECT, "Building a memory system with pgvector and search", 0.7, 0.9, List.of(), ids);
    }
    @Test void acceptsSupportedDistinctSources() {
        MemoryConsolidationService.validatePlan(new MemoryConsolidationService.Plan(List.of(summary(List.of(a.id(), b.id()))), List.of()), List.of(a, b));
    }
    @Test void rejectsInventedAndRepeatedSourceIds() {
        for (var ids : List.of(List.of(a.id(), UUID.randomUUID()), List.of(a.id(), a.id()))) {
            assertThatThrownBy(() -> MemoryConsolidationService.validatePlan(new MemoryConsolidationService.Plan(List.of(summary(ids)), List.of()), List.of(a, b)))
                    .isInstanceOf(MemoryException.class);
        }
    }
    @Test void rejectsFalseSummaryAcrossContradictorySources() {
        var relation = new MemoryConsolidationService.Relation(a.id(), b.id(), MemoryRelation.CONTRADICTION, "incompatible plans");
        assertThatThrownBy(() -> MemoryConsolidationService.validatePlan(new MemoryConsolidationService.Plan(List.of(summary(List.of(a.id(), b.id()))), List.of(relation)), List.of(a, b)))
                .isInstanceOf(MemoryException.class);
    }
    @Test void rejectsSummaryWhenModelOmitsPreviouslyRecordedContradiction() {
        var plan = new MemoryConsolidationService.Plan(List.of(summary(List.of(a.id(), b.id()))), List.of());
        var stored = List.of(new MemoryRepository.Contradiction(b.id(), a.id()));
        assertThatThrownBy(() -> MemoryConsolidationService.validatePlan(plan, List.of(a, b), stored))
                .isInstanceOf(MemoryException.class);
    }
    @Test void allowsSummaryThatDoesNotCombineBothSidesOfStoredContradiction() {
        var c = TestSupport.memory(MemoryType.PROJECT, "Added provenance", Instant.parse("2026-08-03T00:00:00Z"), 0.7, 0);
        var plan = new MemoryConsolidationService.Plan(List.of(summary(List.of(b.id(), c.id()))), List.of());
        assertThatCode(() -> MemoryConsolidationService.validatePlan(plan, List.of(a, b, c),
                List.of(new MemoryRepository.Contradiction(b.id(), a.id())))).doesNotThrowAnyException();
    }
    @Test void rejectsBackwardUpdatesAndCycles() {
        var backwards = new MemoryConsolidationService.Relation(b.id(), a.id(), MemoryRelation.UPDATE, "bad ordering");
        assertThatThrownBy(() -> MemoryConsolidationService.validatePlan(new MemoryConsolidationService.Plan(List.of(), List.of(backwards)), List.of(a, b)))
                .isInstanceOf(MemoryException.class);
    }
    @Test void rejectsSummariesUsingSourcesRetiredByTheSamePlan() {
        for (var relation : List.of(MemoryRelation.DUPLICATE, MemoryRelation.UPDATE, MemoryRelation.REFINEMENT)) {
            var plan = new MemoryConsolidationService.Plan(List.of(summary(List.of(a.id(), b.id()))),
                    List.of(new MemoryConsolidationService.Relation(a.id(), b.id(), relation, "newer state")));
            assertThatThrownBy(() -> MemoryConsolidationService.validatePlan(plan, List.of(a, b)))
                    .isInstanceOf(MemoryException.class);
        }
    }
}
