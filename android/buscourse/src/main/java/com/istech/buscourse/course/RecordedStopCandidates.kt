package com.istech.buscourse.course

import com.istech.buscourse.core.geo.GeoMath

/** 緯度経度の組（度）。停留所の位置・押したマークの位置を、名前や番号を持たずに運ぶ。 */
data class LatLon(val lat: Double, val lon: Double)

/** 停車の検出に使うGPS1点（`gps_point` の必要な列だけ）。 */
data class SpeedFix(val tsEpochMs: Long, val lat: Double, val lon: Double, val speedMps: Double?)

/**
 * 停まったのにマークが押されていない所の候補1件（運行の記録の時刻から停留所を足す、2026-10-22）。
 *
 * [lat]/[lon] は停車区間の真ん中の時刻に一番近い測位（[midMs] はその区間の真ん中の時刻）。
 * 名前は持たない（足したあとも通番だけが付く）。
 */
data class RecordedStopCandidate(
    val startMs: Long,
    val endMs: Long,
    val midMs: Long,
    val lat: Double,
    val lon: Double,
) {
    /** 停車していた秒数（区間の最後の測位時刻 − 最初の測位時刻）。 */
    val durationSec: Double get() = (endMs - startMs) / 1000.0
}

/**
 * [RecordedStopCandidatePicker.pick] の結果。
 * [listed] は既知の停留所の位置・ほかの運行で押したマークの近くで停まった所（一覧に出す）、
 * [others] はそれ以外の停車（「その他の停車を見る」を押したときだけ出す。新しい停留所はここから拾う）。
 */
data class RecordedStopCandidates(
    val listed: List<RecordedStopCandidate>,
    val others: List<RecordedStopCandidate>,
)

/**
 * 運行の記録の時刻から、押し忘れた停留所の候補を拾う純関数（DB・Android に依存しない）。
 *
 * 1. **停車**＝速さが [STOP_SPEED_MPS] 未満の測位が続く区間（速さ未計測の点は停車と断定できないので
 *    区間を断ち切る。[CourseRepository.analyzeStopEstimates] と同じ安全側）。[MIN_STOP_SEC] 以上のものだけ。
 * 2. 区間の真ん中の位置から [OWN_MARK_EXCLUDE_M] 以内にその運行で押したマークがあれば外す（押し済み）。
 * 3. 残りのうち、**停留所の位置**または**ほかの運行で押したマークの位置**から [KNOWN_POSITION_NEAR_M]
 *    以内のものを [RecordedStopCandidates.listed]、それ以外を [RecordedStopCandidates.others] に入れる。
 * 4. すでに足した時刻（[addedAtMs]）を含む停車区間は、どちらにも出さない（同じ所を二重に足さない）。
 *
 * 数値はいずれも指示書 L（2026-10-22）の指定値。
 */
object RecordedStopCandidatePicker {
    /** 停車とみなす速さのしきい値（m/s）。これ未満が続く区間を停車とする。 */
    const val STOP_SPEED_MPS = 0.8

    /** 候補にする最短の停車秒数。 */
    const val MIN_STOP_SEC = 10.0

    /** 区間の真ん中の位置からこの距離（m）以内に、その運行で押したマークがあれば候補から外す。 */
    const val OWN_MARK_EXCLUDE_M = 40.0

    /** 停留所の位置・ほかの運行のマークからこの距離（m）以内なら一覧に出す。 */
    const val KNOWN_POSITION_NEAR_M = 30.0

    /**
     * @param fixes その運行の測位（時刻順でなくてもよい。内部で時刻順に並べる）
     * @param ownMarks その運行で押したマークの位置（`stop_visit_event` の座標）
     * @param stopPositions 停留所の位置（名前なし。ファイルが無ければ空）
     * @param otherRunMarks ほかの運行で押したマークの位置（その運行自身は含めない）
     * @param addedAtMs すでに足した停留所の時刻（写真の撮影時刻）。これを含む停車区間は出さない
     */
    fun pick(
        fixes: List<SpeedFix>,
        ownMarks: List<LatLon>,
        stopPositions: List<LatLon>,
        otherRunMarks: List<LatLon>,
        addedAtMs: Collection<Long> = emptyList(),
    ): RecordedStopCandidates {
        val known = stopPositions + otherRunMarks
        val listed = mutableListOf<RecordedStopCandidate>()
        val others = mutableListOf<RecordedStopCandidate>()
        for (candidate in stops(fixes)) {
            if (ownMarks.any { near(candidate, it, OWN_MARK_EXCLUDE_M) }) continue
            if (addedAtMs.any { it in candidate.startMs..candidate.endMs }) continue
            if (known.any { near(candidate, it, KNOWN_POSITION_NEAR_M) }) listed += candidate else others += candidate
        }
        return RecordedStopCandidates(listed = listed, others = others)
    }

    /** 速さ < [STOP_SPEED_MPS] が続く区間のうち [MIN_STOP_SEC] 以上のもの（時刻順）。位置は区間の真ん中の時刻に一番近い測位。 */
    internal fun stops(fixes: List<SpeedFix>): List<RecordedStopCandidate> {
        val sorted = fixes.sortedBy { it.tsEpochMs }
        val result = mutableListOf<RecordedStopCandidate>()
        var run = mutableListOf<SpeedFix>()
        fun close() {
            if (run.size >= 2) {
                val start = run.first().tsEpochMs
                val end = run.last().tsEpochMs
                if ((end - start) / 1000.0 >= MIN_STOP_SEC) {
                    val mid = start + (end - start) / 2
                    val atMid = run.minBy { kotlin.math.abs(it.tsEpochMs - mid) }
                    result += RecordedStopCandidate(start, end, mid, atMid.lat, atMid.lon)
                }
            }
            run = mutableListOf()
        }
        for (fix in sorted) {
            val slow = fix.speedMps != null && fix.speedMps < STOP_SPEED_MPS
            if (slow) run += fix else close()
        }
        close()
        return result
    }

    private fun near(candidate: RecordedStopCandidate, point: LatLon, withinM: Double): Boolean =
        GeoMath.haversineM(candidate.lat, candidate.lon, point.lat, point.lon) <= withinM
}
