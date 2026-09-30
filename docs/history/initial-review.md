> 過去の開発時点の記録です。現在の構成・検証状況はREADMEと運用ガイドを参照してください。

# AI Long-Term Memory System — コードレビュー報告書

## 1. エグゼクティブサマリー

本リポジトリ（AI Long-Term Memory System: Java 25 + Spring Boot 4 + PostgreSQL / pgvector）について、[SPEC.md](file:///C:/dev/mcp-memory/SPEC.md) および [AGENTS.md](file:///C:/dev/mcp-memory/AGENTS.md) の要件に基づき、全方位的なコードレビューを実施しました。

全体として、**アーキテクチャの骨格、pgvectorを活用した2段階ハイブリッド検索、楽観的排他制御（世代カウンタによるセマンティック決定の一貫性維持）、睡眠サイクルを模したメモリ統合（Consolidation）、派生メモリのライフサイクル管理（V3マイグレーション）、およびTestcontainersを用いた統合テスト**など、高度で完成度の高い実装がなされています。特にSPECで規定された **17項目の必須テスト観点はすべて実装・網羅** されています。

一方で、**セキュリティ（.envコミット）、メモリ判断（Judge）での文字列比較の脆弱性、矛盾検知（Contradiction）時の未解決放置、APIバリデーションエラーの握りつぶし、ランキング計算での二重減衰（Double Decay）** など、修正・改善すべき重要な指摘事項が確認されました。

---

## 2. 指摘事項一覧（深刻度別）

### 🔴 CRITICAL（致命的・重大な欠陥 / セキュリティリスク）

| # | 対象ファイル / 箇所 | 指摘内容 | 影響と対策 |
|---|---|---|---|
| **C-1** | [.env](file:///C:/dev/mcp-memory/.env) | **`.env` ファイルが Git 管理下にコミットされている**<br>`.gitignore` には `.env` が定義されているが、リポジトリ作成時に既にトラックされた状態で追加されている。`SPEC.md`（L1211, L1318）および `AGENTS.md` の「Never commit real API keys, credentials, or secrets」に反する。 | 現状はローカル開発用のダミー値だが、ユーザーが実キーを入力した場合に事故になるリスクがある。<br>**対策:** `git rm --cached .env` でインデックスから除外し、`.env.example` のみ管理する。 |
| **C-2** | [`MemoryJudge.java:19`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ingestion/MemoryJudge.java#L19) | **エビデンス検証が単純な `String.contains()` で実装されている**<br>`if (!userMessage.contains(candidate.evidence()))` により完全一致判定をしている。LLMが空白や改行を正規化して返した場合に、正当な記憶候補が不当にリジェクト（偽陰性）される。また、万が一 `evidence` が空文字 `""` で通過した場合、常に `true` になりチェックが機能しない。 | **対策:** 双方の文字列に対して空白・改行の正規化（`replaceAll("\\s+", " ")`）を行ってから判定する。また、`evidence.isBlank()` の早期リジェクトを明示する。 |
| **C-3** | [`MemoryAdmission.java:41-56`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ingestion/MemoryAdmission.java#L41-L56) | **CONTRADICTION 判定時に古いメモリが supersede されず、両方が active のまま残る**<br>`DuplicateDetector` が `LINK_CONTRADICTION` を返した際、`CONTRADICTS` リンクは張られるが、`superseded` リストには追加されない。そのため、新旧両方の矛盾した記憶が通常検索（`active AND NOT superseded`）で同時にヒットし続ける。 | `SPEC.md`（L466-487）では変更される事実について「create or update the new memory, mark the old memory as superseded, link the memories」と規定されている。<br>**対策:** 高確信度の更新や明らかな事実矛盾については supersede させるか、または未解決矛盾として通常検索から片方を優先・抑制するフラグ制御を行う。 |
| **C-4** | [`MemoryDraft.java:10`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/domain/MemoryDraft.java#L10) | **`MemoryDraft` コンパクトコンストラクタでの NPE リスク**<br>`content = content.strip();` を無条件で呼び出している。`content` が null の場合、バリデーション以前に `NullPointerException` がスローされる。 | **対策:** `Objects.requireNonNull(content, "content must not be null")` を記述し、明確な例外メッセージを投げる。 |

---

### 🟡 IMPORTANT（設計・ロジックの改善点 / 仕様乖離）

| # | 対象ファイル / 箇所 | 指摘内容 | 影響と対策 |
|---|---|---|---|
| **I-1** | [`ApiErrors.java:31-35`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/controller/ApiErrors.java#L31-L35) | **バリデーションエラーの詳細が完全に握りつぶされている**<br>`MethodArgumentNotValidException` 捕捉時に、`"Invalid request fields or JSON."` の固定メッセージのみを返し、どのフィールドがどのような制約（`@Size`, `@NotNull`, `@Min` 等）で弾かれたかを返却していない。 | API 利用者がリクエストを修正できず、デバッグが極めて困難。<br>**対策:** `FieldError` のリスト（フィールド名、拒否された値、エラー理由）を `ProblemDetail` の `properties` または詳細メッセージに含める。 |
| **I-2** | [`ApiErrors.java:40-44`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/controller/ApiErrors.java#L40-L44) | **`DataAccessException` で例外の詳細がロギングされていない**<br>`log.warn("event=database_failure");` のみで、例外オブジェクト自体をロガーに渡していない（引数が `ignored` 扱い）。 | データベース障害（コネクション枯渇、SQLエラー、制約違反など）の発生時に根本原因をトレースできない。<br>**対策:** `log.warn("event=database_failure", exception);` としてスタックトレースを記録する。 |
| **I-3** | [`MemoryRanker.java:30-36`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ranking/MemoryRanker.java#L30-L36) | **ランキング計算において時間減衰（Decay）が二重に適用されている**<br>L30 で `importance * factor`（減衰係数）が掛けられた上で、L36 で最終スコア全体に対しても `(score + typeBoost) * (0.5 + 0.5 * factor) * state` として再度 `factor` が掛けられている。 | 重要度の高い古い記憶が想定以上に急激にランキングから脱落する。`SPEC.md`（L580-597）の重み付け設計の直感と乖離するため、二重減衰の意図を明確にするか、式を整理すべき。 |
| **I-4** | [`ConsolidationSchedule.java:15`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/consolidation/ConsolidationSchedule.java#L15) | **スケジュール実行される定期統合で古い記憶のアーカイブが常にスキップされる**<br>`service.consolidate(false)` とハードコードされており、`archiveStale` が常に `false`。 | `SPEC.md`（L737, L778）にある「睡眠サイクルによる低価値で古い記憶の自動アーカイブ」がスケジュール運用で一切機能しない。<br>**対策:** プロパティ（`memory.consolidation.archive-on-schedule` 等）で制御可能にするか、夜間バッチでは `true` で実行する。 |
| **I-5** | [`MemoryAdmission.java:68-69`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ingestion/MemoryAdmission.java#L68-L69) | **重複マージ（MERGE）時に `confidence` が既存値のまま保持され、候補の値が無視される**<br>`importance` は `Math.max(existing.importance(), candidate.importance())` としているが、`confidence` は `existing.confidence()` を無条件で渡している。 | より確実性の高い文脈から再抽出された情報があっても信頼度スコアが向上しない。<br>**対策:** `Math.max(existing.confidence(), candidate.confidence())` を適用する。 |
| **I-6** | [`MemoryAdmission.java:28-44`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ingestion/MemoryAdmission.java#L28-L44) | **楽観ロック競合（Conflict）時に Embedding API の呼び出しコストが無駄になる**<br>トランザクション外で Embedding を取得したあと、`writes.commit()` で競合が発生した場合、`MemoryIngestionService` はリトライを行わずにそのまま ERROR としている。 | 競合時に外部 API コール（課金/GPU時間）が無駄になり、取り込みも失敗する。<br>**対策:** 計算済み Embedding を再利用した 1〜2 回の軽量リトライ機構を設ける。 |
| **I-7** | [`MemoryConsolidationService.java:27-28`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/consolidation/MemoryConsolidationService.java#L27-L28) | **統合プランの制限数（max 10 relations）とクラスタ最大サイズ（max 20）の不均衡**<br>`Plan` レコードの `@Size(max = 10) List<Relation>` に対し、設定可能なクラスタサイズは最大 20（ペア数は最大 190）。LLM が 11 個以上の関係性を検出するとバリデーションでプラン全体が弾かれる。 | **対策:** 関係性の上限をクラスタサイズに応じた適切な値（例: 30〜50）に緩和する。 |
| **I-8** | [`MemoryWrites.java:17-26`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/repository/MemoryWrites.java#L17-L26) | **`memory_state` テーブルによるシステム全体の単一ロック（直列化）**<br>`FOR UPDATE` を使って世代カウンタを進める方式により、すべての書き込み操作がグローバルに直列化されている。 | 個人利用（シングルユーザー）としては極めてシンプルで確実だが、並行インジェスション時のスループット上限となる。仕様上「数百〜数千程度の個人用」としては許容範囲内だが、将来の拡張時の留意点。 |
| **I-9** | [`MemoryDecayPolicy.java:21`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ranking/MemoryDecayPolicy.java#L21) vs [`MemoryRanker.java:25`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ranking/MemoryRanker.java#L25) | **時間減衰（Decay）と最新性（Recency）の基準タイムスタンプの不整合**<br>`MemoryRanker` の recency は `updatedAt` を基準にしているが、`MemoryDecayPolicy` の decay は `lastAccessedAt ?? createdAt`（`updatedAt` を見ない）。 | PATCH で更新されたがアクセスされていない古い記憶が、「最新（高recency）」でありながら「激しく減衰（低decay）」という不自然な状態になる。`updatedAt` も anchor 候補に含めるべき。 |

---

### 🟢 MINOR（軽微な改善点 / コード品質）

| # | 対象ファイル / 箇所 | 指摘内容 |
|---|---|---|
| **M-1** | [`MemoryIngestionService.java:28`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ingestion/MemoryIngestionService.java#L28), [`MemoryAdmission.java:59`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ingestion/MemoryAdmission.java#L59) 等 | **ロガーがメソッド内で都度 `LoggerFactory.getLogger(...)` 呼び出しされている**<br>クラス定数 `private static final Logger log = LoggerFactory.getLogger(...);` に統一することが推奨される。 |
| **M-2** | [`ProviderHttp.java:16-17`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/llm/ProviderHttp.java#L16-L17) | **`HttpClient` が `DisposableBean` / `AutoCloseable` でクローズされていない**<br>コンテキスト終了時にコネクションプールやスレッドが明示的に破棄されない。 |
| **M-3** | [`StructuredLlm.java:32`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/llm/StructuredLlm.java#L32) | **プロンプト名取得時の null ガード不足**<br>`prompts.get(prompt)` が null の場合、`LlmClient` に null が渡され不透明なエラーになる。`Objects.requireNonNull` で明示的にチェックすべき。 |
| **M-4** | [`HealthController.java:9-10`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/controller/HealthController.java#L9-L10) | **データベースの死活監視が含まれていない**<br>常に静的な `{"status":"UP"}` を返している。`SPEC.md`（L1227-1230）に従い、DB疎通チェックをオプションで含めると実用的。 |
| **M-5** | [`DuplicateDetector.java:17`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/ingestion/DuplicateDetector.java#L17) | **タグが1つでも増えると IGNORE から MERGE に昇格する**<br>`existing.tags().containsAll(candidate.tags())` の完全包含条件。LLM が生成するタグはブレやすいため、実質的にほとんどの重複が MERGE（DB書き込み）になってしまう。 |
| **M-6** | [`ProviderHttp.java:45`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/llm/ProviderHttp.java#L45) | **429（Rate Limit）時のリトライ待機時間が固定 150ms**<br>`Retry-After` ヘッダーを考慮しておらず、レートリミット対策として短すぎる可能性がある。 |
| **M-7** | [`Dockerfile:9`](file:///C:/dev/mcp-memory/Dockerfile#L9) | **JAR ファイル名が `long-term-memory-0.1.0.jar` と固定されている**<br>`build.gradle.kts` でバージョン番号を上げた際に Docker ビルドが壊れる。ワイルドカードや `archiveFileName` の利用が望ましい。 |
| **M-8** | [`MemoryRepository.java`](file:///C:/dev/mcp-memory/src/main/java/dev/memory/repository/MemoryRepository.java) 全般 | **複数ステートメントが1行に圧縮されたスタイルが多数存在する**<br>Java 25 の最新スタイルとしては簡潔だが、デバッガでのブレークポイント設定や可読性の観点からは標準的な複数行フォーマットが好ましい。 |

---

## 3. SPEC.md 必須テスト17項目の網羅性検証

`SPEC.md`（L1103–L1121）で要求されている17の自動テスト観点について、テストコードの実装状況を精査しました。

| # | SPEC 要求テスト項目 | 実装状況 | 検証元テストクラス / メソッド |
|---|---|---|---|
| 1 | Manual memory creation（手動作成） | ✅ 網羅 | `MemoryServiceTest.java:27-36`, `MemoryIntegrationTest.java:149-163` |
| 2 | Memory retrieval by ID（ID検索） | ✅ 網羅 | `MemoryServiceTest.java:38-41`, `MemoryIntegrationTest.java:151` |
| 3 | Memory update（更新・PATCH） | ✅ 網羅 | `MemoryServiceTest.java:43-60`, `MemoryIntegrationTest.java:154-159` |
| 4 | Memory deletion（削除） | ✅ 網羅 | `MemoryServiceTest.java:38-41`, `MemoryIntegrationTest.java:160-162` |
| 5 | Candidate extraction parsing（抽出パース） | ✅ 網羅 | `MemoryPipelineTest.java:28-37`（型付きパース、空結果等） |
| 6 | Usefulness filtering（有用性判定） | ✅ 網羅 | `MemoryPipelineTest.java:49-60`（閾値・エビデンス・雑音判定） |
| 7 | Duplicate detection（重複検知） | ✅ 網羅 | `MemoryPipelineTest.java:69-82`, `MemoryAdmissionTest.java:58-72` |
| 8 | Semantic retrieval（セマンティック検索） | ✅ 網羅 | `MemoryIntegrationTest.java:164-178`（コサイン距離 > 0.99） |
| 9 | Ranking formula（ランキング計算式） | ✅ 網羅 | `MemoryRankerTest.java:15-23`（各要素の加重計算と境界値） |
| 10 | Ranking debug components（スコア詳細出力） | ✅ 網羅 | `MemoryRankerTest.java:19-22`, `MemorySearchServiceTest.java:38-42` |
| 11 | Superseding outdated info（過去情報の失効） | ✅ 網羅 | `MemoryIntegrationTest.java:179-198`（16GB → 32GB RAM シナリオ） |
| 12 | Contradiction/update classification（矛盾/更新分類） | ✅ 網羅 | `MemoryPipelineTest.java:61-67`, `MemoryIntegrationTest.java:200-208` |
| 13 | Decay calculation（記憶減衰計算） | ✅ 網羅 | `MemoryRankerTest.java:24-37`（FACT vs EPISODIC の半減期差異等） |
| 14 | Archival behavior（アーカイブ挙動） | ✅ 網羅 | `MemoryIntegrationTest.java:173-177`, `MemoryConsolidationServiceTest.java:19-38` |
| 15 | Consolidation behavior（記憶統合挙動） | ✅ 網羅 | `MemoryIntegrationTest.java:220-234`, `ConsolidationValidationTest.java` |
| 16 | Malformed provider response（プロバイダ不正出力耐性） | ✅ 網羅 | `MemoryPipelineTest.java:38-47`（8パターンの不正JSON）, `ProviderClientTest.java` |
| 17 | Configuration loading（設定ロード検証） | ✅ 網羅 | `ConfigurationTest.java:9-20`（Bean Validation / プロパティバインド） |

**評価:**
テストスイートは非常に充実しており、H2 に逃げず `Testcontainers`（PostgreSQL + pgvector）を実稼働させてベクトル演算やトランザクション分離を検証している点は特筆すべき高い品質です。

---

## 4. 総評と推奨ネクストステップ

本システムは、単なるチャットログのベクターストアではなく、「人間の長期記憶に近いライフサイクル（抽出・有用性審査・重複統合・減衰・睡眠統合・証跡追跡）」を標榜した `SPEC.md` の設計思想を高いレベルでコード化しています。

今後の改善にあたっては、以下の順序での対応を推奨します：
1. **セキュリティ修正:** `.env` の Git 追跡除外（`git rm --cached .env`）
2. **API 品質向上:** `ApiErrors.java` でバリデーション詳細（フィールド別エラー）を返却するように改善
3. **ロジック堅牢化:** `MemoryJudge.java` のエビデンス文字列比較の正規化、および `MemoryAdmission.java` における `CONTRADICTION` 発生時の挙動整理
4. **スコアリング調整:** `MemoryRanker.java` における二重減衰（double-decay）の仕様見直し
