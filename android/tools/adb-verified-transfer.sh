#!/usr/bin/env bash
#
# ベリファイ付き adb 転送（チャンク照合＋全体ハッシュ照合＋自動再送）
#
# 【なぜ必要か】
# `adb pull` / `adb push` / `adb exec-out` は GB 級のファイルで **exit 0 を返しながら途中で切れる**。
# 実測（istech Android チーム 2026-07-26）: 同一ファイルで 10% / 20% / 32% / 51% と、毎回違う位置で
# 静かに切れた（ディスクは 1.6TB 空き）。**成功を報告しながら壊れたファイルを渡してくる**のが最悪の性質で、
# 気づかずに復元へ流すと「バックアップがあるのに戻せない」という最も避けたい事故になる。
#
# 【★2026-07-29 の実測＝壊れ方の正体と、効く/効かない対策（VM1 から 2.2GB を取り出した記録）】
#   - **adb はエラーを一切出さない**。出力は `[  0%] <path>` の進捗表示だけで、失敗を成功として返す。
#   - **毎回違う壊れ方をする**（同じチャンクを3回取ると 3種のハッシュ／うち1つは**空ファイル**の e3b0c442…）。
#   - **効かなかった対策**: チャンクを 64MB→16MB に落とす（**サイズを下げても壊れた**）／
#     作業ディレクトリを空きの多いドライブへ移す（**容量とは無関係だった**）。
#   - **★効いた対策＝「間隔を空ける」**。失敗が**飛び飛びのチャンク**で起き、しかも**取り直すと通る**——
#     つまり原因はサイズではなく **連続実行で adb が不安定になること**。
#     成功後 0.5 秒・失敗後 3 秒を入れたところ、**132チャンク 2.2GB が全体ハッシュ一致で完走**した
#     （不一致は4回発生したが**すべて再取得で回復**）。**速度より確実性を採る**のが正しい。
#   - 併せて `adb kill-server` で接続をクリーンにしてから始めるとよい（実機とエミュレータを同時に
#     抱えた状態が続くと不安定になりうる）。
#
# 【設計方針（オーナー指示 2026-07-27）】
# 宇宙通信や長距離データセンター間転送と同じく、**通信路の品質を信用しない**。
#   1. ファイルをチャンクに割り、**チャンクごとに SHA-256 を照合**する（サイレントな化けを局所で検出）
#   2. 壊れたチャンクだけを**再送**する（全体をやり直さない）
#   3. 結合後に**全体の SHA-256 を照合**する（結合そのものの失敗も捕まえる）
# 「転送できた」ではなく「**転送できたことを証明した**」を出力の意味とする。
#
# 使い方:
#   bash adb-verified-transfer.sh push <serial> <ローカルファイル> <端末側ディレクトリ>
#   bash adb-verified-transfer.sh pull <serial> <端末側ファイル>   <ローカルディレクトリ>
# 環境変数:
#   CHUNK_MB   チャンクサイズ（既定 32）
#   MAX_RETRY  1チャンクあたりの再送上限（既定 3）
#
# 注意: serial は必ず明示指定する（istech 官房 CHARTER §3d「委託の作法」＝操作対象を曖昧にしない。
#       serial は起動順で入れ替わるので、事前に `adb -s <serial> emu avd name` で名前を確認すること）。

set -uo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash が /sdcard を C:\...\sdcard に変換するのを止める

CHUNK_MB="${CHUNK_MB:-32}"
MAX_RETRY="${MAX_RETRY:-3}"
# 実測（2026-07-29・エミュレータから 2.2GB）: **連続 pull で `EMPTY/欠落` が飛び飛びに出る**。
# サイズを 64MB→16MB に落としても解消せず、**間隔を空けることが効いた**。
# 転送そのものが不安定な環境なので、速度より確実性を採る。
CHUNK_WAIT="${CHUNK_WAIT:-0.5}"   # 成功したチャンクの後に待つ秒数
RETRY_WAIT="${RETRY_WAIT:-3}"     # 失敗後に待つ秒数
WORK_DEVICE="/data/local/tmp/.vxfer"

die() { echo "ERROR: $*" >&2; exit 1; }

usage() {
  echo "usage: $0 push <serial> <local-file> <device-dir>" >&2
  echo "       $0 pull <serial> <device-file> <local-dir>" >&2
  exit 2
}

# Git Bash で adb にローカルパスを渡すときは Windows 形式に直す。
# `MSYS_NO_PATHCONV=1`（端末側の /data/... が C:\...\data\... に化けるのを止めるために必須）は
# **ローカルパスの自動変換も同時に止めてしまう**ため、ローカル側は自分で変換する必要がある
# （これを忘れると adb が `cannot stat '/tmp/...'` を返し、症状は「チャンクが空」に見える）。
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# 端末側で sha256 を取る（先頭フィールドのみ）
device_sha256() { adb -s "$1" shell "sha256sum '$2' 2>/dev/null" | awk '{print $1}'; }
local_sha256()  { sha256sum "$1" | awk '{print $1}'; }

cleanup_device() { adb -s "$1" shell "rm -rf '$WORK_DEVICE'" >/dev/null 2>&1 || true; }

# ------------------------------------------------------------------ push
do_push() {
  local serial="$1" src="$2" dst_dir="$3"
  [ -f "$src" ] || die "ローカルファイルが無い: $src"
  local name; name="$(basename "$src")"
  local dst="$dst_dir/$name"
  local total; total="$(local_sha256 "$src")"
  local size; size="$(stat -c %s "$src")"
  echo "[push] $src -> $serial:$dst"
  echo "[push] size=${size} sha256=${total} chunk=${CHUNK_MB}MB"

  local work; work="$(mktemp -d)"
  trap 'rm -rf "$work"; cleanup_device "'"$serial"'"' RETURN
  split -b "${CHUNK_MB}m" -a 4 -d "$src" "$work/c." || die "split に失敗"

  adb -s "$serial" shell "rm -rf '$WORK_DEVICE'; mkdir -p '$WORK_DEVICE'" >/dev/null || die "作業ディレクトリを作れない"

  local n=0 ok=0
  for f in "$work"/c.*; do
    n=$((n + 1))
    local base; base="$(basename "$f")"
    local want; want="$(local_sha256 "$f")"
    local try=0 got="" err=""
    while [ "$try" -lt "$MAX_RETRY" ]; do
      try=$((try + 1))
      # ★adb のエラーを捨てない（pull 側と同じ理由）。
      err="$(adb -s "$serial" push "$(to_host "$f")" "$WORK_DEVICE/$base" 2>&1)"
      got="$(device_sha256 "$serial" "$WORK_DEVICE/$base")"
      [ "$got" = "$want" ] && break
      echo "[push]   chunk $base 不一致（試行 $try/$MAX_RETRY）: want=$want got=${got:-EMPTY/欠落}" >&2
      [ -n "$err" ] && echo "[push]     adb: $(echo "$err" | tr '\n' ' ' | cut -c1-200)" >&2
      sleep 1
    done
    [ "$got" = "$want" ] || die "chunk $base を $MAX_RETRY 回で送りきれなかった（最後の adb 出力: ${err:-なし}）"
    [ "$try" -gt 1 ] && echo "[push]   chunk $base 再送 $((try - 1)) 回で一致"
    ok=$((ok + 1))
  done
  echo "[push] chunks=$n すべて一致"

  adb -s "$serial" shell "mkdir -p '$dst_dir' && cat '$WORK_DEVICE'/c.* > '$dst' && sync" >/dev/null \
    || die "端末側の結合に失敗"
  local final; final="$(device_sha256 "$serial" "$dst")"
  [ "$final" = "$total" ] || die "結合後の全体ハッシュ不一致: want=$total got=${final:-EMPTY}"
  echo "[push] OK 全体ハッシュ一致 ($final)"
  echo "[push] 端末側: $dst"
}

# ------------------------------------------------------------------ pull
do_pull() {
  local serial="$1" src="$2" dst_dir="$3"
  mkdir -p "$dst_dir" || die "ローカルディレクトリを作れない: $dst_dir"
  local name; name="$(basename "$src")"
  local dst="$dst_dir/$name"
  local total; total="$(device_sha256 "$serial" "$src")"
  [ -n "$total" ] || die "端末側ファイルの sha256 が取れない（存在しない？）: $src"
  echo "[pull] $serial:$src -> $dst"
  echo "[pull] sha256=${total} chunk=${CHUNK_MB}MB"

  adb -s "$serial" shell "rm -rf '$WORK_DEVICE'; mkdir -p '$WORK_DEVICE' && split -b ${CHUNK_MB}m '$src' '$WORK_DEVICE/c.'" >/dev/null \
    || die "端末側の split に失敗"
  local list; list="$(adb -s "$serial" shell "ls '$WORK_DEVICE'" | tr -d '\r')"
  [ -n "$list" ] || die "チャンクが1つも作られていない"

  # 作業ディレクトリは **出力先と同じドライブ**に置く（既定の TEMP は C: で、
  # C: が逼迫していると GB 級で書けずに「空ファイルが取れた」ように見える）。
  local work="$dst_dir/.vxfer_work"
  rm -rf "$work"; mkdir -p "$work" || die "作業ディレクトリを作れない: $work"
  trap 'rm -rf "$work"; cleanup_device "'"$serial"'"' RETURN

  local n=0
  for base in $list; do
    n=$((n + 1))
    local want; want="$(device_sha256 "$serial" "$WORK_DEVICE/$base")"
    local try=0 got="" err=""
    while [ "$try" -lt "$MAX_RETRY" ]; do
      try=$((try + 1))
      rm -f "$work/$base"
      # ★adb のエラーを捨てない（初版の欠陥）。「静かに壊れる」を捕まえる道具が
      #   自分のエラーを黙って捨てていては、**なぜ壊れたのかが永久に分からない**。
      err="$(adb -s "$serial" pull "$WORK_DEVICE/$base" "$(to_host "$work/$base")" 2>&1)"
      got="$([ -s "$work/$base" ] && local_sha256 "$work/$base")"
      [ "$got" = "$want" ] && break
      echo "[pull]   chunk $base 不一致（試行 $try/$MAX_RETRY）: want=$want got=${got:-EMPTY/欠落}" >&2
      [ -n "$err" ] && echo "[pull]     adb: $(echo "$err" | tr '\n' ' ' | cut -c1-200)" >&2
      # ★失敗後は長めに待つ。実測（2026-07-29・VM1 の 2.2GB）では **連続 pull で
      #   `EMPTY/欠落` が飛び飛びに発生**した＝サイズではなく**連続実行で adb が不安定になる**。
      sleep "$RETRY_WAIT"
    done
    [ "$got" = "$want" ] || die "chunk $base を $MAX_RETRY 回で取りきれなかった（最後の adb 出力: ${err:-なし}）"
    [ "$try" -gt 1 ] && echo "[pull]   chunk $base 再取得 $((try - 1)) 回で一致"
    # 成功時も少し間を空ける（連続実行そのものが不安定要因のため）。
    sleep "$CHUNK_WAIT"
  done
  echo "[pull] chunks=$n すべて一致"

  cat "$work"/c.* > "$dst" || die "ローカルの結合に失敗"
  local final; final="$(local_sha256 "$dst")"
  [ "$final" = "$total" ] || die "結合後の全体ハッシュ不一致: want=$total got=$final"
  echo "[pull] OK 全体ハッシュ一致 ($final)"
  echo "[pull] ローカル: $dst"
}

# ------------------------------------------------------------------ main
[ $# -eq 4 ] || usage
case "$1" in
  push) do_push "$2" "$3" "$4" ;;
  pull) do_pull "$2" "$3" "$4" ;;
  *)    usage ;;
esac
