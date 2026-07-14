package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.course.MarkerTimelineRow
import org.junit.Test

/**
 * [splitByHubs] の単体テスト（純Kotlin、DB・Android依存無し、Robolectric不要）。
 * BusCourse単体テスト基盤新設（②「コース編成(抽出)」フェーズA-2・S4「コース創設」共用ロジック、
 * 2026-07-14）の一部。`splitByHubs`・`CourseFragment`・`HubSplitResult`はすべて
 * `internal`（同一モジュール`:buscourse`のtestソースセットからは可視）。
 */
class SplitByHubsTest {

    private fun row(frameId: Long, stopCardId: Long, capturedAt: Long = frameId * 1000L) =
        MarkerTimelineRow(
            frameId = frameId,
            capturedAt = capturedAt,
            stopCardId = stopCardId,
            stopName = "stop$stopCardId",
            distanceM = null,
        )

    @Test
    fun noHubSelected_returnsEmptyResult() {
        val timeline = listOf(row(1, 10), row(2, 20), row(3, 30))

        val result = splitByHubs(timeline, hubStopCardIds = emptySet())

        assertThat(result.fragments).isEmpty()
        assertThat(result.hubEventLabels).isEmpty()
    }

    @Test
    fun emptyTimeline_withHubSelected_returnsEmptyResult() {
        val result = splitByHubs(emptyList(), hubStopCardIds = setOf(100L))

        assertThat(result.fragments).isEmpty()
        assertThat(result.hubEventLabels).isEmpty()
    }

    @Test
    fun singleHubEventInTheMiddle_splitsIntoTwoFragments() {
        // #1,#2(非拠点) 拠点(境界) #3,#4(非拠点)
        val timeline = listOf(
            row(1, 1), row(2, 2),
            row(3, 100), // hub
            row(4, 3), row(5, 4),
        )

        val result = splitByHubs(timeline, hubStopCardIds = setOf(100L))

        assertThat(result.fragments).hasSize(2)
        assertThat(result.fragments[0].stops.map { it.frameId }).containsExactly(1L, 2L).inOrder()
        assertThat(result.fragments[1].stops.map { it.frameId }).containsExactly(4L, 5L).inOrder()
        assertThat(result.hubEventLabels).containsExactly("#3")
    }

    @Test
    fun twoSeparateHubEvents_splitsIntoThreeFragments() {
        // session8実測: 拠点2点選択(非連続)で3断片になる挙動
        val timeline = listOf(
            row(1, 1),
            row(2, 100), // hub #1
            row(3, 2), row(4, 3),
            row(5, 100), // hub #2
            row(6, 4),
        )

        val result = splitByHubs(timeline, hubStopCardIds = setOf(100L))

        assertThat(result.fragments).hasSize(3)
        assertThat(result.fragments[0].stops.map { it.frameId }).containsExactly(1L)
        assertThat(result.fragments[1].stops.map { it.frameId }).containsExactly(3L, 4L).inOrder()
        assertThat(result.fragments[2].stops.map { it.frameId }).containsExactly(6L)
        assertThat(result.hubEventLabels).containsExactly("#2", "#5").inOrder()
    }

    @Test
    fun consecutiveHubMarks_areMergedIntoOneBoundaryEvent() {
        val timeline = listOf(
            row(1, 1),
            row(2, 100), row(3, 100), row(4, 100), // 連続する3つの拠点マーク→1境界にまとめる
            row(5, 2),
        )

        val result = splitByHubs(timeline, hubStopCardIds = setOf(100L))

        assertThat(result.fragments).hasSize(2)
        assertThat(result.fragments[0].stops.map { it.frameId }).containsExactly(1L)
        assertThat(result.fragments[1].stops.map { it.frameId }).containsExactly(5L)
        // 1境界イベントのみ・ラベルは含まれる3マーク分(#2,#3,#4)をまとめて1件で表現する
        assertThat(result.hubEventLabels).containsExactly("#2,#3,#4")
    }

    @Test
    fun leadingAndTrailingHubMarks_doNotProduceEmptyFragments() {
        // 先頭・末尾が拠点マークの場合、そこには空の断片を作らない
        val timeline = listOf(
            row(1, 100), row(2, 100), // 先頭の拠点(連続)
            row(3, 1), row(4, 2),
            row(5, 100), // 中間の拠点
            row(6, 3),
            row(7, 100), // 末尾の拠点
        )

        val result = splitByHubs(timeline, hubStopCardIds = setOf(100L))

        assertThat(result.fragments).hasSize(2)
        assertThat(result.fragments[0].stops.map { it.frameId }).containsExactly(3L, 4L).inOrder()
        assertThat(result.fragments[1].stops.map { it.frameId }).containsExactly(6L)
        assertThat(result.hubEventLabels).containsExactly("#1,#2", "#5", "#7").inOrder()
    }

    @Test
    fun fragment_startAtAndEndAt_matchFirstAndLastRowCapturedAt() {
        val timeline = listOf(
            row(1, 1, capturedAt = 1_000L),
            row(2, 2, capturedAt = 2_000L),
            row(3, 100, capturedAt = 3_000L), // hub
        )

        val result = splitByHubs(timeline, hubStopCardIds = setOf(100L))

        assertThat(result.fragments).hasSize(1)
        val fragment = result.fragments.single()
        assertThat(fragment.startAt).isEqualTo(1_000L)
        assertThat(fragment.endAt).isEqualTo(2_000L)
    }

    @Test
    fun onlyHubMarks_producesNoFragments() {
        val timeline = listOf(row(1, 100), row(2, 100))

        val result = splitByHubs(timeline, hubStopCardIds = setOf(100L))

        assertThat(result.fragments).isEmpty()
        assertThat(result.hubEventLabels).containsExactly("#1,#2")
    }
}
