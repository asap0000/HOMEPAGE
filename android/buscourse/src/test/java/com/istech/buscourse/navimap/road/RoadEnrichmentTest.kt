package com.istech.buscourse.navimap.road

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.navimap.NaviGuidanceCues
import com.istech.buscourse.navimap.NaviGuidanceEngine
import org.junit.Test

class RoadEnrichmentTest {
    private val group = NaviGuidanceEngine.TrackGroup("synthetic", (0..500 step 5).map { m ->
        NaviGuidanceCues.TrackPoint(m.toDouble(), m / 4.0, 1.0 + m / 111195.0, 1.0)
    })
    private fun road(vararg visits: RoadVisit) = RoadEvidence("synthetic", "map", "index", "synthetic", "route",
        visits.toList(), visits.filter { it.decision == "connected-crank" || it.decision == "isolated-geometry-and-GPS" })
    private fun visit(m: Double, node: Long, angle: Double, kind: String, inWay: Long = 1, outWay: Long = 2,
        reason: String = "isolated-geometry-and-GPS", partner: Long? = null,
        controls: List<String> = emptyList()) = RoadVisit(node, "synthetic", m, 2.0, angle, kind,
        inWay, outWay, -1, 1, 20.0, 20.0, 180.0, 90.0, 4, controls, false, false,
        mapOf("8" to angle, "15" to angle, "25" to angle), true, emptyMap(), emptyList(),
        2.0, 3.0, 60.0, reason, partner)

    @Test fun addsMapCueAndKeepsEvidence() {
        val result = NaviGuidanceEngine.build(listOf(group), emptyList(), road(visit(200.0, 101, 45.0, "DIAGONAL_RIGHT")))
        val cue = result.guidance.single { it.source == "map-connected" }
        assertThat(cue.cue.kind).isEqualTo(NaviGuidanceCues.Kind.DIAGONAL_RIGHT)
        assertThat(cue.evidence["nodeId"]).isEqualTo(101L)
        assertThat(cue.evidence["connectionOrder"]).isNotNull()
        assertThat(result.summary["roadAdded"]).isEqualTo(1)
    }

    @Test fun rejectsUnconfirmedCrankAndAcceptsConnectedPair() {
        val first = visit(200.0, 101, 40.0, "DIAGONAL_RIGHT", outWay = 2,
            reason = "connected-crank", partner = 102)
        val second = visit(225.0, 102, -40.0, "DIAGONAL_LEFT", inWay = 2,
            reason = "connected-crank", partner = 101)
        val accepted = NaviGuidanceEngine.build(listOf(group), emptyList(), road(first, second))
        assertThat(accepted.summary["roadAdded"]).isEqualTo(2)
        val refused = NaviGuidanceEngine.build(listOf(group), emptyList(), road(first, second.copy(inWay = 3)))
        assertThat(refused.summary["roadAdded"]).isEqualTo(0)
        assertThat(refused.diagnostics.count { it["decision"] == "crank-connection-unconfirmed" }).isEqualTo(2)
    }

    @Test fun originalTurnStaysAndExpandedPeakCanMoveToNode() {
        fun bend(degrees: Double): NaviGuidanceEngine.TrackGroup {
            val radians = Math.toRadians(degrees)
            return NaviGuidanceEngine.TrackGroup("synthetic", (0..600 step 2).map { m ->
                val after = (m - 300).coerceAtLeast(0)
                val x = after * kotlin.math.sin(radians)
                val y = minOf(m, 300) + after * kotlin.math.cos(radians)
                NaviGuidanceCues.TrackPoint(m.toDouble(), m / 4.0, 1.0 + y / 111195.0,
                    1.0 + x / (111195.0 * kotlin.math.cos(Math.toRadians(1.0))))
            })
        }
        val original = NaviGuidanceEngine.build(listOf(bend(90.0)), emptyList(),
            road(visit(302.0, 101, 90.0, "RIGHT_DIRECTION")))
        assertThat(original.diagnostics.any { it["decision"] == "original-retained" }).isTrue()
        assertThat(original.guidance.any { it.source == "existing" && it.evidence["roadObservation"] != null }).isTrue()
        val expanded = NaviGuidanceEngine.build(listOf(bend(45.0)), emptyList(),
            road(visit(302.0, 102, 45.0, "DIAGONAL_RIGHT")))
        assertThat(expanded.summary["reanchored"]).isEqualTo(1)
        assertThat(expanded.guidance.any { it.evidence["mapNode"] == 102L && it.cue.chainageM == 302.0 }).isTrue()
    }

    @Test fun oneToOneSignalWordingLeavesAmbiguousCuesUntouched() {
        val cue = NaviGuidanceCues.Cue(200.0, NaviGuidanceCues.Kind.RIGHT, NaviGuidanceCues.Variant.V1,
            80.0, 20.0, "80メートル先、右折です。", "まもなく右折です。", null, "右折")
        val guidance = NaviGuidanceEngine.Guidance(cue, "turn", "existing", emptyMap())
        val signal = visit(202.0, 101, 90.0, "RIGHT_DIRECTION", controls = listOf("traffic_signals"))
        val one = NaviGuidanceEngine.withSignalLandmarks(listOf(guidance), road(signal), listOf(group))
        assertThat(one.second).isEqualTo(1)
        assertThat(one.first.single().cue.preText).isEqualTo("80メートル先、信号を右折です。")
        val ambiguous = NaviGuidanceEngine.withSignalLandmarks(listOf(guidance), road(signal, signal.copy(nodeId = 102)), listOf(group))
        assertThat(ambiguous.second).isEqualTo(0)
        assertThat(ambiguous.first.single().cue).isEqualTo(cue)
    }

    @Test fun continuingRoadAndRoundaboutVisitsDoNotAddWords() {
        val continuing = visit(180.0, 101, 45.0, "DIAGONAL_RIGHT", reason = "road-continuity")
            .copy(continuingRoad = true)
        val roundabout = visit(260.0, 102, 90.0, "RIGHT_DIRECTION", reason = "roundabout-sequence-pending")
            .copy(roundabout = true)
        val result = NaviGuidanceEngine.build(listOf(group), emptyList(), road(continuing, roundabout))
        assertThat(result.summary["roadAdded"]).isEqualTo(0)
        assertThat(result.guidance).isEmpty()
    }
}
