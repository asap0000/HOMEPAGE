package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.CourseStopProvenance
import org.junit.Test

/**
 * 運行の記録の時刻から足した停留所の入れ方（[insertRecordedStop]）の単体テスト
 * （委託指示書 L、2026-10-22）。座標・時刻はすべて架空。
 */
class RecordedStopInsertionTest {
    private val t0 = 1_000_000_000_000L

    /** 運行の記録から来た、映像だけの点（創設パス1の出力に相当）。 */
    private fun recorded(frameId: Long, atSec: Int, name: String) = StopRow(
        frameId = frameId, eventId = null, cardId = null, displayName = name, riderCount = 0,
        thumbRelPath = "f$frameId.jpg", capturedAt = t0 + atSec * 1000L, errorSpaceM = null,
        latitude = 1.0, longitude = 2.0,
    )

    private fun cardOnly(cardId: Long, name: String) = StopRow(
        frameId = null, eventId = null, cardId = cardId, displayName = name, riderCount = 2,
        thumbRelPath = null, capturedAt = null, errorSpaceM = null, latitude = 1.0, longitude = 2.0,
    )

    private fun added(frameId: Long, atSec: Int) = StopRow(
        frameId = frameId, eventId = null, cardId = null, displayName = "", riderCount = 0,
        thumbRelPath = "f$frameId.jpg", capturedAt = t0 + atSec * 1000L, errorSpaceM = null,
        latitude = 3.0, longitude = 4.0,
        provenance = CourseStopProvenance.HUMAN_CORRECTED.name, resolvedLatitude = 3.0, resolvedLongitude = 4.0,
    )

    @Test
    fun test_足した停留所は時刻の順に入り名前を持たない() {
        val stops = listOf(recorded(1, 0, "S51-1"), recorded(2, 100, "S51-2"), recorded(3, 200, "S51-3"))

        val result = insertRecordedStop(stops, added(frameId = 9, atSec = 150), sourceSessionId = 51)

        // 100秒の点と200秒の点のあいだ（3番目）に入り、通番が振り直される。
        assertThat(result.map { it.frameId }).containsExactly(1L, 2L, 9L, 3L).inOrder()
        assertThat(result.map { it.displayName }).containsExactly("S51-1", "S51-2", "S51-3", "S51-4").inOrder()
        val row = result[2]
        // 名前（カード）を持たない・カードにも event にも結び付けない・出自は人の補正・小さく「記録から」と出る。
        assertThat(row.cardId).isNull()
        assertThat(row.eventId).isNull()
        assertThat(row.hasCard).isFalse()
        assertThat(row.provenance).isEqualTo(CourseStopProvenance.HUMAN_CORRECTED.name)
        assertThat(row.isFromRecord).isTrue()
        // 保存経路へ渡す形（名前の欄は無い）にも出自と位置が乗る。
        val edit = row.toEdit()
        assertThat(edit.cardId).isNull()
        assertThat(edit.eventId).isNull()
        assertThat(edit.frameId).isEqualTo(9L)
        assertThat(edit.provenance).isEqualTo(CourseStopProvenance.HUMAN_CORRECTED.name)
        assertThat(edit.resolvedLatitude).isEqualTo(3.0)
        assertThat(edit.resolvedLongitude).isEqualTo(4.0)
        // 元の並びの行は動かさない（名前以外は同じ）。
        assertThat(result[3].frameId).isEqualTo(3L)
    }

    @Test
    fun insertedBeforeFirstAndAfterLast_byTime() {
        val stops = listOf(recorded(1, 100, "S51-1"), recorded(2, 200, "S51-2"))

        val head = insertRecordedStop(stops, added(9, 10), 51)
        val tail = insertRecordedStop(stops, added(9, 999), 51)

        assertThat(head.map { it.frameId }).containsExactly(9L, 1L, 2L).inOrder()
        assertThat(tail.map { it.frameId }).containsExactly(1L, 2L, 9L).inOrder()
        assertThat(tail.map { it.displayName }).containsExactly("S51-1", "S51-2", "S51-3").inOrder()
    }

    @Test
    fun cardRowsKeepTheirNameAndAreSkippedWhenComparingTimes() {
        // カードだけの行（時刻なし）は時刻の比較に使わず、名前（カード名）も付け直さない。
        val stops = listOf(cardOnly(7, "架空カードA"), recorded(1, 100, "S51-2"), cardOnly(8, "架空カードB"))

        val result = insertRecordedStop(stops, added(9, 50), 51)

        // 100秒の行（2番目）の手前＝先頭のカード行の後ろ。
        assertThat(result.map { it.frameId }).containsExactly(null, 9L, 1L, null).inOrder()
        assertThat(result[0].displayName).isEqualTo("架空カードA")
        assertThat(result[3].displayName).isEqualTo("架空カードB")
        assertThat(result[1].displayName).isEqualTo("S51-2")
        assertThat(result[2].displayName).isEqualTo("S51-3")
    }

    @Test
    fun withoutAnyTimedRow_goesToTheEnd_andUnknownSessionKeepsQuestionMark() {
        val stops = listOf(cardOnly(7, "架空カードA"))

        val result = insertRecordedStop(stops, added(9, 50), sourceSessionId = null)

        assertThat(result.map { it.frameId }).containsExactly(null, 9L).inOrder()
        assertThat(result[1].displayName).isEqualTo("S?-2")
    }

    @Test
    fun addedFromRecordTimes_listsOnlyRowsAddedFromRecordWithAFrame() {
        val rows = listOf(recorded(1, 100, "S51-1"), added(9, 150), cardOnly(7, "架空カードA"))

        assertThat(addedFromRecordTimes(rows)).containsExactly(t0 + 150_000L)
    }
}
