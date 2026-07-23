#!/usr/bin/env bash
# test-buscourse.sh — :buscourse ユニットテストを回し、§3b の「生の機械出力」を印字する漏斗スクリプト。
#
# 目的: 残存許可プロンプト対策（官房 2026-07-23 裁定②「祝福スクリプトの漏斗」）。
#   毎回手打ちしていた「JAVA_HOME 明示 + gradlew テスト + XML 集計 + 失敗名」を1本の
#   引数なし・git 追跡スクリプトに束ね、これだけを .claude/settings.json の allowlist に載せる。
#
# 出力（§3b 独立レビューが読む生の機械出力）:
#   - gradlew の終了コード
#   - テスト総数 / 失敗+エラー数（JUnit XML の tests/failures/errors 属性を集計）
#   - 失敗したテストケース名（あれば）
#
# 使い方: android/ からでもリポジトリのどこからでも動く（引数なし）。
#   bash android/tools/test-buscourse.sh
set -u

# --- android ディレクトリを自分の位置から解決（呼び出し場所に依存しない） ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- JAVA_HOME 明示（env が有効ならそれを、無ければ Android Studio 同梱 openjdk へフォールバック） ---
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ] && [ ! -x "${JAVA_HOME:-}/bin/java.exe" ]; then
  for cand in \
    "/c/Program Files/Android/openjdk/jdk-21.0.8" \
    "/c/Program Files/Android/Android Studio/jbr"; do
    if [ -x "$cand/bin/java.exe" ] || [ -x "$cand/bin/java" ]; then
      export JAVA_HOME="$cand"
      break
    fi
  done
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "ERROR: JAVA_HOME を解決できません（env 未設定かつ既知の openjdk も見つからず）。" >&2
  exit 2
fi
echo "JAVA_HOME=$JAVA_HOME"

# --- テスト実行 ---
cd "$ANDROID_DIR"
./gradlew :buscourse:testDebugUnitTest --console=plain
GRADLE_EXIT=$?

# --- JUnit XML 集計（§3b: 生の機械出力） ---
RESULT_DIR="$ANDROID_DIR/buscourse/build/test-results/testDebugUnitTest"
total=0; problems=0; files=0
if [ -d "$RESULT_DIR" ]; then
  for f in "$RESULT_DIR"/*.xml; do
    [ -e "$f" ] || continue
    files=$((files + 1))
    t=$(grep -o 'tests="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*'); t=${t:-0}
    fl=$(grep -o 'failures="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*'); fl=${fl:-0}
    er=$(grep -o 'errors="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*'); er=${er:-0}
    total=$((total + t))
    problems=$((problems + fl + er))
  done
fi

echo "----- test-buscourse summary (§3b raw) -----"
echo "gradle_exit=$GRADLE_EXIT"
echo "suites=$files tests=$total failures_errors=$problems"
if [ "$problems" -gt 0 ]; then
  echo "failing_testcases:"
  # failure/error を含む testcase 要素の name を列挙（クラス名は各 XML のファイル名で辿れる）
  grep -rlE "<(failure|error)" "$RESULT_DIR"/*.xml 2>/dev/null | while read -r f; do
    echo "  [$(basename "$f")]"
  done
fi

# 終了コードは gradle を尊重（テスト失敗なら非0）
exit $GRADLE_EXIT
