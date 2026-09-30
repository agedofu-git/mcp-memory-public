package dev.memory.ingestion;

import dev.memory.config.MemoryProperties;
import dev.memory.domain.*;
import org.springframework.stereotype.Component;

@Component
public class DuplicateDetector {
    public enum Decision { IGNORE, MERGE, SUPERSEDE, STORE, LINK_CONTRADICTION }
    private final double threshold;
    public DuplicateDetector(MemoryProperties properties) { threshold = properties.duplicate().similarityThreshold(); }
    public Decision decide(MemoryDraft candidate, Memory existing, MemoryRelation relation, double similarity) {
        if (candidate.type() != existing.type()) return Decision.STORE;
        if (TextNormalizer.normalize(candidate.content()).equals(existing.normalizedContent())) return Decision.IGNORE;
        return switch (relation) {
            // Even below the high-similarity cutoff, an LLM-confirmed duplicate should not create another active row.
            case DUPLICATE -> similarity >= threshold && existing.tags().containsAll(candidate.tags()) ? Decision.IGNORE : Decision.MERGE;
            case UPDATE, REFINEMENT -> Decision.SUPERSEDE;
            case CONTRADICTION -> Decision.LINK_CONTRADICTION;
            case COMPATIBLE, UNRELATED -> Decision.STORE;
        };
    }
}
