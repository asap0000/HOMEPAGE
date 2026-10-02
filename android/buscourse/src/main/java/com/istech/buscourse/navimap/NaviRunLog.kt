package com.istech.buscourse.navimap

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * **ナビの走った跡**（B＝撮り直しの収束の1歩目・2026-10-02 オーナー承認）。
 *
 * 全部入りのナビ本画面を開いている間、届いた測位を全部 `filesDir/buscourse/naviruns/<開始ms>.jsonl` に残す。
 * 1行1つの JSON。1行目＝見出し（コースの身元・使ったナビ用マップ）、以降＝測位（`fix`）と、
 * コースを外れた／戻った（`leave`／`rejoin`）、閉じた（`end`・落ちた回には無い）。
 *
 * - 測位の行の `t`・`lat`・`lon`・`acc`・`spd`・`brg` は**実記録**、`ch`・`off`・`on` は**機械推定**
 *   （そのときのコースへの当てはめ・外れ判定）。欄を分けて置き、規則を変えたら作り直せるようにする。
 * - 運行の記録・コース・DB には触らない。運転者には何も見せない。**書けなくてもナビは止めない**。
 * - **座標を残すのはコースから [CORRIDOR_M]（回廊）以内にいる間だけ**。外では時刻だけの行（`far`）にする。
 *   **一度もコースに乗らなかった回は丸ごと残さない**（関係のない地域で開いた回の位置を残さない・2026-10-02 オーナー承認）。
 *   回廊の外の変化は B では扱わず「運行の記録のし直し（正規）」へ回す決まり（考察 B・回廊＝250m・1分）。
 * - 一覧で全部の本体を読まないよう、閉じたときに要約 `<開始ms>.summary.json` を書き置く。
 */
object NaviRunLog {
    const val BODY_SUFFIX = ".jsonl"
    const val SUMMARY_SUFFIX = ".summary.json"

    /** 開いていた時間がこれ未満の回は残さない。 */
    const val MIN_DURATION_MS = 60_000L

    /** これより古い跡は消す（180日）。 */
    const val RETENTION_MS = 180L * 24 * 60 * 60 * 1000

    /** 回廊の幅（コースの中心線から）。これより外の座標は残さない。 */
    const val CORRIDOR_M = 250.0

    /** 見出し。[offEnterM]/[offExitM] は、そのときの外れ判定の規則（後から規則を変えても読み分けられるように）。 */
    data class Header(
        val startedAtMs: Long,
        val busId: String,
        val courseNo: Int,
        val year: Int,
        val naviMapId: Long,
        val appVersion: String,
        val offEnterM: Double,
        val offExitM: Double,
        val corridorM: Double = CORRIDOR_M,
    )

    /**
     * 測位1つ。[chainageM]/[lateralOffsetM] はコースへの当てはめ（当てはまらなければ null）、[onCourse] は外れ判定の後の値。
     * [inCorridor]＝コースから [CORRIDOR_M] 以内か（false なら座標を書かない）。
     */
    data class Fix(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
        val accuracyM: Float?,
        val speedMps: Float?,
        val bearingDeg: Float?,
        val chainageM: Double?,
        val lateralOffsetM: Double?,
        val onCourse: Boolean,
        val following: Boolean,
        val inCorridor: Boolean = true,
    )

    /**
     * 外れた所1つ。[leaveChainageM]＝外れる直前にコース上にいた所（始めから外れていたら null）、[rejoinChainageM]＝戻った所
     * （戻らずに終わったら null）、[beyondCorridor]＝その間に回廊（250m）より外へ出た。
     */
    data class Outage(val leaveChainageM: Double?, val rejoinChainageM: Double?, val seconds: Long, val beyondCorridor: Boolean = false)

    data class Summary(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val busId: String,
        val courseNo: Int,
        val year: Int,
        val naviMapId: Long,
        val fixCount: Int,
        val outages: List<Outage>,
        /** 一度でもコースに乗ったか（乗らなかった回は残さない）。 */
        val everOnCourse: Boolean = true,
    ) {
        val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0)
    }

    // ---- 行の組み立て ----

    fun headerLine(h: Header): String = JSONObject()
        .put("v", 1).put("kind", "header").put("startedAtMs", h.startedAtMs)
        .put("busId", h.busId).put("courseNo", h.courseNo).put("year", h.year)
        .put("naviMapId", h.naviMapId).put("appVersion", h.appVersion)
        .put("offEnterM", h.offEnterM).put("offExitM", h.offExitM).put("corridorM", h.corridorM)
        .toString()

    /** 回廊の外にいる間の行。**座標は書かない**（時刻だけ）。 */
    fun farLine(timeMs: Long): String = JSONObject().put("kind", "far").put("t", timeMs).toString()

    fun fixLine(f: Fix): String = JSONObject()
        .put("kind", "fix").put("t", f.timeMs).put("lat", f.lat).put("lon", f.lon)
        .put("acc", f.accuracyM ?: JSONObject.NULL)
        .put("spd", f.speedMps ?: JSONObject.NULL)
        .put("brg", f.bearingDeg ?: JSONObject.NULL)
        .put("ch", f.chainageM ?: JSONObject.NULL)
        .put("off", f.lateralOffsetM ?: JSONObject.NULL)
        .put("on", f.onCourse)
        .put("mode", if (f.following) "F" else "P")
        .toString()

    fun eventLine(kind: String, timeMs: Long, chainageM: Double?): String = JSONObject()
        .put("kind", kind).put("t", timeMs).put("ch", chainageM ?: JSONObject.NULL)
        .toString()

    fun endLine(timeMs: Long): String = JSONObject().put("kind", "end").put("t", timeMs).toString()

    // ---- 要約（純関数） ----

    /**
     * 本体の行から要約を作る。**読めない行（落ちた回の途中で切れた最後の行など）は飛ばす**。見出しが無ければ null。
     * 外れた所は短いものも全部数える（数えない基準は実車の跡を見てから決める＝オーナー承認）。
     */
    fun summarize(lines: List<String>): Summary? {
        val rows = lines.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
        val header = rows.firstOrNull { it.optString("kind") == "header" } ?: return null
        val startedAtMs = header.optLong("startedAtMs")
        val outages = mutableListOf<Outage>()
        var leave: JSONObject? = null
        var far = false
        for (row in rows) {
            when (row.optString("kind")) {
                "leave" -> {
                    leave = row
                    far = false
                }
                "far" -> if (leave != null) far = true
                "rejoin" -> leave?.let { left ->
                    outages += Outage(left.chainageOrNull(), row.chainageOrNull(), secondsBetween(left, row.optLong("t")), far)
                    leave = null
                }
            }
        }
        val lastTimeMs = rows.lastOrNull { it.has("t") }?.optLong("t") ?: startedAtMs
        leave?.let { left -> outages += Outage(left.chainageOrNull(), null, secondsBetween(left, lastTimeMs), far) }
        return Summary(
            startedAtMs = startedAtMs,
            endedAtMs = maxOf(lastTimeMs, startedAtMs),
            busId = header.optString("busId"),
            courseNo = header.optInt("courseNo"),
            year = header.optInt("year"),
            naviMapId = header.optLong("naviMapId"),
            fixCount = rows.count { it.optString("kind") == "fix" },
            outages = outages,
            everOnCourse = rows.any { it.optString("kind") == "fix" && it.optBoolean("on") },
        )
    }

    private fun JSONObject.chainageOrNull(): Double? = if (isNull("ch")) null else optDouble("ch").takeUnless { it.isNaN() }

    private fun secondsBetween(left: JSONObject, endMs: Long): Long = ((endMs - left.optLong("t")) / 1000).coerceAtLeast(0)

    /** 残すに値しない回（60秒未満・測位ゼロ・一度もコースに乗らなかった）か。 */
    fun shouldDiscard(s: Summary): Boolean = s.durationMs < MIN_DURATION_MS || s.fixCount == 0 || !s.everOnCourse

    // ---- ファイル ----

    fun summaryFileOf(body: File): File = File(body.parentFile, body.name.removeSuffix(BODY_SUFFIX) + SUMMARY_SUFFIX)

    fun writeSummary(body: File, s: Summary) {
        val outages = JSONArray()
        s.outages.forEach {
            outages.put(
                JSONObject()
                    .put("leaveCh", it.leaveChainageM ?: JSONObject.NULL)
                    .put("rejoinCh", it.rejoinChainageM ?: JSONObject.NULL)
                    .put("seconds", it.seconds)
                    .put("beyondCorridor", it.beyondCorridor),
            )
        }
        summaryFileOf(body).writeText(
            JSONObject()
                .put("v", 1).put("startedAtMs", s.startedAtMs).put("endedAtMs", s.endedAtMs)
                .put("busId", s.busId).put("courseNo", s.courseNo).put("year", s.year)
                .put("naviMapId", s.naviMapId).put("fixCount", s.fixCount).put("outages", outages)
                .toString(),
        )
    }

    fun readSummary(file: File): Summary? = runCatching {
        val o = JSONObject(file.readText())
        val array = o.getJSONArray("outages")
        Summary(
            startedAtMs = o.getLong("startedAtMs"),
            endedAtMs = o.getLong("endedAtMs"),
            busId = o.getString("busId"),
            courseNo = o.getInt("courseNo"),
            year = o.getInt("year"),
            naviMapId = o.getLong("naviMapId"),
            fixCount = o.getInt("fixCount"),
            outages = (0 until array.length()).map { i ->
                val x = array.getJSONObject(i)
                Outage(
                    leaveChainageM = if (x.isNull("leaveCh")) null else x.getDouble("leaveCh"),
                    rejoinChainageM = if (x.isNull("rejoinCh")) null else x.getDouble("rejoinCh"),
                    seconds = x.getLong("seconds"),
                    beyondCorridor = x.optBoolean("beyondCorridor", false),
                )
            },
        )
    }.getOrNull()

    private fun startedAtOf(file: File): Long? =
        file.name.removeSuffix(SUMMARY_SUFFIX).removeSuffix(BODY_SUFFIX).toLongOrNull()

    /** 180日より古い跡（本体と要約）を消す。**名前が `<開始ms>` でないファイルには触らない**。 */
    fun deleteExpired(dir: File, nowMs: Long) {
        dir.listFiles()?.forEach { file ->
            val isRunFile = file.name.endsWith(BODY_SUFFIX) || file.name.endsWith(SUMMARY_SUFFIX)
            val startedAt = startedAtOf(file) ?: return@forEach
            if (isRunFile && nowMs - startedAt > RETENTION_MS) file.delete()
        }
    }

    /** 本体を閉じた（または落ちた回を後から拾った）ときの後始末: 短すぎれば消し、残すなら要約を書き置く。残したら要約を返す。 */
    fun finalize(body: File): Summary? {
        val summary = if (body.exists()) summarize(body.readLines()) else null
        if (summary == null || shouldDiscard(summary)) {
            body.delete()
            summaryFileOf(body).delete()
            return null
        }
        writeSummary(body, summary)
        return summary
    }

    /** 一覧用: 古いものを消し、要約を新しい順に返す。要約の無い本体（落ちた回）はここで要約を作って書き置く。 */
    fun loadSummaries(dir: File, nowMs: Long): List<Summary> {
        deleteExpired(dir, nowMs)
        val bodies = dir.listFiles { f -> f.name.endsWith(BODY_SUFFIX) && startedAtOf(f) != null }.orEmpty()
        return bodies.mapNotNull { body -> readSummary(summaryFileOf(body)) ?: finalize(body) }
            .sortedByDescending { it.startedAtMs }
    }

    // ---- 一覧の文言 ----

    fun outageText(outages: List<Outage>): String {
        if (outages.isEmpty()) return "外れた所なし"
        return "外れた所 ${outages.size}か所：" + outages.joinToString("／") { o ->
            val leave = o.leaveChainageM
            val rejoin = o.rejoinChainageM
            val far = if (o.beyondCorridor) "・${CORRIDOR_M.toInt()}mより外" else ""
            when {
                leave == null && rejoin == null -> "出発時から（戻らずに終了$far）"
                rejoin == null -> "${km(leave!!)}km〜（戻らずに終了$far）"
                leave == null -> "出発時〜${km(rejoin)}km（${durationText(o.seconds)}$far）"
                else -> "${km(leave)}〜${km(rejoin)}km（${durationText(o.seconds)}$far）"
            }
        }
    }

    /**
     * 点 ([lat],[lon]) がコースの線 [route]（緯度・経度の並び）から [limitM] 以内か。
     * ナビの当てはめ（[NaviFollow]）は 150m より外では答えを返さないので、回廊（250m）の判定は別に測る。
     * 正距円筒で平面に直した点と線分の距離（数 km の範囲なら十分な精度）。
     */
    fun isWithinRoute(lat: Double, lon: Double, route: List<Pair<Double, Double>>, limitM: Double = CORRIDOR_M): Boolean {
        if (route.isEmpty()) return false
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = metersPerDegLat * kotlin.math.cos(Math.toRadians(lat))
        val limit2 = limitM * limitM
        fun x(p: Pair<Double, Double>) = (p.second - lon) * metersPerDegLon
        fun y(p: Pair<Double, Double>) = (p.first - lat) * metersPerDegLat
        if (route.size == 1) return x(route[0]).let { it * it } + y(route[0]).let { it * it } <= limit2
        for (i in 0 until route.size - 1) {
            val ax = x(route[i])
            val ay = y(route[i])
            val dx = x(route[i + 1]) - ax
            val dy = y(route[i + 1]) - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            if (px * px + py * py <= limit2) return true
        }
        return false
    }

    fun km(m: Double): String = String.format(Locale.US, "%.1f", m / 1000.0)

    fun durationText(seconds: Long): String =
        if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${(seconds % 60).toString().padStart(2, '0')}秒"
}

/**
 * 走った跡の書き手。GPS のコールバック（メインスレッド）から呼ぶので、**書き込みは [executor] 側で順に行う**。
 * 例外は [onError] へ渡して飲み込む（ナビを止めない）。外れた／戻ったの行は、続けて渡される測位の
 * [NaviRunLog.Fix.onCourse] の変化から自分で起こす（外れた所は、直前にコース上にいた位置）。
 */
class NaviRunWriter(
    private val body: File,
    header: NaviRunLog.Header,
    private val executor: Executor = SHARED_EXECUTOR,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
) {
    private var writer: BufferedWriter? = null
    private var lastFlushMs = 0L
    @Volatile private var closed = false

    // 以下2つは呼び出し側（メインスレッド）だけが触る。
    private var wasOnCourse = true
    private var lastOnCourseChainageM: Double? = null

    init {
        submit {
            body.parentFile?.let { NaviRunLog.deleteExpired(it, header.startedAtMs) }
            body.parentFile?.mkdirs()
            writer = BufferedWriter(FileWriter(body, false)).also {
                it.write(NaviRunLog.headerLine(header))
                it.newLine()
                it.flush()
            }
            lastFlushMs = nowMs()
        }
    }

    fun onFix(fix: NaviRunLog.Fix) {
        if (closed) return
        val lines = mutableListOf<String>()
        if (wasOnCourse && !fix.onCourse) lines += NaviRunLog.eventLine("leave", fix.timeMs, lastOnCourseChainageM)
        lines += if (fix.inCorridor) NaviRunLog.fixLine(fix) else NaviRunLog.farLine(fix.timeMs)
        if (!wasOnCourse && fix.onCourse) lines += NaviRunLog.eventLine("rejoin", fix.timeMs, fix.chainageM)
        wasOnCourse = fix.onCourse
        if (fix.onCourse && fix.chainageM != null) lastOnCourseChainageM = fix.chainageM
        submit {
            val w = writer ?: return@submit
            lines.forEach { w.write(it); w.newLine() }
            if (nowMs() - lastFlushMs >= FLUSH_INTERVAL_MS) {
                w.flush()
                lastFlushMs = nowMs()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        submit {
            writer?.let {
                it.write(NaviRunLog.endLine(nowMs()))
                it.newLine()
                it.close()
            }
            writer = null
            NaviRunLog.finalize(body)
        }
    }

    private fun submit(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    onError(t)
                }
            }
        } catch (t: Throwable) {
            onError(t)
        }
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 3_000L
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "navi-run-writer").apply { isDaemon = true } }
    }
}
