package dev.memory.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("memory")
public record MemoryProperties(
        @Valid Embedding embedding, @Valid Llm llm, @Valid Ranking ranking,
        @Valid Decay decay, @Valid Extraction extraction, @Valid Duplicate duplicate,
        @Valid Consolidation consolidation, boolean logContent,
        @Min(1024) @Max(10485760) int maxRequestBytes) {

    public record Embedding(@NotNull URI baseUrl, String apiKey, @NotBlank String model,
                            @NotBlank String provider, @Min(1) @Max(2000) int dimensions,
                            @NotBlank String version, @NotNull Duration timeout, boolean sendDimensions) {}
    public record Llm(@NotNull URI baseUrl, String apiKey, @NotBlank String model,
                      @NotNull Duration timeout, @DecimalMin("0") @DecimalMax("2") double temperature,
                      @Min(64) @Max(32768) int maxTokens, boolean jsonMode) {}
    public record Ranking(@PositiveOrZero double semanticWeight, @PositiveOrZero double importanceWeight,
                          @PositiveOrZero double recencyWeight, @PositiveOrZero double accessWeight,
                          @PositiveOrZero double confidenceWeight, @PositiveOrZero double textWeight,
                          @DecimalMin("0") @DecimalMax("0.2") double typeBoost,
                          @Min(10) @Max(1000) int candidatePool, @Min(1) int accessCap,
                          @Positive double recencyHalfLifeDays,
                          @DecimalMin("0") @DecimalMax("1") double minimumScore,
                          @DecimalMin("0") @DecimalMax("1") double minimumSemanticScore) {}
    public record Decay(@Positive double factHalfLifeDays, @Positive double preferenceHalfLifeDays,
                        @Positive double episodicHalfLifeDays, @Positive double projectHalfLifeDays,
                        @Positive double proceduralHalfLifeDays) {}
    public record Extraction(@DecimalMin("0") @DecimalMax("1") double minimumConfidence,
                             @DecimalMin("0") @DecimalMax("1") double minimumImportance,
                             @Min(1) @Max(20) int maxCandidates) {}
    public record Duplicate(@DecimalMin("0") @DecimalMax("1") double similarityThreshold,
                            @DecimalMin("0") @DecimalMax("1") double relationThreshold,
                            @Min(1) @Max(20) int candidateLimit) {}
    public record Consolidation(@Min(2) @Max(100) int batchSize, @Min(2) @Max(20) int clusterSize,
                                @DecimalMin("0") @DecimalMax("1") double similarityThreshold,
                                @Min(1) int cooldownDays, @Min(1) int archiveAfterDays,
                                @DecimalMin("0") @DecimalMax("1") double archiveThreshold,
                                boolean archiveOnSchedule) {}
}
