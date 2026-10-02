package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaviGuidanceDispatcherTest {
    private val cue = NaviGuidanceCues.Cue(100.0, NaviGuidanceCues.Kind.LEFT, NaviGuidanceCues.Variant.V1, 40.0, 10.0, "40メートル先、左折です。", "まもなく左折です。")

    @Test fun speaksOnceOnGpsCrossingAndDoesNotReplayAfterSmallBacktrack() {
        var state = NaviGuidanceDispatcher.State()
        state = NaviGuidanceDispatcher.update(listOf(cue), state, 0.0, 50.0, true, true, true).state
        val crossed = NaviGuidanceDispatcher.update(listOf(cue), state, 65.0, 65.0, true, true, true)
        assertThat(crossed.speechText).isEqualTo(cue.preText)
        val back = NaviGuidanceDispatcher.update(listOf(cue), crossed.state, 60.0, 60.0, true, true, true)
        val again = NaviGuidanceDispatcher.update(listOf(cue), back.state, 65.0, 65.0, true, true, true)
        assertThat(again.speechText).isNull()
    }

    @Test fun manualOffCourseAndVoiceOffKeepBandButSuppressSpeech() {
        val initial = NaviGuidanceDispatcher.update(listOf(cue), NaviGuidanceDispatcher.State(), 0.0, 50.0, true, true, true).state
        val manual = NaviGuidanceDispatcher.update(listOf(cue), initial, 100.0, 65.0, false, true, true)
        assertThat(manual.bandText).isNull()
        assertThat(manual.speechText).isNull()
        val offCourse = NaviGuidanceDispatcher.update(listOf(cue), initial, 0.0, 65.0, true, false, true)
        assertThat(offCourse.bandText).isEqualTo("コースに戻ると案内を再開します")
        assertThat(offCourse.speechText).isNull()
        val voiceOff = NaviGuidanceDispatcher.update(listOf(cue), initial, 0.0, 65.0, true, true, false)
        assertThat(voiceOff.speechText).isNull()
    }

    /** ★「まもなく」は角そのものではなく、まもなくの距離（ここでは 10m）手前で鳴る（検分で発見・角を過ぎてから鳴っていた）。 */
    @Test fun nearAnnouncementFiresBeforeTheCornerNotAtIt() {
        var state = NaviGuidanceDispatcher.update(listOf(cue), NaviGuidanceDispatcher.State(), 70.0, 70.0, true, true, true).state
        val beforeNear = NaviGuidanceDispatcher.update(listOf(cue), state, 89.0, 89.0, true, true, true)
        assertThat(beforeNear.speechText).isNull()
        state = beforeNear.state
        val atNear = NaviGuidanceDispatcher.update(listOf(cue), state, 91.0, 91.0, true, true, true)
        assertThat(atNear.speechText).isEqualTo("まもなく左折です。")
        val atCorner = NaviGuidanceDispatcher.update(listOf(cue), atNear.state, 101.0, 101.0, true, true, true)
        assertThat(atCorner.speechText).isNull()
    }

    @Test fun openingAfterTriggerDoesNotAnnouncePastCue() {
        val result = NaviGuidanceDispatcher.update(listOf(cue), NaviGuidanceDispatcher.State(), 70.0, 70.0, true, true, true)
        assertThat(result.speechText).isNull()
        assertThat(result.bandText).isEqualTo("左折 30m")
    }
}
