package com.istech.buscourse.archive

import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 走行の指紋（SHA-256）を、充電中に裏で作り置きしておく（2026-10-29 委託指示書 M）。
 * 「保管庫へ書き出す」のとき、写真を読み直して指紋を作る代わりに [ArchivePrep] を使うため。
 *
 * - 制約は **充電中**（`setRequiresCharging(true)`）。充電が外れると WorkManager が止める（[isStopped]・コルーチンの取り消し）。
 * - **記録中の走行が1本でもあれば何もせず終わる**（記録の邪魔をしない。判定は [ArchiveStore.prepareAll]）。
 * - 作る対象は、PC に受け取られていない走行のうち作り置きの無いもの（古い順）。
 */
class ArchivePrepWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as BusCourseApplication
        try {
            val made = ArchiveStore(applicationContext, app.database).prepareAll { isStopped }
            Log.i(TAG, "指紋の作り置き: ${made}本")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 次の定期実行でやり直す（押したときに作るので、書き出しは止まらない）。
            Log.w(TAG, "指紋の作り置きに失敗しました", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "ArchivePrepWorker"
        const val PERIODIC_WORK_NAME = "archive_prep"
        const val ONCE_WORK_NAME = "archive_prep_once"

        private fun constraints(): Constraints = Constraints.Builder().setRequiresCharging(true).build()

        /** 定期（1時間ごと・充電中）に登録する。[com.istech.buscourse.recording.StorageRotationWorker.schedule] と同じ流儀。 */
        fun schedule(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<ArchivePrepWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
        }

        /** 走行の記録が終わったとき、同じ制約の1回きりを投げる（すでに待っていれば重ねない）。 */
        fun enqueueAfterRecording(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE_WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ArchivePrepWorker>().setConstraints(constraints()).build())
        }
    }
}
