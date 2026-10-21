package com.istech.buscourse.course

import org.json.JSONObject
import java.io.File

/**
 * 停留所の**位置だけ**を持つファイル `stop_positions.json`（運行の記録の時刻から停留所を足す、2026-10-22）。
 *
 * 置き場所は `getExternalFilesDir(null)/stop_positions.json`。PC の `android/tools/stop_positions.py` が
 * PlannerEX の停留所マスタから `lat`/`lon` だけを抜いて書き、adb で置く。形は
 * `{"schema":"stop-positions/1","generatedAt":<ms>,"points":[{"lat":..,"lon":..},…]}`。
 * **名前・番号・人数は入れない・読まない**（オーナー裁定 2026-10-22「位置だけをアプリに持たせてよい」）。
 *
 * ファイルが無い・壊れている・版が違うときは**空**を返す（例外を投げない）。その場合の候補は
 * 「ほかの運行で押したマークの位置」だけで絞られる（[RecordedStopCandidatePicker]）。
 */
object StopPositionsFile {
    const val FILE_NAME = "stop_positions.json"
    const val SCHEMA = "stop-positions/1"

    /** [text] を読んで位置の一覧にする。形が違う・読めないときは空。`lat`/`lon` が数でない点は読み飛ばす。 */
    fun parse(text: String): List<LatLon> = try {
        val root = JSONObject(text)
        if (root.optString("schema") != SCHEMA) {
            emptyList()
        } else {
            val points = root.optJSONArray("points")
            buildList {
                if (points != null) {
                    for (i in 0 until points.length()) {
                        val point = points.optJSONObject(i) ?: continue
                        val lat = point.optDouble("lat", Double.NaN)
                        val lon = point.optDouble("lon", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                        add(LatLon(lat, lon))
                    }
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** [file] を読む。null・無い・読めないときは空。 */
    fun read(file: File?): List<LatLon> {
        if (file == null || !file.isFile) return emptyList()
        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            emptyList()
        }
    }
}
