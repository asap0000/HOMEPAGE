package com.istech.buscourse.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsRunJsonTest {
    @Test
    fun selectedRunsAreCombinedWithoutFilteringAndSessionIdsArePresent() {
        val file = IsRunFile(
            manifest = IsRunManifest(producedAtEpochMs = 10, runCount = 2),
            runs = listOf(
                run(37, "FULL_RUN", "COMPLETED"),
                run(38, "TEST_DRIVE", "INTERRUPTED"),
            ),
        )

        val json = IsRunJson.encode(file)

        assertThat(json).contains("\"run_count\":2")
        assertThat(json).contains("\"session_id\":37")
        assertThat(json).contains("\"session_id\":38")
        assertThat(json).contains("\"type\":\"TEST_DRIVE\"")
        assertThat(json).contains("\"status\":\"INTERRUPTED\"")
    }

    @Test
    fun emptyShapedStopsAndNullValuesRemainDistinctFromZero() {
        val json = IsRunJson.encode(IsRunFile(IsRunManifest(producedAtEpochMs = 10, runCount = 1), listOf(run(1, "PARTIAL_RUN", "DISCARDED"))))

        assertThat(json).contains("\"shaped_stops\":[]")
        assertThat(json).contains("\"ended_at\":null")
        assertThat(json).contains("\"accuracy_m\":null")
        assertThat(json).doesNotContain("\"ended_at\":0")
    }

    @Test
    fun existingExportedAtIsNotOverwritten() {
        assertThat(exportedAtAfterSuccess(existing = 123, now = 999)).isEqualTo(123)
        assertThat(exportedAtAfterSuccess(existing = null, now = 999)).isEqualTo(999)
    }

    @Test
    fun shapedStopNullableValuesRemainNull() {
        val source = run(1, "FULL_RUN", "COMPLETED").copy(
            shapedStops = listOf(IsRunShapedStop(12, 5, 0, null, null, "RECORDED", null, null, 101)),
        )

        val json = IsRunJson.encode(IsRunFile(IsRunManifest(producedAtEpochMs = 10, runCount = 1), listOf(source)))

        assertThat(json).contains("\"folded_press_count\":null")
        assertThat(json).contains("\"error_space_m\":null")
    }

    private fun run(id: Long, type: String, status: String) = IsRunRun(
        sessionId = id,
        type = type,
        status = status,
        startedAt = 1,
        endedAt = null,
        deviceModel = null,
        totalDistanceM = null,
        gpsPoints = listOf(IsRunGpsPoint(1, 35.0, 139.0, null)),
        stopVisitEvents = emptyList(),
        shapedStops = emptyList(),
    )
}
