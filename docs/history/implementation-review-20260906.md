> 過去の開発時点の記録です。現在の構成・検証状況はREADMEと運用ガイドを参照してください。

# Implementation review — updated 2026-09-06

## Follow-up on REVIEW.md and gpt-review.md (2026-09-06)

The existing working-tree edits were preserved. Review recommendations were checked against SPEC.md and the current implementation.

- Admission classifies every related candidate before writing. A duplicate survivor retains all UPDATE / REFINEMENT and CONTRADICTION links in one transaction. Multiple duplicates use a stable creation-time/UUID survivor; retired rows retain evidence and history. Exact-text retries still avoid AI calls.
- Usage updates statistics atomically without changing catalog generation or content version. Automatic archiving locks and rechecks current usage before changing state. Content writes continue to preserve concurrently incremented statistics.
- Chat completions accept stop, eos, null or missing finish reasons. Strict structured JSON/type/validation checks remain mandatory. Truncation, filtering, tool calls, malformed types and unknown reasons are rejected.
- V3 adds stale state, repairs existing version-mismatched/inactive-source links and preserves SOURCE_CHANGED revisions. Source edits, archival, superseding and deletion recursively invalidate derived memories in the source write transaction. Remaining sources are eligible for reconsolidation; stale summaries remain available historically. Sources deleted before V3 with already-cascaded links cannot be reconstructed. Direct SQL writes bypass application invalidation.
- Consolidation rejects summaries citing sources retired by the same plan and checks that dependencies remain active after applying relations.
- Configurable minimum ranking and semantic scores filter before the result limit. Both default to zero (disabled); Japanese retrieval/model quality still needs measurement.
- The suggested Docker volume change was rejected after checking the [official PostgreSQL 18 Dockerfile](https://github.com/docker-library/postgres/blob/master/18/bookworm/Dockerfile) and [pgvector image base](https://github.com/pgvector/pgvector/blob/v0.8.6/Dockerfile). PostgreSQL 18 uses /var/lib/postgresql/18/docker and mounts /var/lib/postgresql.
- Added unit regressions for mixed relation orders, multiple duplicates, provider output variants, relevance thresholds and usage during archive selection. Added real DB scenarios for concurrent usage, mixed admission, all four source lifecycle changes, transitive invalidation, reconsolidation eligibility and transaction rollback.

Verification:

```text
.\gradlew.bat --gradle-user-home .gradle-home build --no-daemon
BUILD SUCCESSFUL — 49 tests, 0 failures, 0 errors, 0 skipped
```

The real integration task was also attempted with TEST_DB_* derived in the child process from the existing ignored .env DB_* settings. PostgreSQL rejected authentication for the configured memory user before schema creation. The environment checker separately confirmed that the existing PostgreSQL 18 service is running and its vector.control is absent. Integration assertions, V3 SQL, database HTTP startup and live AI quality therefore remain unverified. No credentials, installed services or database configuration were changed. Test cleanup now runs only after a test schema was actually created, avoiding a second authentication failure during cleanup after failed setup.

MCP transport, choosing/deploying a specific embedding model, Japanese search extensions and VM deployment remain future work; they are outside this correction pass.

The sections below preserve the earlier verification history. Current behavior supersedes the earlier derived-memory, minimum-score and usage-conflict findings.

## Original review — 2026-09-05

## Environment and preservation

- Resumed at `C:\dev\mcp-memory`, branch `master`, initial commit `3b0ae7f`.
- Working tree was clean at inspection. Existing implementation, `.tools` and Gradle caches were preserved. No reset, checkout, service stop, database replacement or software installation was performed.
- Java: Temurin 25.0.3. Build declares Spring Boot 4.1.1 and Gradle 9.7.1.
- Existing Windows service `postgresql-x64-18` was Running; PostgreSQL tools report 18.0 and localhost:5432 accepts connections.
- Build output had Windows ReadOnly attributes after the move. Removed only those attributes under the verified project `build` directory, without deleting source or database data. The offline build then passed.
- PostgreSQL's `share/extension/vector.control` was absent. No `DB_*` / `TEST_DB_*` environment configuration or pgpass file was available at initial inspection. A noninteractive connection as postgres required a password; the sample memory credentials were rejected. These attempts made no DB changes.
- Docker and Visual Studio C++ commands were not found in PATH; no replacement database or compiler was downloaded.

## Changes made during resumption

- Consolidation now retrieves already stored CONTRADICTS links for its bounded cluster, supplies them to the model, and independently rejects any proposed summary citing both sides. A model omitting the conflict from its answer can no longer bypass this guard.
- Added validation and orchestration regression tests for the known-conflict case, plus an integration scenario covering the real relationship query and preservation of both source rows.
- Added a read-only Windows environment checker. It reports missing prerequisites without prompting for or printing passwords or changing the service.
- Updated the local launch script to resolve the moved project directory, reuse `.gradle-home` when no explicit Gradle home is configured, and accept build/test task arguments. Its `.env` parser is shared with the checker and evaluates no shell expressions.
- Made unit-test configuration independent of the user's provider environment variables so local model/dimension settings do not change test fixtures.
- Added the README, Windows PostgreSQL setup instructions, provider configuration, API examples, scoring formula, migration guidance and honest runtime limitations.

## Verification

Initial verification after fixing build attributes:

```text
.\gradlew.bat --gradle-user-home .gradle-home build --no-daemon --offline
BUILD SUCCESSFUL — 27 tests, no failures
```

The local runner also completed an offline build after the consolidation fix. The final verification reran every build task with `EMBEDDING_DIMENSIONS=1536` and a different `EMBEDDING_MODEL` in the process environment, confirming that unit-test fixtures remain independent of local provider settings:

```text
.\gradlew.bat --gradle-user-home .gradle-home build --no-daemon --offline --rerun-tasks
BUILD SUCCESSFUL — 30 tests, 0 failures, 0 errors, 0 skipped
```

The executable JAR was produced at `build/libs/long-term-memory-0.1.0.jar`. All PowerShell scripts parsed successfully; the checker with `-SkipDatabase` succeeded when launched from `C:\dev`, confirming it resolves the moved project independently of the current working directory. `git diff --check` and local documentation link checks passed. A new ignored `.env` was created from the example without replacing an existing file; its placeholder values still require local configuration.

The environment checker was executed with process-scoped `RemoteSigned` because this machine's default PowerShell policy prohibits script execution. It correctly reported Java 25, the running PostgreSQL 18 service, missing pgvector and unavailable DB authentication, returning exit code 1. The machine policy was not changed.

**Not verified yet:** real PostgreSQL migrations, SQL/vector integration assertions, full HTTP application startup, Docker image/Compose startup, or live AI provider compatibility/quality. Integration tests compile as part of the build but are a separate task. Unit tests and fake HTTP providers cannot establish those behaviors.

## Multi-day scenarios

| Scenario | Expected behavior | Evidence / remaining risk |
| --- | --- | --- |
| Day 1: server has 16 GB; day 7: explicit upgrade to 32 GB | New row supersedes old; source and history survive | Integration scenario exists; awaits database prerequisites. Unit decision tests verify UPDATE differs from CONTRADICTION. |
| Day 8: repeat the upgrade conversation | Existing active memory reused; no extra embedding for exact text | Integration scenario exists; awaits database. Paraphrase duplicate policy has a passing unit test. Extraction/judge may still incur calls. |
| Two incompatible RAM claims without an upgrade | Keep both and link the conflict | Admission integration scenario exists. New consolidation tests verify an omitted known conflict prevents a false summary before embedding or writes. |
| "I'm hungry right now" or unsupported assistant claim | Reject temporary/no-evidence candidates | Unit tests exercise the judge decision and cheap evidence/confidence checks. They do not prove that a live model makes the right judgment. |
| Work on pgvector, embeddings and search across a week | Produce a source-linked PROJECT summary and preserve original episodes | Integration scenario exists. Source-ID, backwards-update and contradiction validation is unit tested. |
| Six months later: unused low-value episode vs current project | Recent useful project outranks semantically closer stale episode | Fixed-clock ranking test passes. Type half-lives and capped access are separately tested. |
| Provider fails during edit | Previous content remains unchanged | Unit test verifies no write transaction starts after embedding failure; real DB assertion awaits integration run. |
| Model returns broken JSON, fabricated IDs, wrong dimensions or truncated output | Reject result and return a controlled failure | Unit/provider tests use malformed responses and an in-process HTTP server. |

## Critical findings and follow-up

1. **Hallucinations remain possible.** Evidence substring validation proves presence, not entailment. The extraction and judge model may share biases. Add a realistic Japanese/English evaluation corpus with human-checked expected memories before changing thresholds.
2. **Conflict detection is bounded.** Only a small same-type/project candidate pool is compared. Manual creation does not run semantic admission. A contradictory row outside the pool can be missed. Search responses expose content but require a separate history call to inspect conflict links.
3. **Derived knowledge may become stale.** A summary retains source IDs and link target versions, but editing/deleting/superseding a source does not automatically deactivate all descendants. This is the next significant lifecycle improvement; deletion also does not remove facts already copied into other summaries.
4. **Ranking needs measurement.** Semantic similarity dominates initial ranking, text uses a simple dictionary, and there is no minimum relevance score. Japanese tokenization and HNSW filter recall need real data. The database selects bounded candidates, but index existence alone does not prove efficient execution or recall.
5. **Consolidation has limits.** Batches and cooldowns bound cost. Stored conflicts within a cluster are now enforced even when omitted by the model. Cross-cluster conflicts, semantic summary duplicates, and inaccurate but source-shaped summaries still need review. Importance promotion is limited to duplicate merging; summaries cannot exceed their sources' maximum importance.
6. **Concurrency favors correctness over throughput.** A global generation check prevents stale decisions without holding a transaction across LLM calls. Unrelated edits and usage acknowledgements can still invalidate work and require caller retry. No automatic ingestion-wide retries or exactly-once key are implemented.
7. **Latency and cost are visible but not benchmarked.** Extraction, per-candidate judgment and several sequential relation calls may take substantial time. Exact duplicates avoid embedding/classification; low confidence/evidence failures avoid further provider calls. Each transient HTTP failure has at most one retry.
8. **Embedding migration is deliberate.** Provider/model/version/dimensions are stored and isolated in search; vector column dimensions are fixed at initial migration. Changing environment variables is not a re-embedding workflow. Add a resumable re-embedding command and new index/column migration later.
9. **Security is local-only.** Request bodies are bounded; SQL uses parameters; provider error bodies and credentials are not returned. Authentication and tenant isolation are absent. Conversation-derived snippets and revisions are retained and need appropriate access control/backups before remote hosting.
10. **Operational prerequisites are still pending.** Add only the PostgreSQL 18 pgvector extension, configure local credentials, run `integrationTest` against the service, then test the selected AI providers and application health. No H2 or alternative PostgreSQL installation should be used to bypass these checks.
