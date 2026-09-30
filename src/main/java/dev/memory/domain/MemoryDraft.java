package dev.memory.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MemoryDraft(MemoryType type, String content, double importance, double confidence,
                          List<String> tags, Map<String, Object> metadata,
                          String source, String conversationId, String evidence) {
    public MemoryDraft {
        content = Objects.requireNonNull(content, "content must not be null").strip();
        tags = tags == null ? List.of() : tags.stream().map(String::strip).distinct().toList();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    public static MemoryDraft extracted(MemoryCandidate candidate, String conversationId, Map<String, Object> metadata) {
        return new MemoryDraft(candidate.type(), candidate.content(), candidate.importance(), candidate.confidence(),
                candidate.tags(), metadata, "EXTRACTED", conversationId, candidate.evidence());
    }
}
