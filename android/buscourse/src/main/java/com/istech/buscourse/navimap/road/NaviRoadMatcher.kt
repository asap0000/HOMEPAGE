package com.istech.buscourse.navimap.road

import android.database.sqlite.SQLiteDatabase
import com.istech.buscourse.navimap.NaviGuidanceEngine
import com.istech.buscourse.navimap.NaviGuidanceCues
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.util.PriorityQueue
import kotlin.math.*

/** Local, read-only translation of the connected-road matching rules. */
class NaviRoadMatcher(private val file: File) {
    private data class P(val x: Double, val y: Double)
    private data class Way(val id: Long, val highway: String, val tags: Map<String, String>,
        val direction: String, val origin: String, val nodes: List<Long>)
    private data class Edge(val a: Long, val b: Long, val way: Long, val pos: Int, val u: P, val v: P,
        val length: Double)
    private data class Candidate(val edge: Int, val t: Double, val d: Double)
    private data class Span(val edge: Int, val start: Double, val end: Double)
    private data class Transition(val distance: Double, val spans: List<Span>)
    private data class Window(val status: String, val nodes: List<RoadVisit> = emptyList(),
        val mean: Double = 0.0, val p95: Double = 0.0, val chord: Double = 0.0)
    private val car = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
        "living_street", "service", "road", "track", "busway", "bus_guideway")
    private lateinit var db: SQLiteDatabase
    private lateinit var origin: NaviGuidanceCues.TrackPoint
    private var sx = 0.0
    private val ways = mutableMapOf<Long, Way>()
    private val coords = mutableMapOf<Long, P>()
    private val edges = mutableListOf<Edge>()
    private val grid = mutableMapOf<Pair<Int, Int>, MutableSet<Int>>()
    private val degrees = mutableMapOf<Long, Int>()
    private val controls = mutableMapOf<Long, List<String>>()

    suspend fun match(index: NaviRoadIndexStore.Index, groups: List<NaviGuidanceEngine.TrackGroup>,
        progress: (Int, Int) -> Unit = { _, _ -> }): RoadEvidence {
        val first = groups.firstOrNull()?.points?.firstOrNull()
            ?: return RoadEvidence(index.regionId, index.mapSha256, index.sha256, null, "", emptyList(), emptyList())
        origin = first
        sx = 111195.0 * cos(Math.toRadians(first.lat))
        db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            loadRoads(groups)
            val targets = mutableListOf<Pair<NaviGuidanceEngine.TrackGroup, Double>>()
            for (group in groups) targets += targetVisits(group)
            progress(0, targets.size)
            val visits = mutableListOf<RoadVisit>()
            targets.forEachIndexed { i, (group, target) ->
                currentCoroutineContext().ensureActive()
                val window = matchWindow(group, target)
                if (window.status == "CONNECTED_FIT") for (node in window.nodes) {
                    val row = node.copy(meanLateralM = window.mean, p95LateralM = window.p95, outerChordM = window.chord)
                    val prior = visits.indexOfFirst { it.nodeId == row.nodeId && it.segmentId == row.segmentId && abs(it.chainageM - row.chainageM) < 40 }
                    if (prior < 0) visits += row else if (row.meanLateralM < visits[prior].meanLateralM) visits[prior] = row
                }
                progress(i + 1, targets.size)
            }
            visits.sortBy { it.chainageM }
            val decided = visits.map { n ->
                val partner = visits.firstOrNull { it.segmentId == n.segmentId && it.nodeId != n.nodeId &&
                    abs(it.chainageM - n.chainageM) in 2.0..45.0 && abs(it.angleDeg) >= 28 &&
                    it.angleDeg * n.angleDeg < 0 && !it.continuingRoad && !it.roundabout }
                val gps = n.gpsAngles.values.any { it != null && it * n.angleDeg > 0 && abs(it) >= 20 }
                val reason = when {
                    abs(n.angleDeg) < 28 -> "straight"
                    n.continuingRoad -> "road-continuity"
                    n.roundabout -> "roundabout-sequence-pending"
                    abs(n.angleDeg) >= 150 -> "large-return-kept-to-GPS"
                    partner != null -> "connected-crank"
                    abs(n.angleDeg) >= 40 && gps -> "isolated-geometry-and-GPS"
                    else -> "isolated-weak-angle-not-added"
                }
                n.copy(decision = reason, crankPartner = if (reason == "connected-crank") partner?.nodeId else null)
            }
            val timestamp = if (tableColumns("metadata").containsAll(listOf("name", "value")))
                db.rawQuery("SELECT value FROM metadata WHERE name IN ('data_timestamp_utc','dataTimestampUTC') LIMIT 1", null).use {
                    if (it.moveToFirst()) it.getString(0) else null
                } else null
            return RoadEvidence(index.regionId, index.mapSha256, index.sha256, timestamp,
                RoadFingerprint.of(groups), decided, decided.filter { it.decision == "connected-crank" || it.decision == "isolated-geometry-and-GPS" })
        } finally { db.close() }
    }

    private suspend fun loadRoads(groups: List<NaviGuidanceEngine.TrackGroup>) {
        // way_tiles は z14 固定の (x, y, way_refs)。way_refs は node_refs と同じ符号化の道ID列（索引の metadata.spatial_index）。
        val tiles = mutableSetOf<Pair<Int, Int>>()
        for (group in groups) {
            val ps = group.points
            var c = ps.first().chainageM
            while (c <= ps.last().chainageM) {
                currentCoroutineContext().ensureActive()
                val p = interpolateGeo(ps, c)
                val (tx, ty) = tile(p.lon, p.lat)
                for (dx in -1..1) for (dy in -1..1) tiles += (tx + dx) to (ty + dy)
                c += 100
            }
            val (tx, ty) = tile(ps.last().lon, ps.last().lat)
            for (dx in -1..1) for (dy in -1..1) tiles += (tx + dx) to (ty + dy)
        }
        val ids = mutableSetOf<Long>()
        for ((tx, ty) in tiles) {
            db.rawQuery("SELECT way_refs FROM way_tiles WHERE x=? AND y=?",
                arrayOf(tx.toString(), ty.toString())).use { c -> while (c.moveToNext()) ids += decodeRefs(c.getBlob(0)) }
        }
        for (batch in ids.chunked(800)) {
            currentCoroutineContext().ensureActive()
            db.rawQuery("SELECT id,highway,tags_json,oneway_direction,oneway_origin,node_count,node_refs FROM ways WHERE id IN (${batch.joinToString(",") { "?" }})",
                batch.map(Long::toString).toTypedArray()).use { c -> while (c.moveToNext()) {
                val highway = c.getString(1)
                val tags = JSONObject(c.getString(2)).let { json -> json.keys().asSequence().associateWith { json.optString(it) } }
                if (highway in car && tags["area"] != "yes") {
                    val refs = decodeRefs(c.getBlob(6))
                    if (refs.size >= 2) ways[c.getLong(0)] = Way(c.getLong(0), highway, tags,
                        c.getString(3) ?: "", c.getString(4) ?: "", refs)
                }
            } }
        }
        loadCoords(ways.values.flatMap { it.nodes }.toSet())
        for (way in ways.values) for (i in 0 until way.nodes.lastIndex) {
            val u = coords[way.nodes[i]] ?: continue
            val v = coords[way.nodes[i + 1]] ?: continue
            val len = distance(u, v)
            if (len < .05) continue
            val index = edges.size
            edges += Edge(way.nodes[i], way.nodes[i + 1], way.id, i, u, v, len)
            val minX = floor(min(u.x, v.x) / 100).toInt(); val maxX = floor(max(u.x, v.x) / 100).toInt()
            val minY = floor(min(u.y, v.y) / 100).toInt(); val maxY = floor(max(u.y, v.y) / 100).toInt()
            for (gx in minX..maxX) for (gy in minY..maxY) grid.getOrPut(gx to gy) { mutableSetOf() } += index
        }
    }

    private fun tableColumns(table: String): Set<String> = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(1)) }
    }
    private suspend fun loadCoords(ids: Set<Long>) {
        for (batch in ids.filterNot(coords::containsKey).chunked(800)) {
            currentCoroutineContext().ensureActive()
            db.rawQuery("SELECT id,lon_e7,lat_e7 FROM nodes WHERE id IN (${batch.joinToString(",") { "?" }})",
                batch.map(Long::toString).toTypedArray()).use { c -> while (c.moveToNext())
                coords[c.getLong(0)] = xy(c.getLong(1) / 1e7, c.getLong(2) / 1e7)
            }
        }
    }
    private fun degree(id: Long): Int = degrees.getOrPut(id) {
        val neighbors = mutableSetOf<Long>()
        db.rawQuery("SELECT way_positions FROM node_links WHERE node_id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) for ((wid, pos) in decodeLinks(c.getBlob(0))) {
                val way = ways[wid] ?: readWay(wid) ?: continue
                if (pos > 0 && pos < way.nodes.size) neighbors += way.nodes[pos - 1]
                if (pos + 1 < way.nodes.size) neighbors += way.nodes[pos + 1]
            }
        }
        neighbors.size
    }
    private fun readWay(id: Long): Way? {
        db.rawQuery("SELECT id,highway,tags_json,oneway_direction,oneway_origin,node_count,node_refs FROM ways WHERE id=?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst() || c.getString(1) !in car) return null
            val json = JSONObject(c.getString(2))
            if (json.optString("area") == "yes") return null
            return Way(id, c.getString(1), json.keys().asSequence().associateWith { json.optString(it) },
                c.getString(3) ?: "", c.getString(4) ?: "", decodeRefs(c.getBlob(6))).also { ways[id] = it }
        }
    }
    private fun controls(id: Long): List<String> = controls.getOrPut(id) {
        db.rawQuery("SELECT kind FROM controls WHERE node_id=?", arrayOf(id.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }
    private suspend fun targetVisits(group: NaviGuidanceEngine.TrackGroup): List<Pair<NaviGuidanceEngine.TrackGroup, Double>> {
        val ps = group.points
        if (ps.size < 2) return emptyList()
        val found = mutableMapOf<Long, MutableList<Pair<Double, Double>>>()
        var c = ps.first().chainageM
        while (c <= ps.last().chainageM) {
            currentCoroutineContext().ensureActive()
            val center = interpolate(ps, c)
            val near = gridEdges(center.x - 65, center.y - 65, center.x + 65, center.y + 65)
                .filter { intersects(edges[it], center.x - 65, center.y - 65, center.x + 65, center.y + 65) }
            val nodes = near.flatMap { ways.getValue(edges[it].way).nodes }.toSet()
            val local = ps.filter { it.chainageM in (c - 85)..(c + 85) }
            if (local.size >= 2) for (id in nodes) {
                val point = coords[id] ?: continue
                if (distance(center, point) > 70 || degree(id) < 3) continue
                val (d, chain) = nearest(local, point)
                if (d > 20) continue
                val previous = found.getOrPut(id) { mutableListOf() }
                val at = previous.indexOfFirst { abs(it.first - chain) < 60 }
                if (at < 0) previous += chain to d else if (d < previous[at].second) previous[at] = chain to d
            }
            c += 60
        }
        return found.values.flatten().map { group to it.first }.sortedBy { it.second }
    }

    private suspend fun matchWindow(group: NaviGuidanceEngine.TrackGroup, center: Double): Window {
        val context = currentCoroutineContext()
        val ps = group.points
        val lo = max(ps.first().chainageM, center - 65); val hi = min(ps.last().chainageM, center + 65)
        val samples = mutableListOf<Pair<Double, P>>()
        var m = lo
        while (m < hi) { samples += m to interpolate(ps, m); m += 5 }
        samples += hi to interpolate(ps, hi)
        if (samples.size < 10) return Window("SHORT_WINDOW")
        val minX = samples.minOf { it.second.x } - 40; val maxX = samples.maxOf { it.second.x } + 40
        val minY = samples.minOf { it.second.y } - 40; val maxY = samples.maxOf { it.second.y } + 40
        val local = gridEdges(minX, minY, maxX, maxY).filter { intersects(edges[it], minX, minY, maxX, maxY) }
        if (local.isEmpty()) return Window("NO_ROAD")
        val adjacency = mutableMapOf<Long, MutableList<Int>>()
        local.forEach { i -> val e = edges[i]; adjacency.getOrPut(e.a) { mutableListOf() } += i; adjacency.getOrPut(e.b) { mutableListOf() } += i }
        val candidates = samples.map { (_, p) -> local.mapNotNull { i ->
            val (d, t) = project(p, edges[i]); if (d <= 35) Candidate(i, t, d) else null
        }.sortedBy { it.d }.take(8) }
        if (candidates.any { it.isEmpty() }) return Window("GAP_OUTSIDE_35M")
        val memo = mutableMapOf<Pair<Long, Long>, Transition>()
        fun shortest(a: Long, b: Long): Transition {
            if (a == b) return Transition(0.0, emptyList())
            memo[a to b]?.let { return it }
            val queue = PriorityQueue(compareBy<Pair<Double, Long>> { it.first }.thenBy { it.second })
            val costs = mutableMapOf(a to 0.0)
            val parents = mutableMapOf<Long, Pair<Long, Int>>()
            queue += 0.0 to a
            while (queue.isNotEmpty()) {
                context.ensureActive()
                val (d, node) = queue.remove()
                if (d != costs[node]) continue
                if (node == b) {
                    val path = mutableListOf<Span>(); var at = b
                    while (at != a) {
                        val (previous, ei) = parents.getValue(at); val e = edges[ei]
                        path += Span(ei, if (e.a == previous) 0.0 else 1.0, if (e.b == at) 1.0 else 0.0)
                        at = previous
                    }
                    return Transition(d, path.asReversed()).also { memo[a to b] = it }
                }
                for (ei in adjacency[node].orEmpty()) {
                    val e = edges[ei]; val next = if (e.a == node) e.b else e.a; val nd = d + e.length
                    if (nd > 180 || nd >= (costs[next] ?: Double.POSITIVE_INFINITY)) continue
                    costs[next] = nd; parents[next] = node to ei; queue += nd to next
                }
            }
            return Transition(Double.POSITIVE_INFINITY, emptyList()).also { memo[a to b] = it }
        }
        fun transition(a: Candidate, b: Candidate): Transition {
            val e = edges[a.edge]; val f = edges[b.edge]
            var best = if (a.edge == b.edge) Transition(abs(a.t - b.t) * e.length, listOf(Span(a.edge, a.t, b.t)))
                else Transition(Double.POSITIVE_INFINITY, emptyList())
            for ((node, t) in listOf(e.a to 0.0, e.b to 1.0)) for ((next, s) in listOf(f.a to 0.0, f.b to 1.0)) {
                val path = shortest(node, next)
                val distance = path.distance + abs(a.t - t) * e.length + abs(b.t - s) * f.length
                if (distance < best.distance) best = Transition(distance,
                    listOf(Span(a.edge, a.t, t)) + path.spans + Span(b.edge, s, b.t))
            }
            return best
        }
        var scores = candidates[0].map { it.d * it.d / 144 }
        val parents = mutableListOf<List<Pair<Int, List<Span>>?>>()
        for (j in 1 until samples.size) {
            context.ensureActive()
            val ds = samples[j].first - samples[j - 1].first
            val gps = distance(samples[j].second, samples[j - 1].second)
            val row = mutableListOf<Double>(); val back = mutableListOf<Pair<Int, List<Span>>?>()
            for (q in candidates[j]) {
                var best = Double.POSITIVE_INFINITY; var prior: Pair<Int, List<Span>>? = null
                candidates[j - 1].forEachIndexed { i, p ->
                    if (!scores[i].isFinite()) return@forEachIndexed
                    val path = transition(p, q)
                    if (path.distance > max(45.0, ds * 6)) return@forEachIndexed
                    val cost = scores[i] + q.d * q.d / 144 + abs(path.distance - ds) / 15 + abs(path.distance - gps) / 12
                    if (cost < best) { best = cost; prior = i to path.spans }
                }
                row += best; back += prior
            }
            if (row.none { it.isFinite() }) return Window("DISCONNECTED_SEQUENCE")
            scores = row; parents += back
        }
        var k = scores.indices.minBy { scores[it] }
        val chunks = mutableListOf<List<Span>>()
        val picked = mutableListOf(candidates.last()[k])
        for (j in samples.lastIndex downTo 1) {
            val (previous, path) = parents[j - 1][k] ?: return Window("DISCONNECTED_SEQUENCE")
            chunks += path; k = previous; picked += candidates[j - 1][k]
        }
        picked.reverse()
        val joined = mutableListOf<Span>()
        for (span in chunks.asReversed().flatten()) {
            if (abs(span.start - span.end) * edges[span.edge].length <= .01) continue
            var start = span.start
            if (joined.isNotEmpty() && joined.last().edge == span.edge && abs(joined.last().end - start) < 1e-6)
                start = joined.removeAt(joined.lastIndex).start
            if (abs(start - span.end) * edges[span.edge].length > .01) joined += span.copy(start = start)
        }
        val residuals = picked.map { it.d }.sorted()
        val mean = residuals.average(); val p95 = residuals[min(residuals.lastIndex, floor(residuals.size * .95).toInt())]
        val chord = min(distance(samples.first().second, interpolate(ps, center)), distance(samples.last().second, interpolate(ps, center)))
        val fit = mean <= 12 && p95 <= 22 && residuals.last() <= 35 && chord >= 20
        val nearby = ps.filter { it.chainageM in (lo - 10)..(hi + 10) }
        val visits = mutableListOf<RoadVisit>()
        for ((a, b) in joined.zipWithNext()) {
            if (a.edge == b.edge || a.end !in listOf(0.0, 1.0) || b.start !in listOf(0.0, 1.0)) continue
            val e = edges[a.edge]; val f = edges[b.edge]
            val id = if (a.end == 0.0) e.a else e.b
            if (id != (if (b.start == 0.0) f.a else f.b) || degree(id) < 3) continue
            val point = coords[id] ?: continue
            val (lateral, chain) = nearest(nearby, point)
            if (abs(chain - center) > 30 || lateral > 20) continue
            val inPosition = e.pos + if (a.end == 1.0) 1 else 0
            val outPosition = f.pos + if (b.start == 1.0) 1 else 0
            val incoming = arm(id, e.way, inPosition, if (a.end == 1.0) -1 else 1)
            val outgoing = arm(id, f.way, outPosition, if (b.start == 0.0) 1 else -1)
            val angle = norm(outgoing.first - incoming.first - 180)
            val ew = ways.getValue(e.way); val fw = ways.getValue(f.way)
            val same = ew.id == fw.id || (ew.tags["name"]?.isNotEmpty() == true && ew.tags["name"] == fw.tags["name"] && ew.highway == fw.highway)
            val gps = listOf(8, 15, 25).associate { width ->
                val before = interpolate(ps, max(ps.first().chainageM, chain - width))
                val middle = interpolate(ps, chain)
                val after = interpolate(ps, min(ps.last().chainageM, chain + width))
                width.toString() to if (min(distance(before, middle), distance(middle, after)) >= 4)
                    round3(norm(bearing(middle, after) - bearing(before, middle))) else null
            }
            visits += RoadVisit(id, group.id, round3(chain), round3(lateral), round3(angle), kind(angle), e.way, f.way,
                if (a.end == 1.0) -1 else 1, if (b.start == 0.0) 1 else -1,
                round3(incoming.second), round3(outgoing.second), round3(incoming.first), round3(outgoing.first),
                degree(id), controls(id), same && abs(angle) < 70,
                listOf(ew, fw).any { it.tags["junction"] in listOf("roundabout", "circular") }, gps, fit,
                (ew.tags + fw.tags).filterKeys { key -> listOf("bus", "psv", "conditional").any(key::contains) },
                listOf(ew, fw).map { mapOf("id" to it.id, "direction" to it.direction, "origin" to it.origin) }, 0.0, 0.0, 0.0)
        }
        val unique = visits.distinctBy { it.nodeId to round(it.chainageM / 10) }
        return Window(if (fit) "CONNECTED_FIT" else "FIT_UNCERTAIN", unique, round3(mean), round3(p95), round3(chord))
    }

    private fun arm(id: Long, wayId: Long, position: Int, step: Int): Pair<Double, Double> {
        val way = ways.getValue(wayId); val start = coords.getValue(id)
        var previous = start; var total = 0.0; var end = start; var j = position + step
        while (j in way.nodes.indices) {
            end = coords[way.nodes[j]] ?: break
            val piece = distance(previous, end)
            if (total + piece >= 20 && piece > 0) {
                val f = (20 - total) / piece
                end = P(previous.x + (end.x - previous.x) * f, previous.y + (end.y - previous.y) * f)
                total = 20.0; break
            }
            total += piece
            if (degree(way.nodes[j]) >= 3) break
            previous = end; j += step
        }
        return bearing(start, end) to total
    }
    private fun gridEdges(loX: Double, loY: Double, hiX: Double, hiY: Double): Set<Int> = buildSet {
        for (gx in floor(loX / 100).toInt()..floor(hiX / 100).toInt())
            for (gy in floor(loY / 100).toInt()..floor(hiY / 100).toInt()) addAll(grid[gx to gy].orEmpty())
    }
    private fun intersects(e: Edge, loX: Double, loY: Double, hiX: Double, hiY: Double): Boolean {
        // Liang-Barsky clipping includes segments that cross the box with both endpoints outside.
        val dx = e.v.x - e.u.x; val dy = e.v.y - e.u.y
        var low = 0.0; var high = 1.0
        for ((p, q) in listOf(-dx to (e.u.x - loX), dx to (hiX - e.u.x), -dy to (e.u.y - loY), dy to (hiY - e.u.y))) {
            if (p == 0.0) { if (q < 0) return false } else {
                val r = q / p
                if (p < 0) low = max(low, r) else high = min(high, r)
            }
        }
        return low <= high
    }
    private fun project(p: P, e: Edge): Pair<Double, Double> {
        val dx = e.v.x - e.u.x; val dy = e.v.y - e.u.y
        val t = ((p.x - e.u.x) * dx + (p.y - e.u.y) * dy) / (e.length * e.length)
        val f = t.coerceIn(0.0, 1.0)
        return distance(p, P(e.u.x + dx * f, e.u.y + dy * f)) to f
    }
    private fun nearest(ps: List<NaviGuidanceCues.TrackPoint>, p: P): Pair<Double, Double> {
        var best = Double.POSITIVE_INFINITY to 0.0
        for (i in 0 until ps.lastIndex) {
            val a = xy(ps[i].lon, ps[i].lat); val b = xy(ps[i + 1].lon, ps[i + 1].lat)
            val dx = b.x - a.x; val dy = b.y - a.y; val d = dx * dx + dy * dy
            val f = if (d == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / d).coerceIn(0.0, 1.0)
            val lateral = distance(p, P(a.x + dx * f, a.y + dy * f))
            if (lateral < best.first) best = lateral to (ps[i].chainageM + (ps[i + 1].chainageM - ps[i].chainageM) * f)
        }
        return best
    }
    private fun interpolate(ps: List<NaviGuidanceCues.TrackPoint>, c: Double): P {
        if (c <= ps.first().chainageM) return xy(ps.first().lon, ps.first().lat)
        if (c >= ps.last().chainageM) return xy(ps.last().lon, ps.last().lat)
        var lo = 0; var hi = ps.lastIndex
        while (lo < hi) { val mid = (lo + hi) / 2; if (ps[mid].chainageM < c) lo = mid + 1 else hi = mid }
        val a = ps[lo - 1]; val b = ps[lo]
        val f = if (a.chainageM == b.chainageM) 0.0 else (c - a.chainageM) / (b.chainageM - a.chainageM)
        val u = xy(a.lon, a.lat); val v = xy(b.lon, b.lat)
        return P(u.x + (v.x - u.x) * f, u.y + (v.y - u.y) * f)
    }
    private fun interpolateGeo(ps: List<NaviGuidanceCues.TrackPoint>, c: Double): NaviGuidanceCues.TrackPoint {
        if (c <= ps.first().chainageM) return ps.first()
        if (c >= ps.last().chainageM) return ps.last()
        var i = 1
        while (i < ps.size && ps[i].chainageM < c) i++
        val a = ps[i - 1]; val b = ps[i]
        val f = if (a.chainageM == b.chainageM) 0.0 else (c - a.chainageM) / (b.chainageM - a.chainageM)
        return a.copy(chainageM = c, lat = a.lat + (b.lat - a.lat) * f, lon = a.lon + (b.lon - a.lon) * f)
    }
    private fun xy(lon: Double, lat: Double) = P((lon - origin.lon) * sx, (lat - origin.lat) * 111195.0)
    private fun distance(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)
    private fun bearing(a: P, b: P) = (Math.toDegrees(atan2(b.x - a.x, b.y - a.y)) + 360) % 360
    private fun norm(v: Double) = ((v + 180) % 360 + 360) % 360 - 180
    private fun round3(v: Double) = floor(v * 1000 + .5) / 1000
    private fun kind(angle: Double): String {
        val side = if (angle >= 0) "RIGHT" else "LEFT"
        val a = abs(norm(angle))
        return when { a < 28 -> "STRAIGHT"; a < 70 -> "DIAGONAL_$side"; a < 110 -> "${side}_DIRECTION"
            a < 150 -> "${side}_FRONT"; a < 170 -> "${side}_RETURN"; else -> "RETURN" }
    }
    private fun tile(lon: Double, lat: Double): Pair<Int, Int> {
        val n = 1 shl 14
        val x = floor((lon + 180) / 360 * n).toInt()
        val y = floor((1 - asinh(tan(Math.toRadians(lat))) / PI) / 2 * n).toInt()
        return x to y
    }
    private fun decodeRefs(blob: ByteArray): List<Long> {
        val values = mutableListOf<Long>(); var previous = 0L; var value = 0L; var shift = 0
        for (byte in blob) {
            val b = byte.toInt() and 255; value = value or ((b and 127).toLong() shl shift)
            if (b and 128 != 0) { shift += 7; require(shift <= 63) } else {
                previous += if (value and 1L != 0L) -(value + 1) / 2 else value / 2
                require(previous > 0); values += previous; value = 0; shift = 0
            }
        }
        require(shift == 0); return values
    }
    private fun decodeLinks(blob: ByteArray): List<Pair<Long, Int>> {
        val values = mutableListOf<Long>(); var value = 0L; var shift = 0
        for (byte in blob) {
            val b = byte.toInt() and 255; value = value or ((b and 127).toLong() shl shift)
            if (b and 128 != 0) { shift += 7; require(shift <= 63) } else { values += value; value = 0; shift = 0 }
        }
        require(shift == 0 && values.size % 2 == 0)
        var way = 0L
        return values.chunked(2).map { way += it[0]; require(way > 0); way to it[1].toInt() }
    }
}
