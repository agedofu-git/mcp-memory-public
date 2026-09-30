package dev.memory.domain;

import jakarta.validation.constraints.*;
import java.util.List;

public record MemoryCandidate(@NotNull MemoryType type, @NotBlank @Size(max = 8000) String content,
                              @NotNull @DecimalMin("0") @DecimalMax("1") Double importance,
                              @NotNull @DecimalMin("0") @DecimalMax("1") Double confidence,
                              @NotNull @Size(max = 20) List<@NotBlank @Size(max = 60) String> tags,
                              @NotBlank @Size(max = 4000) String evidence) {}
