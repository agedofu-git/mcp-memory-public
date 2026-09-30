CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE memories (
    id uuid PRIMARY KEY,
    type varchar(20) NOT NULL CHECK (type IN ('FACT','PREFERENCE','EPISODIC','PROJECT','PROCEDURAL')),
    content text NOT NULL CHECK (length(content) BETWEEN 1 AND 8000),
    normalized_content text NOT NULL,
    embedding vector(${embeddingDimensions}) NOT NULL,
    embedding_provider text NOT NULL,
    embedding_model text NOT NULL,
    embedding_dimensions integer NOT NULL CHECK (embedding_dimensions = ${embeddingDimensions}),
    embedding_version text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_accessed_at timestamptz,
    source varchar(32) NOT NULL,
    source_conversation_id varchar(200),
    importance double precision NOT NULL CHECK (importance BETWEEN 0 AND 1),
    confidence double precision NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    access_count bigint NOT NULL DEFAULT 0 CHECK (access_count >= 0),
    tags jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(tags) = 'array'),
    metadata jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata) = 'object'),
    active boolean NOT NULL DEFAULT true,
    archived boolean NOT NULL DEFAULT false,
    superseded boolean NOT NULL DEFAULT false,
    supersedes_memory_id uuid REFERENCES memories(id) ON DELETE SET NULL,
    parent_memory_id uuid REFERENCES memories(id) ON DELETE SET NULL,
    consolidation_group_id uuid,
    last_consolidated_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    search_document tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    CHECK (NOT superseded OR NOT active),
    CHECK (NOT archived OR NOT active)
);

CREATE UNIQUE INDEX memories_active_exact ON memories(type, coalesce(metadata->>'project',''), md5(normalized_content))
    WHERE active AND NOT archived AND NOT superseded;
CREATE INDEX memories_vector_hnsw ON memories USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX memories_text ON memories USING gin(search_document);
CREATE INDEX memories_tags ON memories USING gin(tags);
CREATE INDEX memories_live_type ON memories(type, created_at DESC) WHERE active AND NOT archived AND NOT superseded;
CREATE INDEX memories_created ON memories(created_at DESC);
CREATE INDEX memories_updated ON memories(updated_at DESC);
CREATE INDEX memories_accessed ON memories(last_accessed_at);
CREATE INDEX memories_conversation ON memories(source_conversation_id);
CREATE INDEX memories_historical ON memories(archived, superseded) WHERE archived OR superseded;
CREATE INDEX memories_project ON memories((metadata->>'project'));

CREATE TABLE memory_revisions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    memory_id uuid NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    reason varchar(40) NOT NULL,
    snapshot jsonb NOT NULL
);
CREATE INDEX revisions_memory ON memory_revisions(memory_id, id);

CREATE TABLE memory_links (
    from_id uuid NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    to_id uuid NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    relation varchar(32) NOT NULL CHECK (relation IN ('SUPERSEDES','CONTRADICTS','CONSOLIDATED_FROM','REFINES')),
    target_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (from_id, to_id, relation),
    CHECK (from_id <> to_id)
);
CREATE INDEX links_target ON memory_links(to_id);

CREATE TABLE memory_evidence (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    memory_id uuid NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    conversation_id varchar(200),
    source varchar(32) NOT NULL,
    evidence text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX evidence_memory ON memory_evidence(memory_id);
