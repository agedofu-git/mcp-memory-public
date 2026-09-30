package dev.memory.mcp;

import dev.memory.controller.Requests;
import dev.memory.domain.Memory;
import dev.memory.ingestion.MemoryIngestionService;
import dev.memory.ranking.MemoryRanker;
import dev.memory.service.MemorySearchService;
import dev.memory.service.MemoryService;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MemoryMcpTools {

    private final MemorySearchService searchService;
    private final MemoryIngestionService ingestionService;
    private final MemoryService memoryService;

    public MemoryMcpTools(
            MemorySearchService searchService,
            MemoryIngestionService ingestionService,
            MemoryService memoryService) {
        this.searchService = searchService;
        this.ingestionService = ingestionService;
        this.memoryService = memoryService;
    }

    @McpTool(
            name = "memory_search",
            description = """
                    Search long-term memory for information relevant to the current conversation.
                    Use this when previous user facts, preferences, projects, decisions,
                    or past conversations may help answer the user.
                    """
    )
    public List<MemoryRanker.Result> search(
            @McpToolParam(
                    description = "Natural-language description of the memory to search for",
                    required = true
            )
            String query,

            @McpToolParam(
                    description = "Maximum number of memories to return. Defaults to 10.",
                    required = false
            )
            Integer limit,

            @McpToolParam(
                    description = "Optional project name used to restrict the search",
                    required = false
            )
            String project) {

        var request = new Requests.Search(
                query,
                limit,
                null,       // type
                null,       // preferredType
                true,       // activeOnly
                false,      // includeArchived
                false,      // includeSuperseded
                null,       // tags
                project,
                null,       // minimumImportance
                null,       // createdFrom
                null,       // createdTo
                false       // debug
        );

        return searchService.search(request);
    }

    @McpTool(
            name = "memory_ingest",
            description = """
                    Process a conversation and extract useful long-term memories.
                    The memory system decides what should be stored, updated,
                    rejected, or merged.
                    """
    )
    public MemoryIngestionService.Result ingest(
            @McpToolParam(
                    description = "Stable identifier for this conversation",
                    required = true
            )
            String conversationId,

            @McpToolParam(
                    description = "The user's message",
                    required = true
            )
            String userMessage,

            @McpToolParam(
                    description = "The assistant's response, if available",
                    required = false
            )
            String assistantMessage,

            @McpToolParam(
                    description = "Optional project associated with the conversation",
                    required = false
            )
            String project,

            @McpToolParam(
                    description = "Enable additional debugging information",
                    required = false
            )
            Boolean debug) {

        return ingestionService.ingest(
                new Requests.Ingest(
                        conversationId,
                        userMessage,
                        assistantMessage,
                        project,
                        Boolean.TRUE.equals(debug)
                )
        );
    }

    @McpTool(
            name = "memory_get",
            description = "Get one memory by its UUID."
    )
    public Memory get(
            @McpToolParam(
                    description = "UUID of the memory",
                    required = true
            )
            String id) {

        return memoryService.get(UUID.fromString(id));
    }

    @McpTool(
            name = "memory_list",
            description = "List memories currently stored in long-term memory."
    )
    public List<Memory> list(
            @McpToolParam(
                    description = "Maximum number of memories to return. Defaults to 20.",
                    required = false
            )
            Integer limit,

            @McpToolParam(
                    description = "Number of memories to skip. Defaults to 0.",
                    required = false
            )
            Integer offset,

            @McpToolParam(
                    description = "Include historical memory versions",
                    required = false
            )
            Boolean historical) {

        int actualLimit = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        int actualOffset = offset == null ? 0 : Math.max(offset, 0);

        return memoryService.list(
                actualLimit,
                actualOffset,
                Boolean.TRUE.equals(historical)
        );
    }

    @McpTool(
            name = "memory_history",
            description = "Get the revision and change history of a memory."
    )
    public Map<String, Object> history(
            @McpToolParam(
                    description = "UUID of the memory",
                    required = true
            )
            String id) {

        return memoryService.history(UUID.fromString(id));
    }
}
