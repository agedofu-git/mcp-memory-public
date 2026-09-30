package dev.memory.controller;

import dev.memory.domain.MemoryType;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Requests {
    private Requests() {}

    public record Create(@NotNull MemoryType type, @NotBlank @Size(max = 8000) String content,
                         @DecimalMin("0") @DecimalMax("1") Double importance,
                         @DecimalMin("0") @DecimalMax("1") Double confidence,
                         @Size(max = 20) List<@NotBlank @Size(max = 60) String> tags,
                         @Size(max = 30) Map<@Size(max = 100) String, Object> metadata,
                         @Size(max = 200) String sourceConversationId) {}

    public record Patch(@NotNull @Min(0) Long version, MemoryType type,
                        @Size(min = 1, max = 8000) String content,
                        @DecimalMin("0") @DecimalMax("1") Double importance,
                        @DecimalMin("0") @DecimalMax("1") Double confidence,
                        @Size(max = 20) List<@NotBlank @Size(max = 60) String> tags,
                        @Size(max = 30) Map<@Size(max = 100) String, Object> metadata,
                        Boolean archived) {}

    public record Ingest(@NotBlank @Size(max = 200) String conversationId,
                         @NotBlank @Size(max = 32000) String userMessage,
                         @Size(max = 32000) String assistantMessage,
                         @Size(max = 100) String project, boolean debug) {}

    public record Search(@NotBlank @Size(max = 8000) String query,
                         @Min(1) @Max(100) Integer limit, MemoryType type, MemoryType preferredType,
                         Boolean activeOnly, boolean includeArchived, boolean includeSuperseded,
                         @Size(max = 20) List<@NotBlank @Size(max = 60) String> tags,
                         @Size(max = 100) String project,
                         @DecimalMin("0") @DecimalMax("1") Double minimumImportance,
                         Instant createdFrom, Instant createdTo, boolean debug) {
        public int effectiveLimit() { return limit == null ? 10 : limit; }
        public boolean effectiveActiveOnly() {
            return activeOnly == null ? !(includeArchived || includeSuperseded) : activeOnly;
        }
    }

    public record Usage(@NotEmpty @Size(max = 100) List<@NotNull UUID> memoryIds) {}
    public record Archive(@NotNull @Min(0) Long version, boolean archived) {}
    public record Consolidate(boolean archiveStale) {}
}
