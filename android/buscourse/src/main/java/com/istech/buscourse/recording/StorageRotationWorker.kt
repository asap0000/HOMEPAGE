package com.istech.buscourse.recording

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.istech.buscourse.BusCourseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 古いセッションのローテーション削除（設計書§4.1・§4.10.3）。`androidx.work.CoroutineWorker` として実装する。
 * `WorkManager`はローカルタスクスケジューラであり、ネットワーク通信を一切伴わないためオフライン方針に抵触しない。
 *
 * 設計判断：保持期間30日、または空き容量閾値超過のいずれか早い方でローテーションする。
 * `configRepository`（保持日数・空き容量閾値）は本格的な設定画面を持たないフェーズ1では
 * [RecordingConfigRepository] による `SharedPreferences` 簡易実装で代替する（要確認・簡略化箇所）。
 */
class StorageRotationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as BusCourseApplication
        val configRepository = RecordingConfigRepository(applicationContext)
        val sessionRepository = RecordingSessionRepository(applicationContext, app.database)

        try {
            val retentionDays = configRepository.retentionDays
            val minFreeBytes = configRepository.minFreeBytes
            sessionRepository.deleteSessionsOlderThan(retentionDays)

            // ★feasibilityレビュー反映（設計書1249行目、重大4）：test_run_comparisonからのFK参照は
            // ON DELETE SET NULLへ変更済み（§3.5）。だが未知のFK・将来追加テーブルに対する防御的
            // フォールバックとして、削除失敗時は例外を握りつぶさず「次の候補へスキップ」し、
            // 同一セッションの再取得による無限ループを防ぐ。
            val skipped = mutableSetOf<Long>()
            var guard = 0
            while (freeBytes() < minFreeBytes && guard++ < MAX_ROTATION_ITERATIONS) {
                val oldest = sessionRepository.findOldestSession(excludeIds = skipped) ?: break
                try {
                    sessionRepository.deleteSession(oldest.id)
                } catch (e: SQLiteConstraintException) {
                    Log.w(TAG, "storage rotation: FK制約によりセッション削除をスキップ id=${oldest.id}", e)
                    skipped += oldest.id
                }
            }
            if (guard >= MAX_ROTATION_ITERATIONS) {
                Log.e(TAG, "storage rotation: 上限反復回数に到達。空き容量閾値を満たせないまま終了した可能性あり")
            }
            Result.success()
        } finally {
            // writeExecutorのスレッドリーク防止（要レビュー修正）。doWork()のたびに新規生成される
            // RecordingSessionRepositoryのスレッドを確実に畳む。
            sessionRepository.shutdown()
        }
    }

    private fun freeBytes(): Long = applicationContext.filesDir.usableSpace

    companion object {
        private const val TAG = "StorageRotationWorker"
        private const val MAX_ROTATION_ITERATIONS = 10_000
        private const val UNIQUE_WORK_NAME = "storage_rotation"

        /**
         * `PeriodicWorkRequest` を登録する（`BusCourseApplication#onCreate` から呼ぶ、設計書§4.10.3）。
         *
         * ★feasibilityレビュー反映（設計書1285行目、軽微指摘9）：`setInitialDelay` は
         * 「深夜3時に実行する」ことを保証しない。`AlarmManager`ではないため、Doze中のバッチング等で
         * 実行時刻が数十分〜数時間ずれることがある。「深夜・充電中を狙う」はあくまで目安であり、
         * 保証された時刻ではない旨をここに明記する。
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StorageRotationWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(computeDelayUntilHourMs(hour = 3), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** 次に迎える現地時刻[hour]:00までのミリ秒を計算する（目安時刻。保証はされない、上記注記参照）。 */
        fun computeDelayUntilHourMs(hour: Int): Long {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
