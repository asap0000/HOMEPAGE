package com.istech.buscourse.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `bus_stop_card`（停留所カード。設計書§3.5）。
 *
 * フェーズ2スコープの列のみで作成する（★consistencyレビュー反映）:
 * 案内・試走用の `approach_radius_m` / `arrival_radius_m` / `heading_tolerance_deg`（D5）は
 * ここには含めず、フェーズ4着手時に ALTER TABLE で追加する（設計書§3.5・§9）。
 */
@Entity(tableName = "bus_stop_card")
data class BusStopCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** `stopcards/{id}/`（中に photo_orig.jpg / photo_thumb.jpg を固定ファイル名で配置） */
    @ColumnInfo(name = "photo_dir_rel_path") val photoDirRelPath: String,
    /** WGS84 */
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "altitude_m") val altitudeM: Double?,
    /** 注意事項（横断歩道注意、徐行区間等）。案内モードの読み上げ元テキストとしても参照される（§6.3） */
    val notes: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 廃止停留所。物理削除しない（過去コース・過去軌跡のFK整合を保つため） */
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
)
