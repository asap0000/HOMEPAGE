package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.navimap.road.RoadEvidence
import com.istech.buscourse.navimap.road.RoadVisit
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RoadFollowingTest {
    private fun straight(length: Int = 800) = NaviGuidanceEngine.TrackGroup("synthetic",
        (0..length step 10).map { m -> NaviGuidanceCues.TrackPoint(m.toDouble(), m / 8.0,
            m / 111_195.0, 0.0) })
    private fun evidence(vararg visits: RoadVisit) = RoadEvidence("synthetic", "map", "index", null,
        "route", visits.toList(), emptyList())
    private fun visit(angle: Double, fit: Boolean = true, continuing: Boolean = false) = RoadVisit(
        1, "synthetic", 300.0, 1.0, angle, "STRAIGHT", 1, 1, -1, 1,
        20.0, 20.0, 0.0, 0.0, 3, emptyList(), continuing, false,
        emptyMap(), fit, emptyMap(), emptyList(), 1.0, 2.0, 30.0)

    @Test fun longStraight_withRoadContinuity_isSavedAsRoadFollowing() {
        val result = NaviGuidanceEngine.build(listOf(straight()), emptyList(), evidence(visit(0.0)))
        val row = result.guidance.single { it.role == "road-following" }
        assertThat(row.cue.kind).isEqualTo(NaviGuidanceCues.Kind.ROAD_FOLLOWING)
        assertThat(row.cue.chainageM).isEqualTo(50.0)
        assertThat(row.chainageEndM).isEqualTo(600.0)
        assertThat(row.cue.preText).isNull()
        assertThat(row.cue.bandText).isEqualTo(RoadFollowing.TEXT)
        assertThat(result.summary["roadFollowingAdopted"]).isEqualTo(1)
        assertThat(RoadFollowing.derive(listOf(straight()), emptyList(), evidence()).single().adopted).isTrue()
    }

    @Test fun turnUnverifiedShortAndSharpIntervalsAreExcluded() {
        fun decision(group: NaviGuidanceEngine.TrackGroup, road: RoadEvidence?) =
            RoadFollowing.derive(listOf(group), emptyList(), road).single()
        assertThat(decision(straight(), evidence(visit(60.0))).reasons)
            .contains("road-continuity-unverified")
        assertThat(decision(straight(), evidence(visit(0.0, fit = false))).reasons)
            .contains("road-continuity-unverified")
        assertThat(decision(straight(), null).reasons).contains("road-continuity-unverified")
        assertThat(decision(straight(490), evidence()).reasons).contains("quiet-distance-short")
        val bent = NaviGuidanceEngine.TrackGroup("synthetic", (0..800 step 5).map { m ->
            val after = (m - 395).coerceAtLeast(0)
            val x = after * sin(Math.toRadians(60.0))
            val y = minOf(m, 395) + after * cos(Math.toRadians(60.0))
            NaviGuidanceCues.TrackPoint(m.toDouble(), m / 8.0, y / 111_195.0, x / 111_195.0)
        })
        assertThat(decision(bent, evidence()).reasons).contains("unannounced-sharp-change")
    }

    private fun fix(m: Double, t: Long, onCourse: Boolean = true, checkMode: Boolean = false) =
        RoadFollowing.RuntimeFix(m, onCourse, !checkMode, checkMode, 0, 5.0, 8.0, t)

    @Test fun runtime_speaksOnceAndShowsBandFromStart() {
        val intervals = listOf(RoadFollowing.Interval(50.0, 600.0))
        val before = RoadFollowing.update(intervals, RoadFollowing.RuntimeState(), fix(40.0, 0), false)
        val entry = RoadFollowing.update(intervals, before.state, fix(50.0, 1_000), false)
        assertThat(entry.speak).isTrue()
        assertThat(entry.band).isTrue()
        val later = RoadFollowing.update(intervals, entry.state, fix(60.0, 2_000), false)
        assertThat(later.speak).isFalse()
        assertThat(later.band).isTrue()
    }

    @Test fun dispatcher_keepsRoadFollowingOutOfNextCueSelection() {
        val intervals = listOf(RoadFollowing.Interval(50.0, 600.0))
        val before = NaviGuidanceDispatcher.update(emptyList(), intervals, NaviGuidanceDispatcher.State(),
            40.0, 40.0, true, true, true, fix(40.0, 0))
        val entry = NaviGuidanceDispatcher.update(emptyList(), intervals, before.state,
            50.0, 50.0, true, true, true, fix(50.0, 1_000))
        assertThat(entry.speechText).isEqualTo(RoadFollowing.TEXT)
        assertThat(entry.bandText).isEqualTo(RoadFollowing.TEXT)
        val after = NaviGuidanceDispatcher.update(emptyList(), intervals, entry.state,
            601.0, 601.0, true, true, true, fix(601.0, 2_000))
        assertThat(after.bandText).isNull()
        val preview = NaviGuidanceDispatcher.update(emptyList(), intervals, NaviGuidanceDispatcher.State(),
            300.0, null, false, true, false, fix(300.0, 0, checkMode = true))
        assertThat(preview.bandText).isEqualTo(RoadFollowing.TEXT)
        assertThat(preview.speechText).isNull()
        val muted = NaviGuidanceDispatcher.update(emptyList(), intervals, before.state,
            50.0, 50.0, true, true, false, fix(50.0, 1_000))
        assertThat(muted.bandText).isEqualTo(RoadFollowing.TEXT)
        assertThat(muted.speechText).isNull()
    }

    @Test fun runtime_skipsMidIntervalStartReentryAndCloseRepeat() {
        val first = listOf(RoadFollowing.Interval(50.0, 600.0))
        assertThat(RoadFollowing.update(first, RoadFollowing.RuntimeState(), fix(300.0, 0), false).speak).isFalse()
        val before = RoadFollowing.update(first, RoadFollowing.RuntimeState(), fix(40.0, 0), false)
        val outside = RoadFollowing.update(first, before.state, fix(45.0, 1_000, false), false)
        assertThat(RoadFollowing.update(first, outside.state, fix(50.0, 2_000), false).speak).isFalse()
        val spoken = RoadFollowing.update(first, before.state, fix(50.0, 1_000), false)
        val next = listOf(RoadFollowing.Interval(900.0, 1500.0))
        val approach = RoadFollowing.update(next, spoken.state, fix(899.0, 190_000), false)
        assertThat(RoadFollowing.update(next, approach.state, fix(900.0, 191_000), false).speak).isFalse()
        val preview = RoadFollowing.update(first, RoadFollowing.RuntimeState(), fix(300.0, 0, checkMode = true), false)
        assertThat(preview.band).isTrue()
        assertThat(preview.speak).isFalse()
    }
}
