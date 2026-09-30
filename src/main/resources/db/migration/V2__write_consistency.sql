CREATE TABLE memory_state (
    id integer PRIMARY KEY CHECK (id=1),
    generation bigint NOT NULL DEFAULT 0
);
INSERT INTO memory_state(id) VALUES (1);

CREATE UNIQUE INDEX evidence_unique ON memory_evidence(memory_id, coalesce(conversation_id,''), source, md5(coalesce(evidence,'')));
CREATE INDEX memories_consolidation ON memories(last_consolidated_at NULLS FIRST, created_at)
    WHERE active AND NOT archived AND NOT superseded;
