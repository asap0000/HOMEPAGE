# HOMEPAGE — istech Android 系統（PrivacyCamera）

> 開発方針は `.istech` サブモジュール（asap0000/istech）から取り込む。
>
> **重要**: `.istech/` が空（サブモジュール未初期化）の場合、下記のインポートは
> **サイレントに失敗**する。`.istech/CLAUDE.md` が存在しない状態では、
> `git submodule update --init` で取得してから作業すること。
> 取得できない環境では、リリース・配布・ネットワーク・署名に関わる作業を進めない
> （istech 方針が読めていない前提で判断しないこと）。

@.istech/CLAUDE.md
@.istech/app-android/CLAUDE.md

## 方針更新の取り込み

サブモジュールはコミット固定のため、istech リポジトリ側の方針更新は自動では反映されない。
取り込むには: `git submodule update --remote .istech && git add .istech && git commit`
