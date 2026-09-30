package dev.memory.service;

import dev.memory.TestSupport;
import dev.memory.controller.Requests;
import dev.memory.domain.*;
import dev.memory.embedding.EmbeddingClient;
import dev.memory.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryServiceTest {
    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final MemoryWrites writes = mock(MemoryWrites.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final MemoryService service = new MemoryService(repository, writes, embeddings);
    private final Memory memory = TestSupport.memory(MemoryType.FACT, "Server has 32 GB RAM", Instant.now(), 0.7, 0);
    private final EmbeddingClient.Embedding vector = new EmbeddingClient.Embedding(new float[]{1, 0, 0}, "test", "test", 3, "1");
    @BeforeEach void setup() {
        when(writes.commit(anyLong(), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        when(repository.require(memory.id())).thenReturn(memory);
    }
    @Test void manualCreationUsesHighTrustAndEmbedsBeforeTransaction() {
        when(embeddings.embed("Server has 32 GB RAM")).thenReturn(vector);
        when(repository.insert(any(), eq(vector), isNull(), isNull(), isNull())).thenReturn(memory);
        var created = service.create(new Requests.Create(MemoryType.FACT, memory.content(), null, null, null, null, null));
        assertThat(created).isEqualTo(memory);
        var draft = org.mockito.ArgumentCaptor.forClass(MemoryDraft.class);
        verify(repository).insert(draft.capture(), eq(vector), isNull(), isNull(), isNull());
        assertThat(draft.getValue().confidence()).isEqualTo(0.99);
        assertThat(draft.getValue().source()).isEqualTo("MANUAL");
        var order = inOrder(embeddings, writes); order.verify(embeddings).embed(memory.content()); order.verify(writes).commit(anyLong(), any());
    }
    @Test void getAndDeleteUseExplicitRepositoryOperations() {
        assertThat(service.get(memory.id())).isEqualTo(memory);
        service.delete(memory.id());
        verify(repository).delete(memory.id());
    }
    @Test void metadataEditAvoidsEmbeddingAndKeepsSource() {
        service.patch(memory.id(), new Requests.Patch(0L, null, null, 0.8, null, List.of("hardware"), null, null));
        verifyNoInteractions(embeddings);
        verify(repository).replace(eq(memory), argThat(draft -> draft.importance() == 0.8 && draft.source().equals("EXTRACTED")), isNull(), eq(false));
    }
    @Test void contentEditRegeneratesEmbeddingAndRejectsStaleVersion() {
        when(embeddings.embed("Server has 64 GB RAM")).thenReturn(vector);
        service.patch(memory.id(), new Requests.Patch(0L, null, "Server has 64 GB RAM", null, null, null, null, null));
        verify(repository).replace(eq(memory), any(), eq(vector), eq(false));
        assertThatThrownBy(() -> service.patch(memory.id(), new Requests.Patch(99L, null, "bad", null, null, null, null, null)))
                .isInstanceOf(MemoryException.class);
    }
    @Test void failedEmbeddingDoesNotStartAWriteTransaction() {
        when(embeddings.embed(anyString())).thenThrow(new MemoryException(MemoryException.Kind.PROVIDER_UNAVAILABLE, "Unavailable"));
        assertThatThrownBy(() -> service.patch(memory.id(), new Requests.Patch(0L, null, "Server has 64 GB RAM", null, null, null, null, null)))
                .isInstanceOf(MemoryException.class);
        verify(writes, never()).commit(anyLong(), any());
        verify(repository, never()).replace(any(), any(), any(), anyBoolean());
    }
    @Test void usageDoesNotInvalidateSemanticDecisions() {
        when(repository.recordUsage(List.of(memory.id()))).thenReturn(1);
        assertThat(service.recordUsage(List.of(memory.id()))).isEqualTo(1);
        verifyNoInteractions(writes, embeddings);
    }
}
