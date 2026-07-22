package com.istech.buscourse.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "navi_map",
    indices = [Index(value = ["bus_id", "course_no", "year"])],
)
data class NaviMapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "schema_version") val schemaVersion: String,
    val profile: String,
    @ColumnInfo(name = "bus_id") val busId: String,
    @ColumnInfo(name = "course_no") val courseNo: Int,
    val year: Int,
    val title: String,
    @ColumnInfo(name = "chainage_step_m", defaultValue = "6") val chainageStepM: Int = 6,
    @ColumnInfo(name = "display_orientation") val displayOrientation: String,
    @ColumnInfo(name = "display_pitch_deg") val displayPitchDeg: Double,
    @ColumnInfo(name = "media_mode") val mediaMode: String,
    @ColumnInfo(name = "media_count") val mediaCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "archived_at", defaultValue = "NULL") val archivedAt: Long? = null,
    /**
     * コース単位 app_settings（増分6契約・schema 1.1 の manifest.app_settings 相乗り値）を生 JSON で保持する。
     * `variables_json`/`payload_json` と同じく TEXT・パースは消費側の責務。`.isnavi` が 1.0（app_settings 欠落）なら
     * リーダーは既定 `"{}"` を入れる（App 自前既定へ degrade）。App Room 版は `.isnavi` schema とは別軸（正典 §9・増分6 Q3-2）。
     */
    @ColumnInfo(name = "app_settings_json", defaultValue = "'{}'") val appSettingsJson: String = "{}",
)

@Entity(
    tableName = "navi_branch",
    indices = [Index(value = ["navi_map_id"])],
    foreignKeys = [ForeignKey(
        entity = NaviMapEntity::class,
        parentColumns = ["id"],
        childColumns = ["navi_map_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class NaviBranchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "navi_map_id") val naviMapId: Long,
    @ColumnInfo(name = "parent_chainage_m") val parentChainageM: Double,
    val label: String,
)

@Entity(
    tableName = "navi_segment",
    indices = [Index(value = ["navi_map_id"]), Index(value = ["navi_map_id", "seq"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = NaviMapEntity::class,
        parentColumns = ["id"],
        childColumns = ["navi_map_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class NaviSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "navi_map_id") val naviMapId: Long,
    val seq: Int,
    val kind: String,
    @ColumnInfo(name = "gap_kind") val gapKind: String? = null,
    @ColumnInfo(name = "chainage_start_m") val chainageStartM: Double,
    @ColumnInfo(name = "chainage_end_m") val chainageEndM: Double,
    @ColumnInfo(name = "session_id") val sessionId: Long? = null,
    @ColumnInfo(name = "base_epoch_ms") val baseEpochMs: Long? = null,
    @ColumnInfo(name = "branch_id") val branchId: Long? = null,
    @ColumnInfo(name = "clip_in_m") val clipInM: Double? = null,
    @ColumnInfo(name = "clip_out_m") val clipOutM: Double? = null,
)

@Entity(
    tableName = "navi_track_point",
    indices = [Index(value = ["segment_id"]), Index(value = ["segment_id", "seq"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = NaviSegmentEntity::class,
        parentColumns = ["id"],
        childColumns = ["segment_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class NaviTrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "segment_id") val segmentId: Long,
    val seq: Int,
    @ColumnInfo(name = "chainage_m") val chainageM: Double,
    @ColumnInfo(name = "t_rel_s") val tRelS: Double,
    val lat: Double,
    val lon: Double,
)

@Entity(
    tableName = "navi_event",
    indices = [Index(value = ["navi_map_id"])],
    foreignKeys = [ForeignKey(
        entity = NaviMapEntity::class,
        parentColumns = ["id"],
        childColumns = ["navi_map_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class NaviEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "navi_map_id") val naviMapId: Long,
    @ColumnInfo(name = "template_id") val templateId: String,
    val category: String,
    @ColumnInfo(name = "anchor_type") val anchorType: String,
    val scope: String,
    val priority: String,
    @ColumnInfo(name = "chainage_start_m") val chainageStartM: Double? = null,
    @ColumnInfo(name = "chainage_end_m") val chainageEndM: Double? = null,
    @ColumnInfo(name = "stop_card_id") val stopCardId: Long? = null,
    @ColumnInfo(name = "branch_id") val branchId: Long? = null,
    val condition: String? = null,
    @ColumnInfo(name = "variables_json", defaultValue = "'{}'") val variablesJson: String = "{}",
    @ColumnInfo(name = "valid_from") val validFrom: Long? = null,
    @ColumnInfo(name = "valid_until") val validUntil: Long? = null,
    @ColumnInfo(name = "repeat_policy") val repeatPolicy: String? = null,
)

@Entity(
    tableName = "navi_event_output",
    indices = [Index(value = ["event_id"])],
    foreignKeys = [ForeignKey(
        entity = NaviEventEntity::class,
        parentColumns = ["id"],
        childColumns = ["event_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class NaviEventOutputEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "output_kind") val outputKind: String,
    @ColumnInfo(name = "payload_json", defaultValue = "'{}'") val payloadJson: String = "{}",
)
