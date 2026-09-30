package dev.memory.controller;

import dev.memory.ranking.MemoryRanker;
import dev.memory.service.MemorySearchService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class SearchController {
    private final MemorySearchService service;
    public SearchController(MemorySearchService service) { this.service = service; }
    @PostMapping("/api/memories/search")
    public List<MemoryRanker.Result> search(@Valid @RequestBody Requests.Search request) { return service.search(request); }
}
