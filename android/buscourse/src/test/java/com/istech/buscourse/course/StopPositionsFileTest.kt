package com.istech.buscourse.course

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [StopPositionsFile]（`stop_positions.json`＝名前なしの停留所の位置）の単体テスト（委託指示書 L、2026-10-22）。
 * `org.json` を使うため Robolectric で動かす（素のJVM JUnitではスタブが例外を投げる）。座標はすべて架空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StopPositionsFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun test_停留所の位置のファイルが無くても動く() {
        val missing = File(tmp.root, StopPositionsFile.FILE_NAME)

        assertThat(StopPositionsFile.read(missing)).isEmpty()
        assertThat(StopPositionsFile.read(null)).isEmpty()

        // 位置が空でも、ほかの運行のマークだけで一覧と「その他」に振り分けられる。
        val t0 = 1_000_000_000_000L
        val fixes = (0..20).map { SpeedFix(t0 + it * 1000L, 0.0, 0.0, 0.1) } +
            (30..32).map { SpeedFix(t0 + it * 1000L, 0.01, 0.0, 9.0) } +
            (100..120).map { SpeedFix(t0 + it * 1000L, 0.02, 0.0, 0.1) }
        val result = RecordedStopCandidatePicker.pick(
            fixes = fixes,
            ownMarks = emptyList(),
            stopPositions = StopPositionsFile.read(missing),
            otherRunMarks = listOf(LatLon(0.0, 0.0)),
        )
        assertThat(result.listed.map { it.startMs }).containsExactly(t0)
        assertThat(result.others.map { it.startMs }).containsExactly(t0 + 100_000)
    }

    @Test
    fun parse_readsOnlyLatLon_andIgnoresAnyOtherKey() {
        val json = """
            {"schema":"stop-positions/1","generatedAt":1,"points":[
              {"lat":1.5,"lon":2.5},
              {"lat":-3.0,"lon":4.0,"name":"架空の名前","id":"x1","riders":3}
            ]}
        """.trimIndent()

        val points = StopPositionsFile.parse(json)

        assertThat(points).containsExactly(LatLon(1.5, 2.5), LatLon(-3.0, 4.0)).inOrder()
    }

    @Test
    fun parse_skipsBrokenPoints_andRejectsOtherSchemaOrBrokenJson() {
        val partly = """{"schema":"stop-positions/1","points":[{"lat":1.0,"lon":2.0},{"lat":"x","lon":2.0},{"lat":95.0,"lon":1.0},{"lon":3.0},5]}"""
        assertThat(StopPositionsFile.parse(partly)).containsExactly(LatLon(1.0, 2.0))

        assertThat(StopPositionsFile.parse("""{"schema":"stop-positions/2","points":[{"lat":1.0,"lon":2.0}]}""")).isEmpty()
        assertThat(StopPositionsFile.parse("""{"points":[{"lat":1.0,"lon":2.0}]}""")).isEmpty()
        assertThat(StopPositionsFile.parse("not json")).isEmpty()
        assertThat(StopPositionsFile.parse("""{"schema":"stop-positions/1"}""")).isEmpty()
    }

    @Test
    fun read_readsTheFileAndSurvivesABrokenOne() {
        val good = File(tmp.root, "good.json").also {
            it.writeText("""{"schema":"stop-positions/1","generatedAt":1,"points":[{"lat":1.0,"lon":2.0}]}""", Charsets.UTF_8)
        }
        val broken = File(tmp.root, "broken.json").also { it.writeText("{", Charsets.UTF_8) }

        assertThat(StopPositionsFile.read(good)).containsExactly(LatLon(1.0, 2.0))
        assertThat(StopPositionsFile.read(broken)).isEmpty()
    }
}
