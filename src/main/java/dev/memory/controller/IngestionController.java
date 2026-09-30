package dev.memory.controller;

import dev.memory.ingestion.MemoryIngestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class IngestionController {
    private final MemoryIngestionService service;
    public IngestionController(MemoryIngestionService service) { this.service = service; }
    @PostMapping("/api/memories/ingest")
    public MemoryIngestionService.Result ingest(@Valid @RequestBody Requests.Ingest request) { return service.ingest(request); }
}
