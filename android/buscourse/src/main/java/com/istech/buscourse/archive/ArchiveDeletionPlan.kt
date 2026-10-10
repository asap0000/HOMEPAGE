package com.istech.buscourse.archive

import com.istech.buscourse.recording.RecordingConfigRepository

/** 受領済みかつ保護されていない走行だけを候補にする。 */
data class ArchiveRunState(
    val id: Long,
    val startedAt: Long,
    val bytes: Long,
    val recording: Boolean,
    val protectedByNavi: Boolean,
    val received: Boolean,
    val startupTestDeletable: Boolean,
)

object ArchiveDeletionPlan {
    private fun removable(run: ArchiveRunState) = !run.recording && !run.protectedByNavi && run.received

    /** 枠に関係なく消してよい（起動テストの印・保持日数切れ）。 */
    private fun unconditional(run: ArchiveRunState, retentionCutoff: Long) =
        run.startupTestDeletable || run.startedAt < retentionCutoff

    fun eligible(run: ArchiveRunState, total: Long, retentionCutoff: Long): Boolean =
        removable(run) && (unconditional(run, retentionCutoff) || total > RecordingConfigRepository.ARCHIVE_QUOTA_BYTES)

    /**
     * 調べる順。枠に関係なく消すもの（起動テスト・日数切れ）を先に、残りを古い順に。
     * 先に起動テストを消して枠に収まれば、古い本物の走行は消さずに済む。
     */
    fun order(runs: List<ArchiveRunState>, retentionCutoff: Long): List<ArchiveRunState> {
        val (first, rest) = runs.sortedBy { it.startedAt }.partition { unconditional(it, retentionCutoff) }
        return first + rest
    }

    fun select(runs: List<ArchiveRunState>, retentionCutoff: Long = Long.MIN_VALUE): List<Long> {
        var total = runs.filter { !it.recording && !it.protectedByNavi }.sumOf { it.bytes }
        val selected = ArrayList<Long>()
        for (run in order(runs, retentionCutoff)) {
            if (!eligible(run, total, retentionCutoff)) continue
            selected += run.id
            total -= run.bytes
        }
        return selected
    }
}
