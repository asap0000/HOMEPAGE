package com.istech.buscourse.navimap.road

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.navimap.NaviGuidanceCues
import com.istech.buscourse.navimap.NaviGuidanceEngine
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.tan

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviRoadMatcherTest {
    @Test fun connectedCrossingProducesVisitAndSignalEvidence() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "synthetic-road-${System.nanoTime()}.sqlite")
        makeRoads(file)
        val group = NaviGuidanceEngine.TrackGroup("synthetic", (0..400 step 5).map { m ->
            val north = minOf(m, 200).toDouble()
            val east = maxOf(0, m - 200).toDouble()
            NaviGuidanceCues.TrackPoint(m.toDouble(), m / 4.0,
                1.0 + north / 111195.0, 1.0 + east / (111195.0 * cos(Math.toRadians(1.0))))
        })
        val index = NaviRoadIndexStore.Index(file, "b".repeat(64), "a".repeat(64), "synthetic")
        val evidence = NaviRoadMatcher(file).match(index, listOf(group))
        assertThat(evidence.visits.any { it.nodeId == 103L && it.degree == 4 && "traffic_signals" in it.controls }).isTrue()
        assertThat(evidence.proposals.any { it.nodeId == 103L && it.decision == "isolated-geometry-and-GPS" }).isTrue()
    }

    private fun makeRoads(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE nodes(id INTEGER PRIMARY KEY, lon_e7 INTEGER, lat_e7 INTEGER)")
            db.execSQL("CREATE TABLE ways(id INTEGER PRIMARY KEY, highway TEXT, tags_json TEXT, oneway_direction TEXT, oneway_origin TEXT, node_count INTEGER, node_refs BLOB)")
            db.execSQL("CREATE TABLE node_links(node_id INTEGER PRIMARY KEY, way_positions BLOB)")
            db.execSQL("CREATE TABLE way_tiles(x INTEGER NOT NULL,y INTEGER NOT NULL,way_refs BLOB NOT NULL,PRIMARY KEY(x,y))")
            db.execSQL("CREATE TABLE controls(node_id INTEGER,kind TEXT)")
            db.execSQL("CREATE TABLE metadata(name TEXT,value TEXT)")
            val points = mapOf(101L to (0.0 to 0.0), 102L to (0.0 to 100.0), 103L to (0.0 to 200.0),
                104L to (100.0 to 200.0), 105L to (200.0 to 200.0), 106L to (0.0 to 300.0),
                107L to (-100.0 to 200.0))
            for ((id, p) in points) db.execSQL("INSERT INTO nodes VALUES(?,?,?)", arrayOf(id,
                ((1.0 + p.first / (111195.0 * cos(Math.toRadians(1.0)))) * 1e7).toLong(),
                ((1.0 + p.second / 111195.0) * 1e7).toLong()))
            val ways = mapOf(201L to listOf(101L, 102L, 103L), 202L to listOf(103L, 104L, 105L),
                203L to listOf(103L, 106L), 204L to listOf(107L, 103L))
            val tx = floor((1 + 180) / 360.0 * 16384).toInt()
            val ty = floor((1 - asinh(tan(Math.toRadians(1.0))) / PI) / 2 * 16384).toInt()
            for ((id, refs) in ways) {
                db.execSQL("INSERT INTO ways VALUES(?,?,?,?,?,?,?)", arrayOf(id, "residential", "{}", "both", "synthetic", refs.size, refsCodec(refs)))
            }
            // 実物の索引と同じ形: z14 固定の (x, y) ごとに、道IDの列を node_refs と同じ符号化で入れる。
            for (dx in -1..1) for (dy in -1..1)
                db.execSQL("INSERT INTO way_tiles VALUES(?,?,?)", arrayOf(tx + dx, ty + dy, refsCodec(ways.keys.sorted())))
            for (id in points.keys) {
                val members = ways.flatMap { (way, refs) -> refs.mapIndexedNotNull { pos, n -> if (n == id) way to pos else null } }.sortedBy { it.first }
                db.execSQL("INSERT INTO node_links VALUES(?,?)", arrayOf(id, linksCodec(members)))
            }
            db.execSQL("INSERT INTO controls VALUES(103,'traffic_signals')")
            db.execSQL("INSERT INTO metadata VALUES('data_timestamp_utc','synthetic')")
        }
    }
    private fun refsCodec(refs: List<Long>): ByteArray {
        var previous = 0L
        return refs.flatMap { id -> val delta = id - previous; previous = id; varint(if (delta < 0) -2 * delta - 1 else 2 * delta) }.toByteArray()
    }
    private fun linksCodec(links: List<Pair<Long, Int>>): ByteArray {
        var previous = 0L
        return links.flatMap { (id, pos) -> val delta = id - previous; previous = id; varint(delta) + varint(pos.toLong()) }.toByteArray()
    }
    private fun varint(value: Long): List<Byte> {
        var n = value; val bytes = mutableListOf<Byte>()
        while (n >= 128) { bytes += ((n and 127) or 128).toByte(); n = n ushr 7 }
        bytes += n.toByte(); return bytes
    }
}
