package com.istech.buscourse.navimap

/** 映像先読みの速度・位置計算。 */
object NaviVideoLead {
    const val GPS_STALE_MS = 2_500L
    const val RESET_BACK_M = 5.0
    const val RESET_FORWARD_M = 150.0

    fun leadSeconds(speedMps: Double?, ageMs: Long, maximumSeconds: Double): Double {
        if (speedMps == null || ageMs >= GPS_STALE_MS || !speedMps.isFinite()) return 0.0
        val speedKmh = speedMps.coerceAtLeast(0.0) * 3.6
        return maximumSeconds.coerceAtLeast(0.0) * ((speedKmh - 10.0) / 50.0).coerceIn(0.0, 1.0)
    }

    fun distanceM(speedMps: Double?, ageMs: Long, maximumSeconds: Double): Double =
        (speedMps?.coerceAtLeast(0.0) ?: 0.0) * leadSeconds(speedMps, ageMs, maximumSeconds)

    fun lookup(previousM: Double?, previousChainageM: Double?, chainageM: Double, leadM: Double, courseEndM: Double): Lookup {
        val reset = previousM == null || previousChainageM == null ||
            chainageM - previousChainageM <= -RESET_BACK_M || chainageM - previousChainageM >= RESET_FORWARD_M
        val candidate = chainageM + leadM.coerceAtLeast(0.0)
        val position = if (reset) candidate else maxOf(previousM!!, candidate)
        return Lookup(position.coerceAtMost(courseEndM), reset)
    }

    data class Lookup(val chainageM: Double, val reset: Boolean)

    fun shouldDraw(previousDrawnM: Double?, lookupM: Double): Boolean =
        previousDrawnM == null || kotlin.math.abs(lookupM - previousDrawnM) >= FRAME_THINNING_MIN_STEP_M
}
