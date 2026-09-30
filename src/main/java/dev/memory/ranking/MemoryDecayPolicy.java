package dev.memory.ranking;

import dev.memory.config.MemoryProperties;
import dev.memory.domain.Memory;
import java.time.*;
import org.springframework.stereotype.Component;

@Component
public class MemoryDecayPolicy {
    private final MemoryProperties.Decay settings;
    private final int accessCap;
    public MemoryDecayPolicy(MemoryProperties properties) { settings = properties.decay(); accessCap = properties.ranking().accessCap(); }
    public double factor(Memory memory, Instant now) {
        double halfLife = switch (memory.type()) {
            case FACT -> settings.factHalfLifeDays();
            case PREFERENCE -> settings.preferenceHalfLifeDays();
            case EPISODIC -> settings.episodicHalfLifeDays();
            case PROJECT -> settings.projectHalfLifeDays();
            case PROCEDURAL -> settings.proceduralHalfLifeDays();
        };
        Instant anchor = memory.updatedAt().isAfter(memory.createdAt()) ? memory.updatedAt() : memory.createdAt();
        if (memory.lastAccessedAt() != null && memory.lastAccessedAt().isAfter(anchor)) anchor = memory.lastAccessedAt();
        double ageDays = Math.max(0, Duration.between(anchor, now).toSeconds() / 86400.0);
        double resilience = 1 + memory.importance() + accessScore(memory.accessCount());
        return Math.pow(0.5, ageDays / (halfLife * resilience));
    }
    public double effectiveImportance(Memory memory, Instant now) { return memory.importance() * factor(memory, now); }
    public double accessScore(long count) { return Math.min(1, Math.log1p(Math.max(0, count)) / Math.log1p(accessCap)); }
}
