package dev.memory.controller;

import dev.memory.domain.Memory;
import dev.memory.service.MemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    private final MemoryService service;
    public MemoryController(MemoryService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Memory> create(@Valid @RequestBody Requests.Create request) {
        var memory = service.create(request);
        return ResponseEntity.created(URI.create("/api/memories/" + memory.id())).body(memory);
    }
    @GetMapping("/{id}") public Memory get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping public List<Memory> list(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                        @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset,
                                        @RequestParam(defaultValue = "false") boolean historical) {
        return service.list(limit, offset, historical);
    }
    @PatchMapping("/{id}") public Memory patch(@PathVariable UUID id, @Valid @RequestBody Requests.Patch request) { return service.patch(id, request); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { service.delete(id); return ResponseEntity.noContent().build(); }
    @GetMapping("/{id}/history") public Map<String, Object> history(@PathVariable UUID id) { return service.history(id); }
    @PostMapping("/{id}/archive") public Memory archive(@PathVariable UUID id, @Valid @RequestBody Requests.Archive request) { return service.archive(id, request); }
    @PostMapping("/usage") public Map<String, Integer> usage(@Valid @RequestBody Requests.Usage request) {
        return Map.of("updated", service.recordUsage(request.memoryIds()));
    }
}
