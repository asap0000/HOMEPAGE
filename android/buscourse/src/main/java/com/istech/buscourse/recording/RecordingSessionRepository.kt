package com.istech.buscourse.recording

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.StopVisitEventEntity
import com.istech.buscourse.core.data.ShockEventEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import com.istech.buscourse.core.geo.GeoMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** `timelapse_frame.kind` の許容値（設計書§3.5、D6）。 */
enum class FrameKind { LORES, HIRES }

/** `stop_visit_event.event_type` の許容値（設計書§3.5・§3.6）。StopDetectorはARRIVEDのみ発火させる（§4.8.2）。 */
enum class StopVisitEventType { APPROACHING, ARRIVED, PASSED, MISSED }

/** `stop_visit_event.trigger_type` の許容値（設計書§4.8.1）。ARRIVED時のみ意味を持つ。 */
enum class StopVisitTriggerType { AUTO, MANUAL }

/** `recording_session.status` の許容値（設計書§4.4）。 */
enum class RecordingSessionStatus { RECORDING, COMPLETED, DISCARDED, INTERRUPTED }

/**
 * 記録セッションの書き込み窓口（設計書§4.1・§3.3・§4.7、D3・D4）。
 *
 * §3正典スキーマ（`recording_session` / `timelapse_frame` / `gps_point` / `stop_visit_event` /
 * `shock_event`）へのRoom書き込みと、`sessions/{id}/` 配下（`frames/`・`gps_raw.jsonl`・`meta.json`）の
 * ファイルI/Oを一手に引き受ける。記録中は `gps_raw.jsonl` に逐次追記し、セッション終了時に
 * `gps_point` へ一括インポートする（D4）。
 *
 * スレッドモデル：すべての可変状態（現在セッション・各種seqカウンタ・JSONLライター）への操作は
 * 単一スレッドの [writeDispatcher] 上で直列化する。これにより、GPSコールバック（メインスレッド）と
 * `ImageAnalysis` の連写コールバック（`analysisExecutor`）という異なるスレッドから同時に
 * 呼ばれても競合しない。[newHiResFile] のみ、`ImageCapture.takePicture` 呼び出し前に同期的な
 * ファイルパス決定が必要なため例外的に非suspendとし、`@Volatile` な現在セッション参照と
 * `AtomicInteger` カウンタで安全性を担保する。
 */
class RecordingSessionRepository(
    private val context: Context,
    database: BusCourseDatabase,
) {
    private val recordingSessionDao = database.recordingSessionDao()
    private val timelapseFrameDao = database.timelapseFrameDao()
    private val gpsPointDao = database.gpsPointDao()
    private val stopVisitEventDao = database.stopVisitEventDao()
    private val shockEventDao = database.shockEventDao()

    private val writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "recording-session-writer") }
    private val writeDispatcher = writeExecutor.asCoroutineDispatcher()
    private val repositoryScope = CoroutineScope(SupervisorJob() + writeDispatcher)

    @Volatile private var session: RecordingSessionEntity? = null
    private var gpsRawWriter: BufferedWriter? = null

    private val gpsSeq = AtomicInteger(0)
    private val frameSeq = AtomicInteger(0)
    private val hiResStopSeq = AtomicInteger(0)
    private val hiResShockSeq = AtomicInteger(0)
    private val gpsAppendCount = AtomicInteger(0)

    /** 現在進行中セッションのID（未開始ならnull）。`BusRecordingService`の多重起動防止に使用。 */
    val activeSessionId: Long?
        get() = session?.id

    // ------------------------------------------------------------------
    // セッションのライフサイクル
    // ------------------------------------------------------------------

    /**
     * 新規セッションを開始する。`sessions/{id}/` ディレクトリ作成、`gps_raw.jsonl` オープン、
     * `meta.json` 初回スナップショット書き込みまでを行う（§3.3・§4.7）。
     */
    suspend fun startSession(
        courseId: Long?,
        type: RecordingSessionType,
        driverId: String?,
        vehicleId: String?,
        targetFromStopCardId: Long? = null,
        targetToStopCardId: Long? = null,
        baseFrameIntervalMs: Long = 1_000L,
    ): RecordingSessionEntity = withContext(writeDispatcher) {
        val now = System.currentTimeMillis()
        val draft = RecordingSessionEntity(
            courseId = courseId,
            type = type.name,
            targetFromStopCardId = targetFromStopCardId,
            targetToStopCardId = targetToStopCardId,
            vehicleId = vehicleId,
            driverId = driverId,
            deviceModel = Build.MODEL,
            startedAt = now,
            endedAt = null,
            gpsRawLogRelPath = "",
            frameDirRelPath = "",
            baseFrameIntervalMs = baseFrameIntervalMs,
            frameCount = 0,
            totalDistanceM = null,
            status = RecordingSessionStatus.RECORDING.name,
        )
        val id = recordingSessionDao.insert(draft)

        val gpsRawRelPath = "${BusCourseStorage.DIR_SESSIONS}/$id/${BusCourseStorage.FILE_SESSION_GPS_RAW}"
        val frameDirRelPath = "${BusCourseStorage.DIR_SESSIONS}/$id/${BusCourseStorage.DIR_SESSION_FRAMES}"
        val finalized = draft.copy(id = id, gpsRawLogRelPath = gpsRawRelPath, frameDirRelPath = frameDirRelPath)
        recordingSessionDao.update(finalized)

        framesDir(id).mkdirs()
        val gpsFile = BusCourseStorage.resolve(context, gpsRawRelPath)
        gpsRawWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(gpsFile, true), Charsets.UTF_8))

        session = finalized
        gpsSeq.set(0)
        frameSeq.set(0)
        hiResStopSeq.set(0)
        hiResShockSeq.set(0)
        gpsAppendCount.set(0)

        writeMetaSnapshotBlocking(finalized)
        finalized
    }

    /**
     * セッションを終了する。`gps_raw.jsonl` を閉じて `gps_point` へ一括インポートし（D4）、
     * 総走行距離を確定計算し、`recording_session.status` を更新して `meta.json` を最終スナップショットする。
     */
    suspend fun endSession(status: RecordingSessionStatus = RecordingSessionStatus.COMPLETED): RecordingSessionEntity? =
        withContext(writeDispatcher) {
            val current = session ?: return@withContext null

            gpsRawWriter?.flush()
            gpsRawWriter?.close()
            gpsRawWriter = null

            importGpsPointsFromJsonl(current.id)
            val distance = computeTotalDistanceM(current.id)

            val latest = recordingSessionDao.getById(current.id) ?: current
            val updated = latest.copy(
                endedAt = System.currentTimeMillis(),
                totalDistanceM = distance,
                status = status.name,
            )
            recordingSessionDao.update(updated)
            writeMetaSnapshotBlocking(updated)
            session = null
            updated
        }

    /** セッション途中経過の `meta.json` スナップショットを最新DB状態で再生成する（§3.3、異常終了リカバリ用）。 */
    suspend fun refreshMetaSnapshot() = withContext(writeDispatcher) {
        val current = session ?: return@withContext
        val fresh = recordingSessionDao.getById(current.id) ?: current
        writeMetaSnapshotBlocking(fresh)
    }

    // ------------------------------------------------------------------
    // GPS生ログ（gps_raw.jsonl）
    // ------------------------------------------------------------------

    /**
     * GPS更新1点を `gps_raw.jsonl` に追記する（§3.3・§4.7）。追記のみでクラッシュ耐性を確保するため、
     * 行ごとに `flush()` する。`ert` は `elapsedRealtimeNanos`（モノトニック時計）、`t` は壁時計。
     */
    fun appendGpsRaw(location: Location) {
        repositoryScope.launch {
            val current = session ?: return@launch
            val seq = gpsSeq.incrementAndGet()
            val json = JSONObject().apply {
                put("seq", seq)
                put("t", System.currentTimeMillis())
                put("ert", SystemClock.elapsedRealtimeNanos())
                put("lat", location.latitude)
                put("lon", location.longitude)
                put("alt", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
                put("acc", if (location.hasAccuracy()) location.accuracy.toDouble() else JSONObject.NULL)
                put("spd", if (location.hasSpeed()) location.speed.toDouble() else JSONObject.NULL)
                put("brg", if (location.hasBearing()) location.bearing.toDouble() else JSONObject.NULL)
            }
            try {
                gpsRawWriter?.apply {
                    write(json.toString())
                    write("\n")
                    flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "gps_raw.jsonl 追記に失敗しました", e)
            }

            // §3.3の「異常終了時のリカバリ用」meta.jsonスナップショットを一定間隔で更新する
            if (gpsAppendCount.incrementAndGet() % META_SNAPSHOT_EVERY_N_GPS_POINTS == 0) {
                val fresh = recordingSessionDao.getById(current.id) ?: current
                writeMetaSnapshotBlocking(fresh)
            }
        }
    }

    private suspend fun importGpsPointsFromJsonl(sessionId: Long) {
        val current = recordingSessionDao.getById(sessionId) ?: return
        val file = BusCourseStorage.resolve(context, current.gpsRawLogRelPath)
        if (!file.exists()) return

        val points = mutableListOf<GpsPointEntity>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val obj = JSONObject(line)
                    points.add(
                        GpsPointEntity(
                            sessionId = sessionId,
                            seq = obj.getInt("seq"),
                            tsEpochMs = obj.getLong("t"),
                            elapsedRealtimeNanos = obj.getLong("ert"),
                            lat = obj.getDouble("lat"),
                            lon = obj.getDouble("lon"),
                            altM = obj.optNullableDouble("alt"),
                            speedMps = obj.optNullableDouble("spd"),
                            bearingDeg = obj.optNullableDouble("brg"),
                            accuracyM = obj.optNullableDouble("acc"),
                        )
                    )
                } catch (e: JSONException) {
                    Log.w(TAG, "gps_raw.jsonl の1行を解析できず読み飛ばしました: $line", e)
                }
            }
        }
        if (points.isNotEmpty()) {
            gpsPointDao.insertAll(points)
        }
    }

    private suspend fun computeTotalDistanceM(sessionId: Long): Double? {
        val points = gpsPointDao.getBySession(sessionId)
        if (points.size < 2) return if (points.isEmpty()) null else 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += GeoMath.haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        return total
    }

    // ------------------------------------------------------------------
    // 連写フレーム（LORES）・単写（HIRES）
    // ------------------------------------------------------------------

    /**
     * 低fps連写フレーム1枚を `frames/` へ保存し `timelapse_frame`（kind=LORES）に登録する
     * （設計書§4.5.2、`CameraCaptureController.LoresFrameAnalyzer` から呼ばれる）。
     * 呼び出し元は `ImageAnalysis` の解析スレッド上の非suspendコンテキストのため、非suspend関数として
     * 提供し内部で [repositoryScope] へ書き込みを委譲する。
     */
    fun enqueueLoresFrame(jpeg: ByteArray, capturedAtMs: Long, location: Location?) {
        repositoryScope.launch {
            val current = session ?: return@launch
            val seq = frameSeq.incrementAndGet()
            val file = File(framesDir(current.id), "lores_%06d_%d.jpg".format(seq, capturedAtMs))
            try {
                file.writeBytes(jpeg)
                val (w, h) = decodeJpegDimensions(jpeg)
                val frame = TimelapseFrameEntity(
                    sessionId = current.id,
                    seq = seq,
                    kind = FrameKind.LORES.name,
                    fileRelPath = relPathOf(file),
                    capturedAt = capturedAtMs,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    width = w,
                    height = h,
                    sizeBytes = jpeg.size.toLong(),
                )
                timelapseFrameDao.insert(frame)
                recordingSessionDao.incrementFrameCount(current.id)
            } catch (e: IOException) {
                Log.e(TAG, "連写フレームの書き込みに失敗しました seq=$seq", e)
            }
        }
    }

    /**
     * 高解像度単写（停留所マーキング／衝撃イベント）用の保存先ファイルを決定する
     * （設計書§4.5.2、`CameraCaptureController.captureHiRes` から`ImageCapture.OutputFileOptions`
     * 構築のために同期的に呼ばれる）。ファイル名は §3.3 の例
     * （`hires_stop_0001_....jpg` / `hires_shock_0001_....jpg`）に従う。
     */
    fun newHiResFile(reason: HiResReason): File {
        val current = session ?: error("RecordingSessionRepository: セッションが開始されていません")
        val seq = when (reason) {
            HiResReason.SHOCK -> hiResShockSeq.incrementAndGet()
            HiResReason.STOP_AUTO, HiResReason.STOP_MANUAL -> hiResStopSeq.incrementAndGet()
        }
        val prefix = when (reason) {
            HiResReason.SHOCK -> "hires_shock"
            HiResReason.STOP_AUTO, HiResReason.STOP_MANUAL -> "hires_stop"
        }
        return File(framesDir(current.id), "%s_%04d_%d.jpg".format(prefix, seq, System.currentTimeMillis()))
    }

    /** `newHiResFile` で決めたファイルへの撮影完了後、`timelapse_frame`（kind=HIRES）へ登録する。 */
    suspend fun recordHiResFrame(file: File, capturedAtMs: Long, location: Location?): Long =
        withContext(writeDispatcher) {
            val current = session ?: error("RecordingSessionRepository: セッションが開始されていません")
            val seq = frameSeq.incrementAndGet()
            val (w, h) = decodeJpegDimensionsFromFile(file)
            val frame = TimelapseFrameEntity(
                sessionId = current.id,
                seq = seq,
                kind = FrameKind.HIRES.name,
                fileRelPath = relPathOf(file),
                capturedAt = capturedAtMs,
                latitude = location?.latitude,
                longitude = location?.longitude,
                width = w,
                height = h,
                sizeBytes = if (file.exists()) file.length() else null,
            )
            val id = timelapseFrameDao.insert(frame)
            recordingSessionDao.incrementFrameCount(current.id)
            id
        }

    /** 指定時刻に最も近い、既に記録済みのLORESフレームIDを探す（衝撃バーストの範囲特定、§4.9.1）。 */
    suspend fun findClosestLoresFrameId(before: Boolean, tsEpochMs: Long): Long? = withContext(writeDispatcher) {
        val current = session ?: return@withContext null
        val frame = if (before) {
            timelapseFrameDao.findClosestLoresAtOrBefore(current.id, tsEpochMs)
        } else {
            timelapseFrameDao.findClosestLoresAtOrAfter(current.id, tsEpochMs)
        }
        frame?.id
    }

    /**
     * 手動停留所マーク時、押下時刻に最も近いLORESフレームへ [stopCardId] をマーカーとして記録する
     * （運行記録③機能、2026-07-12）。HIRES撮影を伴わない停留所マーク方式のため、既に保存済みの
     * LORESフレームへの後追いUPDATEのみで完結する。
     */
    suspend fun markStopCardOnLoresFrame(frameId: Long, stopCardId: Long) = withContext(writeDispatcher) {
        timelapseFrameDao.markStopCardOnLoresFrame(frameId, stopCardId)
    }

    // ------------------------------------------------------------------
    // 停留所通過イベント・衝撃検知イベント
    // ------------------------------------------------------------------

    /** `stop_visit_event` を記録する（設計書§4.8）。 */
    suspend fun recordStopVisitEvent(
        stopCardId: Long,
        eventType: StopVisitEventType,
        triggerType: StopVisitTriggerType?,
        location: Location?,
        distanceAtEventM: Double?,
        positionErrorM: Double?,
        hiresFrameId: Long?,
    ): Long = withContext(writeDispatcher) {
        val current = session ?: error("RecordingSessionRepository: セッションが開始されていません")
        val event = StopVisitEventEntity(
            sessionId = current.id,
            stopCardId = stopCardId,
            eventType = eventType.name,
            triggerType = triggerType?.name,
            eventTs = System.currentTimeMillis(),
            lat = location?.latitude,
            lon = location?.longitude,
            distanceAtEventM = distanceAtEventM,
            positionErrorM = positionErrorM,
            hiresFrameId = hiresFrameId,
        )
        val id = stopVisitEventDao.insert(event)
        val fresh = recordingSessionDao.getById(current.id) ?: current
        writeMetaSnapshotBlocking(fresh)
        id
    }

    /** `shock_event` を記録する（設計書§4.9.1）。バースト終端フレームは未確定のままnullで挿入できる。 */
    suspend fun recordShockEvent(
        tsEpochMs: Long,
        magnitudeMps2: Double,
        location: Location?,
        hiresFrameId: Long?,
        burstStartFrameId: Long?,
        burstEndFrameId: Long?,
    ): Long = withContext(writeDispatcher) {
        val current = session ?: error("RecordingSessionRepository: セッションが開始されていません")
        val event = ShockEventEntity(
            sessionId = current.id,
            tsEpochMs = tsEpochMs,
            magnitudeMps2 = magnitudeMps2,
            lat = location?.latitude,
            lon = location?.longitude,
            hiresFrameId = hiresFrameId,
            burstStartFrameId = burstStartFrameId,
            burstEndFrameId = burstEndFrameId,
        )
        val id = shockEventDao.insert(event)
        val fresh = recordingSessionDao.getById(current.id) ?: current
        writeMetaSnapshotBlocking(fresh)
        id
    }

    /** 衝撃イベント発生+3秒後に確定するバースト終端フレームIDを後追いで更新する（§4.9.1）。 */
    suspend fun updateShockBurstEndFrame(shockEventId: Long, frameId: Long?) = withContext(writeDispatcher) {
        shockEventDao.updateBurstEndFrame(shockEventId, frameId)
    }

    // ------------------------------------------------------------------
    // ストレージローテーション（§4.10.3、StorageRotationWorkerから呼ばれる）
    // ------------------------------------------------------------------

    /** 保持日数を超えたセッションを削除する。個々の削除失敗（FK制約）はスキップして継続する。 */
    suspend fun deleteSessionsOlderThan(days: Int) {
        val cutoffMs = System.currentTimeMillis() - days * MILLIS_PER_DAY
        val old = recordingSessionDao.getStartedBefore(cutoffMs)
        for (s in old) {
            try {
                deleteSession(s.id)
            } catch (e: SQLiteConstraintException) {
                Log.w(TAG, "保持期間ローテーション: FK制約によりセッション削除をスキップ id=${s.id}", e)
            }
        }
    }

    /** [excludeIds] を除いた最も古いセッションを1件返す（空き容量ローテーションのループ用、§4.10.3）。 */
    suspend fun findOldestSession(excludeIds: Set<Long>): RecordingSessionEntity? =
        recordingSessionDao.findOldestExcluding(excludeIds.toList())

    /**
     * セッションを削除する。Room側は `recording_session` 行の削除に伴い、子テーブル
     * （`timelapse_frame` / `gps_point` / `stop_visit_event` / `shock_event`）がON DELETE
     * CASCADEで連動削除される。DB削除に成功した場合のみファイルも削除する。
     * `SQLiteConstraintException` は呼び出し元（`StorageRotationWorker`）でスキップ処理するため
     * ここでは握りつぶさずそのまま伝播させる。
     */
    suspend fun deleteSession(id: Long) {
        recordingSessionDao.deleteById(id)
        sessionDir(id).deleteRecursively()
    }

    // ------------------------------------------------------------------
    // ファイルパス・meta.jsonヘルパー
    // ------------------------------------------------------------------

    private fun sessionDir(sessionId: Long): File =
        File(BusCourseStorage.resolve(context, BusCourseStorage.DIR_SESSIONS), sessionId.toString())

    private fun framesDir(sessionId: Long): File =
        File(sessionDir(sessionId), BusCourseStorage.DIR_SESSION_FRAMES)

    private fun relPathOf(file: File): String =
        file.relativeTo(BusCourseStorage.root(context)).path.replace(File.separatorChar, '/')

    /**
     * `meta.json` を一時ファイル書き込み→リネームで擬似アトミックに更新する（§3.3、
     * 「Roomと二重持ち＝異常終了時のリカバリ用」）。呼び出しは全て [writeDispatcher] 上で直列化される
     * 前提のため、追加のロックは取らない。
     */
    private fun writeMetaSnapshotBlocking(row: RecordingSessionEntity) {
        val dir = sessionDir(row.id)
        dir.mkdirs()
        val json = JSONObject().apply {
            put("id", row.id)
            put("courseId", row.courseId ?: JSONObject.NULL)
            put("type", row.type)
            put("targetFromStopCardId", row.targetFromStopCardId ?: JSONObject.NULL)
            put("targetToStopCardId", row.targetToStopCardId ?: JSONObject.NULL)
            put("vehicleId", row.vehicleId ?: JSONObject.NULL)
            put("driverId", row.driverId ?: JSONObject.NULL)
            put("deviceModel", row.deviceModel ?: JSONObject.NULL)
            put("startedAt", row.startedAt)
            put("endedAt", row.endedAt ?: JSONObject.NULL)
            put("gpsRawLogRelPath", row.gpsRawLogRelPath)
            put("frameDirRelPath", row.frameDirRelPath)
            put("baseFrameIntervalMs", row.baseFrameIntervalMs)
            put("frameCount", row.frameCount)
            put("totalDistanceM", row.totalDistanceM ?: JSONObject.NULL)
            put("status", row.status)
        }
        val target = File(dir, BusCourseStorage.FILE_SESSION_META)
        val tmp = File(dir, "${BusCourseStorage.FILE_SESSION_META}.tmp")
        try {
            tmp.writeText(json.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(json.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.e(TAG, "meta.json の書き込みに失敗しました sessionId=${row.id}", e)
        }
    }

    private fun decodeJpegDimensions(bytes: ByteArray): Pair<Int?, Int?> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null to null
    }

    private fun decodeJpegDimensionsFromFile(file: File): Pair<Int?, Int?> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null to null
    }

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name)

    /**
     * このリポジトリが保持する単一スレッド [writeExecutor] を停止する（要レビュー修正）。
     * 呼び出し元（[BusRecordingService.onDestroy]、`StorageRotationWorker.doWork`）は
     * インスタンス生成のたびに必ずこれを呼ぶこと。呼ばなければ生成のたびにスレッドが1本ずつ
     * 蓄積し、長期連続稼働で`OutOfMemoryError: unable to create new native thread`に至る。
     */
    fun shutdown() {
        repositoryScope.cancel()
        writeExecutor.shutdown()
    }

    companion object {
        private const val TAG = "RecordingSessionRepo"
        private const val META_SNAPSHOT_EVERY_N_GPS_POINTS = 20
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
