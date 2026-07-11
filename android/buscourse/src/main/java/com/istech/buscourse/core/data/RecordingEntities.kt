package com.istech.buscourse.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `recording_session`（走行セッション。設計書§3.5・§3.6、D3で統合）。
 * type は FULL_RUN / PARTIAL_RUN / LIVE_GUIDANCE / TEST_DRIVE の4値。
 * 人間可読な走行識別子は主キーにせず、course.name＋started_at から表示時に組み立てる（D3）。
 */
@Entity(
    tableName = "recording_session",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class, parentColumns = ["id"],
            childColumns = ["course_id"], onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class RecordingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 紐付くコース（単発試走はNULL可） */
    @ColumnInfo(name = "course_id") val courseId: Long?,
    /** FULL_RUN | PARTIAL_RUN | LIVE_GUIDANCE | TEST_DRIVE */
    val type: String,
    /** PARTIAL_RUN 時に埋める対象区間 */
    @ColumnInfo(name = "target_from_stop_card_id") val targetFromStopCardId: Long?,
    @ColumnInfo(name = "target_to_stop_card_id") val targetToStopCardId: Long?,
    /** 車両・乗務員識別（任意運用） */
    @ColumnInfo(name = "vehicle_id") val vehicleId: String?,
    @ColumnInfo(name = "driver_id") val driverId: String?,
    /** 端末機種名（環境差分の記録用） */
    @ColumnInfo(name = "device_model") val deviceModel: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    /** `sessions/{id}/gps_raw.jsonl` */
    @ColumnInfo(name = "gps_raw_log_rel_path") val gpsRawLogRelPath: String,
    /** `sessions/{id}/frames/` */
    @ColumnInfo(name = "frame_dir_rel_path") val frameDirRelPath: String,
    /** 連写間隔の基準値（実際は速度連動で可変、§4.6） */
    @ColumnInfo(name = "base_frame_interval_ms") val baseFrameIntervalMs: Long,
    @ColumnInfo(name = "frame_count", defaultValue = "0") val frameCount: Int = 0,
    /** 完了後に確定計算 */
    @ColumnInfo(name = "total_distance_m") val totalDistanceM: Double?,
    /** RECORDING | COMPLETED | DISCARDED | INTERRUPTED */
    val status: String,
    /**
     * セッションメモ（2026-07-11追加）。「いつ・何の目的で走ったか」を後から自由記述する。
     * コース編成・区間抽出を後日まとめて別の担当者が行うことがあるため、区間抽出画面の
     * セッション一覧から編集する。
     */
    val memo: String? = null,
)

/**
 * `timelapse_frame`（連写フレーム・停留所/衝撃単写の統合メタデータ。設計書§3.5、D6で拡張）。
 * kind = LORES（低fps連写） / HIRES（停留所・衝撃イベント単写）。
 */
@Entity(
    tableName = "timelapse_frame",
    indices = [
        Index(value = ["session_id", "seq"], unique = true),
        Index(value = ["session_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class, parentColumns = ["id"],
            childColumns = ["session_id"], onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TimelapseFrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val seq: Int,
    /** LORES（低fps連写） / HIRES（停留所・衝撃イベント単写） */
    val kind: String,
    @ColumnInfo(name = "file_rel_path") val fileRelPath: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    /** 撮影時刻最近傍のGPS点を後処理で紐付け */
    val latitude: Double?,
    val longitude: Double?,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
)

/**
 * `gps_point`（GPS点列。設計書§3.5、D4で正典化）。
 * セッション終了時に gps_raw.jsonl から一括インポートされる、クエリ可能な正典GPS点列。
 * 時間差分の算出には壁時計でなく elapsed_realtime_nanos を必ず使う（§3.3）。
 */
@Entity(
    tableName = "gps_point",
    indices = [
        Index(value = ["session_id", "seq"], unique = true),
        Index(value = ["session_id", "ts_epoch_ms"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class, parentColumns = ["id"],
            childColumns = ["session_id"], onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GpsPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val seq: Int,
    @ColumnInfo(name = "ts_epoch_ms") val tsEpochMs: Long,
    @ColumnInfo(name = "elapsed_realtime_nanos") val elapsedRealtimeNanos: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "alt_m") val altM: Double?,
    @ColumnInfo(name = "speed_mps") val speedMps: Double?,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Double?,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double?,
    /** D1によりGPS固定（将来プロバイダ追加に備え列は残す） */
    @ColumnInfo(name = "provider", defaultValue = "'GPS'") val provider: String = "GPS",
)

/**
 * `stop_visit_event`（停留所通過イベント。設計書§3.5・§3.6で統合新設）。
 * 記録エンジンの撮影トリガー記録と案内モードの状態機械遷移ログを1テーブルに統合。
 */
@Entity(
    tableName = "stop_visit_event",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class, parentColumns = ["id"],
            childColumns = ["session_id"], onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BusStopCardEntity::class, parentColumns = ["id"],
            childColumns = ["stop_card_id"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = TimelapseFrameEntity::class, parentColumns = ["id"],
            childColumns = ["hires_frame_id"], onDelete = ForeignKey.SET_NULL
        ),
    ],
    indices = [Index("session_id"), Index("stop_card_id")]
)
data class StopVisitEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "stop_card_id") val stopCardId: Long,
    /** APPROACHING | ARRIVED | PASSED | MISSED */
    @ColumnInfo(name = "event_type") val eventType: String,
    /** AUTO | MANUAL（ARRIVED時のみ意味を持つ） */
    @ColumnInfo(name = "trigger_type") val triggerType: String?,
    @ColumnInfo(name = "event_ts") val eventTs: Long,
    /** イベント発生時fix */
    val lat: Double?,
    val lon: Double?,
    @ColumnInfo(name = "distance_at_event_m") val distanceAtEventM: Double?,
    /** ARRIVED時、停留所座標との誤差 */
    @ColumnInfo(name = "position_error_m") val positionErrorM: Double?,
    /** 停留所マーキング撮影の参照 */
    @ColumnInfo(name = "hires_frame_id") val hiresFrameId: Long?,
)

/**
 * `shock_event`（衝撃検知イベント。設計書§3.5）。記録エンジン調査の設計をそのまま採用。
 */
@Entity(
    tableName = "shock_event",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class, parentColumns = ["id"],
            childColumns = ["session_id"], onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TimelapseFrameEntity::class, parentColumns = ["id"],
            childColumns = ["hires_frame_id"], onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = TimelapseFrameEntity::class, parentColumns = ["id"],
            childColumns = ["burst_start_frame_id"], onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = TimelapseFrameEntity::class, parentColumns = ["id"],
            childColumns = ["burst_end_frame_id"], onDelete = ForeignKey.SET_NULL
        ),
    ],
    indices = [
        Index("session_id"),
        Index("hires_frame_id"),
        Index("burst_start_frame_id"),
        Index("burst_end_frame_id"),
    ]
)
data class ShockEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "ts_epoch_ms") val tsEpochMs: Long,
    @ColumnInfo(name = "magnitude_mps2") val magnitudeMps2: Double,
    val lat: Double?,
    val lon: Double?,
    @ColumnInfo(name = "hires_frame_id") val hiresFrameId: Long?,
    @ColumnInfo(name = "burst_start_frame_id") val burstStartFrameId: Long?,
    @ColumnInfo(name = "burst_end_frame_id") val burstEndFrameId: Long?,
)
