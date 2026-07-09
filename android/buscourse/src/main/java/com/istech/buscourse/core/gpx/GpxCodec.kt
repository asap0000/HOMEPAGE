package com.istech.buscourse.core.gpx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** GPX解析失敗（整形不良・必須属性欠落等）。UI側でユーザー向けメッセージに変換する。 */
class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** GPXの1点（trkpt / wpt / rtept 共通の座標＋標高＋時刻。設計書§3.11.1）。 */
data class GpxPoint(
    val lat: Double,
    val lon: Double,
    /** `<ele>`（メートル）。無ければ null。 */
    val eleM: Double? = null,
    /** `<time>` を epoch millis 化した値。無ければ null。 */
    val timeEpochMs: Long? = null,
)

/**
 * `<trkseg>` に付与する独自拡張 `<bc:segment from=".." to=".." status=".."/>`（設計書§3.11.2）。
 * 自アプリ発ファイルのラウンドトリップ（区間の有向ペア・status 復元）に使う。
 */
data class GpxSegmentExtension(
    val fromStopCardId: Long?,
    val toStopCardId: Long?,
    val status: String?,
)

/** `<trkseg>`（trkpt列＋独自拡張。設計書§3.11.1）。 */
data class GpxTrackSegment(
    val points: List<GpxPoint>,
    val extension: GpxSegmentExtension? = null,
)

/** `<trk>`（1コース1trk、区間数ぶんの trkseg。設計書§3.11.1）。 */
data class GpxTrack(
    val name: String?,
    val segments: List<GpxTrackSegment>,
) {
    /** 全 trkseg を連結した点列（route_point 再構築等、区間単位GPXの読み出しで使用）。 */
    val points: List<GpxPoint>
        get() = segments.flatMap { it.points }
}

/**
 * `<wpt>`（停留所カード相当。設計書§3.11.1）。
 * [stopCardId] / [photoRef] は独自拡張 `<bc:stopCard id=".." photoRef=".."/>` 由来で、
 * bc: 拡張が無い他アプリ産GPXでは null（§3.11.3：他アプリ産 wpt は参考ポイント扱い）。
 */
data class GpxWaypoint(
    val lat: Double,
    val lon: Double,
    val eleM: Double? = null,
    val name: String? = null,
    val desc: String? = null,
    val stopCardId: Long? = null,
    val photoRef: String? = null,
)

/** `<rte>`（経由順のみ。区間の詳細軌跡は持たない。設計書§3.11.1）。 */
data class GpxRoute(
    val name: String?,
    val points: List<GpxPoint>,
)

/** GPXファイル全体（`<metadata>` → `<wpt>*` → `<rte>*` → `<trk>*`。設計書§3.11.1）。 */
data class GpxDocument(
    val metadataName: String?,
    val metadataTimeEpochMs: Long?,
    val waypoints: List<GpxWaypoint>,
    val routes: List<GpxRoute>,
    val tracks: List<GpxTrack>,
)

/** コース全体エクスポートの入力（設計書§3.11.1の構成要素を揃えたもの。`GpxCodec.writeCourse` が消費）。 */
data class GpxCourseExport(
    val courseName: String,
    val exportedAtEpochMs: Long,
    /** 停留所カード＝wpt（かつ rte の経由点。sequence_index 順）。 */
    val stops: List<GpxWaypoint>,
    /** CONFIRMED 区間＝trkseg（sequence_index 順。PENDING 区間は trkseg を持たない）。 */
    val segments: List<GpxCourseSegmentExport>,
)

/** コースエクスポート内の1区間（trkseg 1つぶん）。 */
data class GpxCourseSegmentExport(
    val fromStopCardId: Long,
    val toStopCardId: Long,
    /** CONFIRMED / PENDING（設計書§3.5 course_segment.status） */
    val status: String,
    val points: List<GpxPoint>,
)

/**
 * GPX 1.1 読み書き（設計書§2.1・§3.11）。`android.util.Xml` の標準 XmlPullParser / XmlSerializer による
 * 自前実装とし、外部GPXライブラリは導入しない（オフライン方針監査対象を増やさない、§3.11.3）。
 *
 * - スキーマ順序は GPX 1.1（`<metadata>` → `<wpt>*` → `<rte>*` → `<trk>*`）に準拠する（§3.11.1）。
 * - 独自拡張は namespace `urn:istech:buscourse:gpx:1`（URN形式、§3.11.2）の `<extensions>` 配下に置く。
 * - 読み込みは寛容（要素順不同・未知要素スキップ・bc:拡張が無い他アプリ産GPXも trk/trkseg/trkpt を受理）、
 *   書き出しは厳格（GPX 1.1 XSD の要素順に従う。※設計書§3.11.1のサンプルは trkseg 内で extensions を
 *   trkpt より先に置いているが、GPX 1.1 XSD の trksegType は trkpt* → extensions の順のため、
 *   本実装は XSD 準拠を優先して extensions を trkpt 列の後に書く。読み込みはどちらの順でも受理する）。
 *
 * `core.*` 層のため Android コンポーネント（Service/Activity）・Room には依存しない（§2.2）。
 * ファイル・Uri の解決や DB 反映（`exportCourse` / `importAsSegmentTrack` の §3.11.3 API としての全体フロー）は
 * `course.CourseRepository` が担い、本オブジェクトはストリームに対する純粋な読み書きのみを提供する。
 */
object GpxCodec {

    /** GPX 1.1 正規 namespace。 */
    const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"

    /** 独自拡張 namespace（URN形式。実URLに見える形式は採らない、設計書§3.11.2）。 */
    const val BC_NAMESPACE = "urn:istech:buscourse:gpx:1"

    /** `<gpx creator="...">`。 */
    const val CREATOR = "BusCourse/istech"

    // ------------------------------------------------------------------
    // 読み込み
    // ------------------------------------------------------------------

    /**
     * GPXファイル全体を読む。信頼して取り込むのは `<trk><trkseg><trkpt>`（＋自アプリ発の bc: 拡張）で、
     * `<wpt>` は参考ポイントとして返すのみ（自動でカード化しない。設計書§3.11.3）。
     */
    fun readDocument(input: InputStream): GpxDocument {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(input, null)

            var metadataName: String? = null
            var metadataTime: Long? = null
            val waypoints = mutableListOf<GpxWaypoint>()
            val routes = mutableListOf<GpxRoute>()
            val tracks = mutableListOf<GpxTrack>()

            parser.nextTag()
            if (parser.name != "gpx") throw GpxParseException("ルート要素が <gpx> ではありません: <${parser.name}>")
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "metadata" -> {
                        val (n, t) = readMetadata(parser)
                        metadataName = n
                        metadataTime = t
                    }
                    "wpt" -> waypoints += readWaypoint(parser)
                    "rte" -> routes += readRoute(parser)
                    "trk" -> tracks += readTrackElement(parser)
                    else -> skip(parser)
                }
            }
            return GpxDocument(metadataName, metadataTime, waypoints, routes, tracks)
        } catch (e: XmlPullParserException) {
            throw GpxParseException("GPXの解析に失敗しました: ${e.message}", e)
        } catch (e: IOException) {
            throw GpxParseException("GPXの読み込みに失敗しました: ${e.message}", e)
        }
    }

    /**
     * トラック読み出し（§3.11.3 `readTrack`。route_point 再構築・区間GPX読み出し用）。
     * 複数 `<trk>` があれば全 trkseg を1本の [GpxTrack] に連結して返す（区間GPXは常に1trk）。
     */
    fun readTrack(file: File): GpxTrack {
        val doc = file.inputStream().buffered().use { readDocument(it) }
        val tracks = doc.tracks
        if (tracks.isEmpty()) throw GpxParseException("<trk> が存在しません: ${file.name}")
        return if (tracks.size == 1) tracks[0]
        else GpxTrack(name = tracks[0].name, segments = tracks.flatMap { it.segments })
    }

    private fun readMetadata(parser: XmlPullParser): Pair<String?, Long?> {
        var name: String? = null
        var time: Long? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "name" -> name = readText(parser)
                "time" -> time = parseTimeOrNull(readText(parser))
                else -> skip(parser)
            }
        }
        return name to time
    }

    private fun readWaypoint(parser: XmlPullParser): GpxWaypoint {
        val lat = requireDoubleAttr(parser, "lat")
        val lon = requireDoubleAttr(parser, "lon")
        var ele: Double? = null
        var name: String? = null
        var desc: String? = null
        var stopCardId: Long? = null
        var photoRef: String? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "ele" -> ele = readText(parser).toDoubleOrNull()
                "name" -> name = readText(parser)
                "desc" -> desc = readText(parser)
                "extensions" -> {
                    val ext = readWaypointExtensions(parser)
                    stopCardId = ext.first
                    photoRef = ext.second
                }
                else -> skip(parser)
            }
        }
        return GpxWaypoint(lat, lon, ele, name, desc, stopCardId, photoRef)
    }

    /** `<extensions><bc:stopCard id=".." photoRef=".."/></extensions>`（設計書§3.11.2）。 */
    private fun readWaypointExtensions(parser: XmlPullParser): Pair<Long?, String?> {
        var stopCardId: Long? = null
        var photoRef: String? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.namespace == BC_NAMESPACE && parser.name == "stopCard") {
                stopCardId = parser.getAttributeValue(null, "id")?.toLongOrNull()
                photoRef = parser.getAttributeValue(null, "photoRef")
                skip(parser)
            } else {
                skip(parser)
            }
        }
        return stopCardId to photoRef
    }

    private fun readRoute(parser: XmlPullParser): GpxRoute {
        var name: String? = null
        val points = mutableListOf<GpxPoint>()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "name" -> name = readText(parser)
                "rtept" -> points += readPointElement(parser)
                else -> skip(parser)
            }
        }
        return GpxRoute(name, points)
    }

    private fun readTrackElement(parser: XmlPullParser): GpxTrack {
        var name: String? = null
        val segments = mutableListOf<GpxTrackSegment>()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "name" -> name = readText(parser)
                "trkseg" -> segments += readTrackSegment(parser)
                else -> skip(parser)
            }
        }
        return GpxTrack(name, segments)
    }

    private fun readTrackSegment(parser: XmlPullParser): GpxTrackSegment {
        val points = mutableListOf<GpxPoint>()
        var extension: GpxSegmentExtension? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "trkpt" -> points += readPointElement(parser)
                "extensions" -> extension = readSegmentExtensions(parser) ?: extension
                else -> skip(parser)
            }
        }
        return GpxTrackSegment(points, extension)
    }

    /** `<extensions><bc:segment from=".." to=".." status=".."/></extensions>`（設計書§3.11.1・§3.11.2）。 */
    private fun readSegmentExtensions(parser: XmlPullParser): GpxSegmentExtension? {
        var ext: GpxSegmentExtension? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.namespace == BC_NAMESPACE && parser.name == "segment") {
                ext = GpxSegmentExtension(
                    fromStopCardId = parser.getAttributeValue(null, "from")?.toLongOrNull(),
                    toStopCardId = parser.getAttributeValue(null, "to")?.toLongOrNull(),
                    status = parser.getAttributeValue(null, "status"),
                )
                skip(parser)
            } else {
                skip(parser)
            }
        }
        return ext
    }

    /** trkpt / rtept 共通の点要素（lat/lon 属性＋ele/time 子要素）。 */
    private fun readPointElement(parser: XmlPullParser): GpxPoint {
        val lat = requireDoubleAttr(parser, "lat")
        val lon = requireDoubleAttr(parser, "lon")
        var ele: Double? = null
        var time: Long? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "ele" -> ele = readText(parser).toDoubleOrNull()
                "time" -> time = parseTimeOrNull(readText(parser))
                else -> skip(parser)
            }
        }
        return GpxPoint(lat, lon, ele, time)
    }

    private fun requireDoubleAttr(parser: XmlPullParser, name: String): Double =
        parser.getAttributeValue(null, name)?.toDoubleOrNull()
            ?: throw GpxParseException("<${parser.name}> の $name 属性が不正です（行 ${parser.lineNumber}）")

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text ?: ""
            parser.nextTag()
        }
        return result.trim()
    }

    private fun skip(parser: XmlPullParser) {
        check(parser.eventType == XmlPullParser.START_TAG)
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT ->
                    throw GpxParseException("GPXが途中で終了しています（行 ${parser.lineNumber}）")
            }
        }
    }

    /**
     * `<time>` のISO 8601文字列を epoch millis へ。`Z` 終端（自アプリ発）とオフセット付き
     * （他アプリ産、例 `+09:00`）の両方を受理し、解釈できない場合は null（点自体は捨てない）。
     */
    private fun parseTimeOrNull(text: String): Long? {
        if (text.isEmpty()) return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    // ------------------------------------------------------------------
    // 書き出し
    // ------------------------------------------------------------------

    /**
     * 区間軌跡1本（`segment_track` の実体、`segments/{from}_{to}.gpx`）を書き出す（§3.11.3
     * 「segment_trackのGPX化」）。1trk・1trkseg 構成で、trkseg に `<bc:segment from=".." to=".."/>` を付す
     * （status はコース非依存の資産のため書かない。コースエクスポート時に course_segment.status を付す）。
     */
    fun writeSegmentTrack(
        output: OutputStream,
        fromStopCardId: Long,
        toStopCardId: Long,
        points: List<GpxPoint>,
        name: String? = null,
    ) {
        val serializer = Xml.newSerializer()
        serializer.setOutput(output, "UTF-8")
        serializer.startDocument("UTF-8", null)
        serializer.setPrefix("", GPX_NAMESPACE)
        serializer.setPrefix("bc", BC_NAMESPACE)
        serializer.startTag(GPX_NAMESPACE, "gpx")
        serializer.attribute(null, "version", "1.1")
        serializer.attribute(null, "creator", CREATOR)

        serializer.startTag(GPX_NAMESPACE, "trk")
        if (name != null) writeTextElement(serializer, "name", name)
        writeTrackSegment(serializer, points, fromStopCardId, toStopCardId, status = null)
        serializer.endTag(GPX_NAMESPACE, "trk")

        serializer.endTag(GPX_NAMESPACE, "gpx")
        serializer.endDocument()
        output.flush()
    }

    /**
     * コース全体エクスポート（§3.11.1・§3.11.3「コース全体エクスポート」）。
     * 1ファイルに 停留所（wpt）・順列（rte）・実測軌跡（trk、CONFIRMED区間ぶんの trkseg）を全て含める。
     */
    fun writeCourse(output: OutputStream, course: GpxCourseExport) {
        val serializer = Xml.newSerializer()
        serializer.setOutput(output, "UTF-8")
        serializer.startDocument("UTF-8", null)
        serializer.setPrefix("", GPX_NAMESPACE)
        serializer.setPrefix("bc", BC_NAMESPACE)
        serializer.startTag(GPX_NAMESPACE, "gpx")
        serializer.attribute(null, "version", "1.1")
        serializer.attribute(null, "creator", CREATOR)

        // <metadata>
        serializer.startTag(GPX_NAMESPACE, "metadata")
        writeTextElement(serializer, "name", course.courseName)
        writeTextElement(serializer, "time", formatTime(course.exportedAtEpochMs))
        serializer.endTag(GPX_NAMESPACE, "metadata")

        // 停留所カード = wpt（wptType の子要素順: ele → name → desc → extensions）
        for (stop in course.stops) {
            serializer.startTag(GPX_NAMESPACE, "wpt")
            serializer.attribute(null, "lat", formatCoord(stop.lat))
            serializer.attribute(null, "lon", formatCoord(stop.lon))
            if (stop.eleM != null) writeTextElement(serializer, "ele", formatDecimal(stop.eleM))
            if (stop.name != null) writeTextElement(serializer, "name", stop.name)
            if (stop.desc != null) writeTextElement(serializer, "desc", stop.desc)
            if (stop.stopCardId != null) {
                serializer.startTag(GPX_NAMESPACE, "extensions")
                serializer.startTag(BC_NAMESPACE, "stopCard")
                serializer.attribute(null, "id", stop.stopCardId.toString())
                if (stop.photoRef != null) serializer.attribute(null, "photoRef", stop.photoRef)
                serializer.endTag(BC_NAMESPACE, "stopCard")
                serializer.endTag(GPX_NAMESPACE, "extensions")
            }
            serializer.endTag(GPX_NAMESPACE, "wpt")
        }

        // 順列 = rte（経由点の並びのみ）
        serializer.startTag(GPX_NAMESPACE, "rte")
        writeTextElement(serializer, "name", course.courseName)
        for (stop in course.stops) {
            serializer.startTag(GPX_NAMESPACE, "rtept")
            serializer.attribute(null, "lat", formatCoord(stop.lat))
            serializer.attribute(null, "lon", formatCoord(stop.lon))
            if (stop.name != null) writeTextElement(serializer, "name", stop.name)
            serializer.endTag(GPX_NAMESPACE, "rtept")
        }
        serializer.endTag(GPX_NAMESPACE, "rte")

        // 区間軌跡 = trk（1コース1trk、区間数ぶんの trkseg。trksegの切れ目=停留所境界）
        if (course.segments.isNotEmpty()) {
            serializer.startTag(GPX_NAMESPACE, "trk")
            writeTextElement(serializer, "name", "${course.courseName} 実測軌跡")
            for (seg in course.segments) {
                writeTrackSegment(serializer, seg.points, seg.fromStopCardId, seg.toStopCardId, seg.status)
            }
            serializer.endTag(GPX_NAMESPACE, "trk")
        }

        serializer.endTag(GPX_NAMESPACE, "gpx")
        serializer.endDocument()
        output.flush()
    }

    private fun writeTrackSegment(
        serializer: org.xmlpull.v1.XmlSerializer,
        points: List<GpxPoint>,
        fromStopCardId: Long?,
        toStopCardId: Long?,
        status: String?,
    ) {
        serializer.startTag(GPX_NAMESPACE, "trkseg")
        for (p in points) {
            serializer.startTag(GPX_NAMESPACE, "trkpt")
            serializer.attribute(null, "lat", formatCoord(p.lat))
            serializer.attribute(null, "lon", formatCoord(p.lon))
            if (p.eleM != null) writeTextElement(serializer, "ele", formatDecimal(p.eleM))
            if (p.timeEpochMs != null) writeTextElement(serializer, "time", formatTime(p.timeEpochMs))
            serializer.endTag(GPX_NAMESPACE, "trkpt")
        }
        if (fromStopCardId != null && toStopCardId != null) {
            // GPX 1.1 XSD の trksegType に従い extensions は trkpt 列の後（KDoc冒頭の注記参照）
            serializer.startTag(GPX_NAMESPACE, "extensions")
            serializer.startTag(BC_NAMESPACE, "segment")
            serializer.attribute(null, "from", fromStopCardId.toString())
            serializer.attribute(null, "to", toStopCardId.toString())
            if (status != null) serializer.attribute(null, "status", status)
            serializer.endTag(BC_NAMESPACE, "segment")
            serializer.endTag(GPX_NAMESPACE, "extensions")
        }
        serializer.endTag(GPX_NAMESPACE, "trkseg")
    }

    private fun writeTextElement(serializer: org.xmlpull.v1.XmlSerializer, name: String, text: String) {
        serializer.startTag(GPX_NAMESPACE, name)
        serializer.text(text)
        serializer.endTag(GPX_NAMESPACE, name)
    }

    /**
     * 緯度経度・標高の数値表記（GPX 1.1 XSD の lat/lon/ele は xsd:decimal で、指数表記
     * （`1.0E-5` 等）を許容しない）。`Double.toString()` は絶対値が1e-3未満だと指数表記を
     * 返しうるため、`BigDecimal.valueOf`（内部で `Double.toString` の最短往復表現を経由する
     * ため精度は変わらない）→`toPlainString()` で固定小数点表記に変換する。
     */
    private fun formatDecimal(value: Double): String = java.math.BigDecimal.valueOf(value).toPlainString()

    /** 緯度経度の数値表記（[formatDecimal] と同じ。呼び出し箇所の可読性のため別名を残す）。 */
    private fun formatCoord(value: Double): String = formatDecimal(value)

    /** ISO 8601 UTC（`Z`終端、秒精度。設計書§3.11.1のサンプル形式）。 */
    private fun formatTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()
}
