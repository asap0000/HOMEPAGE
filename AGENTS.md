# AGENTS.md — HOMEPAGE（BusCourse Android）で Codex が守ること

- 実体は `android/`。**制度・現況は別リポ** `D:\dev\istech\teams\android\{CHARTER,STATE}.md` と `docs/` の設計正典（依頼文に §番号で示される）。**落としてはならない条項は依頼文に列挙される——列挙外の仕様変更をしない。**
- テストは **`bash android/tools/test-buscourse.sh`**（JAVA_HOME 内包・生の数字を印字）。**完了報告にはこの出力をそのまま貼る。** **APK は作らない**（実機検証は人）。
- DB スキーマ（Room）の変更は、依頼文に**版番号と台帳番号**が無ければ着手しない（版鋳造は人の専権）。着手する場合は `schemas/` の export 差分と MigrationTest を必ず添える。
- **テストの都合で本番のシグネチャを変えない**（既定引数・コンストラクタ）。継ぎ目はテスト側で作る。
- 触ってよい範囲は**依頼文のディレクトリのみ**。`.github/`・`gradle.properties`・署名関連は触らない。
- `git add` / `commit` / `push` はしない。秘密ファイル（`CLAUDE.local.md` `*.keystore` `*.jks` `*.pem` `*.key` `.env` **`~/.codex/config.toml`**）は読まない・中身を出力しない。
- **実地名・実座標・個人名をコードやテストデータに書かない**（PII の規律）。
