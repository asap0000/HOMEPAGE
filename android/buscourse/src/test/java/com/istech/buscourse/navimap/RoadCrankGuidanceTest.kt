package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.navimap.road.RoadCrank
import com.istech.buscourse.navimap.road.RoadEvidence
import com.istech.buscourse.navimap.road.RoadVisit
import org.junit.Test

class RoadCrankGuidanceTest {
    private val group = NaviGuidanceEngine.TrackGroup("synthetic", (0..500 step 5).map { m ->
        NaviGuidanceCues.TrackPoint(m.toDouble(), m / 10.0, 1.0 + m / 111195.0, 1.0)
    })
    private fun visit(id: Long, m: Double, angle: Double, incoming: Long, outgoing: Long,
        partner: Long?, signal: Boolean = false) = RoadVisit(id, "synthetic", m, 1.0, angle,
        if (angle > 0) "RIGHT_DIRECTION" else "LEFT_DIRECTION", incoming, outgoing, -1, 1,
        20.0, 20.0, 180.0, 90.0, 3, if (signal) listOf("traffic_signals") else emptyList(),
        false, false, emptyMap(), true, emptyMap(), emptyList(), 1.0, 2.0, 30.0,
        "connected-crank", partner)
    private fun evidence(signal: Boolean = false): RoadEvidence {
        val a = visit(1, 200.0, 90.0, 9, 10, 4, signal)
        val b = visit(4, 225.0, -90.0, 11, 12, 1)
        val pair = RoadCrank("synthetic:1:4:200.0", "synthetic", a, b, listOf(1, 2, 4),
            listOf(10, 11), 25.0, emptyList())
        return RoadEvidence("synthetic", "map", "index", null, "route", listOf(a, b), listOf(a, b), listOf(pair))
    }

    @Test fun twoActionsKeepExitSpeechAfterEntryAndSwitchBandAtRoadNodes() {
        val result = NaviGuidanceEngine.build(listOf(group), emptyList(), evidence())
        assertThat(result.summary["crank"]).isEqualTo(1)
        val cues = result.guidance.map { it.cue }
        assertThat(cues.map { it.kind }).containsExactly(NaviGuidanceCues.Kind.RIGHT, NaviGuidanceCues.Kind.LEFT).inOrder()
        assertThat(cues[0].preText).isEqualTo("120メートル先、右折、その先すぐ左折です。")
        assertThat(cues[0].nearText).isEqualTo("まもなく右折、すぐ左折です。")
        assertThat(cues[1].preText).isNull()
        assertThat(cues[1].nearText).isEqualTo("続いて、左折です。")
        assertThat(cues[1].nearAtM).isEqualTo(202.0)
        assertThat(NaviGuidanceDispatcher.update(cues, NaviGuidanceDispatcher.State(), 199.0, null,
            false, true, false).bandText).isEqualTo("右折、すぐ左折 0m")
        assertThat(NaviGuidanceDispatcher.update(cues, NaviGuidanceDispatcher.State(), 201.0, null,
            false, true, false).bandText).isEqualTo("左折 20m")
        var state = NaviGuidanceDispatcher.update(cues, NaviGuidanceDispatcher.State(), 70.0, 70.0,
            true, true, true).state
        val pre = NaviGuidanceDispatcher.update(cues, state, 81.0, 81.0, true, true, true)
        assertThat(pre.speechText).isEqualTo(cues[0].preText)
        state = pre.state
        val near = NaviGuidanceDispatcher.update(cues, state, 161.0, 161.0, true, true, true)
        assertThat(near.speechText).isEqualTo(cues[0].nearText)
        state = near.state
        val beforeExit = NaviGuidanceDispatcher.update(cues, state, 201.0, 201.0, true, true, true)
        assertThat(beforeExit.speechText).isNull()
        val exit = NaviGuidanceDispatcher.update(cues, beforeExit.state, cues[1].nearAtM + .1,
            cues[1].nearAtM + .1, true, true, true)
        assertThat(exit.speechText).isEqualTo("続いて、左折です。")
    }

    @Test fun signalWordingFollowsCrankFormatting() {
        val result = NaviGuidanceEngine.build(listOf(group), emptyList(), evidence(signal = true))
        val entry = result.guidance.first().cue
        assertThat(entry.bandText).isEqualTo("信号を右折、すぐ左折")
        assertThat(entry.preText).isEqualTo("120メートル先、信号を右折、その先すぐ左折です。")
        assertThat(entry.nearText).isEqualTo("まもなく右折、すぐ左折です。")
    }

    @Test fun twoOriginalEntryCuesMakePairAmbiguous() {
        val pair = evidence().cranks.single()
        fun existing(m: Double, kind: NaviGuidanceCues.Kind) = NaviGuidanceEngine.Guidance(
            NaviGuidanceCues.Cue(m, kind, NaviGuidanceCues.Variant.V1, 40.0, 10.0,
                "40メートル先、${kind.phrase()}です。", "まもなく${kind.phrase()}です。"),
            "turn", "existing", emptyMap())
        val input = listOf(existing(194.0, NaviGuidanceCues.Kind.RIGHT),
            existing(202.0, NaviGuidanceCues.Kind.RIGHT), existing(225.0, NaviGuidanceCues.Kind.LEFT))
        val method = NaviGuidanceEngine::class.java.getDeclaredMethod("applyRoadCranks", List::class.java,
            List::class.java, List::class.java).apply { isAccessible = true }
        val output = method.invoke(NaviGuidanceEngine, input, listOf(pair), group.points)
        val accepted = output.javaClass.getDeclaredMethod("getAccepted").apply { isAccessible = true }.invoke(output)
        assertThat(accepted).isEqualTo(0)
        @Suppress("UNCHECKED_CAST")
        val decisions = output.javaClass.getDeclaredMethod("getDecisions").apply { isAccessible = true }
            .invoke(output) as List<Map<String, Any?>>
        assertThat(decisions.single()["decision"]).isEqualTo("ambiguous-original-cues")
    }
}
