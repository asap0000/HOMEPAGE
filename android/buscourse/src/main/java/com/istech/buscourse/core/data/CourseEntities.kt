package com.istech.buscourse.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `course`（コース。設計書§3.5）。
 * 企画原則「コース＝停留所カードの順列＋区間軌跡」の順列側の親。
 */
@Entity(
    tableName = "course",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class, parentColumns = ["id"],
            childColumns = ["base_course_id"], onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String?,
    /** STANDARD（正規コース） / TEMPORARY（臨時編成コース） */
    val kind: String,
    /** TEMPORARY の派生元コース。「元のコースに戻す」UIのために保持 */
    @ColumnInfo(name = "base_course_id") val baseCourseId: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * コース確定（②「コース編成(抽出)」フェーズC-1、2026-07-14追加）の出所セッション。
     * [com.istech.buscourse.course.CourseRepository.confirmCourseRouteFromSession] で
     * route_point を生成した際のセッションIDを記録する。FK制約は付けない（既存の
     * `is_hub`・`stop_card_id` 列と同様、単純な ALTER TABLE ADD COLUMN に留める方針、§3.5）。
     */
    @ColumnInfo(name = "source_session_id") val sourceSessionId: Long? = null,
)

/**
 * `course_stop`（コース内の停留所順列。設計書§3.5）。企画原則の「順列」部分そのもの。
 *
 * version 11（2026-07-15、「座標を持つ点」への転換の土台）: `course_stop` を「座標を持つ点」として
 * 扱えるよう再定義した。映像（ローレゾ点＝[frameId]、`timelapse_frame` 参照）と戸籍（カード＝
 * [stopCardId]、`bus_stop_card` 参照）はどちらも任意の肉付けで、少なくとも一方があればよい。
 * 位置は「frame座標 → card座標」の順にcoalesceして解決する想定（この解決ロジック自体は
 * [com.istech.buscourse.course.CourseRepository] 側で今回未実装。データモデルの用意のみ）。
 * SQLiteはALTERでNOT NULL制約を外せないため、[stopCardId] のNULL許容化はテーブル再作成で行った
 * （[BusCourseDatabase.MIGRATION_10_11]）。既存データはすべて stop_card_id を保持・frame_id は NULL
 * ＝card-onlyの点として移行済み。
 *
 * **不変条件（コード層で担保。DBのCHECK制約は使わない）**: [frameId] と [stopCardId] の
 * 少なくとも一方は非nullでなければならない（RoomはCHECK制約と相性が悪いため）。この制約は
 * `course_stop` への書き込み経路である
 * [com.istech.buscourse.course.CourseRepository.setCourseStops] の
 * `requireCoordinateSource` で担保する（同メソッドのKDoc参照）。
 */
@Entity(
    tableName = "course_stop",
    indices = [
        Index(value = ["course_id", "sequence_index"], unique = true),
        Index(value = ["course_id"]),
        Index(value = ["frame_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class, parentColumns = ["id"],
            childColumns = ["course_id"], onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BusStopCardEntity::class, parentColumns = ["id"],
            childColumns = ["stop_card_id"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = TimelapseFrameEntity::class, parentColumns = ["id"],
            childColumns = ["frame_id"], onDelete = ForeignKey.RESTRICT
        ),
    ]
)
data class CourseStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "course_id") val courseId: Long,
    /**
     * 戸籍（カード）側の座標参照。version 11でNULL許容化（本クラスのKDoc「不変条件」参照）。
     * カラム名は `timelapse_frame.stop_card_id` と揃えるため `stop_card_id` を維持している
     * （`card_id` への改名はしない、2026-07-15オーナー確定）。
     */
    @ColumnInfo(name = "stop_card_id") val stopCardId: Long? = null,
    /**
     * 映像（ローレゾ点）側の座標参照。`timelapse_frame.id` を指す（version 11で新設）。
     * NULL許容。[stopCardId] とあわせて「少なくとも一方は非null」という不変条件がある
     * （本クラスのKDoc参照）。
     */
    @ColumnInfo(name = "frame_id") val frameId: Long? = null,
    /** 0-based順序 */
    @ColumnInfo(name = "sequence_index") val sequenceIndex: Int,
    /** コース起点からの累積距離キャッシュ。RoutePreprocessor が route_point 生成時に算出（§3.9・§7.3） */
    @ColumnInfo(name = "expected_chainage_m") val expectedChainageM: Double?,
)

/**
 * `course_segment`（コース内の区間＝順列の隣接ペアごとの軌跡割当。設計書§3.5）。
 */
@Entity(
    tableName = "course_segment",
    indices = [
        Index(value = ["course_id", "sequence_index"], unique = true),
        Index(value = ["course_id", "status"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class, parentColumns = ["id"],
            childColumns = ["course_id"], onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BusStopCardEntity::class, parentColumns = ["id"],
            childColumns = ["from_stop_card_id"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = BusStopCardEntity::class, parentColumns = ["id"],
            childColumns = ["to_stop_card_id"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = SegmentTrackEntity::class, parentColumns = ["id"],
            childColumns = ["segment_track_id"]
        ),
    ]
)
data class CourseSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "course_id") val courseId: Long,
    /** 区間番号（0 = stop[0]→stop[1]） */
    @ColumnInfo(name = "sequence_index") val sequenceIndex: Int,
    /** 冗長だがクエリ簡略化のため非正規化 */
    @ColumnInfo(name = "from_stop_card_id") val fromStopCardId: Long,
    @ColumnInfo(name = "to_stop_card_id") val toStopCardId: Long,
    /** 未走行区間は NULL */
    @ColumnInfo(name = "segment_track_id") val segmentTrackId: Long?,
    /** CONFIRMED（実測あり） / PENDING（未走行＝試走待ち） */
    val status: String,
)

/**
 * `route_point`（コースのchainage確定済みポリライン。設計書§3.5、D7で新設）。
 * course_segment の CONFIRMED 区間の GPX を連結して RoutePreprocessor が生成する。
 */
@Entity(
    tableName = "route_point",
    indices = [Index(value = ["course_id", "seq"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class, parentColumns = ["id"],
            childColumns = ["course_id"], onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "course_id") val courseId: Long,
    /** 0始まり、起点→終点 */
    val seq: Int,
    val lat: Double,
    val lon: Double,
    /** コース起点からの累積距離（前処理で事前計算しキャッシュ） */
    @ColumnInfo(name = "chainage_m") val chainageM: Double,
)
