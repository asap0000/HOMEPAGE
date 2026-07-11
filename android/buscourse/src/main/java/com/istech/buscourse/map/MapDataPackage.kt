package com.istech.buscourse.map

import org.json.JSONException
import org.json.JSONObject

/**
 * `.iscmap`の検証・パース失敗（manifest.json形式不良・schemaVersion非対応・SHA-256不一致等）。
 * [MapPackageImporter]がユーザー向けエラー表示に変換する（設計書§5.6.3手順2・4）。
 */
class MapPackageValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `manifest.json`（org.json.JSONObject）をパースした中間表現（設計書§5.6.4 `MapDataPackage`）。
 *
 * ★実物優先の方針（[com.istech.buscourse.core.data.MapDataPackageEntity]のKDoc参照）に合わせ、
 * フィールド構成は設計書§5.6.2のサンプルJSONそのものではなく、実運用で検証済みの`.iscmap`
 * （`D:/ishix/BusCourse/maps/saitama-east-2026.iscmap`実測）の実物`manifest.json`スキーマに揃えている：
 * - `attribution`・`glyphs`キーは設計書執筆後に生まれた正当な拡張として含む。
 * - `mbtiles.format`（常に`"pbf"`固定、設計書§5.4.1）は将来のラスタ対応検討のため参考保持するのみで、
 *   [com.istech.buscourse.core.data.MapDataPackageEntity]の列には含めない（現状は値の検証もしない）。
 * - `tracks`/`stops`はベースマップのみの`.iscmap`では空配列/nullで供給される。将来tracks/stopsを
 *   含むパッケージが来る可能性を考慮しつつ、無い場合でも正常にパースできるようnullable/空配列を許容する
 *   （§5.6.3手順7の`segment_track`/`bus_stop_card`への合流自体は本タスクの対象外）。
 */
data class MapDataPackage(
    val schemaVersion: Int,
    val regionId: String,
    val displayName: String,
    /** ISO8601文字列のまま保持する（[com.istech.buscourse.core.data.MapDataPackageEntity.preparedAt]のKDoc参照）。 */
    val preparedAt: String,
    val preparedBy: String,
    val attribution: String,
    val mbtiles: MbtilesRef,
    val style: StyleRef,
    val glyphs: GlyphsRef,
    val tracks: List<TrackRef> = emptyList(),
    val stops: String? = null,
) {
    companion object {
        /** このインポータが対応する`schemaVersion`（[MapPackageValidator.validateSchemaVersion]が検証）。 */
        const val SUPPORTED_SCHEMA_VERSION = 1

        /**
         * `manifest.json`の内容（[json]）から[MapDataPackage]を組み立てる。
         * 形式不正・必須キー欠落は[MapPackageValidationException]にラップして呼び出し側へ伝える
         * （生の[JSONException]/[IllegalArgumentException]をそのまま外へ漏らさない）。
         */
        fun fromJson(json: JSONObject): MapDataPackage {
            try {
                val mbtilesJson = json.getJSONObject("mbtiles")
                val bounds = mbtilesJson.getJSONArray("bounds")
                require(bounds.length() == 4) {
                    "mbtiles.bounds は [west, south, east, north] の4要素配列である必要があります"
                }
                val styleJson = json.getJSONObject("style")
                val glyphsJson = json.getJSONObject("glyphs")
                val fontstacksJson = glyphsJson.getJSONArray("fontstacks")
                val fontstacks = (0 until fontstacksJson.length()).map { fontstacksJson.getString(it) }

                val tracksJson = json.optJSONArray("tracks")
                val tracks = if (tracksJson == null) {
                    emptyList()
                } else {
                    (0 until tracksJson.length()).map { i ->
                        val t = tracksJson.getJSONObject(i)
                        TrackRef(
                            fromStopCardId = t.getLong("from"),
                            toStopCardId = t.getLong("to"),
                            gpxRelPath = t.getString("gpx"),
                            sha256 = t.getString("sha256"),
                        )
                    }
                }

                return MapDataPackage(
                    schemaVersion = json.getInt("schemaVersion"),
                    regionId = json.getString("regionId"),
                    displayName = json.getString("displayName"),
                    preparedAt = json.getString("preparedAt"),
                    preparedBy = json.getString("preparedBy"),
                    attribution = json.getString("attribution"),
                    mbtiles = MbtilesRef(
                        relPath = mbtilesJson.getString("file"),
                        sha256 = mbtilesJson.getString("sha256"),
                        minzoom = mbtilesJson.getInt("minzoom"),
                        maxzoom = mbtilesJson.getInt("maxzoom"),
                        boundsWest = bounds.getDouble(0),
                        boundsSouth = bounds.getDouble(1),
                        boundsEast = bounds.getDouble(2),
                        boundsNorth = bounds.getDouble(3),
                        format = mbtilesJson.optNullableString("format"),
                    ),
                    style = StyleRef(
                        relPath = styleJson.getString("file"),
                        sha256 = styleJson.getString("sha256"),
                    ),
                    glyphs = GlyphsRef(
                        dirRelPath = glyphsJson.getString("dir"),
                        fontstacks = fontstacks,
                    ),
                    tracks = tracks,
                    stops = json.optNullableString("stops"),
                )
            } catch (e: JSONException) {
                throw MapPackageValidationException("manifest.json の形式が不正です: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw MapPackageValidationException("manifest.json の内容が不正です: ${e.message}", e)
            }
        }

        /** `RecordingSessionRepository.optNullableDouble`と同じ慣習のString版（キー欠落/nullをnullに正規化）。 */
        private fun JSONObject.optNullableString(name: String): String? =
            if (!has(name) || isNull(name)) null else getString(name)
    }
}

/** `manifest.json` `mbtiles`（設計書§5.6.2）。相対パスは`.iscmap`展開先ディレクトリからの相対。 */
data class MbtilesRef(
    val relPath: String,
    val sha256: String,
    val minzoom: Int,
    val maxzoom: Int,
    val boundsWest: Double,
    val boundsSouth: Double,
    val boundsEast: Double,
    val boundsNorth: Double,
    /** 常に`"pbf"`固定（設計書§5.4.1）。上記クラスKDoc参照、現状は参考保持のみで検証しない。 */
    val format: String?,
)

/** `manifest.json` `style`（設計書§5.6.2）。 */
data class StyleRef(val relPath: String, val sha256: String)

/** `manifest.json` `glyphs`（設計書執筆後の拡張キー。上記クラスKDoc参照）。 */
data class GlyphsRef(val dirRelPath: String, val fontstacks: List<String>)

/** `manifest.json` `tracks[]`の1件（設計書§5.6.2。§5.6.3手順7の`segment_track`合流は本タスクの対象外）。 */
data class TrackRef(val fromStopCardId: Long, val toStopCardId: Long, val gpxRelPath: String, val sha256: String)
