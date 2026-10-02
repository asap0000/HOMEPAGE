package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executor
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ナビの走った跡（B の1歩目）。座標はすべて架空。org.json を使うので Robolectric で回す。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviRunLogTest {
    @get:Rule val temp = TemporaryFolder()

    private val direct = Executor { it.run() }
    private val day = 24L * 60 * 60 * 1000

    private fun header(startedAtMs: Long = 1_000_000L) =
        NaviRunLog.Header(startedAtMs, "架空", 1, 2026, 7L, "test", 40.0, 20.0)

    private fun fix(tMs: Long, ch: Double?, on: Boolean) =
        NaviRunLog.Fix(tMs, 10.0, 20.0, 4f, 5f, 90f, ch, if (on) 2.0 else 60.0, on, true)

    /** 書き手に測位を流し、閉じたあとの要約を返す。 */
    private fun record(nowAtClose: Long, fixes: List<NaviRunLog.Fix>, body: File = File(temp.root, "1000000.jsonl")): NaviRunLog.Summary? {
        var now = 1_000_000L
        val writer = NaviRunWriter(body, header(), direct, nowMs = { now })
        fixes.forEach { writer.onFix(it) }
        now = nowAtClose
        writer.close()
        return NaviRunLog.readSummary(NaviRunLog.summaryFileOf(body))
    }

    @Test fun writerToSummary_leaveAt5000_rejoinAt5300_after70s() {
        val s = record(
            nowAtClose = 1_000_000L + 300_000,
            fixes = listOf(
                fix(1_010_000, 4900.0, true),
                fix(1_020_000, 5000.0, true),
                fix(1_030_000, 5100.0, false),
                fix(1_100_000, 5300.0, true),
                fix(1_200_000, 6000.0, true),
            ),
        )!!
        assertThat(s.outages).containsExactly(NaviRunLog.Outage(5000.0, 5300.0, 70))
        assertThat(s.fixCount).isEqualTo(5)
        assertThat(s.busId).isEqualTo("架空")
        assertThat(s.naviMapId).isEqualTo(7L)
    }

    @Test fun offCourseUntilTheEnd_rejoinIsNull_withAndWithoutEndLine() {
        val withEnd = record(1_000_000L + 120_000, listOf(fix(1_010_000, 100.0, true), fix(1_020_000, null, false)))!!
        assertThat(withEnd.outages.single().rejoinChainageM).isNull()
        assertThat(withEnd.outages.single().leaveChainageM).isEqualTo(100.0)

        // 落ちた回（end の行が無い）
        val lines = listOf(
            NaviRunLog.headerLine(header()),
            NaviRunLog.fixLine(fix(1_010_000, 100.0, true)),
            NaviRunLog.eventLine("leave", 1_020_000, 100.0),
            NaviRunLog.fixLine(fix(1_080_000, null, false)),
        )
        val crashed = NaviRunLog.summarize(lines)!!
        assertThat(crashed.outages).containsExactly(NaviRunLog.Outage(100.0, null, 60))
        assertThat(crashed.endedAtMs).isEqualTo(1_080_000)
    }

    @Test fun truncatedLastLine_isSkipped() {
        val lines = listOf(
            NaviRunLog.headerLine(header()),
            NaviRunLog.fixLine(fix(1_010_000, 100.0, true)),
            NaviRunLog.fixLine(fix(1_070_000, 200.0, true)).take(20),
        )
        val s = NaviRunLog.summarize(lines)!!
        assertThat(s.fixCount).isEqualTo(1)
        assertThat(s.endedAtMs).isEqualTo(1_010_000)
    }

    @Test fun shortOutageIsStillCounted() {
        val s = record(
            1_000_000L + 120_000,
            listOf(fix(1_010_000, 100.0, true), fix(1_014_000, 100.0, false), fix(1_018_000, 110.0, true)),
        )!!
        assertThat(s.outages).containsExactly(NaviRunLog.Outage(100.0, 110.0, 4))
    }

    @Test fun startingOffCourse_leaveIsNull() {
        val s = record(1_000_000L + 120_000, listOf(fix(1_010_000, null, false), fix(1_050_000, 300.0, true)))!!
        assertThat(s.outages).containsExactly(NaviRunLog.Outage(null, 300.0, 40))
    }

    @Test fun under60sOrNoFix_isDiscarded_61sIsKept() {
        val short = File(temp.root, "1000000.jsonl")
        assertThat(record(1_000_000L + 59_000, listOf(fix(1_010_000, 1.0, true)), short)).isNull()
        assertThat(short.exists()).isFalse()

        val noFix = File(temp.root, "1000000.jsonl")
        assertThat(record(1_000_000L + 300_000, emptyList(), noFix)).isNull()
        assertThat(noFix.exists()).isFalse()

        val kept = File(temp.root, "1000000.jsonl")
        assertThat(record(1_000_000L + 61_000, listOf(fix(1_010_000, 1.0, true)), kept)).isNotNull()
        assertThat(kept.exists()).isTrue()
    }

    @Test fun retention_removesOlderThan180Days_keeps179_andLeavesForeignFiles() {
        val now = 400 * day
        val old = now - 181 * day
        val recent = now - 179 * day
        val files = listOf("$old.jsonl", "$old.summary.json", "$recent.jsonl", "$recent.summary.json", "memo.txt", "x.jsonl")
            .map { File(temp.root, it).apply { writeText("x") } }
        NaviRunLog.deleteExpired(temp.root, now)
        assertThat(files.filter { it.exists() }.map { it.name })
            .containsExactly("$recent.jsonl", "$recent.summary.json", "memo.txt", "x.jsonl")
    }

    @Test fun loadSummaries_rebuildsMissingSummary_andSortsNewestFirst() {
        fun body(start: Long, endMs: Long) = File(temp.root, "$start.jsonl").apply {
            writeText(
                listOf(
                    NaviRunLog.headerLine(header(start)),
                    NaviRunLog.fixLine(fix(start + 1000, 1.0, true)),
                    NaviRunLog.endLine(endMs),
                ).joinToString("\n"),
            )
        }
        val older = body(2_000_000, 2_000_000 + 90_000)
        val newer = body(3_000_000, 3_000_000 + 90_000)
        val list = NaviRunLog.loadSummaries(temp.root, 4_000_000)
        assertThat(list.map { it.startedAtMs }).containsExactly(3_000_000L, 2_000_000L).inOrder()
        assertThat(NaviRunLog.summaryFileOf(older).exists()).isTrue()
        assertThat(NaviRunLog.summaryFileOf(newer).exists()).isTrue()
    }

    @Test fun unwritableLocation_doesNotThrow_andReportsError() {
        val blocker = File(temp.root, "blocker").apply { writeText("file") }
        val errors = mutableListOf<Throwable>()
        val writer = NaviRunWriter(File(blocker, "child/1.jsonl"), header(), direct, onError = { errors += it })
        writer.onFix(fix(1_010_000, 1.0, true))
        writer.close()
        assertThat(errors).isNotEmpty()
    }

    /** 関係のない地域で開いた回（一度もコースに乗らない）は、測位が来ても丸ごと残さない。 */
    @Test fun neverOnCourse_isDiscarded() {
        val body = File(temp.root, "1000000.jsonl")
        val far = (0 until 120).map { i -> fix(1_010_000L + i * 1000, null, false).copy(inCorridor = false) }
        val near = (0 until 120).map { i -> fix(1_010_000L + i * 1000, null, false) }
        assertThat(record(1_000_000L + 300_000, far, body)).isNull()
        assertThat(body.exists()).isFalse()
        assertThat(record(1_000_000L + 300_000, near, body)).isNull()
        assertThat(body.exists()).isFalse()
    }

    /** 回廊（250m）の外では座標を書かず、その外れ方に「250mより外」が付く。 */
    @Test fun beyondCorridor_writesNoCoordinates_andMarksOutage() {
        val body = File(temp.root, "1000000.jsonl")
        var now = 1_000_000L
        val writer = NaviRunWriter(body, header(), direct, nowMs = { now })
        writer.onFix(fix(1_010_000, 5000.0, true))
        writer.onFix(fix(1_020_000, null, false).copy(lat = 11.0, lon = 21.0))
        writer.onFix(fix(1_030_000, null, false).copy(lat = 12.0, lon = 22.0, inCorridor = false))
        writer.onFix(fix(1_090_000, 5800.0, true))
        now = 1_000_000L + 300_000
        writer.close()
        val text = body.readText()
        assertThat(text).contains("\"lat\":11")
        assertThat(text).doesNotContain("\"lat\":12")
        assertThat(text).contains("\"kind\":\"far\"")
        val s = NaviRunLog.readSummary(NaviRunLog.summaryFileOf(body))!!
        assertThat(s.outages).containsExactly(NaviRunLog.Outage(5000.0, 5800.0, 70, beyondCorridor = true))
        assertThat(NaviRunLog.outageText(s.outages)).isEqualTo("外れた所 1か所：5.0〜5.8km（1分10秒・250mより外）")
    }

    @Test fun outageInsideCorridor_isNotMarked() {
        val s = record(
            1_000_000L + 300_000,
            listOf(fix(1_010_000, 100.0, true), fix(1_020_000, null, false), fix(1_030_000, 200.0, true)),
        )!!
        assertThat(s.outages.single().beyondCorridor).isFalse()
    }

    @Test fun isWithinRoute_200mIsInside_300mIsOutside() {
        // 東西にまっすぐの架空のコース（経度 0.00〜0.02・赤道上）。緯度 0.0018° ≒ 200m、0.0027° ≒ 300m。
        val route = listOf(0.0 to 0.0, 0.0 to 0.01, 0.0 to 0.02)
        assertThat(NaviRunLog.isWithinRoute(0.0018, 0.015, route)).isTrue()
        assertThat(NaviRunLog.isWithinRoute(0.0027, 0.015, route)).isFalse()
        // 端の先は端からの距離（0.02 から東へ 0.0027° ≒ 300m）
        assertThat(NaviRunLog.isWithinRoute(0.0, 0.0227, route)).isFalse()
        assertThat(NaviRunLog.isWithinRoute(0.0, 0.0, emptyList())).isFalse()
    }

    @Test fun labels() {
        assertThat(NaviRunLog.outageText(emptyList())).isEqualTo("外れた所なし")
        assertThat(NaviRunLog.outageText(listOf(NaviRunLog.Outage(100.0, 120.0, 4), NaviRunLog.Outage(5000.0, 5300.0, 65))))
            .isEqualTo("外れた所 2か所：0.1〜0.1km（4秒）／5.0〜5.3km（1分05秒）")
        assertThat(NaviRunLog.outageText(listOf(NaviRunLog.Outage(5000.0, null, 9))))
            .isEqualTo("外れた所 1か所：5.0km〜（戻らずに終了）")
        assertThat(NaviRunLog.outageText(listOf(NaviRunLog.Outage(null, 300.0, 40))))
            .isEqualTo("外れた所 1か所：出発時〜0.3km（40秒）")
    }
}
