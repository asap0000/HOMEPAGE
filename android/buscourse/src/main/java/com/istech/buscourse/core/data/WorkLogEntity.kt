package com.istech.buscourse.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 作業進捗ログ（依頼３、2026-07-11）。
 *
 * 「何を操作したかわかるイベントログ」をアプリ内に永続化し、作業進捗ログ画面で時系列表示する。
 * 開発中はデバッグログ（エラーポップアップの発生も含む）として人の操作経過を確認する用途、
 * システム完成後は作業者への通知機能（未利用の運行記録がある・停留所カードがn枚追加された等）へ
 * 昇華させる余地を残す。解像度（記録粒度）はオーナー一任のため、主要な状態変更操作と
 * エラー発生のみを記録し、画面遷移などの閲覧系操作は記録しない。
 */
@Entity(
    tableName = "work_log",
    indices = [Index(value = ["ts_epoch_ms"])],
)
data class WorkLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ts_epoch_ms") val tsEpochMs: Long,
    /** 種別（[WorkLogCategory] の name）。表示時のアイコン・色分け用。 */
    val category: String,
    /** 1行サマリ（例：「停留所カード『候補003』を作成」）。 */
    val message: String,
    /** 補足（エラーのスタック要約・対象IDなど）。無ければ null。 */
    val detail: String? = null,
)

/** work_log.category の正典値。 */
enum class WorkLogCategory {
    /** 停留所カードの作成・編集・撮り直し・アーカイブ */
    STOP_CARD,

    /** コースの作成・編成確定・停留所追加/削除 */
    COURSE,

    /** 運行記録の開始・終了 */
    RECORDING,

    /** 区間抽出の実行（成功・スキップ・診断付き） */
    EXTRACTION,

    /** GPXエクスポート/インポート */
    GPX,

    /** 地図パッケージ（.iscmap）の取り込み・選択切替（フェーズ3、2026-07-12追加） */
    MAP,

    /** エラー発生（ポップアップ表示されたものを含む） */
    ERROR,
}
