# 運用ガイド

## 初回の準備

1. リポジトリを取得し、`.env.example`を`.env`にコピーします。既存の`.env`は上書きしません。
2. `DB_PASSWORD`に固有の値を設定し、EmbeddingとLLMのURL・モデル・APIキーを設定します。
3. 埋め込みモデルの次元を`EMBEDDING_DIMENSIONS`に設定します。
4. `docker compose --profile app up -d --build`で起動し、`/api/health`を確認します。

`.env`はGitにもDockerのビルドコンテキストにも含めません。Composeは実行時にアプリへ環境変数を渡します。パスワードを変更しても、既存ボリューム内のPostgreSQLロールのパスワードは自動では変わりません。

## 日常の確認・停止・再起動

```sh
docker compose --profile app ps
docker compose --profile app logs --tail=100 app
curl -fsS http://127.0.0.1:8080/api/health

# コンテナを止める。DBボリュームは保持
docker compose --profile app stop

# 作成済みコンテナを再開
docker compose --profile app start

# アプリだけ再起動
docker compose --profile app restart app
```

`docker compose down`はコンテナとネットワークを削除し、名前付きボリュームは保持します。`down -v`はDBボリュームも削除するため、データを残す運用では使用しません。

## ソースを更新する

更新前にバックアップを取り、作業ツリーに変更がないことを確認します。Flywayは起動時に新しいマイグレーションを適用します。

```sh
git status --short
git pull --ff-only
docker compose --profile app up -d --build
curl -fsS http://127.0.0.1:8080/api/health
```

`.env`は各環境で保持されます。埋め込みモデル・次元・PostgreSQLメジャーバージョンを変更する更新では、個別のデータ移行計画が必要です。

## DBをバックアップする

Linux/macOSのシェルで実行する例です。`pg_dump`はコンテナ内のユーザー・DB設定を使います。バックアップには記憶本文・履歴・根拠・ベクトルが含まれます。

```sh
mkdir -p backups
chmod 700 backups
backup_file="backups/memory-$(date +%Y%m%d-%H%M%S).dump"
(umask 077; docker compose exec -T db sh -c \
  'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup_file")
```

このダンプはDB内のデータを保存します。`.env`、DBロール、リバースプロキシやネットワーク設定は別途保管してください。`backups/`はGit管理から除外されます。

復元は、同じpgvectorを備えた**専用の空DB**で検証してから行います。次のコマンドは、接続先DBにダンプ内容を作成します。通常の稼働DBへそのまま投入しません。

```sh
docker compose exec -T db sh -c \
  'exec pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < backups/復元対象.dump
```

## SSHの鍵認証

鍵は接続するPCで生成し、サーバーには公開鍵だけを登録します。秘密鍵はリポジトリやサーバーのソースフォルダーへ置きません。

```sh
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519_proxmox
```

Windowsの`%USERPROFILE%\.ssh\config`、Linux/macOSの`~/.ssh/config`に接続先と鍵を指定します。

```sshconfig
Host pve
    HostName <ProxmoxのIPまたはホスト名>
    User root
    IdentityFile ~/.ssh/id_ed25519_proxmox
    IdentitiesOnly yes

Host debian-vm
    HostName <Debian VMのIPまたはホスト名>
    User <VMのユーザー名>
    IdentityFile ~/.ssh/id_ed25519_proxmox
    IdentitiesOnly yes
```

公開鍵ファイルの内容を、対象ユーザーの`~/.ssh/authorized_keys`へ1行で追記します。既存の鍵は残し、`.ssh`を700、`authorized_keys`を600にします。鍵にパスフレーズを設定した場合はssh-agentを使って毎回の入力を省けます。

```sh
ssh -o BatchMode=yes pve hostname
ssh -o BatchMode=yes debian-vm hostname
```

localhostで動いているサービスへ別端末から接続する場合は、SSHトンネルを利用できます。

```sh
ssh -N -L 18080:127.0.0.1:8080 debian-vm
```

このターミナルを開いたまま、接続するPCの`http://127.0.0.1:18080/mcp`をMCPクライアントへ登録します。

## ベクトルマップ

Python 3とDockerを使います。マップは記憶本文を含むため、生成したHTMLを公開リポジトリへ追加しません。

```sh
python3 scripts/vector_graph.py --output vector-graph.html
python3 scripts/vector_graph_server.py --host 127.0.0.1 --port 8765
```

Tailscaleを使う場合は、`--host`にそのサーバー自身のTailscale IPv4を指定できます。`run_vector_graph.sh`はスクリプトの場所からソースを解決し、TailscaleのIPを確認して再起動します。

```sh
chmod +x scripts/run_vector_graph.sh
scripts/run_vector_graph.sh
```

自動起動が必要なら、`crontab -e`で`@reboot /チェックアウト先の絶対パス/scripts/run_vector_graph.sh`を登録します。ポートは`VECTOR_GRAPH_PORT`で変更できます。ログとロックは`${XDG_CACHE_HOME:-$HOME/.cache}/mcp-memory/`にあります。

## テスト

```sh
./gradlew build integrationTest --no-daemon
```

通常テストにはAI APIやDBは不要です。統合テストではDockerで専用DBを起動します。`TEST_DB_URL`を指定すると、その接続先にランダムなテスト用スキーマを作り、終了時にそのスキーマを削除します。この方式ではpgvectorとスキーマ作成権限が必要です。

```dotenv
TEST_DB_URL=jdbc:postgresql://localhost:5432/memory_test
TEST_DB_USER=memory_test
TEST_DB_PASSWORD=<テスト用DBのパスワード>
```

2026-10-01の公開準備では、稼働サービスとは別のソースフォルダーと専用DBで次を確認しました。

| 検証 | 結果 |
| --- | --- |
| Java 25で`build integrationTest` | 成功 |
| 通常テスト | 57件、失敗・エラー・スキップ0 |
| PostgreSQL + pgvectorの統合テスト | 18件、失敗・エラー・スキップ0 |
| MCP | 初期接続、5ツールの検出、`memory_list`呼び出しを統合テストで確認 |
| Docker | Compose設定、イメージのビルド、専用DBへの起動、`/api/health`を確認 |
| 公開内容の点検 | 実環境の秘密情報との一致0。環境ファイル・実データ・秘密鍵を除外 |
| スクリプト・文書 | PythonとPowerShellの構文、シェルの構文、文書リンク、Gitの空白チェックを確認 |

AI providerはテスト用の偽物を使い、実際の有料API・本番の記憶データは検証に使っていません。実モデルでの日本語検索・抽出精度は別途評価が必要です。テスト用Dockerコンテナ・ネットワーク・ボリューム・イメージは検証後に削除しました。

過去のレビューは`docs/history/`に保存し、当時の未検証事項を現在の結果と混同しないようにしています。

## GitHubに公開する

公開するのはソースと設定の雛形です。運用データや秘密情報は別に保管します。

```sh
git status --short
git ls-files
git diff --cached
```

`.env.example`以外の環境ファイル、APIキー、DBバックアップ、記憶を含むHTML、SSH秘密鍵が対象に入っていないことを確認します。Gitの過去の履歴も公開対象になるため、公開用リポジトリは確認済みの内容から新しい履歴で作成しています。

変更を追加する際は、必要なファイルを指定してコミットし、テスト後にpushします。

```sh
git add README.md docs/operations.md
git diff --cached
git commit -m "Update operating instructions"
git push origin main
```

ライセンスは未設定です。
