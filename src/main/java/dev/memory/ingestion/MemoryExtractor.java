package dev.memory.ingestion;

import dev.memory.config.MemoryProperties;
import dev.memory.controller.Requests.Ingest;
import dev.memory.domain.MemoryCandidate;
import dev.memory.llm.StructuredLlm;
import dev.memory.service.MemoryException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MemoryExtractor {
    public record Extraction(@NotNull @Size(max = 20) List<@NotNull @Valid MemoryCandidate> memories) {}
    private final StructuredLlm llm;
    private final int maxCandidates;
    public MemoryExtractor(StructuredLlm llm, MemoryProperties properties) { this.llm = llm; maxCandidates = properties.extraction().maxCandidates(); }
    public List<MemoryCandidate> extract(Ingest request) {
        var result = llm.call("memory-extraction", Map.of("userMessage", request.userMessage(),
                "assistantMessage", request.assistantMessage() == null ? "" : request.assistantMessage(),
                "maxCandidates", maxCandidates), Extraction.class);
        if (result.memories().size() > maxCandidates) throw MemoryException.malformed();
        return result.memories();
    }
}
