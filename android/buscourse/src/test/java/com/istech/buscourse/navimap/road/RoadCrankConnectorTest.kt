package com.istech.buscourse.navimap.road

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RoadCrankConnectorTest {
    private fun visit(id: Long, m: Double, angle: Double, incoming: Long, outgoing: Long,
        partner: Long?) = RoadVisit(id, "synthetic", m, 1.0, angle, if (angle > 0) "RIGHT_DIRECTION" else "LEFT_DIRECTION",
        incoming, outgoing, -1, 1, 20.0, 20.0, 180.0, 90.0, 3, emptyList(), false, false,
        emptyMap(), true, emptyMap(), emptyList(), 1.0, 2.0, 30.0, "connected-crank", partner)

    private fun examine(a: RoadVisit, b: RoadVisit, path: List<Long>,
        ways: Map<Long, RoadCrankConnector.Way>, positions: Map<Long, Pair<Double, Double>>) =
        RoadCrankConnector.build(listOf(a, b), listOf(RoadCrankConnector.Window("synthetic", listOf(a, b), path)), ways, positions)

    private fun way(vararg nodes: Long) = RoadCrankConnector.Way(nodes.toList(), null, "forward", "source")

    @Test fun rightThenLeftAcrossTwoWays() {
        val a = visit(1, 200.0, 90.0, 9, 10, 4)
        val b = visit(4, 230.0, -90.0, 11, 12, 1)
        val result = examine(a, b, listOf(10, 11), mapOf(10L to way(0, 1, 2), 11L to way(2, 3, 4, 5)),
            (0L..5L).associateWith { it.toDouble() * 10.0 to 0.0 })
        assertThat(result.cranks).hasSize(1)
        assertThat(result.cranks.single().nodeIds).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(result.cranks.single().wayIds).containsExactly(10L, 11L).inOrder()
        assertThat(result.cranks.single().lengthM).isEqualTo(30.0)
        assertThat(result.cranks.single().legs).hasSize(2)
    }

    @Test fun leftThenRightAcrossThreeWays() {
        val a = visit(1, 200.0, -90.0, 9, 10, 4)
        val b = visit(4, 240.0, 90.0, 12, 13, 1)
        val result = examine(a, b, listOf(10, 11, 12),
            mapOf(10L to way(0, 1, 2), 11L to way(2, 3), 12L to way(3, 4, 5)),
            (0L..5L).associateWith { it.toDouble() * 10.0 to 0.0 })
        assertThat(result.cranks).hasSize(1)
        assertThat(result.cranks.single().wayIds).containsExactly(10L, 11L, 12L).inOrder()
    }

    @Test fun weakAngleLongConnectorAndTwoPathsAreRejected() {
        val a = visit(1, 200.0, 90.0, 9, 10, 5)
        val b = visit(5, 240.0, -90.0, 11, 12, 1)
        val roads = mapOf(10L to way(0, 1, 2), 11L to way(2, 3, 4, 5, 6))
        val positions = (0L..6L).associateWith { it.toDouble() * 10.0 to 0.0 }
        assertThat(examine(a.copy(angleDeg = 69.0), b, listOf(10, 11), roads, positions).cranks).isEmpty()
        assertThat(examine(a, b, listOf(10, 11), roads, positions.mapValues { (_, p) -> p.first * 2 to p.second }).cranks).isEmpty()
        val multiple = examine(a, b, listOf(10, 11),
            mapOf(10L to way(0, 1, 2, 3), 11L to way(2, 4, 3, 5, 6)), positions)
        assertThat(multiple.cranks).isEmpty()
        assertThat(multiple.decisions.last()["decision"]).isEqualTo("connector-not-unique")
    }
}
