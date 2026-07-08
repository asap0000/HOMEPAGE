package com.istech.buscourse.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `segment_track`（区間軌跡マスタ＝実測ポリラインの実体。設計書§3.5・§3.6）。
 * コースに従属させず、有向停留所ペア（from→to）に従属させる独立資産。
 * UNIQUE(from, to) により有向ペアにつき常に最新の「正」の軌跡を1本に保つ（再試走時はUPSERT）。
 * 逆方向（to→from）は自動流用しない（一方通行・右左折動線対策、設計書§3.5）。
 */
@Entity(
    tableName = "segment_track",
    indices = [Index(value = ["from_stop_card_id", "to_stop_card_id"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = BusStopCardEntity::class, parentColumns = ["id"],
            childColumns = ["from_stop_card_id"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = BusStopCardEntity::class, parentColumns = ["id"],
            childColumns = ["to_stop_card_id"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = RecordingSessionEntity::class, parentColumns = ["id"],
            childColumns = ["recorded_session_id"], onDelete = ForeignKey.SET_NULL
        ),
    ]
)
data class SegmentTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "from_stop_card_id") val fromStopCardId: Long,
    @ColumnInfo(name = "to_stop_card_id") val toStopCardId: Long,
    /** `segments/{from}_{to}.gpx` */
    @ColumnInfo(name = "track_file_rel_path") val trackFileRelPath: String,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    @ColumnInfo(name = "duration_sec") val durationSec: Long,
    @ColumnInfo(name = "point_count") val pointCount: Int,
    /** true＝実測でなく直線補間の仮データ */
    @ColumnInfo(name = "is_interpolated") val isInterpolated: Boolean = false,
    /** 供給元セッション（provenance） */
    @ColumnInfo(name = "recorded_session_id") val recordedSessionId: Long?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
)
