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

    @Test fun bandKindFollowsBandCueAndIsNullOffCourse() {
        val plain = NaviGuidanceDispatcher.update(listOf(cue), NaviGuidanceDispatcher.State(), 70.0, 70.0, true, true, true)
        assertThat(plain.bandKind).isEqualTo(NaviGuidanceCues.Kind.LEFT)
        val pair = NaviGuidanceCues.Cue(
            100.0, NaviGuidanceCues.Kind.RIGHT, NaviGuidanceCues.Variant.V2, 40.0, 10.0, "x", "まもなく右折です。",
            groupText = "x", bandText = "右折、すぐ左折",
        )
        val merged = NaviGuidanceDispatcher.update(listOf(pair), NaviGuidanceDispatcher.State(), 70.0, 70.0, true, true, true)
        assertThat(merged.bandText).isEqualTo("右折、すぐ左折 30m")
        assertThat(merged.bandKind).isEqualTo(NaviGuidanceCues.Kind.RIGHT)
        val off = NaviGuidanceDispatcher.update(listOf(cue), NaviGuidanceDispatcher.State(), 0.0, 65.0, true, false, true)
        assertThat(off.bandKind).isNull()
        val none = NaviGuidanceDispatcher.update(listOf(cue), NaviGuidanceDispatcher.State(), 150.0, 150.0, true, true, true)
        assertThat(none.bandKind).isNull()
    }

    @Test fun persistedBandTextTakesPrecedenceOverKindLabel() {
        val cue = NaviGuidanceCues.Cue(100.0, NaviGuidanceCues.Kind.UNKNOWN, NaviGuidanceCues.Variant.V1,
            40.0, 10.0, null, "案内", bandText = "保存した案内帯")
        val result = NaviGuidanceDispatcher.update(listOf(cue), NaviGuidanceDispatcher.State(), 25.0, null,
            following = false, onCourse = true, voiceEnabled = false)
        assertThat(result.bandText).isEqualTo("保存した案内帯 80m")
    }

    @Test fun bandUsesNearestRoadDisplayPositionWhileSpeechUsesCuePosition() {
        val entry = cue.copy(chainageM = 95.0, kind = NaviGuidanceCues.Kind.RIGHT,
            displayOverrideM = 100.0, bandText = "右折、すぐ左折")
        val exit = cue.copy(chainageM = 110.0, displayOverrideM = 125.0, bandText = "左折")
        val before = NaviGuidanceDispatcher.update(listOf(exit, entry), NaviGuidanceDispatcher.State(),
            96.0, null, false, true, false)
        assertThat(before.bandText).isEqualTo("右折、すぐ左折 0m")
        val after = NaviGuidanceDispatcher.update(listOf(exit, entry), NaviGuidanceDispatcher.State(),
            101.0, null, false, true, false)
        assertThat(after.bandText).isEqualTo("左折 20m")
        assertThat(entry.nearAtM).isEqualTo(85.0)
    }
}
