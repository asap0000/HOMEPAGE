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
    indices = [
        Index(value = ["bus_id", "course_no", "year"], unique = true, name = "index_course_identity"),
    ],
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
    @ColumnInfo(name = "bus_id") val busId: String? = null,
    @ColumnInfo(name = "course_no") val courseNo: Int? = null,
    /** 年度（4月始まり）。2026 = 2026-04-01〜2027-03-31。 */
    @ColumnInfo(name = "year") val year: Int? = null,
)

data class CourseIdentity(val busId: String, val courseNo: Int, val year: Int)

fun CourseEntity.identityOrNull(): CourseIdentity? {
    val identityBusId = busId ?: return null
    val identityCourseNo = courseNo ?: return null
    val identityYear = year ?: return null
    return CourseIdentity(identityBusId, identityCourseNo, identityYear)
}

/**
 * `course_stop`（コース内の停留所順列。設計書§3.5）。企画原則の「順列」部分そのもの。
 *
 * version 11（2026-07-15、「座標を持つ点」への転換の土台）: `course_stop` を「座標を持つ点」として
 * 扱えるよう再定義した。映像（ローレゾ点＝[frameId]、`timelapse_frame` 参照）と戸籍（カード＝
 * [stopCardId]、`bus_stop_card` 参照）はどちらも任意の肉付けで、少なくとも一方があればよい。
 * SQLiteはALTERでNOT NULL制約を外せないため、[stopCardId] のNULL許容化はテーブル再作成で行った
 * （[BusCourseDatabase.MIGRATION_10_11]）。既存データはすべて stop_card_id を保持・frame_id は NULL
 * ＝card-onlyの点として移行済み。
 *
 * version 12（2026-07-16、実機セッション#17が暴いた誤吸着の是正）: [eventId]
 * （`stop_visit_event` 参照）を新設した。カメラが動かなかったセッションでは [frameId] を持てる点が
 * 1つも作れないため、`stop_visit_event`（MANUAL）を一次素材にする必要があるが（設計ドラフトv2
 * §3パス1）、押下時の正しい座標は `stop_visit_event.lat/lon` 自身にしかない。従来は
 * `event.stop_card_id`（記録時の誤吸着先）をそのまま [stopCardId] に引き継いでいたため、実機
 * セッション#17では24件中21件が300m〜3.3kmの誤吸着になっていた。[eventId] の新設により、
 * 「イベント自身を指す点」を [stopCardId] を経由せず正確な座標のまま表現できる。
 *
 * **位置解決の順序（coalesce）**: 位置 = `coalesce(frame座標, event座標, card座標)`。
 * - frame座標が最優先: 実際に撮影した静止画に紐づく実測値そのもの（手動マーク操作が成功した証跡）。
 * - event座標が次点: `stop_visit_event.lat/lon` は押下瞬間の実測GPS fixで、frameが無い
 *   （カメラ故障等）場合でも正確な位置が残っている。
 * - card座標は最後の砦: `bus_stop_card` の座標は「記録時に（誤って）割り当てられたカードの位置」に
 *   過ぎず誤吸着の影響を直接受けるため、frame/event座標が無い場合に限り使う。
 * 実装は [com.istech.buscourse.course.CourseRepository] の `resolveStopPosition`（コース創設
 * パス1、[com.istech.buscourse.course.CourseRepository.generatePass1RawStops] が呼ぶ）を参照。
 *
 * **不変条件（コード層で担保。DBのCHECK制約は使わない）**: [frameId]・[eventId]・[stopCardId] の
 * 少なくとも一つは非nullでなければならない（RoomはCHECK制約と相性が悪いため）。この制約は
 * `course_stop` への書き込み経路である
 * [com.istech.buscourse.course.CourseRepository.setCourseStops]・
 * [com.istech.buscourse.course.CourseRepository.insertCourseStopsFromPreview] の
 * `requireCoordinateSource` で担保する（同メソッドのKDoc参照）。
 */
@Entity(
    tableName = "course_stop",
    indices = [
        Index(value = ["course_id", "sequence_index"], unique = true),
        Index(value = ["course_id"]),
        Index(value = ["frame_id"]),
        Index(value = ["event_id"]),
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
        ForeignKey(
            entity = StopVisitEventEntity::class, parentColumns = ["id"],
            childColumns = ["event_id"], onDelete = ForeignKey.RESTRICT
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
     * NULL許容。[stopCardId]・[eventId] とあわせて「少なくとも一つは非null」という不変条件がある
     * （本クラスのKDoc参照）。
     */
    @ColumnInfo(name = "frame_id") val frameId: Long? = null,
    /**
     * イベント（`stop_visit_event.id`）側の座標参照。NULL可（version 12で新設）。カメラが動かず
     * [frameId] を持てないセッションで、押下時の正しい座標（`stop_visit_event.lat/lon`）を経由する
     * ための参照。[frameId]・[stopCardId] とあわせて「少なくとも一つは非null」という不変条件がある
     * （本クラスのKDoc参照）。
     */
    @ColumnInfo(name = "event_id") val eventId: Long? = null,
    /** 0-based順序 */
    @ColumnInfo(name = "sequence_index") val sequenceIndex: Int,
    /** コース起点からの累積距離キャッシュ。RoutePreprocessor が route_point 生成時に算出（§3.9・§7.3） */
    @ColumnInfo(name = "expected_chainage_m") val expectedChainageM: Double?,

    // ------------------------------------------------------------------
    // version 19（2026-07-30。元は v18 として実装したが、v18 は `937e340`（旧データ救済・
    // navi_frame_index）の先着予約と判明し、官房裁定で v19 へリナンバー）: 結合で生まれた点を扱うための4列。
    //
    // 【なぜ要るのか】3ポインタ（frame/event/card）は**そのセッションに実体があること**を前提にする。
    // ところが 2026-07-30 に発覚した停留所マーカー欠落セッションのリカバリーでは、
    // **どのポインタも指せない点**を作る必要が出た——
    //   ①別セッションの停留所位置を移植した点（移植元のカードは別 DB・別端末にある）
    //   ②軌跡の滞留だけを根拠に推定した点（カードも映像コマも存在しない）
    // これらは「座標を自分で持つ点」でなければ表現できない。
    //
    // 【出自と誤差が必須である理由】istech 正典 `docs/2026-07-30_制度定義_時空の分離と再統合.md`
    // 条2「結合は必ず出自と誤差を持つ」。**復元・推定した値を実記録と同格に置かない**
    // ——同格に混ぜると後から区別できなくなる（不可逆）。下流が品質でフィルタできることが要件。
    // ------------------------------------------------------------------

    /**
     * 結合で生まれた点の座標（緯度）。**3ポインタのどれも指せない点だけが持つ**。
     * ポインタがある点では null（位置は従来どおり coalesce(frame, event, card) で解決する）。
     */
    @ColumnInfo(name = "resolved_latitude") val resolvedLatitude: Double? = null,

    /** 結合で生まれた点の座標（経度）。[resolvedLatitude] と同時に非null／同時にnull。 */
    @ColumnInfo(name = "resolved_longitude") val resolvedLongitude: Double? = null,

    /**
     * 出自（どうやってこの点を得たか）。**族は横断で固定**（正典 §4.1・2026-07-30 官房裁定）。
     * 値は [CourseStopProvenance] の name。既定 `RECORDED`＝実記録（従来の点はすべてこれ）。
     *
     * **「出自は最も弱い根拠に合わせる」**（正典 条2）——厳密に算出した部分があっても、
     * 土台が近似で作られているなら同じ札を継ぐ。強い部分だけ見て強い札を付けると下流がフィルタできない。
     */
    @ColumnInfo(name = "provenance", defaultValue = "'RECORDED'")
    val provenance: String = CourseStopProvenance.RECORDED.name,

    /**
     * 空間軸の推定誤差（±m）。移植なら「移植元の停留所と、貼り付けた軌跡上の点との距離」。
     *
     * **時間軸の誤差（±秒）とは別列で持つ**（正典 条2・2026-07-30 裁定。当チームの問いが採られた）。
     * 単位の違う値を同じ列に入れない——時間と空間が別軸なら誤差も別軸で持つのが正しい。
     * 当チームの移植・推定は空間で合わせるため、現時点で使うのはこの列だけ
     * （`error_time_s` は Windows 側の時間結合が請求元。必要になった時点で足す）。
     */
    @ColumnInfo(name = "error_space_m") val errorSpaceM: Double? = null,
)

/**
 * [CourseStopEntity.provenance] の値（**族は istech 正典 §4.1 で横断固定**・2026-07-30 官房裁定）。
 *
 * **綴りは各系統ローカルのままでよい**と明記されている（改名は移行コストを復旧のブロッカーに変えるため）。
 * 当チームが使うのは4値で、`JOINED_TEMPORAL`（時間結合＝Windows/EX の請求元）は当面使わない。
 */
enum class CourseStopProvenance {
    /** 実測。一次情報そのまま（結合していない）。**従来の点はすべてこれ**。 */
    RECORDED,

    /**
     * 空間一致。座標の近接で既知の停留所カードに一致させた（正典の概念値 `MATCHED_SPATIAL`）。
     * 綴りは鋳造時（当初 v18 として実装・v19 へリナンバー）のまま（正典 §4.1 が改名不要と明記）。
     */
    GEOFENCE_MATCHED,

    /**
     * 移植。**別セッションの記録を持ち込んだ**。
     * `JOINED_TEMPORAL`（時刻をずらして貼る）**では言い足りない**——**出所そのものが別セッション**。
     * 2026-07-30 のリカバリーでは 53 件すべてが別の走行日に由来し、**当日人が押したマーカーは1件も無い**。
     */
    TRANSPLANTED,

    /**
     * 合成。一致せずフォールバックで生成した＝**根拠が最も弱い**（正典の概念値 `SYNTHESIZED`）。
     * 軌跡の滞留だけを根拠にした点（カードも映像コマも無い）がこれに当たる。
     */
    FALLBACK_SYNTHESIZED,

    /** 人の補正。人が後から直した・足した（正典 条3）。 */
    HUMAN_CORRECTED,
}

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
