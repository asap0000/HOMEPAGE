package com.istech.buscourse.course

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HubCandidateDetectionTest {

    @Test
    fun `detects repeated cards and explicit hubs in first appearance order`() {
        val candidates = detectHubCandidates(
            listOf(
                preview(cardId = 10, displayName = "A"),
                preview(cardId = 20, displayName = "B"),
                preview(cardId = null, displayName = "カードなし"),
                preview(cardId = 10, displayName = "A"),
                preview(cardId = 30, displayName = "C", isHubCandidate = true),
                preview(cardId = 10, displayName = "A"),
            ),
        )

        assertThat(candidates.map { it.cardId }).containsExactly(10L, 30L).inOrder()
        assertThat(candidates).doesNotContain(HubCandidate(20, "B", 1, false))
        assertThat(candidates.single { it.cardId == 10L })
            .isEqualTo(HubCandidate(10, "A", 3, false))
        assertThat(candidates.single { it.cardId == 30L })
            .isEqualTo(HubCandidate(30, "C", 1, true))
    }

    private fun preview(
        cardId: Long?,
        displayName: String,
        isHubCandidate: Boolean = false,
    ) = CourseCreationStopPreview(
        frameId = null,
        cardId = cardId,
        eventId = null,
        displayName = displayName,
        capturedAt = 0,
        latitude = 35.0,
        longitude = 139.0,
        isHubCandidate = isHubCandidate,
    )
}
