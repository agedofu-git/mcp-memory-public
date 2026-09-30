# Implementation checklist

## Review fixes (2026-09-06)

- [x] Collect all admission relations before committing; preserve duplicates, supersedes and contradictions together.
- [x] Separate atomic usage statistics from catalog generation; add concurrency regressions and recheck stale archiving.
- [x] Accept compatible LLM completion reasons while preserving strict JSON validation.
- [x] Implement derived-memory invalidation, history and reconsolidation eligibility (real DB verification pending).
- [x] Add optional minimum retrieval relevance and regression coverage.
- [x] Verify PostgreSQL 18 Docker volume location against the official Dockerfile; keep /var/lib/postgresql.
- [x] Build and run unit/provider regressions: 49 passed; document integration attempt and environment limitations.
- [ ] Pass real PostgreSQL/pgvector integration assertions, including V3 migration and recursive invalidation; local authentication and pgvector remain unavailable.

## Original implementation

- [x] Read AGENTS.md and SPEC.md completely; inspect the repository.
- [x] Verify Java 25, Spring Boot 4, Gradle compatibility against official sources.
- [x] Foundation implementation: build, wrapper, configuration, Docker files, migrations, health.
- [x] CRUD, revisions, archival, explicit deletion and usage tracking implementation.
- [x] Provider clients, hybrid vector/text search, ranking and decay implementation.
- [x] Validated extraction, usefulness judgment, duplicate/relation decisions.
- [x] Atomic superseding, provenance and optimistic concurrency implementation.
- [x] Bounded consolidation and optional scheduled maintenance implementation.
- [x] Preserve stored contradictions during consolidation; add regression tests.
- [x] Restore moved build output attributes; reuse the existing Gradle cache.
- [x] Windows environment checker and portable .env-aware launch script.
- [x] README, API examples and scenario review.
- [x] Final unit/provider build after all changes: 30 tests passed, no failures/errors/skips.
- [ ] Configure credentials for the existing PostgreSQL 18 service in ignored .env.
- [ ] Add PostgreSQL 18 pgvector extension files and enable vector in the target DB.
- [ ] Run real PostgreSQL/pgvector integration tests using TEST_DB_URL (without Docker locally).
- [ ] Verify application health and real configured AI providers.
- [ ] Validate Docker Compose execution on a host with Docker.

## Defaults and decisions

- One imperative Spring MVC service, Java 25 virtual threads, Spring JDBC and Jackson 3.
- Spring Boot 4.1.1 and Gradle 9.7.1 are stable releases verified during implementation.
- PostgreSQL 18 with pgvector; no JPA, reactive stack, or external search engine.
- Configurable vector dimensions via Flyway placeholder at initial database creation;
  model changes require deliberate re-embedding. Store model/provider/version/dimensions.
- Search previews do not count as usage. Clients explicitly acknowledge selected IDs.
- Ambiguous contradictions are preserved as linked alternatives, rather than silently overwriting truth.
- LLM calls happen before short write transactions; version checks reject stale decisions.
- Personal localhost service without authentication; reverse proxy authentication is required for remote use.
- Normal tests use fake providers; integration tests require real pgvector, never H2.
- Use the running Windows PostgreSQL 18 service; do not reinstall PostgreSQL or micromamba.
- Implementation checkboxes do not imply database/runtime validation. See docs/operations.md for current checks and docs/history/ for earlier review records.
