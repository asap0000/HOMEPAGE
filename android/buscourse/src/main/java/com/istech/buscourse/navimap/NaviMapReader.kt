package com.istech.buscourse.navimap

import androidx.room.withTransaction
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.NaviBranchEntity
import com.istech.buscourse.core.data.NaviEventEntity
import com.istech.buscourse.core.data.NaviEventOutputEntity
import com.istech.buscourse.core.data.NaviMapEntity
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** `.isnavi` の拒否理由。呼出し側はこの値をそのままユーザー向け説明へ対応付けられる。 */
class IsnaviValidationException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason {
        NOT_A_ZIP, MISSING_ENTRY, MALFORMED_JSON,
        UNSUPPORTED_SCHEMA, EXTERNAL_URL, NON_FINITE_NUMBER,
        MISSING_COURSE_IDENTITY, INVALID_PROFILE, GPX_INVARIANT,
        TRACKGAP_MANEUVER, CHAINAGE_NOT_MONOTONIC, MISSING_TEMPLATE_VARIABLE,
        /** navi_branch/navi_segment/navi_event の id がファイル内で重複（後勝ちで子データが取り違え／欠落し
         *  INV-NAVIMAP-GPX を静かに破るため拒否・敵対的レビュー F-isnavi-01）。 */
        DUPLICATE_ID,
        /** segment/event の branch_id が同一パッケージ内の navi_branch に存在しない（破損ファイルの兆候・F-isnavi-02）。 */
        DANGLING_BRANCH_REF,
    }
}

/**
 * InputStream の `.isnavi` を読み込む、ネットワーク非依存のリーダー。
 *
 * 重要: [parseAndValidate] は DB に一切触れない。従って検証例外では、トランザクションを開始する前から
 * Room が不変である。書込み段階も単一トランザクションなので I/O 以外の失敗で部分投入しない。
 */
class NaviMapReader(private val database: BusCourseDatabase) {
    private val repository = NaviMapRepository(database)

    suspend fun read(input: InputStream, now: Long = System.currentTimeMillis()): Long {
        val packageData = parseAndValidate(input)
        return database.withTransaction {
            val mapId = repository.registerMap(packageData.map.copy(createdAt = now, updatedAt = now), now)
            val dao = database.naviMapDao()
            val branches = packageData.branches.associate { it.sourceId to dao.insertBranch(it.entity(mapId)) }
            val segments = packageData.segments.associate { segment ->
                val branchId = segment.sourceBranchId?.let { branches[it] }
                segment.sourceId to dao.insertSegment(segment.entity(mapId, branchId))
            }
            packageData.tracks.forEach { (sourceSegmentId, points) ->
                val segmentId = checkNotNull(segments[sourceSegmentId])
                points.forEachIndexed { index, point -> dao.insertTrackPoint(point.entity(segmentId, index)) }
            }
            val events = packageData.events.associate { event ->
                val branchId = event.sourceBranchId?.let { branches[it] }
                event.sourceId to dao.insertEvent(event.entity(mapId, branchId))
            }
            packageData.outputs.forEach { output ->
                val eventId = events[output.sourceEventId]
                    ?: throw IsnaviValidationException(IsnaviValidationException.Reason.MALFORMED_JSON, "output が未知の event_id を参照しています")
                dao.insertOutput(output.entity(eventId))
            }
            mapId
        }
    }

    private fun parseAndValidate(input: InputStream): PackageData {
        val entries = readZip(input)
        val manifest = parseObject(entries.required("manifest.json"))
        val segmentsJson = parseObject(entries.required("segments.json"))
        val eventsJson = parseObject(entries.required("events.json"))

        // Offline と有限性は、保持する JSON 全体（未知の将来フィールドも含む）に対して先に検証する。
        listOf(manifest, segmentsJson, eventsJson).forEach(::validateJsonTree)

        val schema = manifest.requiredString("schema_version")
        if (schema != "1.0" && schema != "1.1") fail(IsnaviValidationException.Reason.UNSUPPORTED_SCHEMA, "未対応 schema_version: $schema")
        val profile = manifest.requiredString("profile")
        if (profile !in setOf("ex_full", "app_simple")) fail(IsnaviValidationException.Reason.INVALID_PROFILE, "不正な profile: $profile")
        val identity = manifest.optJSONObject("course_identity")
            ?: fail(IsnaviValidationException.Reason.MISSING_COURSE_IDENTITY, "course_identity がありません")
        val busId = identity.optString("bus_id", "").takeIf { it.isNotBlank() }
            ?: fail(IsnaviValidationException.Reason.MISSING_COURSE_IDENTITY, "bus_id がありません")
        val courseNo = identity.requiredIntOrIdentity("course_no")
        val year = identity.requiredIntOrIdentity("year")
        val display = manifest.optJSONObject("display")
        val orientation = display?.optString("orientation", "")
            ?.takeIf { it == "heading_up" || it == "north_up" } ?: "north_up"
        val pitch = display?.optNumber("pitch_deg")?.toDouble()?.coerceIn(0.0, 60.0) ?: 0.0
        val appSettings = if (manifest.has("app_settings") && !manifest.isNull("app_settings")) {
            manifest.optJSONObject("app_settings")?.toString()
                ?: fail(IsnaviValidationException.Reason.MALFORMED_JSON, "app_settings が object ではありません")
        } else "{}"

        val branches = segmentsJson.requiredArray("navi_branch").mapObjects(::parseBranch)
        val segments = segmentsJson.requiredArray("navi_segment").mapObjects(::parseSegment).sortedBy { it.seq }
        // ★id はファイル内一意でなければならない（後勝ちで track/output が取り違え／欠落し INV-NAVIMAP-GPX を
        //   静かに破るため。F-isnavi-01）。branch_id のダングリング（同一パッケージ内に存在しない参照）も弾く（F-isnavi-02）。
        requireUniqueIds(branches.map { it.sourceId }, "navi_branch")
        requireUniqueIds(segments.map { it.sourceId }, "navi_segment")
        val branchIds = branches.map { it.sourceId }.toSet()
        segments.forEach { seg ->
            if (seg.sourceBranchId != null && seg.sourceBranchId !in branchIds) {
                fail(IsnaviValidationException.Reason.DANGLING_BRANCH_REF, "navi_segment ${seg.sourceId} の branch_id が存在しません")
            }
        }
        validateSegments(segments)
        val tracks = linkedMapOf<String, List<TrackPoint>>()
        segments.filter { it.kind == "TRACK" }.forEach { segment ->
            val raw = entries["tracks/track_${segment.sourceId}.json"]
                ?: fail(IsnaviValidationException.Reason.GPX_INVARIANT, "TRACK ${segment.sourceId} のファイルがありません")
            val array = parseArray(raw)
            validateJsonTree(array)
            val points = array.mapObjects(::parseTrackPoint)
            if (points.size < 2) fail(IsnaviValidationException.Reason.GPX_INVARIANT, "TRACK ${segment.sourceId} の実走点が2点未満です")
            if (points.zipWithNext().any { it.first.chainageM > it.second.chainageM }) {
                fail(IsnaviValidationException.Reason.CHAINAGE_NOT_MONOTONIC, "TRACK ${segment.sourceId} の chainage が後退しています")
            }
            tracks[segment.sourceId] = points
        }
        val events = eventsJson.requiredArray("navi_event").mapObjects(::parseEvent)
        val outputs = eventsJson.requiredArray("navi_event_output").mapObjects(::parseOutput)
        requireUniqueIds(events.map { it.sourceId }, "navi_event")
        events.forEach { ev ->
            if (ev.sourceBranchId != null && ev.sourceBranchId !in branchIds) {
                fail(IsnaviValidationException.Reason.DANGLING_BRANCH_REF, "navi_event ${ev.sourceId} の branch_id が存在しません")
            }
        }
        if (outputs.any { it.sourceEventId !in events.map { event -> event.sourceId }.toSet() }) {
            fail(IsnaviValidationException.Reason.MALFORMED_JSON, "output が未知の event_id を参照しています")
        }
        validateGapManeuvers(segments, events, outputs)

        return PackageData(
            NaviMapEntity(schemaVersion = schema, profile = profile, busId = busId, courseNo = courseNo, year = year,
                title = manifest.optString("title", ""), chainageStepM = manifest.optInt("chainage_step_m", 6),
                displayOrientation = orientation, displayPitchDeg = pitch,
                mediaMode = manifest.optJSONObject("media")?.optString("mode", "") ?: "",
                mediaCount = manifest.optJSONObject("media")?.optInt("count", 0) ?: 0,
                createdAt = 0, updatedAt = 0, appSettingsJson = appSettings),
            branches, segments, tracks, events, outputs,
        )
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> = try {
        val bytes = input.readBytes()
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            fail(IsnaviValidationException.Reason.NOT_A_ZIP, "zip ではありません")
        }
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory) {
                    if (name.startsWith('/') || name.startsWith('\\') || name.matches(Regex("^[A-Za-z]:.*")) || name.split('/').any { it == ".." }) {
                        fail(IsnaviValidationException.Reason.NOT_A_ZIP, "危険な zip エントリ名です")
                    }
                    if (result.put(name, zip.readBytes()) != null) fail(IsnaviValidationException.Reason.MALFORMED_JSON, "重複 zip エントリです: $name")
                }
                zip.closeEntry()
            }
        }
        result
    } catch (e: IsnaviValidationException) { throw e
    } catch (e: ZipException) { fail(IsnaviValidationException.Reason.NOT_A_ZIP, "zip を読み込めません")
    } catch (e: java.io.IOException) { fail(IsnaviValidationException.Reason.NOT_A_ZIP, "zip を読み込めません") }

    private fun parseObject(bytes: ByteArray): JSONObject = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
    catch (_: JSONException) { fail(IsnaviValidationException.Reason.MALFORMED_JSON, "JSON object が不正です") }
    private fun parseArray(bytes: ByteArray): JSONArray = try { JSONArray(bytes.toString(Charsets.UTF_8)) }
    catch (_: JSONException) { fail(IsnaviValidationException.Reason.MALFORMED_JSON, "JSON array が不正です") }

    /** ソース id 配列がファイル内で一意であることを要求する（重複は後勝ちで子データを取り違えるため拒否）。 */
    private fun requireUniqueIds(ids: List<String>, label: String) {
        if (ids.size != ids.toSet().size) {
            fail(IsnaviValidationException.Reason.DUPLICATE_ID, "$label の id がファイル内で重複しています")
        }
    }

    private fun validateJsonTree(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().forEach { validateJsonTree(value.opt(it)) }
            is JSONArray -> (0 until value.length()).forEach { validateJsonTree(value.opt(it)) }
            is String -> if (value.contains("http://") || value.contains("https://")) fail(IsnaviValidationException.Reason.EXTERNAL_URL, "外部 URL は許可されません")
            is Number -> if (!value.toDouble().isFinite()) fail(IsnaviValidationException.Reason.NON_FINITE_NUMBER, "非有限数は許可されません")
        }
    }

    private fun validateSegments(segments: List<Segment>) {
        segments.zipWithNext().forEach { (a, b) -> if (a.chainageEndM > b.chainageStartM) fail(IsnaviValidationException.Reason.CHAINAGE_NOT_MONOTONIC, "segment chainage が後退しています") }
        segments.forEach { if (it.chainageStartM > it.chainageEndM) fail(IsnaviValidationException.Reason.CHAINAGE_NOT_MONOTONIC, "segment chainage 範囲が不正です") }
    }

    private fun validateGapManeuvers(segments: List<Segment>, events: List<Event>, outputs: List<Output>) {
        val arrowEventIds = outputs.filter { it.kind == "ARROW" }.map { it.sourceEventId }.toSet()
        segments.filter { it.gapKind == "REQUIRES_CAPTURE" }.forEach { gap ->
            if (events.any { event -> (event.category.contains("maneuver", ignoreCase = true) || event.sourceId in arrowEventIds) && event.overlaps(gap.chainageStartM, gap.chainageEndM) }) {
                fail(IsnaviValidationException.Reason.TRACKGAP_MANEUVER, "REQUIRES_CAPTURE 内に maneuver イベントがあります")
            }
        }
    }

    private fun Map<String, ByteArray>.required(name: String): ByteArray = this[name]
        ?: fail(IsnaviValidationException.Reason.MISSING_ENTRY, "$name がありません")
    private fun JSONObject.requiredString(name: String): String = opt(name).takeIf { it is String } as? String
        ?: fail(IsnaviValidationException.Reason.MALFORMED_JSON, "$name が文字列ではありません")
    private fun JSONObject.requiredIntOrIdentity(name: String): Int {
        val number = optNumber(name) ?: fail(IsnaviValidationException.Reason.MISSING_COURSE_IDENTITY, "$name がありません")
        val value = number.toDouble()
        if (!value.isFinite()) fail(IsnaviValidationException.Reason.NON_FINITE_NUMBER, "$name が非有限数です")
        if (value != value.toInt().toDouble()) fail(IsnaviValidationException.Reason.MISSING_COURSE_IDENTITY, "$name が整数ではありません")
        return value.toInt()
    }
    private fun JSONObject.optNumber(name: String): Number? = opt(name).takeIf { it is Number } as? Number
    private fun JSONObject.requiredArray(name: String): JSONArray = optJSONArray(name)
        ?: fail(IsnaviValidationException.Reason.MALFORMED_JSON, "$name が配列ではありません")
    private fun <T> JSONArray.mapObjects(parser: (JSONObject) -> T): List<T> = (0 until length()).map { index ->
        optJSONObject(index) ?: fail(IsnaviValidationException.Reason.MALFORMED_JSON, "配列[$index] が object ではありません")
    }.map(parser)
    private fun fail(reason: IsnaviValidationException.Reason, message: String): Nothing = throw IsnaviValidationException(reason, message)

    private data class PackageData(val map: NaviMapEntity, val branches: List<Branch>, val segments: List<Segment>, val tracks: Map<String, List<TrackPoint>>, val events: List<Event>, val outputs: List<Output>)
    private data class Branch(val sourceId: String, val parent: Double, val label: String) {
        fun entity(mapId: Long) = NaviBranchEntity(naviMapId = mapId, parentChainageM = parent, label = label)
    }
    private data class Segment(val sourceId: String, val seq: Int, val kind: String, val gapKind: String?, val chainageStartM: Double, val chainageEndM: Double, val sessionId: Long?, val baseEpochMs: Long?, val sourceBranchId: String?, val clipInM: Double?, val clipOutM: Double?) {
        fun entity(mapId: Long, branchId: Long?) = NaviSegmentEntity(naviMapId = mapId, seq = seq, kind = kind, gapKind = gapKind, chainageStartM = chainageStartM, chainageEndM = chainageEndM, sessionId = sessionId, baseEpochMs = baseEpochMs, branchId = branchId, clipInM = clipInM, clipOutM = clipOutM)
    }
    private data class TrackPoint(val chainageM: Double, val time: Double, val lat: Double, val lon: Double) {
        fun entity(segmentId: Long, seq: Int) = NaviTrackPointEntity(segmentId = segmentId, seq = seq, chainageM = chainageM, tRelS = time, lat = lat, lon = lon)
    }
    private data class Event(val sourceId: String, val templateId: String, val category: String, val anchorType: String, val scope: String, val priority: String, val start: Double?, val end: Double?, val stopCardId: Long?, val sourceBranchId: String?, val condition: String?, val variables: String, val validFrom: Long?, val validUntil: Long?, val repeat: String?) {
        fun entity(mapId: Long, branchId: Long?) = NaviEventEntity(naviMapId = mapId, templateId = templateId, category = category, anchorType = anchorType, scope = scope, priority = priority, chainageStartM = start, chainageEndM = end, stopCardId = stopCardId, branchId = branchId, condition = condition, variablesJson = variables, validFrom = validFrom, validUntil = validUntil, repeatPolicy = repeat)
        fun overlaps(from: Double, to: Double) = (end ?: start ?: Double.NEGATIVE_INFINITY) >= from && (start ?: end ?: Double.POSITIVE_INFINITY) <= to
    }
    private data class Output(val sourceEventId: String, val kind: String, val payload: String) {
        fun entity(eventId: Long) = NaviEventOutputEntity(eventId = eventId, outputKind = kind, payloadJson = payload)
    }
    private fun parseBranch(o: JSONObject) = Branch(o.requiredString("id"), o.requiredDouble("parent_chainage_m"), o.requiredString("label"))
    private fun parseSegment(o: JSONObject) = Segment(o.requiredString("id"), o.requiredInt("seq"), o.requiredString("kind"), o.optNullableString("gap_kind"), o.requiredDouble("chainage_start_m"), o.requiredDouble("chainage_end_m"), o.optLong("session_id"), o.optLong("base_epoch_ms"), o.optNullableString("branch_id"), o.optDouble("clip_in_m"), o.optDouble("clip_out_m"))
    private fun parseTrackPoint(o: JSONObject) = TrackPoint(o.requiredDouble("chainage_m"), o.requiredDouble("t_rel_s"), o.requiredDouble("lat"), o.requiredDouble("lon"))
    private fun parseEvent(o: JSONObject): Event {
        val variables = o.requiredObjectOrDefault("variables")
        return Event(o.requiredString("id"), o.requiredString("template_id"), o.requiredString("category"), o.requiredString("anchor_type"), o.requiredString("scope"), o.requiredString("priority"), o.optDouble("chainage_start_m"), o.optDouble("chainage_end_m"), o.optLong("stop_card_id"), o.optNullableString("branch_id"), o.optNullableString("condition"), variables, o.optLong("valid_from"), o.optLong("valid_until"), o.optNullableString("repeat_policy"))
    }
    private fun parseOutput(o: JSONObject) = Output(o.requiredString("event_id"), o.requiredString("output_kind"), o.requiredObjectOrDefault("payload"))
    private fun JSONObject.requiredDouble(name: String): Double = optNumber(name)?.toDouble()?.also { if (!it.isFinite()) fail(IsnaviValidationException.Reason.NON_FINITE_NUMBER, "$name が非有限数です") } ?: fail(IsnaviValidationException.Reason.MALFORMED_JSON, "$name が数値ではありません")
    private fun JSONObject.requiredInt(name: String): Int = requiredDouble(name).let { if (it == it.toInt().toDouble()) it.toInt() else fail(IsnaviValidationException.Reason.MALFORMED_JSON, "$name が整数ではありません") }
    private fun JSONObject.optDouble(name: String): Double? = if (isNull(name) || !has(name)) null else requiredDouble(name)
    private fun JSONObject.optLong(name: String): Long? = optDouble(name)?.let { if (it == it.toLong().toDouble()) it.toLong() else fail(IsnaviValidationException.Reason.MALFORMED_JSON, "$name が整数ではありません") }
    private fun JSONObject.optNullableString(name: String): String? = if (isNull(name) || !has(name)) null else requiredString(name)
    private fun JSONObject.requiredObjectOrDefault(name: String): String = if (!has(name) || isNull(name)) "{}" else optJSONObject(name)?.toString()
        ?: fail(IsnaviValidationException.Reason.MALFORMED_JSON, "$name が object ではありません")
}
