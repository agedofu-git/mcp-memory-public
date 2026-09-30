ALTER TABLE memories ADD COLUMN stale boolean NOT NULL DEFAULT false;
ALTER TABLE memories ADD CONSTRAINT memories_stale_inactive CHECK (NOT stale OR NOT active);

-- Repair surviving provenance links from before invalidation was implemented.
-- A deleted source whose link already cascaded away cannot be reconstructed.
WITH RECURSIVE invalid(id) AS (
    SELECT l.from_id FROM memory_links l JOIN memories source ON source.id=l.to_id
    WHERE l.relation='CONSOLIDATED_FROM'
        AND (l.target_version<>source.version OR NOT source.active)
    UNION
    SELECT l.from_id FROM memory_links l JOIN invalid i ON l.to_id=i.id
    WHERE l.relation='CONSOLIDATED_FROM'
), changed AS (
    UPDATE memories SET stale=true, active=false, version=version+1, updated_at=now()
    WHERE id IN (SELECT id FROM invalid)
    RETURNING *
)
INSERT INTO memory_revisions(memory_id,reason,snapshot)
SELECT id,'SOURCE_CHANGED',to_jsonb(changed)-'embedding'-'search_document' FROM changed;

UPDATE memories SET last_consolidated_at=NULL
WHERE active AND id IN (
    SELECT l.to_id FROM memory_links l JOIN memories derived ON derived.id=l.from_id
    WHERE l.relation='CONSOLIDATED_FROM' AND derived.stale
);
