package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.navimap.NaviFollow
import org.junit.Test

/**
 * 走行追従の状態モデル（設計§5-1）の純関数テスト（増分P4b-2）。
 * Composable本体([NaviMainScreen])には依存しない、[NaviMainFollowState]と3つの遷移関数のみを対象にする。
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
        val next = naviMainApplyFollowFix(state, NaviFollow.FollowFix(chainageM = 42.5, lateralOffsetM = 1.2))
        assertThat(next.chainageM).isEqualTo(42.5f)
        assertThat(next.lastFixChainageM).isEqualTo(42.5f)
    }

    @Test fun applyFollowFix_whilePreview_updatesLastFixButNotDisplayedChainage() {
        // プレビュー中（スライダーで先読み中）にGPSが進んでも、表示中のchainageは動かさない
        // （スライダーで固定した値をGPSが上書きしない）。
        val state = NaviMainFollowState(mode = NaviMainMode.PREVIEW, chainageM = 300f, lastFixChainageM = 10f)
        val next = naviMainApplyFollowFix(state, NaviFollow.FollowFix(chainageM = 42.5, lateralOffsetM = 1.2))
        assertThat(next.chainageM).isEqualTo(300f)
        assertThat(next.lastFixChainageM).isEqualTo(42.5f)
    }

    @Test fun applyFollowFix_nullFix_holdsPreviousState() {
        // GPS欠測／経路外＝chainageAtがnullを返す場合は直前の状態を保持する（フリーズ、§7-課題B）。
        val state = NaviMainFollowState(mode = NaviMainMode.FOLLOWING, chainageM = 200f, lastFixChainageM = 190f)
        val next = naviMainApplyFollowFix(state, null)
        assertThat(next).isEqualTo(state)
    }
}
