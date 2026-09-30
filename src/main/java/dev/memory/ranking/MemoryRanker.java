package dev.memory.ranking;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.memory.config.MemoryProperties;
import dev.memory.domain.*;
import dev.memory.repository.MemoryRepository.Hit;
import java.time.*;
import org.springframework.stereotype.Component;

@Component
public class MemoryRanker {
    public record Components(double semantic, double importance, double recency, double access,
                             double confidence, double text, double decay, double state, double typeBoost) {}
    public record Result(Memory memory, double score, @JsonInclude(JsonInclude.Include.NON_NULL) Components components) {}
    private final MemoryProperties.Ranking settings;
    private final MemoryDecayPolicy decay;
    private final Clock clock;
    public MemoryRanker(MemoryProperties properties, MemoryDecayPolicy decay, Clock clock) {
        settings = properties.ranking(); this.decay = decay; this.clock = clock;
        if (weightSum() <= 0) throw new IllegalArgumentException("Ranking weights must have a positive sum");
    }
    public Result rank(Hit hit, MemoryType preferredType, boolean debug) {
        var memory = hit.memory();
        var now = clock.instant();
        double age = Math.max(0, Duration.between(memory.updatedAt(), now).toSeconds() / 86400.0);
        double recency = Math.pow(0.5, age / settings.recencyHalfLifeDays());
        double factor = decay.factor(memory, now);
        double state = memory.superseded() ? 0.35 : memory.archived() ? 0.5 : memory.active() ? 1 : 0.6;
        double typeBoost = memory.type() == preferredType ? settings.typeBoost() : 0;
        var components = new Components(clamp(hit.semantic()), memory.importance() * factor, recency,
                decay.accessScore(memory.accessCount()), memory.confidence(), clamp(hit.text()), factor, state, typeBoost);
        double score = (components.semantic() * settings.semanticWeight() + components.importance() * settings.importanceWeight()
                + recency * settings.recencyWeight() + components.access() * settings.accessWeight()
                + components.confidence() * settings.confidenceWeight() + components.text() * settings.textWeight()) / weightSum();
        score = clamp((score + typeBoost) * state);
        return new Result(memory, score, debug ? components : null);
    }
    private double weightSum() { return settings.semanticWeight() + settings.importanceWeight() + settings.recencyWeight()
            + settings.accessWeight() + settings.confidenceWeight() + settings.textWeight(); }
    private static double clamp(double value) { return Math.clamp(value, 0, 1); }
}
