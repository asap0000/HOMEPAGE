package com.istech.buscourse.navimap.road

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** Confirms short cranks using the ordered nodes of the roads already loaded near the course. */
object RoadCrankConnector {
    data class Way(val nodes: List<Long>, val junction: String?, val oneway: String, val origin: String)
    data class Window(val segmentId: String, val nodes: List<RoadVisit>, val pathWays: List<Long>)
    data class Result(val cranks: List<RoadCrank>, val decisions: List<Map<String, Any?>>)

    fun build(proposals: List<RoadVisit>, windows: List<Window>, ways: Map<Long, Way>,
        coordinates: Map<Long, Pair<Double, Double>>): Result {
        val cranks = mutableListOf<RoadCrank>()
        val decisions = mutableListOf<Map<String, Any?>>()
        for (a in proposals.filter { it.crankPartner != null }) {
            val matches = proposals.filter { it.nodeId == a.crankPartner && it.segmentId == a.segmentId &&
                it.chainageM - a.chainageM in 2.0..45.0 }
            // prepare-roads.py と同じく、相手がちょうど1つでなければ記録せず飛ばす（出口側から見た組もここで落ちる）。
            if (matches.size != 1) continue
            val b = matches.single()
            val id = "${a.segmentId}:${a.nodeId}:${b.nodeId}:${a.chainageM}"
            fun decide(reason: String, extra: Map<String, Any?> = emptyMap()) {
                decisions += mapOf("pairId" to id, "decision" to reason) + extra
            }
            if (listOf(a, b).any { !it.fit || it.degree < 3 || abs(it.angleDeg) < 70 || abs(it.angleDeg) >= 150 ||
                    it.roundabout || it.continuingRoad || it.inWay == it.outWay || it.lateralM > 20 ||
                    it.meanLateralM > 12 || it.p95LateralM > 22 } || a.angleDeg * b.angleDeg >= 0) {
                decide("not-two-distinct-substantial-turns"); continue
            }
            fun supports(w: Window, n: RoadVisit) = w.segmentId == n.segmentId &&
                w.nodes.any { it.nodeId == n.nodeId && abs(it.chainageM - n.chainageM) <= 1 }
            val candidates = mutableListOf<List<Long>>()
            if (a.outWay == b.inWay && windows.any { supports(it, a) } && windows.any { supports(it, b) })
                candidates += listOf(a.outWay)
            for (window in windows.filter { supports(it, a) && supports(it, b) }) {
                val ids = window.pathWays
                for (i in ids.indices) if (ids[i] == a.outWay)
                    for (j in i until minOf(ids.size, i + 5)) if (ids[j] == b.inWay)
                        candidates += ids.subList(i, j + 1)
            }
            val paths = candidates.flatMap { connect(a, b, it, ways, coordinates) }
                .distinctBy { it.nodeIds }
            if (paths.size != 1) { decide("connector-not-unique", mapOf("paths" to paths.size)); continue }
            val path = paths.single()
            cranks += RoadCrank(id, a.segmentId, a, b, path.nodeIds, path.wayIds, path.lengthM, path.legs)
            decide("accepted", mapOf("connectorLengthM" to path.lengthM, "wayIds" to path.wayIds))
        }
        return Result(cranks, decisions)
    }

    private data class Path(val nodeIds: List<Long>, val wayIds: List<Long>, val lengthM: Double,
        val legs: List<Map<String, Any?>>)

    private fun connect(a: RoadVisit, b: RoadVisit, ids: List<Long>, ways: Map<Long, Way>,
        coordinates: Map<Long, Pair<Double, Double>>): List<Path> {
        if (ids.isEmpty() || ids.first() != a.outWay || ids.last() != b.inWay) return emptyList()
        val rows = ids.map { ways[it] ?: return emptyList() }
        val joins = rows.zipWithNext().map { (x, y) -> x.nodes.toSet().intersect(y.nodes.toSet()) }
        if (joins.any { it.isEmpty() }) return emptyList()
        val paths = mutableListOf<Path>()
        fun inspect(joinNodes: List<Long>) {
            val ends = listOf(a.nodeId) + joinNodes + b.nodeId
            val full = mutableListOf<Long>()
            val legs = mutableListOf<Map<String, Any?>>()
            for (i in ids.indices) {
                val w = rows[i]; val start = ends[i]; val end = ends[i + 1]
                if (start == end || w.nodes.count { it == start } != 1 || w.nodes.count { it == end } != 1 ||
                    w.junction in setOf("roundabout", "circular", "turning_loop")) return
                val x = w.nodes.indexOf(start); val y = w.nodes.indexOf(end)
                val step = if (y > x) 1 else -1
                if (i == 0 && step != a.outStep || i == ids.lastIndex && -step != b.inStep) return
                val part = if (step == 1) w.nodes.subList(x, y + 1) else w.nodes.subList(y, x + 1).asReversed()
                full += if (full.isEmpty()) part else part.drop(1)
                legs += mapOf("wayId" to ids[i], "step" to step, "oneway" to w.oneway, "origin" to w.origin)
            }
            if (full.size != full.toSet().size) return
            var length = 0.0
            for ((u, v) in full.zipWithNext()) {
                val x = coordinates[u] ?: return; val y = coordinates[v] ?: return
                length += hypot(x.first - y.first, x.second - y.second)
            }
            if (length in 2.0..60.0) paths += Path(full, ids, round(length * 1000) / 1000, legs)
        }
        fun join(i: Int, selected: List<Long>) {
            if (i == joins.size) { inspect(selected); return }
            for (node in joins[i]) join(i + 1, selected + node)
        }
        join(0, emptyList())
        return paths.distinctBy { it.nodeIds }
    }
}
