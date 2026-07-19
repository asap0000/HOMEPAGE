package com.istech.buscourse.course

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HubCandidateInvariantTest {

    @Test
    fun `candidates preserve repeated visit and explicit hub invariants`() {
        val preview = listOf(
            preview(cardId = 1, displayName = "往復停留所"),
            preview(cardId = 2, displayName = "通常停留所"),
            preview(cardId = null, displayName = "カードなし"),
            preview(cardId = 1, displayName = "往復停留所"),
            preview(cardId = 3, displayName = "明示拠点", isHubCandidate = true),
        )
        val candidates = detectHubCandidates(preview)
        val previewCardIds = preview.mapNotNull { it.cardId }.toSet()
        val explicitHubIds = preview.filter { it.isHubCandidate }.mapNotNull { it.cardId }.toSet()

        assertThat(candidates).isNotEmpty()
        assertThat(candidates.map { it.cardId }).contains(1L)
        assertThat(previewCardIds).containsAtLeastElementsIn(candidates.map { it.cardId }.toSet())
        assertThat(candidates.map { it.cardId }.toSet()).containsAtLeastElementsIn(explicitHubIds)
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
