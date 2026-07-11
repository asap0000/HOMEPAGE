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
    /**
     * この停留所で乗車する人数（2026-07-10追加）。コース編成画面での累計乗車人数・定員警告
     * （中型マイクロバス定員39名、35名以上でイエローシグナル）の算出に用いる。
     * 混在停留所（複数園の園児が同じ停留所を使う場合）で臨時コース種別ごとに人数が変わるケースは
     * 現時点ではスキーマ上区別しない（必要になれば園ごとに別カードを作成する運用、または
     * 将来のスキーマ拡張で対応する）。
     */
    @ColumnInfo(name = "rider_count", defaultValue = "0") val riderCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 廃止停留所。物理削除しない（過去コース・過去軌跡のFK整合を保つため） */
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
    /**
     * クイック採取モード（P1-1、2026-07-10追加）で作成され、まだ名前・注意事項・乗車人数を
     * 「情報成熟」編集していないカードに立つフラグ。StopCardEditScreenでの保存時にfalseへ落ちる。
     */
    @ColumnInfo(name = "needs_maturation", defaultValue = "0") val needsMaturation: Boolean = false,
    /** 音声メモ（P2-2、2026-07-10追加）。`stopcards/{id}/voice_memo.m4a` 相対パス。未録音はnull。 */
    @ColumnInfo(name = "voice_memo_rel_path") val voiceMemoRelPath: String? = null,
    /**
     * 園区分（色選択、依頼１続き 2026-07-11追加）。任意項目。"#RRGGBB"形式の文字列、未設定はnull。
     * 色と園の対応はアプリ側では固定せず運用で決める（将来のPC側プランナー＝バスコースプランナーEXが
     * この色で園を識別する）。選択肢は[com.istech.buscourse.ui.GardenColorPalette]参照。
     */
    @ColumnInfo(name = "garden_color") val gardenColor: String? = null,
)
