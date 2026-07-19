package com.istech.buscourse.course

/**
 * コース創設プレビューから検出した拠点候補。
 *
 * [visitCount] は同じカードがプレビュー点列に現れた回数で、カード未吸着の点は数えない。
 */
data class HubCandidate(
    val cardId: Long,
    val displayName: String,
    val visitCount: Int,
    val isHub: Boolean,
)

/**
 * 設計ドラフトv2 §4 の拠点候補を、プレビューの走行順で検出する。
 *
 * 明示的に拠点化されたカード（§4.1）と、2回以上通過したカード（§4.2）の和集合を返す。
 * カード未吸着の点は拠点にできないため無視する。
 */
fun detectHubCandidates(stops: List<CourseCreationStopPreview>): List<HubCandidate> {
    val visitsByCardId = linkedMapOf<Long, MutableList<CourseCreationStopPreview>>()
    stops.forEach { stop ->
        stop.cardId?.let { cardId ->
            visitsByCardId.getOrPut(cardId) { mutableListOf() } += stop
        }
    }

    return visitsByCardId.mapNotNull { (cardId, visits) ->
        val isHub = visits.any { it.isHubCandidate }
        if (visits.size < 2 && !isHub) {
            null
        } else {
            HubCandidate(
                cardId = cardId,
                displayName = visits.first().displayName,
                visitCount = visits.size,
                isHub = isHub,
            )
        }
    }
}
