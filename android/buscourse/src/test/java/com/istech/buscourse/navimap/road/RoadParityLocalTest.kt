package com.istech.buscourse.navimap.road

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.navimap.NaviGuidanceCues
import com.istech.buscourse.navimap.NaviGuidanceEngine
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/** Local comparison inputs stay outside the public repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RoadParityLocalTest {
    @Test fun browserDerivedGuidanceMatches() = runTest {
        val root = System.getenv("BUSCOURSE_PARITY_DIR")?.let(::File)
        assumeTrue("BUSCOURSE_PARITY_DIR is unset", root?.isDirectory == true)
        val indexFile = File(root!!, "nav.sqlite")
        assumeTrue("navigation index is absent", indexFile.isFile)
        val differences = mutableListOf<String>()
        for (key in listOf("blue123", "red91011", "late2")) {
            val source = JSONObject(File(root, "courses/$key.json").readText())
            val r7 = File(root, "derived-r7/$key.json")
            val derived = JSONObject((if (r7.isFile) r7 else File(root, "derived/$key.json")).readText())
            val expected = derived.getJSONArray("guidance")
            val segments = source.getJSONArray("segments")
            val pointSets = source.getJSONObject("points")
            val groups = buildList {
                for (i in 0 until segments.length()) {
                    val segment = segments.getJSONObject(i)
                    if (segment.getString("kind") != "TRACK") continue
                    val id = segment.get("id").toString()
                    val rows = pointSets.getJSONArray(id)
                    val points = (0 until rows.length()).map { j -> rows.getJSONObject(j).let { p ->
                        NaviGuidanceCues.TrackPoint(p.getDouble("chainageM"), p.optDouble("tRelS", 0.0),
                            p.getDouble("lat"), p.getDouble("lon"))
                    } }
                    add(NaviGuidanceEngine.TrackGroup(id, points))
                }
            }
            val stops = source.getJSONArray("stops").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getDouble("chainageM") } }
            val index = NaviRoadIndexStore.Index(indexFile, "local", "local", derived.getJSONObject("mapAssets").getJSONObject("summary").getString("regionId"))
            val evidence = NaviRoadMatcher(indexFile).match(index, groups)
            if (evidence.routeSha256 != derived.getJSONObject("mapGuidance").getString("routeSHA256"))
                differences += "$key: route SHA differs"
            // r7 guidance has cranks but predates signal wording.
            val actual = NaviGuidanceEngine.build(groups, stops, evidence.copy(visits = emptyList())).guidance
            val withSignals = NaviGuidanceEngine.build(groups, stops, evidence).guidance.map { it.cue }
            println("$key 信号を付けた案内 ${withSignals.count { it.bandText?.startsWith("信号を") == true }} 件（r9: 青123=19・赤91011=19・第一青バス遅2=8）")
            if (actual.size != expected.length()) differences += "$key: count actual=${actual.size} expected=${expected.length()}"
            for (i in 0 until minOf(actual.size, expected.length())) {
                val guidance = actual[i]; val a = guidance.cue; val e = expected.getJSONObject(i)
                val fields = mutableListOf<String>()
                if (a.kind.name != e.getString("kind")) fields += "kind"
                if (abs(a.chainageM - e.getDouble("chainageM")) > .5) fields += "chainageM"
                // 予告の距離と文は、ブラウザ版 r6 が文を2回作り直す（2回目で「続いて、」が落ち、予告距離が1回目の値で残る）ため一致しない。
                // アプリは1回で作る今までの決まりを保つので、ここは差を印字するだけにする（2026-10-09 Android 席）。
                if (abs(a.preDistanceM - e.getDouble("preDistanceM")) > .5 || a.preText != e.optString("preText").takeUnless { e.isNull("preText") })
                    println("$key [$i] 予告の差 a=(${a.preDistanceM.toInt()},${a.preText}) e=(${e.getDouble("preDistanceM").toInt()},${e.optString("preText")})")
                if (a.nearText != e.optString("nearText")) fields += "nearText"
                if (r7.isFile) {
                    val crank = guidance.evidence["crank"] as? Map<*, *>
                    val expectedCrank = e.optJSONObject("crank")
                    if (crank?.get("role") != expectedCrank?.optString("role")) fields += "crankRole"
                    val expectedDisplay = expectedCrank?.let { it.optDouble(if (it.optString("role") == "entry") "entryM" else "exitM") }
                        ?: e.getDouble("chainageM")
                    if (abs(a.displayM - expectedDisplay) > .5) fields += "displayM"
                }
                if (fields.isNotEmpty()) differences += "$key [$i] ${fields.joinToString()}"
            }
        }
        assertThat(differences).isEmpty()
    }
}
