package dev.memory.controller;

import dev.memory.consolidation.MemoryConsolidationService;
import org.springframework.web.bind.annotation.*;

@RestController
public class ConsolidationController {
    private final MemoryConsolidationService service;
    public ConsolidationController(MemoryConsolidationService service) { this.service = service; }
    @PostMapping("/api/memories/consolidate")
    public MemoryConsolidationService.Result consolidate(@RequestBody(required = false) Requests.Consolidate request) {
        return service.consolidate(request != null && request.archiveStale());
    }
}
