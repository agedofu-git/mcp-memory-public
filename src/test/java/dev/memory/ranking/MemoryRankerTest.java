package dev.memory.ranking;

import dev.memory.TestSupport;
import dev.memory.domain.MemoryType;
import dev.memory.repository.MemoryRepository.Hit;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MemoryRankerTest {
    private final Instant now = Instant.parse("2026-09-05T00:00:00Z");
    private final MemoryDecayPolicy decay = new MemoryDecayPolicy(TestSupport.properties());
    private final MemoryRanker ranker = new MemoryRanker(TestSupport.properties(), decay, Clock.fixed(now, ZoneOffset.UTC));

    @Test void formulaAndDebugComponentsAreExplicit() {
        var memory = TestSupport.memory(MemoryType.FACT, "Server has 32 GB RAM", now, 0.7, 0);
        var result = ranker.rank(new Hit(memory, 0.9, 0.5), null, true);
        assertThat(result.score()).isCloseTo(0.9 * 0.45 + 0.7 * 0.18 + 0.08 + 0.95 * 0.10 + 0.5 * 0.15, within(1e-10));
        assertThat(result.components().decay()).isEqualTo(1);
        assertThat(result.components().access()).isZero();
        assertThat(result.components().semantic()).isEqualTo(0.9);
        assertThat(ranker.rank(new Hit(memory, 0.9, 0.5), null, false).components()).isNull();
    }
    @Test void episodicMemoriesDecayFasterAndUsageMitigatesDecay() {
        var old = now.minus(Duration.ofDays(365));
        var episode = TestSupport.memory(MemoryType.EPISODIC, "Debugged pgvector", old, 0.4, 0);
        var fact = TestSupport.memory(MemoryType.FACT, "Server has 32 GB RAM", old, 0.4, 0);
        var used = TestSupport.memory(MemoryType.EPISODIC, "Deployment workflow", old, 0.4, 100);
        assertThat(decay.factor(episode, now)).isLessThan(decay.factor(fact, now));
        assertThat(decay.factor(used, now)).isGreaterThan(decay.factor(episode, now));
        assertThat(decay.accessScore(Long.MAX_VALUE)).isEqualTo(1);
        assertThat(decay.accessScore(0)).isZero();
    }
    @Test void halfLifeAndFutureTimestampsAreBounded() {
        var memory = TestSupport.memory(MemoryType.EPISODIC, "Important episode", now.minus(Duration.ofDays(45)), 0.5, 0);
        assertThat(decay.factor(memory, now)).isCloseTo(0.5, within(1e-10));
        assertThat(decay.factor(memory, now.minus(Duration.ofDays(100)))).isEqualTo(1);
    }
    @Test void staleNoiseLosesToCurrentUsefulKnowledgeAcrossWeeks() {
        var noisy = TestSupport.memory(MemoryType.EPISODIC, "Tweaked database setting", now.minus(Duration.ofDays(180)), 0.3, 0);
        var project = TestSupport.memory(MemoryType.PROJECT, "Building memory search with PostgreSQL and pgvector", now.minus(Duration.ofDays(7)), 0.8, 5);
        assertThat(ranker.rank(new Hit(project, 0.85, 0.5), MemoryType.PROJECT, true).score())
                .isGreaterThan(ranker.rank(new Hit(noisy, 0.95, 0.5), MemoryType.PROJECT, true).score());
    }

    @Test void decayIsAppliedOnceThroughEffectiveImportance() {
        var memory = TestSupport.memory(MemoryType.EPISODIC, "Old episode", now.minus(Duration.ofDays(45)), 0.5, 0);
        var result = ranker.rank(new Hit(memory, 0.8, 0.4), null, true);
        var components = result.components();
        double expected = components.semantic() * 0.45 + components.importance() * 0.18
                + components.recency() * 0.08 + components.access() * 0.04
                + components.confidence() * 0.10 + components.text() * 0.15;
        assertThat(components.decay()).isCloseTo(0.5, within(1e-10));
        assertThat(result.score()).isCloseTo(expected, within(1e-10));
    }

    @Test void aRecentEditRefreshesTheDecayAnchor() {
        var created = now.minus(Duration.ofDays(365));
        var memory = TestSupport.memory(MemoryType.EPISODIC, "Corrected episode", created, 0.5, 0);
        memory = new dev.memory.domain.Memory(memory.id(), memory.type(), memory.content(), memory.normalizedContent(),
                memory.createdAt(), now, null, memory.source(), memory.sourceConversationId(), memory.importance(), memory.confidence(),
                memory.accessCount(), memory.tags(), memory.metadata(), memory.active(), memory.archived(), memory.superseded(), memory.stale(),
                memory.supersedesMemoryId(), memory.parentMemoryId(), memory.consolidationGroupId(), memory.embeddingProvider(),
                memory.embeddingModel(), memory.embeddingDimensions(), memory.embeddingVersion(), memory.version());
        assertThat(decay.factor(memory, now)).isEqualTo(1);
    }
}
