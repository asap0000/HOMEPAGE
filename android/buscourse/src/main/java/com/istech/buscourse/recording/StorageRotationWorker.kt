package com.istech.buscourse.recording

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.archive.ArchiveReceipt
import com.istech.buscourse.archive.ArchiveDeletionPlan
import com.istech.buscourse.archive.ArchiveRunState
import com.istech.buscourse.archive.ArchiveStore
import com.istech.buscourse.core.data.WorkLogCategory
import com.istech.buscourse.core.data.WorkLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** PC 受領票に一致した走行だけを整理する。 */
class StorageRotationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as BusCourseApplication
        val repo = RecordingSessionRepository(applicationContext, app.database)
        try {
            val archive = ArchiveStore(applicationContext, app.database)
            val receipt = archive.receipt
            val protected = archive.protectedIds()
            val runs = app.database.recordingSessionDao().getAll().sortedBy { it.startedAt }
            val sizes = runs.associate { it.id to archive.size(it) }
            val frames = runs.associate { it.id to archive.frameCount(it.id) }
            val matched = runs.associate { it.id to ArchiveReceipt.matches(receipt, it, frames.getValue(it.id)) }
            var total = archive.nonProtectedSize(runs, protected)
            var deleted = 0
            var deletedBytes = 0L
            val deletedIds = mutableSetOf<Long>()
            val fkIds = mutableSetOf<Long>()
            val retention = RecordingConfigRepository(applicationContext).retentionDays
            val cutoff = if (retention > 0) System.currentTimeMillis() - retention.toLong() * 86400000L else Long.MIN_VALUE
            val states = runs.map { run ->
                ArchiveRunState(run.id, run.startedAt, sizes.getValue(run.id), run.status == "RECORDING",
                    run.id in protected, matched[run.id] != null, matched[run.id]?.startupTestDeletable == true)
            }
            for (state in ArchiveDeletionPlan.order(states, cutoff)) {
                val run = runs.first { it.id == state.id }
                if (!ArchiveDeletionPlan.eligible(state, total, cutoff)) continue
                try {
                    repo.deleteSession(run.id)
                    deleted++
                    deletedIds += run.id
                    val bytes = sizes.getValue(run.id)
                    deletedBytes += bytes
                    total -= bytes
                } catch (e: SQLiteConstraintException) {
                    fkIds += run.id
                    Log.w(TAG, "FK制約により走行を残しました id=${run.id}", e)
                }
            }
            // 受領済みの書き出しは元セッションの削除結果に関係なく再利用しない。
            for (run in runs) if (matched[run.id] != null) {
                File(archive.outDir, archive.runKey(run)).deleteRecursively()
            }
            val remaining = runs.size - deleted
            fun summary(predicate: (com.istech.buscourse.core.data.RecordingSessionEntity) -> Boolean): String {
                val selected = runs.filter { it.id !in deletedIds && predicate(it) }
                return "${selected.size}本/${selected.sumOf { sizes.getValue(it.id) }}B"
            }
            val recordingSummary = summary { it.status == "RECORDING" }
            val naviSummary = summary { it.status != "RECORDING" && it.id in protected }
            val unreceivedSummary = summary { it.status != "RECORDING" && it.id !in protected && matched[it.id] == null }
            val fkSummary = summary { it.id in fkIds }
            val quotaSummary = summary { it.status != "RECORDING" && it.id !in protected && matched[it.id] != null && it.id !in fkIds }
            app.database.workLogDao().insert(WorkLogEntity(
                tsEpochMs = System.currentTimeMillis(), category = WorkLogCategory.RECORDING.name,
                message = "保管庫受領票で整理: 削除${deleted}本/${deletedBytes}B・残り${remaining}本",
                detail = "撮影中 $recordingSummary、ナビ使用中 $naviSummary、未受領 $unreceivedSummary、FK保留 $fkSummary、枠内等 $quotaSummary。枠対象残り${total}B",
            ))
            Result.success()
        } finally { repo.shutdown() }
    }

    companion object {
        private const val TAG = "StorageRotationWorker"
        private const val UNIQUE_WORK_NAME = "storage_rotation"
        fun schedule(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<StorageRotationWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(computeDelayUntilHourMs(3), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
            WorkManager.getInstance(context).enqueueUniqueWork("storage_rotation_startup", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<StorageRotationWorker>().build())
        }
        fun computeDelayUntilHourMs(hour: Int): Long {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
