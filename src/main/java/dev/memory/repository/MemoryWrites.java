package dev.memory.repository;

import dev.memory.service.MemoryException;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Optimistic catalog generation prevents two concurrent semantic decisions from committing against stale candidates. */
@Component
public class MemoryWrites {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public MemoryWrites(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc; transactions = new TransactionTemplate(manager);
    }
    public long generation() { return jdbc.queryForObject("SELECT generation FROM memory_state WHERE id=1", Long.class); }
    public <T> T commit(long expected, Supplier<T> changes) {
        return transactions.execute(status -> {
            long current = jdbc.queryForObject("SELECT generation FROM memory_state WHERE id=1 FOR UPDATE", Long.class);
            if (current != expected) throw MemoryException.conflict();
            T value = changes.get();
            jdbc.update("UPDATE memory_state SET generation=generation+1 WHERE id=1");
            return value;
        });
    }
}
