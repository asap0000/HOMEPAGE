package com.istech.buscourse.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * [StyleJsonResolver.resolveAndValidate]がfail-closedで投げる違反例外（設計書§5.6.3手順5）。
 * 部分的な無効化（違反フィールドだけ潰す等）はしない。呼び出し側でインポート処理全体を中止すること。
 */
class StyleJsonSchemeViolationException(val offendingFields: List<String>) :
    Exception("外部スキーム(http/https)を検出したためインポートを中止します: $offendingFields")

/**
 * `style.json`内のプレースホルダ（`{{MBTILES_ABS_PATH}}`・`{{GLYPHS_ABS_PATH}}`）を実パスへ解決し、
 * 解決後に外部スキーム（http/https）の混入がないか再走査する
 * （設計書§5.3.2「文字列置換ではなく`org.json.JSONObject`でパース→走査して書き換える」方針・
 * §5.6.3手順5・§5.6.4 `StyleJsonResolver`）。
 *
 * ## ★実物優先の調整（設計書執筆時の想定との相違）
 * 設計書§5.3.2のサンプルは`sources.*.tiles`配列＋`sprite`/`glyphs`とも`asset://`固定を想定していたが、
 * 実運用で検証済みの`.iscmap`実測の実物`style.json`は以下のとおり（
 * [com.istech.buscourse.core.data.MapDataPackageEntity]のKDoc・依頼メモ参照）：
 * - `sources.<name>.tiles`配列ではなく`sources.<name>.url`単一文字列
 *   （`"url": "mbtiles://{{MBTILES_ABS_PATH}}"`）。
 * - `sprite`フィールドはこのビルドに存在しない（スプライト無し方針。SymbolManagerで実行時addImage）。
 * - トップレベル`glyphs`は`"{{GLYPHS_ABS_PATH}}/{fontstack}/{range}.pbf"`という新規プレースホルダを持つ。
 * 本実装は`tiles`配列・`sprite`も（将来のバリエーションに備え）解決対象・走査対象に含めるが、
 * 現行の実物パッケージでは`sources.*.url`と`glyphs`だけが実際に置換される。
 *
 * ## `{{GLYPHS_ABS_PATH}}`のスキームについて（実地調査結果、設計書上未実証だった事項の確認）
 * MapLibre Android SDK（`org.maplibre.gl:android-sdk:13.3.1`）はURLスキームに応じて別々の
 * ネイティブ`FileSource`実装へディスパッチする（mbgl-core、`FileSourceType`列挙: `Asset`＝APKの
 * `assets/`をAndroidの`AssetManager`経由で読む`AssetFileSource`、`FileSystem`＝任意の実ファイル
 * システム絶対パスを読む`LocalFileSource`、他に`Network`・`Mbtiles`・`PMTiles`）。
 * AAR（`~/.gradle/caches/modules-2/files-2.1/org.maplibre.gl/android-sdk/13.3.1/`配下で取得済み）内の
 * 各ABI配下の`libmaplibre.so`（例: `jni/arm64-v8a/libmaplibre.so`）を実際にバイト列走査し、
 * `asset://`・`file://`・`mbtiles://`・`pmtiles://`の
 * 各スキームリテラル文字列と、`AssetFileSource`・`LocalFileSource`という個別のC++クラスの
 * マングル済みシンボル（`N4mbgl15AssetFileSourceE`・`N4mbgl15LocalFileSourceE`）が両方とも
 * 実在することを確認した（＝`asset://`と`file://`は同一クラスの別名ではなく、別々の実装を持つ）。
 * さらにMapLibre Native公式リポジトリのIssue #3559
 * （https://github.com/maplibre/maplibre-native/issues/3559 ）は「Android上で`asset://`は
 * `file://`が使える場所ならどこでも使える代替」という趣旨を明記しており、`asset://`はAPK同梱
 * `assets/`専用（`AssetManager`経由）である一方、`file://`は任意の実ファイルパス
 * （本インポート先である`filesDir`配下を含む）を指せる汎用スキームであることが裏付けられる。
 * これは設計書§5.4.1が`mbtiles://`について既に定義している「空ホスト＋絶対パスの3スラッシュ形式
 * （`mbtiles:///<絶対パス>`）は`file://`と同型」という記述とも整合する。
 * 以上により、`filesDir`配下に展開したグリフディレクトリを指す`{{GLYPHS_ABS_PATH}}`は
 * `file:///<絶対パス>`（3スラッシュ形式、`mbtiles://`と同型）へ解決する。グリフはAPKの`assets/`では
 * なく実行時にインポートされたファイルのため`asset://`は使えない。
 */
object StyleJsonResolver {

    /** style.json内の`sources.*.url`/`sources.*.tiles`に現れるmbtilesの絶対パスプレースホルダ。 */
    const val MBTILES_PLACEHOLDER = "{{MBTILES_ABS_PATH}}"

    /**
     * style.json内のトップレベル`glyphs`に現れるグリフディレクトリの絶対パスプレースホルダ。
     * 置換値そのものに`file://`スキームを含める（上記クラスKDoc参照。style.json側のテンプレートに
     * 固定スキーム接頭辞が無いため、置換元の[resolveAndValidate]呼び出し側が渡す
     * `glyphsAbsDirPath`にこちらで`file://`を付与する）。
     */
    const val GLYPHS_PLACEHOLDER = "{{GLYPHS_ABS_PATH}}"

    /** 走査対象のトップレベル単一文字列フィールド（設計書§5.3.2のLintと同一ロジックを共用）。 */
    private val TOP_LEVEL_URL_FIELDS = listOf("sprite", "glyphs")

    /**
     * [rawStyleJson]内のプレースホルダを実パスへ解決し、解決後に外部スキームが残っていないか検査する。
     * 1件でも検出した場合は[StyleJsonSchemeViolationException]を投げる（fail-closed、部分適用はしない）。
     * 呼び出し元（[rawStyleJson]自体）は変更しない（複製してから書き換える）。
     *
     * @param mbtilesAbsPath 展開済み`region.mbtiles`の絶対パス（スキームを含まない生パス。
     *   style.json側のテンプレートに`"mbtiles://"`が既に含まれるため、プレースホルダ置換ではパスのみを渡す）。
     * @param glyphsAbsDirPath 展開済み`glyphs/`ディレクトリの絶対パス（末尾スラッシュなし、スキームを含まない）。
     *   置換時にこちらで`file://`を前置する（上記[GLYPHS_PLACEHOLDER]のKDoc参照）。
     */
    fun resolveAndValidate(
        rawStyleJson: JSONObject,
        mbtilesAbsPath: String,
        glyphsAbsDirPath: String,
    ): JSONObject {
        // JSONObjectはミュータブルなので、呼び出し元のインスタンスを直接書き換えないよう複製してから処理する
        val resolved = JSONObject(rawStyleJson.toString())
        replaceMbtilesPlaceholder(resolved, mbtilesAbsPath)
        replaceGlyphsPlaceholder(resolved, "file://$glyphsAbsDirPath")

        val violations = scanForHttpSchemes(resolved)
        if (violations.isNotEmpty()) {
            throw StyleJsonSchemeViolationException(violations)
        }
        return resolved
    }

    private fun replaceMbtilesPlaceholder(style: JSONObject, mbtilesAbsPath: String) {
        val sources = style.optJSONObject("sources") ?: return
        for (name in sources.keys()) {
            val source = sources.optJSONObject(name) ?: continue

            // 実物フォーマット: sources.*.url（単一文字列、設計書執筆時の想定はtiles配列。上記クラスKDoc参照）
            val url = source.optNullableString("url")
            if (url != null && url.contains(MBTILES_PLACEHOLDER)) {
                source.put("url", url.replace(MBTILES_PLACEHOLDER, mbtilesAbsPath))
            }

            // 将来のtiles配列版にも備えて両対応する（現行の実物パッケージでは通らない経路）
            val tiles = source.optJSONArray("tiles")
            if (tiles != null) {
                for (i in 0 until tiles.length()) {
                    val t = tiles.optNullableString(i)
                    if (t != null && t.contains(MBTILES_PLACEHOLDER)) {
                        tiles.put(i, t.replace(MBTILES_PLACEHOLDER, mbtilesAbsPath))
                    }
                }
            }
        }
    }

    private fun replaceGlyphsPlaceholder(style: JSONObject, glyphsAbsUri: String) {
        val glyphs = style.optNullableString("glyphs") ?: return
        if (glyphs.contains(GLYPHS_PLACEHOLDER)) {
            style.put("glyphs", glyphs.replace(GLYPHS_PLACEHOLDER, glyphsAbsUri))
        }
    }

    /**
     * 解決後の`sources.*.url`/`sources.*.tiles`/`sprite`/`glyphs`全フィールドに対し
     * `http://`または`https://`スキームが含まれていないか走査する（設計書§5.3.2のLintと同一ロジック）。
     * 違反したフィールドパス（例: "sources.openmaptiles.url"）の一覧を返す（空なら違反なし）。
     */
    fun scanForHttpSchemes(style: JSONObject): List<String> {
        val violations = mutableListOf<String>()

        val sources = style.optJSONObject("sources")
        if (sources != null) {
            for (name in sources.keys()) {
                val source = sources.optJSONObject(name) ?: continue
                val url = source.optNullableString("url")
                if (url != null && isHttpScheme(url)) violations += "sources.$name.url"
                val tiles = source.optJSONArray("tiles")
                if (tiles != null) {
                    for (i in 0 until tiles.length()) {
                        val t = tiles.optNullableString(i)
                        if (t != null && isHttpScheme(t)) violations += "sources.$name.tiles[$i]"
                    }
                }
            }
        }
        for (field in TOP_LEVEL_URL_FIELDS) {
            val value = style.optNullableString(field)
            if (value != null && isHttpScheme(value)) violations += field
        }
        return violations
    }

    private fun isHttpScheme(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** キー欠落/JSON nullをKotlinのnullへ正規化するString版（`org.json.JSONObject.optString`はプラットフォーム型の警告が出るため用意）。 */
    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    /** 上記のJSONArray版（tiles配列要素用）。 */
    private fun JSONArray.optNullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)
}
