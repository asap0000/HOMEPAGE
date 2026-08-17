package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.navimap.NaviFollow
import org.junit.Test

/**
 * 走行追従の状態モデル（設計§5-1）の純関数テスト（増分P4b-2）。
 * Composable本体([NaviMainScreen])には依存しない、[NaviMainFollowState]と純粋な遷移関数のみを対象にする。
 */
class NaviMainFollowStateTest {

    @Test fun initialState_isFollowingAtZero() {
        val state = NaviMainFollowState()
        assertThat(state.mode).isEqualTo(NaviMainMode.FOLLOWING)
        assertThat(state.chainageM).isEqualTo(0f)
        assertThat(state.lastFixChainageM).isNull()
    }

    @Test fun enterPreview_switchesModeAndSetsChainageFromSlider() {
        val state = NaviMainFollowState(mode = NaviMainMode.FOLLOWING, chainageM = 50f, lastFixChainageM = 50f)
        val next = naviMainEnterPreview(state, 120f)
        assertThat(next.mode).isEqualTo(NaviMainMode.PREVIEW)
        assertThat(next.chainageM).isEqualTo(120f)
        // 直近のGPS位置は保持される（プレビュー中もNaviFollowの探索窓維持・復帰先のため）。
        assertThat(next.lastFixChainageM).isEqualTo(50f)
    }

    @Test fun recenter_returnsToFollowingAtLastFix() {
        val state = NaviMainFollowState(mode = NaviMainMode.PREVIEW, chainageM = 300f, lastFixChainageM = 80f)
        val next = naviMainRecenter(state)
        assertThat(next.mode).isEqualTo(NaviMainMode.FOLLOWING)
        assertThat(next.chainageM).isEqualTo(80f)
    }

    @Test fun recenter_withoutAnyFixYet_keepsCurrentChainage() {
        // GPS fixがまだ一度も来ていない状態でボタンを押しても、既存chainageのまま追従モードに入る
        // （0mへワープしない）。
        val state = NaviMainFollowState(mode = NaviMainMode.PREVIEW, chainageM = 300f, lastFixChainageM = null)
        val next = naviMainRecenter(state)
        assertThat(next.mode).isEqualTo(NaviMainMode.FOLLOWING)
        assertThat(next.chainageM).isEqualTo(300f)
    }

    @Test fun applyFollowFix_whileFollowing_movesDisplayedChainage() {
        val state = NaviMainFollowState(mode = NaviMainMode.FOLLOWING, chainageM = 10f, lastFixChainageM = 10f)
        val next = apply(state, NaviFollow.FollowFix(chainageM = 42.5, lateralOffsetM = 1.2))
        assertThat(next.chainageM).isEqualTo(42.5f)
        assertThat(next.lastFixChainageM).isEqualTo(42.5f)
    }

    @Test fun applyFollowFix_whilePreview_updatesLastFixButNotDisplayedChainage() {
        // プレビュー中（スライダーで先読み中）にGPSが進んでも、表示中のchainageは動かさない
        // （スライダーで固定した値をGPSが上書きしない）。
        val state = NaviMainFollowState(mode = NaviMainMode.PREVIEW, chainageM = 300f, lastFixChainageM = 10f)
        val next = apply(state, NaviFollow.FollowFix(chainageM = 42.5, lateralOffsetM = 1.2))
        assertThat(next.chainageM).isEqualTo(300f)
        assertThat(next.lastFixChainageM).isEqualTo(42.5f)
    }

    @Test fun nullFixWhileOnCourse_entersOffCourseAndHoldsChainage() {
        val state = NaviMainFollowState(mode = NaviMainMode.FOLLOWING, chainageM = 200f, lastFixChainageM = 190f)
        val next = apply(state, null)
        assertThat(next.onCourse).isFalse()
        assertThat(next.chainageM).isEqualTo(200f)
    }

    @Test fun offCourseHysteresis_freezesUntilOffsetAtMostTwenty() {
        val entered = apply(NaviMainFollowState(chainageM = 10f), NaviFollow.FollowFix(41.0, 41.0))
        val stillOff = apply(entered, NaviFollow.FollowFix(21.0, 21.0))
        val returned = apply(stillOff, NaviFollow.FollowFix(19.0, 19.0))
        assertThat(entered.onCourse).isFalse()
        assertThat(entered.chainageM).isEqualTo(10f)
        assertThat(stillOff.onCourse).isFalse()
        assertThat(stillOff.chainageM).isEqualTo(10f)
        assertThat(returned.onCourse).isTrue()
        assertThat(returned.chainageM).isEqualTo(19f)
    }

    @Test fun offCourse_updatesSelfPositionButKeepsHeadingBelowMinimumSpeed() {
        val state = NaviMainFollowState(onCourse = false, chainageM = 30f, selfHeadingDeg = 90.0)
        val next = naviMainApplyLocation(state, 35.0, 139.0, 180.0, 0.9, NaviFollow.FollowFix(80.0, 21.0))
        assertThat(next.selfLat).isEqualTo(35.0)
        assertThat(next.selfLon).isEqualTo(139.0)
        assertThat(next.selfHeadingDeg).isEqualTo(90.0)
        assertThat(next.chainageM).isEqualTo(30f)
    }

    @Test fun preview_returnFromOffCourse_updatesLastFixButNotDisplayedChainage() {
        val state = NaviMainFollowState(
            mode = NaviMainMode.PREVIEW, chainageM = 300f, lastFixChainageM = 10f, onCourse = false,
        )
        val next = apply(state, NaviFollow.FollowFix(42.5, 19.0))
        assertThat(next.onCourse).isTrue()
        assertThat(next.chainageM).isEqualTo(300f)
        assertThat(next.lastFixChainageM).isEqualTo(42.5f)
    }

    /** ★境界値: 40ちょうどは「乗っている」・20ちょうどは「戻った」（>40 で外れ・<=20 で復帰）。 */
    @Test fun hysteresisBoundaries_fortyStaysOn_twentyReturns() {
        val on = NaviMainFollowState(mode = NaviMainMode.FOLLOWING, chainageM = 10f, lastFixChainageM = 10f)
        val atForty = apply(on, NaviFollow.FollowFix(50.0, 40.0))
        assertThat(atForty.onCourse).isTrue()
        assertThat(atForty.chainageM).isEqualTo(50f)
        val off = NaviMainFollowState(onCourse = false, chainageM = 10f)
        val atTwenty = apply(off, NaviFollow.FollowFix(60.0, 20.0))
        assertThat(atTwenty.onCourse).isTrue()
        assertThat(atTwenty.chainageM).isEqualTo(60f)
    }

    /** ★走行中（速度1.0m/s以上）は実測方位を採用する（停車保持の裏側）。 */
    @Test fun headingUpdates_atOrAboveMinimumSpeed() {
        val state = NaviMainFollowState(selfHeadingDeg = 90.0)
        val next = naviMainApplyLocation(state, 35.0, 139.0, 180.0, 1.0, NaviFollow.FollowFix(5.0, 1.0))
        assertThat(next.selfHeadingDeg).isEqualTo(180.0)
    }

    private fun apply(state: NaviMainFollowState, fix: NaviFollow.FollowFix?) =
        naviMainApplyLocation(state, 35.0, 139.0, null, null, fix)
}
