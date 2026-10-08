package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaviGuidanceEngineTest {
    @Test fun tenDirectionBoundariesAndSignalWording() {
        assertThat(NaviGuidanceCues.directionKind(27.9)).isEqualTo(NaviGuidanceCues.Kind.STRAIGHT)
        assertThat(NaviGuidanceCues.directionKind(45.0)).isEqualTo(NaviGuidanceCues.Kind.DIAGONAL_RIGHT)
        assertThat(NaviGuidanceCues.directionKind(-45.0)).isEqualTo(NaviGuidanceCues.Kind.DIAGONAL_LEFT)
        assertThat(NaviGuidanceCues.directionKind(90.0)).isEqualTo(NaviGuidanceCues.Kind.RIGHT_DIRECTION)

        val right = cue(NaviGuidanceCues.Kind.RIGHT, "80メートル先、右折です。", "80メートル先、右折、その先すぐ左折です。", "右折、すぐ左折")
        val signaled = NaviGuidanceEngine.withSignalWording(right, mapOf("status" to "MATCHED_INTERSECTION"))
        assertThat(signaled.preText).isEqualTo("80メートル先、信号を右折です。")
        assertThat(signaled.groupText).isEqualTo("80メートル先、信号を右折、その先すぐ左折です。")
        assertThat(signaled.bandText).isEqualTo("信号を右折、すぐ左折")
        assertThat(signaled.nearText).isEqualTo(right.nearText)
        val guidance = NaviGuidanceEngine.withSignalWording(
            NaviGuidanceEngine.Guidance(right, "turn", "existing", emptyMap()), mapOf("status" to "MATCHED_INTERSECTION"),
        )
        assertThat(guidance.evidence["originalPresentation"]).isEqualTo(mapOf(
            "preText" to right.preText, "groupText" to right.groupText, "bandText" to right.bandText,
        ))
        val left = NaviGuidanceEngine.withSignalWording(cue(NaviGuidanceCues.Kind.LEFT, "60メートル先、左折です。"), mapOf("status" to "MATCHED_INTERSECTION"))
        assertThat(left.preText).isEqualTo("60メートル先、信号を左折です。")
        assertThat(NaviGuidanceEngine.withSignalWording(cue(NaviGuidanceCues.Kind.DIAGONAL_RIGHT, "80メートル先、斜め右方向です。"), mapOf("status" to "MATCHED_INTERSECTION")))
            .isEqualTo(cue(NaviGuidanceCues.Kind.DIAGONAL_RIGHT, "80メートル先、斜め右方向です。"))
        assertThat(NaviGuidanceEngine.withSignalWording(cue(NaviGuidanceCues.Kind.STOP, "この先、停留所です。"), mapOf("status" to "MATCHED_INTERSECTION")))
            .isEqualTo(cue(NaviGuidanceCues.Kind.STOP, "この先、停留所です。"))
    }

    @Test fun straightRouteAddsNoExpandedCue() {
        val points = (0..600 step 2).map { m ->
            NaviGuidanceCues.TrackPoint(m.toDouble(), m / 2.0, 35.0 + m / 111_000.0, 139.0)
        }
        val result = NaviGuidanceEngine.build(listOf(NaviGuidanceEngine.TrackGroup("synthetic", points)), emptyList())
        assertThat(result.guidance.filter { it.source == "expanded-28" }).isEmpty()
        assertThat(result.status).isEqualTo("GPS_ONLY")
    }

    @Test fun diagonalCrossingAddsDirectionAndLegacyRightIsNotDuplicated() {
        val diagonal = NaviGuidanceEngine.build(listOf(NaviGuidanceEngine.TrackGroup("synthetic", turnTrack(300.0, 45.0))), emptyList())
        assertThat(diagonal.guidance.any { it.source == "expanded-28" && it.cue.kind == NaviGuidanceCues.Kind.DIAGONAL_RIGHT }).isTrue()
        val rightTurn = NaviGuidanceEngine.build(listOf(NaviGuidanceEngine.TrackGroup("synthetic", turnTrack(300.0, 90.0))), emptyList())
        assertThat(rightTurn.guidance.count { it.cue.kind == NaviGuidanceCues.Kind.RIGHT }).isEqualTo(1)
        assertThat(rightTurn.guidance.none { it.source == "expanded-28" }).isTrue()
    }

    @Test fun expandedCueDoesNotStartBeforeSeventyMeters() {
        val result = NaviGuidanceEngine.build(listOf(NaviGuidanceEngine.TrackGroup("synthetic", turnTrack(40.0, 90.0))), emptyList())
        assertThat(result.guidance.filter { it.source == "expanded-28" }.all { it.cue.chainageM >= 70.0 }).isTrue()
    }

    private fun cue(kind: NaviGuidanceCues.Kind, pre: String, group: String? = null, band: String? = group) = NaviGuidanceCues.Cue(
        200.0, kind, NaviGuidanceCues.Variant.V1, 80.0, 20.0, pre, "まもなく${kind.phrase()}です。", group, group,
    ).copy(bandText = band)

    private fun turnTrack(at: Double, angleDeg: Double): List<NaviGuidanceCues.TrackPoint> = (0..600 step 2).map { m ->
        val d = (m - at).coerceAtLeast(0.0)
        val theta = Math.toRadians(angleDeg)
        val x = if (m <= at) 0.0 else d * kotlin.math.sin(theta)
        val y = if (m <= at) m.toDouble() else at + d * kotlin.math.cos(theta)
        NaviGuidanceCues.TrackPoint(m.toDouble(), m / 4.0, 35.0 + y / 111_000.0, 139.0 + x / 91_000.0)
    }
}
