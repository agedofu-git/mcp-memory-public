package dev.memory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Memory(UUID id, MemoryType type, String content, String normalizedContent,
                     Instant createdAt, Instant updatedAt, Instant lastAccessedAt,
                     String source, String sourceConversationId, double importance, double confidence,
                     long accessCount, List<String> tags, Map<String, Object> metadata,
                     boolean active, boolean archived, boolean superseded, boolean stale,
                     UUID supersedesMemoryId, UUID parentMemoryId, UUID consolidationGroupId,
                     String embeddingProvider, String embeddingModel, int embeddingDimensions,
                     String embeddingVersion, long version) {}
