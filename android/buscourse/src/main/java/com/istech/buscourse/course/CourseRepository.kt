package com.istech.buscourse.course

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.BusStopCardEntity
import com.istech.buscourse.core.photo.ExifAwareBitmap
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.CourseSegmentEntity
import com.istech.buscourse.core.data.CourseStopEntity
import com.istech.buscourse.core.data.CourseWithDetails
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.RoutePointEntity
import com.istech.buscourse.core.data.SegmentTrackEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import com.istech.buscourse.core.data.requireCard
import com.istech.buscourse.core.data.requireStopCardId
import com.istech.buscourse.core.data.WorkLogCategory
import com.istech.buscourse.core.data.WorkLogEntity
import com.istech.buscourse.core.geo.GeoMath
import com.istech.buscourse.core.gpx.GpxCodec
import com.istech.buscourse.core.gpx.GpxCourseExport
import com.istech.buscourse.core.gpx.GpxCourseSegmentExport
import com.istech.buscourse.core.gpx.GpxParseException
import com.istech.buscourse.core.gpx.GpxPoint
import com.istech.buscourse.core.gpx.GpxWaypoint
import com.istech.buscourse.recording.FrameKind
import com.istech.buscourse.recording.RecordingSessionStatus
import com.istech.buscourse.recording.RecordingSessionType
import com.istech.buscourse.recording.StopMaster
import com.istech.buscourse.recording.StopVisitEventType
import com.istech.buscourse.recording.StopVisitTriggerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** `course.kind` の許容値（設計書§3.5）。Room側は素のString列（TypeConverterを増やさない、フェーズ0方針）。 */
enum class CourseKind { STANDARD, TEMPORARY }

/** `course_segment.status` の許容値（設計書§3.5）。 */
enum class CourseSegmentStatus { CONFIRMED, PENDING }

/** ジオフェンス半径内で一致しなかった停留所（気づきにくい抽出失敗の可視化、2026-07-11追加）。 */
data class UnmatchedStop(
    val name: String,
    val nearestDistanceM: Double,
)

/** §3.9 区間自動抽出の実行結果サマリ（UI表示用）。 */
data class SegmentExtractionResult(
    /** UPSERTした区間（有向ペア）の数。 */
    val extractedSegmentCount: Int,
    /** 再評価（regenerateCourseSegments）を行ったコースの数。 */
    val affectedCourseCount: Int,
    /** GPS点不足等でスキップした隣接ペアの数。 */
    val skippedPairCount: Int,
    /** GPS点不足等でスキップした隣接ペア（from停留所名, to停留所名）。 */
    val skippedPairs: List<Pair<String, String>> = emptyList(),
    /** 半径内マッチしなかった停留所（2026-07-11追加）。 */
    val unmatchedStops: List<UnmatchedStop> = emptyList(),
)

/**
 * セッション解析レポートのマーカー時系列1行（②「コース編成(抽出)」フェーズA-1、読み取り専用、
 * 2026-07-13追加）。[distanceM] は、そのマーカーコマの撮影位置と紐づく停留所カード座標との
 * Haversine距離。マーカーコマにGPS座標が無い、またはカードが見つからない場合はnull。
 */
data class MarkerTimelineRow(
    val frameId: Long,
    val capturedAt: Long,
    val stopCardId: Long,
    val stopName: String,
    val distanceM: Double?,
)

/**
 * ダブりグループ内の1コマ（フェーズA-1/B(a)）。[distanceM] はカード座標との距離（Haversine、
 * 取得できない場合はnull）。[CourseRepository.applyDuplicateMerges] にそのまま渡し、距離最小の
 * 1枚を代表として残す判定に使う。
 */
data class DuplicateFrameCandidate(
    val frameId: Long,
    val capturedAt: Long,
    val distanceM: Double?,
)

/**
 * ダブり検出（統合候補）の1グループ（フェーズA-1、読み取り専用）。seq順で同一stop_card_idが
 * 連続し、かつ captured_at 間隔が [CourseRepository.DUPLICATE_GROUP_INTERVAL_MS] 未満の
 * マーカーコマ列をまとめたもの。[frameCandidates] は[timestamps]と同じ並び順（2026-07-14、
 * フェーズB(a)承認キュー対応で追加）。
 */
data class MarkerDuplicateGroup(
    val stopCardId: Long,
    val stopName: String,
    val count: Int,
    val timestamps: List<Long>,
    val frameCandidates: List<DuplicateFrameCandidate>,
)

/**
 * find-or-create候補の近接既存カード（候補選択UI用、思いつき2、2026-07-14追加）。
 * [CourseRepository.findOrCreateRadiusFor] の半径以内にある別の既存カードを距離順に列挙したもの。
 */
data class NearbyCard(val cardId: Long, val name: String, val distanceM: Double)

/**
 * find-or-create候補の1件（②「コース編成(抽出)」フェーズB(c)、2026-07-14追加、読み取り専用。
 * [CourseRepository.analyzeFindOrCreateCandidates] の返り値）。マーカーコマの撮影位置と
 * 現在割り当てられている停留所カード座標との距離が [CourseRepository.FIND_OR_CREATE_RADIUS_M]
 * を超える＝誤吸着の疑いがある候補。[nearbyCards] が非空の場合、コマ位置の半径内に別の既存カードが
 * あるため自動作成の対象にせず、UIで「付替え／新規作成／選択しない」の候補選択を提示する
 * （思いつき2、2026-07-14）。
 */
data class FindOrCreateCandidate(
    val frameId: Long,
    val capturedAt: Long,
    val currentStopCardId: Long,
    val currentStopName: String,
    val distanceM: Double,
    val frameLatitude: Double,
    val frameLongitude: Double,
    /** そのコマの実ファイル相対パス（`BusCourseStorage.resolve` で解決）。新規カード作成の写真元。 */
    val fileRelPath: String,
    /** コマ位置の半径内にある別の既存カード（距離順、思いつき2で候補選択UIに使う）。 */
    val nearbyCards: List<NearbyCard> = emptyList(),
) {
    /** 後方互換: 最も近い既存カードの名前（非nullなら旧UIでは「候補選択が必要」扱い）。 */
    val nearbyExistingCardName: String? get() = nearbyCards.firstOrNull()?.name
}

/** 承認キュー適用結果の集計（フェーズB、[CourseRepository.applyApprovedCandidates] の返り値）。 */
data class ApplyApprovedResult(
    val duplicatesMerged: Int,
    val interruptionsApplied: Int,
    val findOrCreateApplied: Int,
    val hubFlagsApplied: Int,
) {
    val total: Int get() = duplicatesMerged + interruptionsApplied + findOrCreateApplied + hubFlagsApplied
}

/** セッション解析レポート全体（フェーズA-1、[CourseRepository.analyzeSessionMarkers] の返り値）。 */
data class SessionMarkerAnalysis(
    val sessionId: Long,
    val sessionType: String,
    val startedAt: Long,
    /** endedAtが無い（異常終了等）セッションではnull。 */
    val durationSec: Long?,
    val gpsPointCount: Int,
    val markerCount: Int,
    val distinctMarkedStopCount: Int,
    val timeline: List<MarkerTimelineRow>,
    val duplicates: List<MarkerDuplicateGroup>,
)

/**
 * 欠損/割り込みレポート（②「コース編成(抽出)」フェーズA-2、2026-07-13追加、読み取り専用）の
 * 停留所ごとの分類。[CourseRepository.analyzeCourseCoverage] のdwell判定結果。
 */
enum class StopCoverageClassification {
    /** 半径内のいずれかの通過（パス）でdwell条件を満たした＝停車確定。マーク漏れ＝割り込み候補。 */
    STOP_CONFIRMED,
    /** 半径内に点はあるがdwell条件を満たさない＝停車せず通過しただけ。マーク不要。 */
    PASS_THROUGH,
    /** カード座標の半径内にGPS点が一つも無い＝コース外/未走行。 */
    OUT_OF_COURSE,
}

/**
 * 欠損/割り込みレポートの1停留所分（フェーズA-2）。[dwellSec]・[minSpeedKmh] は判定根拠になった
 * パス（STOP_CONFIRMEDなら条件を満たした最長dwellのパス、PASS_THROUGHなら最長dwellのパス）の値。
 * OUT_OF_COURSEでは両方null。
 */
data class MissingStop(
    val stopId: Long,
    val name: String,
    val classification: StopCoverageClassification,
    val dwellSec: Double?,
    val minSpeedKmh: Double?,
)

/** 欠損/割り込みレポート全体（フェーズA-2、[CourseRepository.analyzeCourseCoverage] の返り値）。 */
data class CourseCoverageReport(
    val sessionId: Long,
    val courseId: Long,
    val missing: List<MissingStop>,
)

/**
 * 全体レポート（トップダウン創設フェーズ、course非依存の停車確認、
 * [CourseRepository.analyzeSessionCoverage] の返り値）。
 */
data class SessionCoverageReport(
    val sessionId: Long,
    /** OUT_OF_COURSE を除外した、軌跡コリドー内カード（STOP_CONFIRMED / PASS_THROUGH）。 */
    val candidates: List<MissingStop>,
)

/**
 * コース創設（トップダウン、3パス成熟モデルのパス1＋パス2、設計ドラフトv2 §2〜§3、2026-07-16改）の
 * 1点のプレビュー（[CourseRepository.previewCourseCreation] の要素、[CourseRepository.createCoursesFromSession]
 * の内部でも同じ形で組み立てて使う）。
 *
 * v1の「2軸マトリクスで評価して採否」（旧 `CourseStopSource.Existing`/`CourseStopSource.NewFromFrame`、
 * 廃止）から、「マーカーがあれば停留所は確実にできる」を実装する3パス成熟モデルへ全面転換した
 * （設計ドラフト§0・§1）。[frameId]・[cardId]・[eventId] は `course_stop` の同名列にそのまま対応し、
 * いずれも任意・少なくとも一つが非null（[requireCoordinateSource]）。
 *
 * - パス1（悉皆生成）: マーカー付きLORESフレームからは `frameId` のみの点（`cardId=null`,
 *   `eventId=null`）、対応するLORESが無い `stop_visit_event`（MANUAL）からは `eventId` のみの点
 *   （`frameId=null`, `cardId=null`）を作る（[CourseRepository.generatePass1RawStops] 参照）。
 *   **どちらも記録時の（誤）吸着先は引き継がない**：frame由来点が `timelapse_frame.stop_card_id` を
 *   無視するのと同じく、event由来点も `stop_visit_event.stop_card_id`（実機セッション#17では
 *   24件中21件が300m〜3.3kmの誤吸着）を無視する。カードの判定は一律パス2に委ねる
 *   （2026-07-16改：以前はevent由来点だけ誤吸着を引き継ぐ非対称な実装だった）。
 *   **ローレゾはこの時点でカード化しない**（v1の仮カード `S{n}-NNN` 作成は廃止。パス1が
 *   bus_stop_cardを一切作らないのが設計ドラフトv2の核心）。
 * - パス2（吸着・昇格）: `cardId` が未確定の点について、**その点の真の位置**（frame座標があれば
 *   それ、無ければevent座標。位置解決は[CourseStopEntity]のKDoc「位置解決の順序」参照）から
 *   軌跡コリドー内の既存カードを探して `cardId` を埋める（[CourseRepository.attachPass2Cards] 参照）。
 *   記録時の吸着結果に一切依存しないため誤吸着は自己修正される。見つからなければ `cardId` は
 *   null のまま（＝映像／イベントはあるがカードが無い点）。
 *
 * [displayName] はDBに保存しない、その場で導出した表示名（カードがあればカード名、
 * 無ければ `"S{sessionId}-{通番}"`。設計ドラフト§2.3）。
 */
data class CourseCreationStopPreview(
    val frameId: Long?,
    val cardId: Long?,
    /** `stop_visit_event.id` への参照（2026-07-16追加）。[CourseStopEntity.eventId]のKDoc参照。 */
    val eventId: Long?,
    val displayName: String,
    val capturedAt: Long,
    val latitude: Double,
    val longitude: Double,
    /** [cardId] のカードが拠点（[BusStopCardEntity.isHub]）かどうか。拠点分割UIの初期選択に使う。 */
    val isHubCandidate: Boolean,
)

/** [splitCourseCreationStops] が返す1断片（拠点マーク間の非拠点点列）。 */
data class CourseCreationFragment(
    val startAt: Long,
    val endAt: Long,
    val stops: List<CourseCreationStopPreview>,
)

/**
 * 創設結果（[CourseRepository.createCoursesFromSession] の返り値）。createdCourseIds は拠点分割後の
 * 断片と同じ並び。パス1はbus_stop_cardを一切作らない設計（v2の核心、クラスKDoc参照）のため、
 * 旧 `newCardCount`（新規カード数）は意味を失い廃止した。代わりに生成した点の内訳を返す
 * （UI結果表示・work_log記録用）。
 */
data class CourseCreationResult(
    val createdCourseIds: List<Long>,
    /** 生成した停留所点の総数（断片横断の合計。拠点分割の境界点自体はどの断片にも属さないため含まない）。 */
    val totalStopCount: Int,
    /**
     * うちカードが付いている点数（パス2で軌跡コリドー内のカードを吸着できた点。2026-07-16改：
     * MANUALイベント由来の点はパス1では `cardId=null` で起こされ、記録時の誤吸着先を引き継がない
     * ため、この件数に入るのはすべてパス2での吸着結果）。
     */
    val cardAttachedStopCount: Int,
    /**
     * うちカードが付かなかった（`cardId` が null のまま）点数の名称は歴史的に「frameOnly」だが、
     * 実際にはframe由来点・event由来点の両方がここに入りうる（変数名は現状維持。フィールド名変更は
     * UI側の参照更新を伴うため本タスクのスコープ外）。
     */
    val frameOnlyStopCount: Int,
)

/**
 * [stops] を拠点（[hubStopCardIds]、`cardId` がこの集合に含まれる点）で断片化する（設計ドラフトv2
 * §4「拠点判定と分割」）。[com.istech.buscourse.ui.splitByHubs]（旧「コース編成(抽出)」フェーズA-2、
 * 2026-07-13追加）と**同じアルゴリズム**（連続する拠点点を1つの境界イベントにまとめ、境界間の
 * 非拠点点列を1断片とする。境界となった拠点点自体はどの断片にも含めない＝従来どおりの挙動を踏襲）
 * だが、型として直接は流用できないため独自実装している：`splitByHubs` が受け取る
 * `MarkerTimelineRow` は `frameId`/`stopCardId` がどちらも非null前提（v1のカード起点モデル）のため、
 * パス1が生む「映像のみでカード未吸着の点」（`cardId=null`）を表現できない。[hubStopCardIds] は
 * 実在するカードIDの集合のため、`cardId=null` の点は判定上「拠点ではない」に自然に落ちる
 * （拠点フラグ自体がカードに立つものであり、カードの無い点が拠点になり得ないのは設計上当然）。
 *
 * [hubStopCardIds] が空なら拠点分割を行わず [stops] 全体を1断片として返す（拠点を選ばなくても
 * セッション全体から1コースだけ創れるようにするフォールバック、設計ドラフト§4.2「拠点なしで創設」）。
 * UI（[com.istech.buscourse.ui.CourseCreateScreen]、拠点チップのトグルのたびに呼ぶ）と
 * リポジトリ（[CourseRepository.createCoursesFromSession]、実際の創設直前に呼ぶ）の両方から
 * 同じ関数を呼ぶことで、プレビューと実際の創設が必ず同じ分割結果になるようにしている
 * （internal＝モジュール内公開、[com.istech.buscourse.ui] からもインポートして呼べる）。
 */
internal fun splitCourseCreationStops(
    stops: List<CourseCreationStopPreview>,
    hubStopCardIds: Set<Long>,
): List<CourseCreationFragment> {
    if (stops.isEmpty()) return emptyList()
    if (hubStopCardIds.isEmpty()) {
        return listOf(CourseCreationFragment(stops.first().capturedAt, stops.last().capturedAt, stops))
    }

    val fragments = mutableListOf<CourseCreationFragment>()
    var current = mutableListOf<CourseCreationStopPreview>()
    for (stop in stops) {
        val isHub = stop.cardId != null && stop.cardId in hubStopCardIds
        if (isHub) {
            if (current.isNotEmpty()) {
                fragments += CourseCreationFragment(current.first().capturedAt, current.last().capturedAt, current.toList())
                current = mutableListOf()
            }
        } else {
            current += stop
        }
    }
    if (current.isNotEmpty()) {
        fragments += CourseCreationFragment(current.first().capturedAt, current.last().capturedAt, current.toList())
    }
    return fragments
}

/**
 * 停車推定の示唆1件（トップダウン創設 S3・パス3、[CourseRepository.analyzeStopEstimates] の要素、
 * 設計ドラフトv2 §3「パス3」・§10.1、2026-07-18追加）。
 *
 * パス3は「マーカーが無くても車速から停車と判断できる点」を検出するが、1セッションだけでは
 * 停留所・信号待ち・（園児欠席等で）減速しただけの通過を区別できない（実機セッション#8では
 * 速度<1.5m/s・15秒以上の低速クラスタが23個検出され、最長271秒＝実際の拠点、短いものは信号の
 * 可能性がある）。そのため**この型はcourse_stopへ書き込む値ではなく、UIに提示する「示唆」に
 * とどまる**。採否（格上げ）は人が行う（UIは後続S4、要確認バー・速度ヒート地図）。
 */
data class StopEstimate(
    /** クラスタ内GPS点の緯度の単純平均（重心）。低速でほぼ静止している前提のため中央値との差は実用上無視できる。 */
    val latitude: Double,
    /** クラスタ内GPS点の経度の単純平均（重心）。 */
    val longitude: Double,
    /** クラスタの滞在秒数（([endAt] − [startAt]) / 1000）。 */
    val dwellSec: Double,
    /** クラスタ先頭GPS点の `ts_epoch_ms`。UIが軌跡上の正しい時系列位置に割り込み表示するために持つ。 */
    val startAt: Long,
    /** クラスタ末尾GPS点の `ts_epoch_ms`。 */
    val endAt: Long,
)

/**
 * コース管理機能の窓口（設計書§2.1 course パッケージ、フェーズ2）。
 *
 * - 停留所カードCRUD（写真の `stopcards/{id}/photo_orig.jpg` 保存＋長辺320px/JPEG q80 の
 *   `photo_thumb.jpg` 自動生成、§3.3。廃止は is_archived による論理削除のみ）
 * - コース編成（順列書き換え確定時の `regenerateCourseSegments`、§3.8）
 * - 試走ログからの区間自動抽出（§3.9）
 * - GPXエクスポート/インポート（§3.11.3 の `exportCourse` / `importAsSegmentTrack`。
 *   ストリームへの読み書き自体は `core.gpx.GpxCodec` に委譲し、本クラスがファイル解決とDB反映を担う）
 */
class CourseRepository(
    private val context: Context,
    private val database: BusCourseDatabase,
) {
    private val busStopCardDao = database.busStopCardDao()
    private val courseDao = database.courseDao()
    private val courseStopDao = database.courseStopDao()
    private val courseSegmentDao = database.courseSegmentDao()
    private val segmentTrackDao = database.segmentTrackDao()
    private val recordingSessionDao = database.recordingSessionDao()
    private val gpsPointDao = database.gpsPointDao()
    private val stopVisitEventDao = database.stopVisitEventDao()
    private val timelapseFrameDao = database.timelapseFrameDao()
    private val routePointDao = database.routePointDao()
    private val workLogDao = database.workLogDao()

    /** route_point / expected_chainage_m の再生成主体（§3.5・§3.9。2026-07-08決定で course 所属）。 */
    val routePreprocessor = RoutePreprocessor(context, database)

    // ------------------------------------------------------------------
    // 作業進捗ログ（依頼３ 2026-07-11。work_log）
    // ------------------------------------------------------------------

    /**
     * 作業進捗ログを1件記録する。ログ書き込み自体の失敗で本体操作を巻き添えにしないため、
     * 例外は握りつぶす（記録は本体操作の成否確定後に呼ぶこと）。
     */
    suspend fun logWork(category: WorkLogCategory, message: String, detail: String? = null) {
        runCatching {
            workLogDao.insert(
                WorkLogEntity(
                    tsEpochMs = System.currentTimeMillis(),
                    category = category.name,
                    message = message,
                    detail = detail,
                )
            )
            workLogDao.pruneOld()
        }
    }

    suspend fun getRecentWorkLogs(limit: Int = 500): List<WorkLogEntity> = workLogDao.getRecent(limit)

    // ------------------------------------------------------------------
    // 停留所カードCRUD（§3.5 bus_stop_card）
    // ------------------------------------------------------------------

    suspend fun getActiveStopCards(): List<BusStopCardEntity> = busStopCardDao.getAllActive()

    suspend fun getStopCard(id: Long): BusStopCardEntity? = busStopCardDao.getById(id)

    /**
     * 停留所カードを新規作成する。ID採番後に `photo_dir_rel_path = stopcards/{id}/` を確定し、
     * [photoTempFile]（撮影済み一時ファイル）があれば `photo_orig.jpg` へ移動して
     * サムネイル（長辺320px・JPEG q80、§3.3）を自動生成する。
     */
    suspend fun createStopCard(
        name: String,
        latitude: Double,
        longitude: Double,
        altitudeM: Double?,
        notes: String?,
        riderCount: Int,
        photoTempFile: File?,
        voiceMemoTempFile: File? = null,
        needsMaturation: Boolean = false,
        gardenColor: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // DB確定（voiceMemoRelPathの非null/null）と実ファイル移動を同じ判定基準にする
        // （2026-07-11レビュー指摘の修正: 別々の条件だと、確定直前にtempFileが消えるケースで
        // DBは「録音済み」を示すのに実ファイルが存在しない不整合が生まれうる）
        val voiceMemoAvailable = voiceMemoTempFile?.exists() == true
        val draft = BusStopCardEntity(
            name = name,
            photoDirRelPath = "",
            latitude = latitude,
            longitude = longitude,
            altitudeM = altitudeM,
            notes = notes?.takeIf { it.isNotBlank() },
            riderCount = riderCount,
            needsMaturation = needsMaturation,
            gardenColor = gardenColor,
            createdAt = now,
            updatedAt = now,
        )
        // IDなし作成→photoDirRelPath確定（+ voiceMemoTempFileがあればvoiceMemoRelPathも同時確定）の
        // 2回のupsertを1トランザクションに直列化し、途中キャンセル時に photoDirRelPath="" の
        // 孤児レコードが残らないようにする（フェーズ2レビュー#8）
        val id = database.withTransaction {
            val newId = busStopCardDao.upsert(draft)
            val dirRelPath = "${BusCourseStorage.DIR_STOPCARDS}/$newId/"
            busStopCardDao.upsert(
                draft.copy(
                    id = newId,
                    photoDirRelPath = dirRelPath,
                    voiceMemoRelPath = if (voiceMemoAvailable) "$dirRelPath${BusCourseStorage.FILE_STOPCARD_VOICE_MEMO}" else null,
                )
            )
            newId
        }
        val dirRelPath = "${BusCourseStorage.DIR_STOPCARDS}/$id/"

        val dir = BusCourseStorage.resolve(context, dirRelPath)
        dir.mkdirs()
        if (photoTempFile != null && photoTempFile.exists()) {
            attachPhoto(dir, photoTempFile)
        }
        if (voiceMemoAvailable) {
            moveVoiceMemoFile(dir, voiceMemoTempFile!!)
        }
        id
    }

    /** 名前・notes・座標の手動修正（編集画面、§9 フェーズ2スコープ）。 */
    suspend fun updateStopCard(
        id: Long,
        name: String,
        latitude: Double,
        longitude: Double,
        altitudeM: Double?,
        notes: String?,
        riderCount: Int,
        gardenColor: String? = null,
    ) {
        val current = busStopCardDao.getById(id) ?: return
        busStopCardDao.upsert(
            current.copy(
                name = name,
                latitude = latitude,
                longitude = longitude,
                altitudeM = altitudeM,
                notes = notes?.takeIf { it.isNotBlank() },
                riderCount = riderCount,
                needsMaturation = false,
                gardenColor = gardenColor,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** アーカイブ（論理削除。物理削除しない＝過去コース・過去軌跡のFK整合を保つ、§3.5）。 */
    suspend fun archiveStopCard(id: Long) {
        busStopCardDao.archive(id, System.currentTimeMillis())
    }

    /**
     * 写真・座標だけをID維持で上書きする（P2-1、2026-07-11追加）。別日に同じ停留所を撮り直した際、
     * 新規作成→旧カードアーカイブだとコース編成のFK参照が旧カードに残ってしまうため、
     * name/notes/riderCount/needsMaturationは変更せずphoto_orig.jpg/photo_thumb.jpgと座標だけ差し替える。
     */
    suspend fun retakePhotoAndLocation(
        cardId: Long,
        latitude: Double,
        longitude: Double,
        altitudeM: Double?,
        photoTempFile: File,
    ): Unit = withContext(Dispatchers.IO) {
        val current = busStopCardDao.getById(cardId) ?: return@withContext
        val dir = BusCourseStorage.resolve(context, current.photoDirRelPath)
        // DB確定を先に行う。ファイル上書きを先に行うと、その後のDB書き込みが失敗した場合に
        // 位置情報と写真が食い違ったまま復旧不能になる（レビュー指摘の修正）
        busStopCardDao.upsert(
            current.copy(latitude = latitude, longitude = longitude, altitudeM = altitudeM, updatedAt = System.currentTimeMillis())
        )
        attachPhoto(dir, photoTempFile)
    }

    /** カードのサムネイルファイル（未撮影なら存在しない）。UI一覧表示用。 */
    fun stopCardThumbFile(card: BusStopCardEntity): File =
        File(BusCourseStorage.resolve(context, card.photoDirRelPath), BusCourseStorage.FILE_STOPCARD_PHOTO_THUMB)

    /** カードのオリジナル写真ファイル（未撮影なら存在しない）。 */
    fun stopCardPhotoFile(card: BusStopCardEntity): File =
        File(BusCourseStorage.resolve(context, card.photoDirRelPath), BusCourseStorage.FILE_STOPCARD_PHOTO_ORIG)

    /** 撮影一時ファイルの置き場（アプリ専用領域内。保存確定時に photo_orig.jpg へ移動する）。 */
    fun newCaptureTempFile(): File =
        File(context.cacheDir, "stopcard_capture_${System.currentTimeMillis()}.jpg")

    /** カードの音声メモファイル（未録音なら存在しない、P2-2）。 */
    fun stopCardVoiceMemoFile(card: BusStopCardEntity): File =
        File(BusCourseStorage.resolve(context, card.photoDirRelPath), BusCourseStorage.FILE_STOPCARD_VOICE_MEMO)

    /** 録音一時ファイルの置き場（アプリ専用領域内。保存確定時に voice_memo.m4a へ移動する、P2-2）。 */
    fun newVoiceMemoTempFile(): File =
        File(context.cacheDir, "stopcard_voice_${System.currentTimeMillis()}.m4a")

    /** 録音済み一時ファイルをカードの音声メモとして確定する（P2-2）。 */
    suspend fun attachVoiceMemo(cardId: Long, recordedTempFile: File): Unit = withContext(Dispatchers.IO) {
        val current = busStopCardDao.getById(cardId) ?: return@withContext
        val dir = BusCourseStorage.resolve(context, current.photoDirRelPath)
        dir.mkdirs()
        // DB確定を先に行う（retakePhotoAndLocationと同じ理由。ファイル上書きが先だとDB書き込み失敗時に
        // 旧音声メモが既に消えているのにDBが更新されない中途半端な状態になる）
        busStopCardDao.upsert(
            current.copy(voiceMemoRelPath = "${current.photoDirRelPath}${BusCourseStorage.FILE_STOPCARD_VOICE_MEMO}", updatedAt = System.currentTimeMillis())
        )
        moveVoiceMemoFile(dir, recordedTempFile)
    }

    /** 録音一時ファイルを `voice_memo.m4a` へ移動する（[createStopCard]・[attachVoiceMemo]共用）。 */
    private fun moveVoiceMemoFile(cardDir: File, tempFile: File) {
        val target = File(cardDir, BusCourseStorage.FILE_STOPCARD_VOICE_MEMO)
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
    }

    private fun attachPhoto(cardDir: File, photoTempFile: File) {
        val orig = File(cardDir, BusCourseStorage.FILE_STOPCARD_PHOTO_ORIG)
        if (!photoTempFile.renameTo(orig)) {
            photoTempFile.copyTo(orig, overwrite = true)
            photoTempFile.delete()
        }
        try {
            generateThumbnail(orig, File(cardDir, BusCourseStorage.FILE_STOPCARD_PHOTO_THUMB))
        } catch (e: IOException) {
            Log.e(TAG, "サムネイル生成に失敗しました: ${orig.path}", e)
        }
    }

    /**
     * 長辺 [THUMB_LONG_EDGE_PX]px・JPEG q[THUMB_JPEG_QUALITY] のサムネイルを生成する（§3.3）。
     * EXIF回転タグをピクセルに焼き込んでから縮小する（2026-07-10実車テストで発覚した
     * 「サムネイルが90度回転する」不具合の修正、[ExifAwareBitmap]参照）。
     */
    private fun generateThumbnail(orig: File, thumb: File) {
        val bitmap = ExifAwareBitmap.decode(orig.absolutePath, THUMB_LONG_EDGE_PX)
        if (bitmap == null) {
            Log.w(TAG, "JPEGを解釈できずサムネイル生成をスキップしました: ${orig.path}")
            return
        }
        try {
            val scale = THUMB_LONG_EDGE_PX.toDouble() / max(bitmap.width, bitmap.height)
            val scaled = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            try {
                thumb.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, it) }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 既存カードのサムネイルを原本（photo_orig.jpg）から再生成する（2026-07-10追加）。
     * EXIF回転修正前に生成された既存サムネイルは、修正後のコード（[generateThumbnail]）で
     * 再生成しない限り回転したままになる（原本のEXIF情報自体は無傷なので再生成すれば直る）。
     * 一括再生成はStopCardListScreenの「サムネイル再生成」から呼ぶ想定。
     */
    suspend fun regenerateThumbnail(cardId: Long) = withContext(Dispatchers.IO) {
        val card = busStopCardDao.getById(cardId) ?: return@withContext
        val dir = BusCourseStorage.resolve(context, card.photoDirRelPath)
        val orig = File(dir, BusCourseStorage.FILE_STOPCARD_PHOTO_ORIG)
        if (orig.exists()) {
            generateThumbnail(orig, File(dir, BusCourseStorage.FILE_STOPCARD_PHOTO_THUMB))
        }
    }

    /** 全アクティブカードのサムネイルを一括再生成する（[regenerateThumbnail]参照）。失敗件数を返す。 */
    suspend fun regenerateAllThumbnails(): Int = withContext(Dispatchers.IO) {
        var failed = 0
        for (card in busStopCardDao.getAllActive()) {
            try {
                regenerateThumbnail(card.id)
            } catch (e: Exception) {
                Log.e(TAG, "サムネイル再生成に失敗しました: id=${card.id}", e)
                failed++
            }
        }
        failed
    }

    // ------------------------------------------------------------------
    // コース編成（§3.5 course / course_stop、§3.8）
    // ------------------------------------------------------------------

    suspend fun getCourses(): List<CourseEntity> = courseDao.getAll()

    suspend fun getCourseWithDetails(courseId: Long): CourseWithDetails? = courseDao.getWithDetails(courseId)

    suspend fun createCourse(name: String, kind: CourseKind, baseCourseId: Long? = null): Long {
        val now = System.currentTimeMillis()
        return courseDao.upsert(
            CourseEntity(
                name = name,
                description = null,
                kind = kind.name,
                baseCourseId = baseCourseId,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    /**
     * 順列の書き換え確定（追加・削除・ドラッグ&ドロップ並べ替えの確定操作）。
     * `course_stop` を全削除→再挿入し、§3.8どおり `regenerateCourseSegments` に接続する。
     *
     * `course_stop.stop_card_id` は version 11（[BusCourseDatabase.MIGRATION_10_11]）でNULL許容化
     * されたが（「座標を持つ点」への転換、[CourseStopEntity]参照）、この関数自体は3パス化の解決
     * ロジックを実装しない現状のスコープに合わせ、**従来どおり必ずカードを指す点として**作る
     * （`frameId` は常に null）。将来frame座標のみの点を書き込む経路が追加されても、
     * [requireCoordinateSource] を通すことで不変条件（frame_id/stop_card_idの少なくとも一方は非null）
     * を担保する。
     */
    suspend fun setCourseStops(courseId: Long, stopCardIds: List<Long>) {
        database.withTransaction {
            courseStopDao.deleteAllForCourse(courseId)
            if (stopCardIds.isNotEmpty()) {
                courseStopDao.insertAll(
                    stopCardIds.mapIndexed { index, cardId ->
                        requireCoordinateSource(stopCardId = cardId, frameId = null, eventId = null, context = "courseId=$courseId, index=$index")
                        CourseStopEntity(
                            courseId = courseId,
                            stopCardId = cardId,
                            frameId = null, // 3パス化の座標解決ロジックは未実装のため、現状は常にカードのみの点として作る
                            eventId = null, // 同上
                            sequenceIndex = index,
                            expectedChainageM = null, // regenerate 後に RoutePreprocessor が再計算
                        )
                    }
                )
            }
            courseDao.getById(courseId)?.let {
                courseDao.upsert(it.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        regenerateCourseSegments(courseId)
    }

    /**
     * `course_stop` の不変条件チェック（[CourseStopEntity]のKDoc「不変条件」参照）: [frameId]・
     * [eventId]・[stopCardId] の少なくとも一つが非nullであることを担保する（2026-07-16改、
     * event_id追加でstop_card_id/frame_idの2択から3択に拡張）。RoomはCHECK制約と相性が悪いため、
     * DBのCHECK制約ではなくコード層（この関数）で担保する方針（2026-07-15オーナー確定）。
     * `course_stop` への書き込み経路（[setCourseStops]・[insertCourseStopsFromPreview]）は
     * 必ずこの関数を通すこと。違反時は [IllegalArgumentException] を送出する（既存の
     * `require`/`check` を使ったバリデーション流儀に合わせる）。
     *
     * テストから直接呼べるよう `internal` にしている（[splitCourseCreationStops] と同じ理由。
     * DB非依存の純粋なバリデーションであり、公開APIとして外部モジュールに広める意図はない）。
     */
    internal fun requireCoordinateSource(stopCardId: Long?, frameId: Long?, eventId: Long? = null, context: String) {
        require(stopCardId != null || frameId != null || eventId != null) {
            "course_stop には stop_card_id / frame_id / event_id の少なくとも一つが必要です（$context）"
        }
    }

    /**
     * コース区間の再構築（設計書§3.8の疑似コードどおり）。順列の隣接ペアごとに既存 `segment_track`
     * （有向エッジ）を引き当て、実測ありは CONFIRMED・未走行は PENDING で `course_segment` を作り直し、
     * 続けて `RoutePreprocessor.rebuildRoutePoints` で route_point / expected_chainage_m を再生成する。
     *
     * 2026-07-15改（3パス化パス1/パス2対応）: `course_stop.stop_card_id` はNULL許容化されており
     * （[CourseStopEntity]参照）、パス1が作る「映像のみの点」（`frameId`はあるが吸着先カードが
     * 見つからず`stopCardId`がnullのまま、[CourseCreationStopPreview]参照）が実在するようになった。
     * `course_segment` の `from/to_stop_card_id` はNOT NULL（`bus_stop_card` FK）のためカードが無い
     * 点は区間の端点にできない。以前は `requireStopCardId`（非null前提の例外化）で押し通していたが、
     * それだとパス1で映像のみの点を1つでも含むコースの創設が丸ごと落ちてしまう（3パス化の意義を
     * 損なう）ため、隣接ペアのどちらかがカード無しなら**例外にせず、その区間だけ静かにスキップする**
     * よう改めた（カードの確定・区間の補完は編集画面の仕事、設計ドラフトv2 §1）。
     */
    suspend fun regenerateCourseSegments(courseId: Long) {
        database.withTransaction {
            val stops = courseStopDao.getOrderedStops(courseId) // sequence_index順

            courseSegmentDao.deleteAllForCourse(courseId)

            val newSegments = stops.zipWithNext().mapIndexedNotNull { i, (from, to) ->
                val fromCardId = from.stopCardId ?: return@mapIndexedNotNull null
                val toCardId = to.stopCardId ?: return@mapIndexedNotNull null
                val track = segmentTrackDao.findByDirectedEdge(fromCardId, toCardId)
                CourseSegmentEntity(
                    courseId = courseId,
                    sequenceIndex = i,
                    fromStopCardId = fromCardId,
                    toStopCardId = toCardId,
                    segmentTrackId = track?.id,
                    status = if (track != null) CourseSegmentStatus.CONFIRMED.name else CourseSegmentStatus.PENDING.name,
                )
            }
            if (newSegments.isNotEmpty()) courseSegmentDao.insertAll(newSegments)
        }
        // ★統合で追加: route_point / course_stop.expected_chainage_m を再生成（§3.5 route_point）
        routePreprocessor.rebuildRoutePoints(courseId)
    }

    /** UI側：このコースであとどこを試走すべきか（§3.8）。 */
    suspend fun getPendingSegments(courseId: Long): List<CourseSegmentEntity> =
        courseSegmentDao.getByStatus(courseId, CourseSegmentStatus.PENDING.name)

    /** カード選択ダイアログの使用中バッジ用（P1-4）。他コースで使用中のカードID→コース名。 */
    suspend fun getStopCardUsage(excludeCourseId: Long): Map<Long, String> =
        courseStopDao.getUsageExcluding(excludeCourseId).associate { it.cardId to it.courseName }

    /** 停留所カード一覧の使用中バッジ用。全カードの使用状況（カードID→(コース名, 0始まりの順序)）。 */
    suspend fun getAllStopCardUsage(): Map<Long, Pair<String, Int>> =
        courseStopDao.getAllUsage().associate { it.cardId to (it.courseName to it.sequenceIndex) }

    /**
     * コースを削除する（コース＝参照リストのため物理削除）。course_stop / route_point / course_segment は
     * FK ON DELETE CASCADE で連動削除される。停留所カード・記録セッションには触れない（源泉として残す）。
     * base_course_id で本コースを基底参照している他コースがあれば、その参照は SET NULL される。
     * 冪等: 存在しないIDを渡しても何も起きない。
     */
    suspend fun deleteCourse(courseId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            courseDao.deleteById(courseId)
        }
    }

    // ------------------------------------------------------------------
    // 試走ログからの区間自動抽出（§3.9）
    // ------------------------------------------------------------------

    /** 区間抽出の対象にできる完了済みセッション一覧（UI「セッション一覧→抽出実行」導線用）。 */
    suspend fun getExtractableSessions() =
        recordingSessionDao.getByStatus(RecordingSessionStatus.COMPLETED.name)
            .filter { it.type in EXTRACTABLE_SESSION_TYPES }

    /** セッションメモの更新（区間抽出画面「いつの何の目的で走ったか」の後付け記録、2026-07-11追加）。 */
    suspend fun updateSessionMemo(sessionId: Long, memo: String?) {
        recordingSessionDao.updateMemo(sessionId, memo?.takeIf { it.isNotBlank() })
    }

    // ------------------------------------------------------------------
    // セッション解析レポート（②「コース編成(抽出)」フェーズA-1、2026-07-13追加、読み取り専用）
    // ------------------------------------------------------------------

    /**
     * session8のような長回し記録に付いた停留所マーカー（`timelapse_frame.stop_card_id`、
     * 運行記録③機能・version 8）を解析する（フェーズA-1）。**読み取り専用**：DB書き込み・
     * スキーマ変更は一切行わない。ExtractionScreenのセッション一覧「解析」ボタンから呼ばれる。
     *
     * - マーカー時系列: seq順のマーク済みフレームそれぞれについて、撮影位置と紐づく停留所カード
     *   座標とのHaversine距離を計算する（誤吸着＝離れた停留所に誤って割り当てられた疑いの目安）。
     * - ダブり検出: seq順で同一stop_card_idが連続し、かつcaptured_at間隔が
     *   [DUPLICATE_GROUP_INTERVAL_MS] 未満のマーカー列を統合候補としてグループ化する。
     */
    suspend fun analyzeSessionMarkers(sessionId: Long): SessionMarkerAnalysis = withContext(Dispatchers.IO) {
        val session = recordingSessionDao.getById(sessionId)
            ?: throw IllegalArgumentException("セッションが見つかりません: id=$sessionId")

        val markedFrames = timelapseFrameDao.getMarkedFrames(sessionId) // seq順、stop_card_id IS NOT NULL
        val gpsPointCount = gpsPointDao.getBySession(sessionId).size

        // 停留所カードはアーカイブ済み（is_archived=1）でも過去マーカーの解析対象になりうるため、
        // getAllActive() ではなく getById を都度引く（idごとにキャッシュして重複クエリを避ける）
        val cardCache = mutableMapOf<Long, BusStopCardEntity?>()
        suspend fun cardFor(id: Long): BusStopCardEntity? = cardCache.getOrPut(id) { busStopCardDao.getById(id) }

        val timeline = markedFrames.map { frame ->
            val stopCardId = requireNotNull(frame.stopCardId) { "getMarkedFramesはstop_card_id IS NOT NULLで絞っているはず" }
            val card = cardFor(stopCardId)
            val distanceM = if (card != null && frame.latitude != null && frame.longitude != null) {
                GeoMath.haversineM(frame.latitude, frame.longitude, card.latitude, card.longitude)
            } else {
                null
            }
            MarkerTimelineRow(
                frameId = frame.id,
                capturedAt = frame.capturedAt,
                stopCardId = stopCardId,
                stopName = card?.name ?: "停留所#$stopCardId",
                distanceM = distanceM,
            )
        }

        val duplicates = mutableListOf<MarkerDuplicateGroup>()
        var i = 0
        while (i < markedFrames.size) {
            var j = i + 1
            while (
                j < markedFrames.size &&
                markedFrames[j].stopCardId == markedFrames[i].stopCardId &&
                (markedFrames[j].capturedAt - markedFrames[j - 1].capturedAt) < DUPLICATE_GROUP_INTERVAL_MS
            ) {
                j++
            }
            if (j - i > 1) {
                val stopCardId = requireNotNull(markedFrames[i].stopCardId)
                val card = cardFor(stopCardId)
                duplicates += MarkerDuplicateGroup(
                    stopCardId = stopCardId,
                    stopName = card?.name ?: "停留所#$stopCardId",
                    count = j - i,
                    timestamps = (i until j).map { markedFrames[it].capturedAt },
                    // timelineはmarkedFramesと同じ順序・同じ要素数で構築されているため、同じインデックスで対応する
                    frameCandidates = (i until j).map { idx ->
                        DuplicateFrameCandidate(
                            frameId = markedFrames[idx].id,
                            capturedAt = markedFrames[idx].capturedAt,
                            distanceM = timeline[idx].distanceM,
                        )
                    },
                )
            }
            i = j
        }

        SessionMarkerAnalysis(
            sessionId = session.id,
            sessionType = session.type,
            startedAt = session.startedAt,
            durationSec = session.endedAt?.let { (it - session.startedAt) / 1000 },
            gpsPointCount = gpsPointCount,
            markerCount = markedFrames.size,
            distinctMarkedStopCount = markedFrames.mapNotNull { it.stopCardId }.distinct().size,
            timeline = timeline,
            duplicates = duplicates,
        )
    }

    /**
     * 欠損/割り込みレポート（②「コース編成(抽出)」フェーズA-2、2026-07-13追加、読み取り専用）。
     * [courseId] の停留所（`course_stop`）と、[sessionId] のマーク済み停留所
     * （`timelapse_frame.stop_card_id`）を突き合わせ、未マーク停留所（欠損）を列挙する。
     * 各欠損停留所は、カード座標近傍のGPS点列（[COVERAGE_RADIUS_M] 以内）から停車確定(dwell)判定
     * （[classifyMissingStop]）して分類する。**読み取り専用**：DB書き込み・スキーマ変更は行わない。
     */
    suspend fun analyzeCourseCoverage(sessionId: Long, courseId: Long): CourseCoverageReport = withContext(Dispatchers.IO) {
        val details = courseDao.getWithDetails(courseId)
            ?: throw IllegalArgumentException("コースが見つかりません: id=$courseId")
        val markedStopIds = timelapseFrameDao.getMarkedFrames(sessionId).mapNotNull { it.stopCardId }.toSet()
        val points = gpsPointDao.getBySession(sessionId) // seq順

        val missing = details.stops
            .sortedBy { it.courseStop.sequenceIndex }
            .map { it.requireCard } // カードのみの点前提（[CourseStopWithCard]のクラスKDoc参照）
            .distinctBy { it.id }
            .filter { it.id !in markedStopIds }
            .map { card -> classifyMissingStop(card, points) }

        CourseCoverageReport(sessionId = sessionId, courseId = courseId, missing = missing)
    }

    /**
     * 全体レポート（トップダウン創設フェーズ、course非依存の停車確認、読み取り専用）。
     * まだコースが無い状態から、セッション軌跡の近傍（軌跡コリドー半径 [COVERAGE_CORRIDOR_R_M]）に
     * ある登録カードのうち、マーク未済のものを [classifyMissingStop] で分類する。[analyzeCourseCoverage]
     * と異なりコース（`course_stop`）を前提にせず、登録済み全カード（[BusStopCardDao.getAllActive]）を
     * 走査対象にする。OUT_OF_COURSE（コリドー外）は候補から除外し、STOP_CONFIRMED / PASS_THROUGH を
     * 割り込み候補として返す（UI側はSTOP_CONFIRMEDのみ確定可にする想定）。**読み取り専用**：
     * DB書き込み・スキーマ変更は行わない。
     */
    suspend fun analyzeSessionCoverage(sessionId: Long): SessionCoverageReport = withContext(Dispatchers.IO) {
        val points = gpsPointDao.getBySession(sessionId) // seq順
        val markedIds = timelapseFrameDao.getMarkedFrames(sessionId).mapNotNull { it.stopCardId }.toSet()

        val candidates = busStopCardDao.getAllActive()
            .filter { it.id !in markedIds }
            .map { card -> classifyMissingStop(card, points, COVERAGE_CORRIDOR_R_M) }
            .filter { it.classification != StopCoverageClassification.OUT_OF_COURSE }

        SessionCoverageReport(sessionId = sessionId, candidates = candidates)
    }

    /**
     * 1停留所分の停車確定(dwell)判定（実データ校正済み、[analyzeCourseCoverage] 参照）。
     * カード座標から半径 [radiusM]（デフォルト [COVERAGE_RADIUS_M]）以内のGPS点を集め、seq連続で
     * 時間間隔が [COVERAGE_PASS_GAP_MS] を超えたら別パスとして分割する（通過ごとに分割）。各パスの
     * dwell＝そのパスの最終ts−先頭ts、minSpeed＝そのパスの最小speed。いずれかのパスで
     * dwell≥[COVERAGE_DWELL_MIN_SEC] かつ minSpeed<[COVERAGE_MIN_SPEED_MPS] なら停車確定。
     * 半径内に点が無ければ圏外、点はあるが停車条件を満たさなければ通過。
     * [radiusM] は [analyzeSessionCoverage]（トップダウン創設、コリドー半径 [COVERAGE_CORRIDOR_R_M]）
     * からも共用するための引数化（2026-07-14追加、デフォルト値により既存呼び出しの挙動は不変）。
     */
    private fun classifyMissingStop(card: BusStopCardEntity, points: List<GpsPointEntity>, radiusM: Double = COVERAGE_RADIUS_M): MissingStop {
        val nearby = points.filter {
            GeoMath.haversineM(it.lat, it.lon, card.latitude, card.longitude) <= radiusM
        }
        if (nearby.isEmpty()) {
            return MissingStop(card.id, card.name, StopCoverageClassification.OUT_OF_COURSE, null, null)
        }

        // seq連続で時間間隔が閾値を超えたら別パスに分割（半径内の点だけを対象にした間隔判定）
        val passes = mutableListOf<MutableList<GpsPointEntity>>()
        for (p in nearby) {
            val currentPass = passes.lastOrNull()
            if (currentPass == null || p.tsEpochMs - currentPass.last().tsEpochMs > COVERAGE_PASS_GAP_MS) {
                passes += mutableListOf(p)
            } else {
                currentPass += p
            }
        }

        var confirmed = false
        var bestConfirmedDwellSec = -1.0
        var bestConfirmedSpeedKmh: Double? = null
        var bestAnyDwellSec = -1.0
        var bestAnySpeedKmh: Double? = null
        for (pass in passes) {
            val dwellSec = (pass.last().tsEpochMs - pass.first().tsEpochMs) / 1000.0
            val minSpeedMps = pass.mapNotNull { it.speedMps }.minOrNull()
            val minSpeedKmh = minSpeedMps?.let { it * 3.6 }
            if (dwellSec > bestAnyDwellSec) {
                bestAnyDwellSec = dwellSec
                bestAnySpeedKmh = minSpeedKmh
            }
            if (dwellSec >= COVERAGE_DWELL_MIN_SEC && minSpeedMps != null && minSpeedMps < COVERAGE_MIN_SPEED_MPS) {
                confirmed = true
                if (dwellSec > bestConfirmedDwellSec) {
                    bestConfirmedDwellSec = dwellSec
                    bestConfirmedSpeedKmh = minSpeedKmh
                }
            }
        }

        return if (confirmed) {
            MissingStop(card.id, card.name, StopCoverageClassification.STOP_CONFIRMED, bestConfirmedDwellSec, bestConfirmedSpeedKmh)
        } else {
            MissingStop(
                card.id,
                card.name,
                StopCoverageClassification.PASS_THROUGH,
                bestAnyDwellSec.takeIf { it >= 0 },
                bestAnySpeedKmh,
            )
        }
    }

    // ------------------------------------------------------------------
    // find-or-create候補の解析（②「コース編成(抽出)」フェーズB(c)、2026-07-14追加、読み取り専用）
    //
    // 2026-07-15注記: このセクションはS4「コース創設」（トップダウン、[createCoursesFromSession]）とは
    // 別導線の、既存の「コース編成(抽出)」（[com.istech.buscourse.ui.SessionAnalysisScreen]）が使う
    // フェーズB(c)専用の解析・適用ロジックのまま残している（[applyFindOrCreate]参照）。設計ドラフト
    // v2 §11の「analyzeFindOrCreateCandidates撤去」はコース創設フロー（S4）内での話であり、
    // 本セクション自体（フェーズB(c)、[com.istech.buscourse.ui.SessionAnalysisScreen]から実働で
    // 呼ばれている）を廃止する指示ではないと判断し、[findOrCreateRadiusFor] を含め温存した
    // （半径選択ロジックは新パス2 [attachPass2Cards]・[findNearbyCardsForCorridor] とも共用する）。
    // ------------------------------------------------------------------

    /**
     * find-or-create候補の解析（フェーズB(c)、読み取り専用）。マーク済みフレームのうち、撮影位置と
     * 現在割り当てられている停留所カード座標との距離が [FIND_OR_CREATE_RADIUS_M]
     * （拠点カードは [FIND_OR_CREATE_HUB_RADIUS_M]、[findOrCreateRadiusFor] 参照）を超えるもの
     * （誤吸着の疑い）を候補として列挙する。各候補について、コマ位置の半径内にある別の既存カードを
     * 全件・距離順に集め（[FindOrCreateCandidate.nearbyCards]）、UI側で「付替え／新規作成／
     * 選択しない」の候補選択を提示できるようにする（思いつき2、2026-07-14）。
     */
    suspend fun analyzeFindOrCreateCandidates(sessionId: Long): List<FindOrCreateCandidate> = withContext(Dispatchers.IO) {
        val markedFrames = timelapseFrameDao.getMarkedFrames(sessionId)
        if (markedFrames.isEmpty()) return@withContext emptyList()
        val activeCards = busStopCardDao.getAllActive()

        markedFrames.mapNotNull { frame ->
            val stopCardId = frame.stopCardId ?: return@mapNotNull null
            val lat = frame.latitude ?: return@mapNotNull null
            val lon = frame.longitude ?: return@mapNotNull null
            val assignedCard = activeCards.find { it.id == stopCardId } ?: busStopCardDao.getById(stopCardId) ?: return@mapNotNull null
            val distanceM = GeoMath.haversineM(lat, lon, assignedCard.latitude, assignedCard.longitude)
            val assignRadius = findOrCreateRadiusFor(assignedCard)
            if (distanceM <= assignRadius) return@mapNotNull null

            val nearbyCards = activeCards
                .filter { it.id != stopCardId }
                .map { it to GeoMath.haversineM(lat, lon, it.latitude, it.longitude) }
                .filter { (card, d) -> d <= findOrCreateRadiusFor(card) }
                .sortedBy { (_, d) -> d }
                .map { (card, d) -> NearbyCard(cardId = card.id, name = card.name, distanceM = d) }

            FindOrCreateCandidate(
                frameId = frame.id,
                capturedAt = frame.capturedAt,
                currentStopCardId = stopCardId,
                currentStopName = assignedCard.name,
                distanceM = distanceM,
                frameLatitude = lat,
                frameLongitude = lon,
                fileRelPath = frame.fileRelPath,
                nearbyCards = nearbyCards,
            )
        }
    }

    /**
     * find-or-create候補判定用の半径選択（[FIND_OR_CREATE_RADIUS_M] / [FIND_OR_CREATE_HUB_RADIUS_M]）。
     * 拠点（[BusStopCardEntity.isHub]）カードは敷地が広いため、半径を広げて候補から外す。
     */
    private fun findOrCreateRadiusFor(card: BusStopCardEntity): Double =
        if (card.isHub) FIND_OR_CREATE_HUB_RADIUS_M else FIND_OR_CREATE_RADIUS_M

    /**
     * 指定座標の軌跡コリドー内（[findOrCreateRadiusFor]の半径。拠点は広く判定）にある既存カードを
     * 距離順に列挙する（コース創設パス2 [attachPass2Cards] の核。2026-07-15追加）。
     *
     * 公開関数にしている理由: パス2は複数候補があっても最も近い1枚だけを吸着し、1:N候補の一覧は
     * `course_stop` に保存しない（設計ドラフトv2 §3パス2「1:Nの候補はDBに保存しない」。
     * [CourseCreationStopPreview] は `cardId` を1つしか持てない構造で、そもそも複数候補を表現できない）。
     * 編集画面が将来「吸着候補から1つ選び直す」UIを作る際、`frame_id`/`stop_visit_event` の座標さえ
     * わかればこの関数でその場から再計算できるため、候補一覧を先回りしてDBに保存する必要がない
     * （[analyzeFindOrCreateCandidates] と同種のロジックだが、あちらは「フレームに既に割り当て済みの
     * カードとの距離が半径を超えたら誤吸着候補」という別の入力・別の用途のため、素朴に統合はしない）。
     */
    suspend fun findNearbyCardsForCorridor(latitude: Double, longitude: Double): List<NearbyCard> =
        withContext(Dispatchers.IO) { nearbyCardsWithinCorridor(latitude, longitude, busStopCardDao.getAllActive()) }

    /**
     * [findNearbyCardsForCorridor] の中身（DB非依存の純関数）。[com.istech.buscourse.course.CourseRepository.attachPass2Cards]
     * がセッション内の全点についてループする際、点ごとに `getAllActive()` を呼び直さずに済むよう
     * [cards] を呼び出し側で1回だけ取得して渡せるようにしている（2026-07-15追加）。
     */
    private fun nearbyCardsWithinCorridor(latitude: Double, longitude: Double, cards: List<BusStopCardEntity>): List<NearbyCard> =
        cards
            .map { it to GeoMath.haversineM(latitude, longitude, it.latitude, it.longitude) }
            .filter { (card, d) -> d <= findOrCreateRadiusFor(card) }
            .sortedBy { (_, d) -> d }
            .map { (card, d) -> NearbyCard(cardId = card.id, name = card.name, distanceM = d) }

    // ------------------------------------------------------------------
    // 承認キュー適用（②「コース編成(抽出)」フェーズB、2026-07-14追加）
    //
    // フェーズA/フェーズB(c)の読み取り専用解析が出した候補のうち、人が選んで承認したものだけを
    // 実際にDBへ反映する。すべて suspend・冪等（同じ候補で再度呼んでも状態が壊れない）。
    // 実データ実機での初の書き込み＋マイグレーションのため、既存機能・既存データへの影響を
    // 最小化する設計にしている（詳細は各メソッドのKDoc参照）。
    // ------------------------------------------------------------------

    /**
     * (a) ダブり統合の適用。[groups] は [analyzeSessionMarkers] が返す
     * [MarkerDuplicateGroup.frameCandidates] のうち、承認（チェック）されたグループをそのまま渡す。
     * 各グループについて、カード座標との距離が最小の1枚（距離不明なコマは代表に選ばれにくいよう
     * 最大値扱いで比較）を代表として残し、他のコマの `stop_card_id` を NULL に戻す
     * （[TimelapseFrameDao.clearStopCardOnFrame]）。
     *
     * 冪等性: 既に NULL のフレームへ再度 NULL を書いても結果は変わらない。1枚しかないグループ
     * （既に統合済み）は何もしない。DB書き込みは単純な UPDATE のみのため1トランザクションに束ねる。
     */
    suspend fun applyDuplicateMerges(groups: List<List<DuplicateFrameCandidate>>): Int = withContext(Dispatchers.IO) {
        var cleared = 0
        database.withTransaction {
            for (group in groups) {
                if (group.size <= 1) continue
                val keep = group.minByOrNull { it.distanceM ?: Double.MAX_VALUE } ?: continue
                for (candidate in group) {
                    if (candidate.frameId == keep.frameId) continue
                    timelapseFrameDao.clearStopCardOnFrame(candidate.frameId)
                    cleared++
                }
            }
        }
        cleared
    }

    /**
     * (b) 割り込みの適用。[stopIds] は欠損×停車確定(STOP_CONFIRMED)と判定された停留所カードID
     * （[analyzeCourseCoverage] の [MissingStop.classification]、承認されたもの）。
     * 各停留所について、当該セッションのLORESフレームからカード座標に最も近いものへ
     * `stop_card_id` を設定する（[TimelapseFrameDao.markStopCardOnLoresFrame]）。
     *
     * 安全策（要件外の追加）: 最近傍フレームが既に**別の**停留所のマーカーとして使われている場合は
     * 上書きしない（他停留所の既存マーカーを壊さないため）。その場合はそのフレームを除外して
     * 次に近いフレームを探す。候補がすべて他停留所に使用済みならその停留所はスキップする。
     *
     * 冪等性: 一度割り当てたフレームに同じstopIdを再度設定しても値は変わらない（no-op UPDATE）。
     * 再実行時は該当停留所が既にマーク済みとなり [analyzeCourseCoverage] の欠損から外れるため、
     * 通常はそもそも候補として再度渡されない。
     */
    suspend fun applyInterruptions(sessionId: Long, stopIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (stopIds.isEmpty()) return@withContext 0
        val loresFrames = timelapseFrameDao.getBySession(sessionId)
            .filter { it.kind == FrameKind.LORES.name && it.latitude != null && it.longitude != null }
        var applied = 0
        database.withTransaction {
            for (stopId in stopIds) {
                val card = busStopCardDao.getById(stopId) ?: continue
                val candidateFrame = loresFrames
                    .filter { it.stopCardId == null || it.stopCardId == stopId }
                    .minByOrNull { GeoMath.haversineM(it.latitude!!, it.longitude!!, card.latitude, card.longitude) }
                    ?: continue
                if (candidateFrame.stopCardId == stopId) continue // 既に割り当て済み（冪等・no-op）
                timelapseFrameDao.markStopCardOnLoresFrame(candidateFrame.id, stopId)
                applied++
            }
        }
        applied
    }

    /**
     * (c) find-or-create の適用。[candidates] は [analyzeFindOrCreateCandidates] の返り値のうち、
     * 「新規作成」として承認（チェック）されたものだけを渡すこと。`nearbyCards` が空
     * （＝半径内に別の既存カードが無い）候補はチェックボックスで、非空の候補は候補選択UI
     * （付替え／新規作成／選択しない、思いつき2）で「新規作成」を選んだ場合にここへ渡る
     * （近くに既存カードがあっても、ユーザーが明示的に新規作成を選べば作成する）。各候補について、
     * そのLORESフレームから新規カードを作成し
     * （`name = "候補%03d"` の連番、`needsMaturation = true`）、フレームの `stop_card_id` を
     * 新カードidへ付替える。
     *
     * 実ファイルの扱いに注意: [CourseRepository.createStopCard] が受け取る `photoTempFile` は
     * 内部で `renameTo`（失敗時は copy+delete）により**消費**される前提の一時ファイルである。
     * そのままセッションのLORESフレーム実ファイル（`sessions/{id}/frames/...`）を渡すと、
     * 元のセッション記録が消えてしまう（他の解析・再表示が壊れる）。そのため、まず一時コピーを
     * 作ってからそれを渡す（既存データを壊さないための対応）。
     *
     * 冪等性: 適用直前に対象フレームの現在の `stop_card_id` を読み直し、[FindOrCreateCandidate]
     * 取得時点の `currentStopCardId` と異なっていれば（＝取得後に既に処理済み・別途変更された）
     * そのフレームはスキップする（新規カードの二重作成を防ぐ、[TimelapseFrameDao.getById]使用）。
     */
    suspend fun applyFindOrCreate(candidates: List<FindOrCreateCandidate>): Int = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext 0
        var seq = nextCandidateNameSeq()
        var applied = 0
        for (candidate in candidates) {
            val currentFrame = timelapseFrameDao.getById(candidate.frameId) ?: continue
            if (currentFrame.stopCardId != candidate.currentStopCardId) continue // 冪等ガード

            val sourceFile = BusCourseStorage.resolve(context, candidate.fileRelPath)
            if (!sourceFile.exists()) continue

            val tempCopy = newCaptureTempFile()
            try {
                sourceFile.copyTo(tempCopy, overwrite = true)
            } catch (e: IOException) {
                Log.e(TAG, "find-or-create: LORESフレームの一時コピーに失敗しました frameId=${candidate.frameId}", e)
                continue
            }

            val newCardId = createStopCard(
                name = "候補%03d".format(seq),
                latitude = candidate.frameLatitude,
                longitude = candidate.frameLongitude,
                altitudeM = null,
                notes = null,
                riderCount = 0,
                photoTempFile = tempCopy,
                needsMaturation = true,
            )
            timelapseFrameDao.markStopCardOnLoresFrame(candidate.frameId, newCardId)
            seq++
            applied++
        }
        applied
    }

    /**
     * (c') find-or-create候補選択UI（思いつき2、2026-07-14追加）で「付替え」を選んだフレームを、
     * 指定の既存カードへ再吸着する。[reassignments] は frameId → 付替え先 stop_card_id。
     * 単純な `stop_card_id` の UPDATE のみ（[TimelapseFrameDao.markStopCardOnLoresFrame] を再利用）
     * のため、新規カード作成を伴う [applyFindOrCreate] とは別の独立した適用関数にしている。
     *
     * 冪等性: 同じ値を再設定しても結果は変わらない（no-op UPDATE）。空のマップを渡せば何もしない。
     *
     * @return 適用したフレーム件数（[reassignments] のサイズと同じ）。
     */
    suspend fun reassignMarkerFrames(reassignments: Map<Long, Long>): Int = withContext(Dispatchers.IO) {
        if (reassignments.isEmpty()) return@withContext 0
        database.withTransaction {
            reassignments.forEach { (frameId, cardId) -> timelapseFrameDao.markStopCardOnLoresFrame(frameId, cardId) }
        }
        reassignments.size
    }

    /** [applyFindOrCreate] の連番の開始値。既存の「候補NNN」カード名の最大値+1から始める（衝突回避）。 */
    private suspend fun nextCandidateNameSeq(): Int {
        val maxExisting = busStopCardDao.getAllActive()
            .mapNotNull { CANDIDATE_NAME_REGEX.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return maxExisting + 1
    }

    /**
     * (d) 拠点フラグの適用。[stopIds] はセッション解析の「拠点で分割」（承認されたもの）。
     * `bus_stop_card.is_hub` を [hub] に設定する（[BusStopCardDao.setHub]）。
     *
     * 冪等性: 同じ値を再設定しても結果は変わらない（no-op UPDATE）。
     */
    suspend fun applyHubFlags(stopIds: List<Long>, hub: Boolean = true): Int = withContext(Dispatchers.IO) {
        if (stopIds.isEmpty()) return@withContext 0
        val now = System.currentTimeMillis()
        database.withTransaction {
            stopIds.forEach { id -> busStopCardDao.setHub(id, hub, now) }
        }
        stopIds.size
    }

    /**
     * 承認キューの一括適用（フェーズBのUI「承認して適用」ボタンから呼ばれるオーケストレーション）。
     * (a)〜(d) を順に呼ぶだけで、これ自体は追加のトランザクションを持たない
     * （(c)は写真ファイルI/Oを伴うため、DBトランザクションと長時間のファイルI/Oを混在させない
     * 既存方針＝[extractSegmentsFromSession]等の踏襲。各メソッドは個々に冪等・トランザクション済み）。
     */
    suspend fun applyApprovedCandidates(
        sessionId: Long,
        duplicateGroups: List<List<DuplicateFrameCandidate>>,
        interruptionStopIds: List<Long>,
        findOrCreateCandidates: List<FindOrCreateCandidate>,
        hubStopIds: List<Long>,
    ): ApplyApprovedResult = withContext(Dispatchers.IO) {
        val merged = applyDuplicateMerges(duplicateGroups)
        val interrupted = applyInterruptions(sessionId, interruptionStopIds)
        val created = applyFindOrCreate(findOrCreateCandidates)
        val hubApplied = applyHubFlags(hubStopIds)
        ApplyApprovedResult(
            duplicatesMerged = merged,
            interruptionsApplied = interrupted,
            findOrCreateApplied = created,
            hubFlagsApplied = hubApplied,
        )
    }

    // ------------------------------------------------------------------
    // コース確定→route_point生成（②「コース編成(抽出)」フェーズC-1、2026-07-14追加）
    //
    // フェーズB（承認キュー）を経て停留所マーカーが整った承認済みセッションから、そのコースの
    // ナビ用連続トラックを確定し `route_point` へ保存する。範囲はコースの拠点→拠点にクリップする。
    // 描画側（RouteMapScreen）の変更はC-2で行うためここでは扱わない。
    // ------------------------------------------------------------------

    /**
     * コース確定（フェーズC-1）。[sessionId] のセッションから、[courseId] のコースのナビ用連続
     * トラックを確定し、既存の `route_point` テーブルへ保存する（`course.source_session_id` にも
     * 出所として記録する）。
     *
     * **時間窓（クリップ窓）の決め方（2026-07-14見直し）**:
     * 共有停留所（複数コースに属す停留所）が別コースの走行中にマークされると、旧ロジック
     * （コース停留所マーカーの[最小,最大]captured_at）では窓がその時刻まで伸びてしまい、
     * 隣コースの脚まで route_point に混入する不具合があった（実測: course1が7km→13kmに膨張）。
     * これを避けるため、次の優先順で窓を決める。
     *
     * 一次: 拠点フラグでフラグメント化（[HUB_FRAGMENT_MIN_COURSE_STOPS]参照）
     * 1. セッション内の全マーカー（`timelapse_frame.stop_card_id`、コースの停留所に限らない）を
     *    拠点（`bus_stop_card.is_hub=1`）のマークを境界にフラグメント化する。連続する拠点マークは
     *    1つの境界イベントにまとめ、境界イベント間の非拠点マーク列を1フラグメントとする
     *    （セクション4「拠点で分割」＝[com.istech.buscourse.ui.SessionAnalysisScreen]の
     *    `splitByHubs` と同じ考え方）。セッションに拠点マークが1つも無ければこの経路は試さない。
     * 2. 各フラグメントについて、含まれるマークの `stop_card_id` と `courseStopIds` の重なり数
     *    （マーク件数ベース）を数え、最多のフラグメントを選ぶ。
     * 3. **誤用ガード**: 選んだフラグメントの重なり数が [HUB_FRAGMENT_MIN_COURSE_STOPS] 未満なら、
     *    この経路は無効とみなしフォールバックへ進む。
     * 4. 有効なら window = 選んだフラグメントの[最小,最大]captured_at。さらに、その直前の拠点境界
     *    イベントの最後の拠点マーク／直後の拠点境界イベントの最初の拠点マークがあれば、そこまで
     *    前後に延伸する（拠点→拠点）。
     *
     * フォールバック: 拠点マークが無い、または一次が誤用ガードで無効だった場合（[CLUSTER_GAP_MS]参照）
     * 1. コースの停留所（`course_stop.stop_card_id`）のうち、このセッションでマーク済みのもの
     *    （courseMarks）を時刻順に集める。空ならこの関数は0を返す（現行どおり）。
     * 2. courseMarks を時間ギャップが [CLUSTER_GAP_MS] を超える箇所で連続クラスタに分割する。
     * 3. course停留所の異なり数が最多のクラスタを選ぶ（同数なら継続時間が最長のもの）。これにより
     *    別コース走行中に紛れ込んだ孤立マーカー（例: 08:37の#13）は別クラスタとして除外される。
     * 4. window = 選んだクラスタの[最小,最大]captured_at。セッションに拠点マークがある場合は、
     *    この窓の直前直後1件が拠点であれば延伸する（一次と同じ「直前/直後の1件」限定の延伸方式。
     *    拠点を探して遡り続けることはしない）。
     *
     * 窓 [windowStart, windowEnd]（captured_at基準）に入る `gps_point`（`ts_epoch_ms` 基準、seq順）
     * だけを連続トラックとして採用する（以降は現行どおり）。
     *
     * `route_point` は `routePointDao.deleteAllForCourse` → 再挿入で書き込むため、同じセッション・
     * 同じマーカー状態で再実行しても同じ結果になる（冪等）。`course.source_session_id` の更新も
     * 同じUPDATEを繰り返すだけなので冪等。すべて `database.withTransaction {}` で1つにまとめる。
     *
     * @return 生成した `route_point` の件数。次の場合は0を返す（`source_session_id` の更新自体は
     *   行ってよい）: このセッションに該当コースの停留所マーカーが1つも無い場合／窓内のGPS点が
     *   2点未満の場合。
     */
    suspend fun confirmCourseRouteFromSession(courseId: Long, sessionId: Long): Int = withContext(Dispatchers.IO) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            courseDao.updateSourceSession(courseId, sessionId, now)

            val details = courseDao.getWithDetails(courseId) ?: return@withTransaction 0
            // 2026-07-15改（3パス化対応）: パス1が作る「映像のみの点」はカードを持たない
            // （[CourseCreationStopPreview]参照）ため、以前の `requireCard`（非null前提の例外化）だと
            // そういう点を1つでも含むコースでこの関数自体が例外落ちしていた。クリップ窓の算出は
            // 「カードを持つ停留所がこのセッションでマークされているか」の突き合わせが目的であり、
            // カード無しの点はそもそも突き合わせ対象になり得ないため、素直に読み飛ばす（mapNotNull）。
            val courseStopIds = details.stops.mapNotNull { it.card?.id }.toSet()
            if (courseStopIds.isEmpty()) return@withTransaction 0

            // セッション内の全マーカー（seq順＝時系列順）。拠点分割／拠点延伸の探索にはコース外のマーカーも要る。
            // getMarkedFramesはSQL側でstop_card_id IS NOT NULLに絞っているが、エンティティの型自体は
            // 引き続きLong?のため、下記のnull安全な参照で扱う。
            val marks = timelapseFrameDao.getMarkedFrames(sessionId)

            val hubCache = mutableMapOf<Long, Boolean>()
            suspend fun isHub(stopCardId: Long): Boolean =
                hubCache.getOrPut(stopCardId) { busStopCardDao.getById(stopCardId)?.isHub == true }

            // マークごとに拠点マークか否かを判定しておく（以降のフラグメント化・延伸探索の両方で使う）
            val hubFlagByMark = marks.map { mark ->
                val stopId = mark.stopCardId
                stopId != null && isHub(stopId)
            }

            // marksを拠点マークの境界でフラグメント化した1断片（拠点境界イベント or 非拠点マーク列）。
            data class MarkSegment(val isHubGroup: Boolean, val marks: List<TimelapseFrameEntity>)

            val segments = mutableListOf<MarkSegment>()
            run {
                var i = 0
                while (i < marks.size) {
                    val hub = hubFlagByMark[i]
                    val start = i
                    while (i < marks.size && hubFlagByMark[i] == hub) i++
                    segments += MarkSegment(hub, marks.subList(start, i).toList())
                }
            }

            // --- 一次: 拠点フラグでフラグメント化し、コース停留所との重なりが最多のフラグメントを選ぶ ---
            val hubWindow: Pair<Long, Long>? = run {
                if (hubFlagByMark.none { it }) return@run null // 拠点マークが1つも無ければこの経路は試さない

                var bestIdx = -1
                var bestOverlap = -1
                for ((idx, seg) in segments.withIndex()) {
                    if (seg.isHubGroup) continue
                    val overlap = seg.marks.count { it.stopCardId != null && it.stopCardId in courseStopIds }
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap
                        bestIdx = idx
                    }
                }
                if (bestIdx == -1) return@run null
                if (bestOverlap < HUB_FRAGMENT_MIN_COURSE_STOPS) return@run null // 誤用ガード→フォールバックへ

                val chosen = segments[bestIdx]
                var start = chosen.marks.minOf { it.capturedAt }
                var end = chosen.marks.maxOf { it.capturedAt }
                // 直前/直後の拠点境界イベント（あれば）まで延伸する
                segments.getOrNull(bestIdx - 1)?.takeIf { it.isHubGroup }?.let { start = it.marks.last().capturedAt }
                segments.getOrNull(bestIdx + 1)?.takeIf { it.isHubGroup }?.let { end = it.marks.first().capturedAt }
                start to end
            }

            var windowStart: Long
            var windowEnd: Long

            if (hubWindow != null) {
                windowStart = hubWindow.first
                windowEnd = hubWindow.second
            } else {
                // --- フォールバック: 拠点マーク無し、または一次が誤用ガードで無効だった場合 ---
                val courseMarks = marks
                    .filter { it.stopCardId != null && it.stopCardId in courseStopIds }
                    .sortedBy { it.capturedAt }
                if (courseMarks.isEmpty()) return@withTransaction 0

                // 時間ギャップ > CLUSTER_GAP_MS で連続クラスタに分割する
                val clusters = mutableListOf<MutableList<TimelapseFrameEntity>>()
                for (mark in courseMarks) {
                    val current = clusters.lastOrNull()
                    if (current != null && mark.capturedAt - current.last().capturedAt <= CLUSTER_GAP_MS) {
                        current += mark
                    } else {
                        clusters += mutableListOf(mark)
                    }
                }

                // course停留所の異なり数が最多のクラスタを選ぶ（同数なら継続時間が最長のもの）
                val bestCluster = clusters.maxWithOrNull(
                    compareBy(
                        { cluster: List<TimelapseFrameEntity> -> cluster.mapNotNull { it.stopCardId }.toSet().size },
                        { cluster: List<TimelapseFrameEntity> -> cluster.last().capturedAt - cluster.first().capturedAt },
                    )
                ) ?: return@withTransaction 0

                windowStart = bestCluster.first().capturedAt
                windowEnd = bestCluster.last().capturedAt

                // 開始直前の1件のマーカーが拠点なら、そのcaptured_atまで前へ延伸する（該当が無ければ延伸しない）
                marks.lastOrNull { it.capturedAt < windowStart }?.let { candidate ->
                    val stopId = candidate.stopCardId
                    if (stopId != null && isHub(stopId)) {
                        windowStart = candidate.capturedAt
                    }
                }
                // 終了直後の1件のマーカーが拠点なら、そのcaptured_atまで後ろへ延伸する（該当が無ければ延伸しない）
                marks.firstOrNull { it.capturedAt > windowEnd }?.let { candidate ->
                    val stopId = candidate.stopCardId
                    if (stopId != null && isHub(stopId)) {
                        windowEnd = candidate.capturedAt
                    }
                }
            }

            val windowPoints = gpsPointDao.getBySession(sessionId) // seq順
                .filter { it.tsEpochMs in windowStart..windowEnd }
            if (windowPoints.size < 2) return@withTransaction 0

            var cumulativeM = 0.0
            val routePoints = windowPoints.mapIndexed { index, point ->
                if (index > 0) {
                    cumulativeM += GeoMath.haversineM(
                        windowPoints[index - 1].lat, windowPoints[index - 1].lon,
                        point.lat, point.lon,
                    )
                }
                RoutePointEntity(courseId = courseId, seq = index, lat = point.lat, lon = point.lon, chainageM = cumulativeM)
            }

            routePointDao.deleteAllForCourse(courseId)
            routePointDao.insertAll(routePoints)
            routePoints.size
        }
    }

    // ------------------------------------------------------------------
    // コース創設（トップダウン、3パス成熟モデルのパス1＋パス2、2026-07-14追加・2026-07-15全面改訂・
    // 2026-07-16誤吸着是正）
    //
    // v1「2軸マトリクスで評価して採否」（[CourseStopSource]、廃止）から、「マーカーがあれば停留所は
    // 確実にできる」を実装する3パス成熟モデルへ全面転換（設計ドラフトv2 §0・§1・§3）。
    // パス1: セッションから course_stop の点を悉皆生成（[generatePass1RawStops]）。誤吸着判定・採否は
    //        一切しない。ローレゾ（映像）はこの時点でカード化しない（v1の仮カード作成は廃止）。
    //        2026-07-16改: MANUALイベント由来の点も記録時の誤吸着先（event.stop_card_id）を
    //        引き継がず、frame由来点と同じく `cardId=null` で起こす（実機セッション#17で判明した
    //        非対称な実装を是正）。
    // パス2: パス1の各点の**真の位置**（frame座標優先、無ければevent座標）からコリドー内のハイレゾ
    //        （カード）を吸着（[attachPass2Cards]）。記録時の吸着結果に一切依存しない。
    // 拠点分割は既存の [com.istech.buscourse.ui.splitByHubs] と同じアルゴリズムを
    // [splitCourseCreationStops] に流用（型がcard-onlyの点を扱えないため、この関数自体は新規実装。
    // クラスKDoc参照）。既存コース・既存カードは一切変更しない（新規追加のみ）。
    // ------------------------------------------------------------------

    /** パス1〜パス2の中間表現（DBには未書き込みの1点）。[CourseCreationStopPreview]のクラスKDoc参照。 */
    private data class DraftCourseStop(
        val capturedAt: Long,
        val frameId: Long?,
        val cardId: Long?,
        val eventId: Long?,
        val latitude: Double,
        val longitude: Double,
    )

    /**
     * `course_stop` の位置解決（[CourseStopEntity]のKDoc「位置解決の順序」参照、2026-07-16実装）。
     * 位置 = **coalesce(frame座標, event座標, card座標)**。3引数とも省略可能で、非nullの座標ペアを
     * 優先順位どおりに先頭から採用する（片方だけ非nullの座標ペアは無視する。呼び出し側は必ず
     * lat/lonをセットで渡すこと）。全て未指定/不完全なら null。
     *
     * 優先順位の理由:
     * - **frame座標が最優先**: 実際に撮影した静止画に紐づく実測値そのもの。手動マーク操作が
     *   成功した証跡でもある。
     * - **event座標が次点**: `stop_visit_event.lat/lon` はボタン押下瞬間の実測GPS fixで、frameが
     *   無い（カメラ故障等、セッション#17実例）場合でも押下時の正確な位置が残っている。
     * - **card座標は最後の砦**: `bus_stop_card` の座標は「記録時に（誤って）割り当てられたカードの
     *   位置」に過ぎず誤吸着の影響を直接受ける（セッション#17は24件中21件・300m〜3.3kmのズレ）ため、
     *   実測座標（frame/event）が無い場合に限り使う。
     *
     * [generatePass1RawStops] が生成する点は必ずframe座標かevent座標のどちらかを持つ（両方とも
     * 無い素材はそもそも一次素材に採用しない、同メソッドの絞り込み条件参照）ため、このパイプライン
     * 内でcard座標へフォールバックすることは実質無い。それでも将来カードのみの点（パス3「＋停留所を
     * 追加」、設計ドラフト§5）を本関数に通す可能性に備え、3段のcoalesceとして実装しておく。
     *
     * テストから直接呼べるよう `internal` にしている（[splitCourseCreationStops] と同じ理由）。
     */
    internal fun resolveStopPosition(
        frameLatitude: Double? = null, frameLongitude: Double? = null,
        eventLatitude: Double? = null, eventLongitude: Double? = null,
        cardLatitude: Double? = null, cardLongitude: Double? = null,
    ): Pair<Double, Double>? = when {
        frameLatitude != null && frameLongitude != null -> frameLatitude to frameLongitude
        eventLatitude != null && eventLongitude != null -> eventLatitude to eventLongitude
        cardLatitude != null && cardLongitude != null -> cardLatitude to cardLongitude
        else -> null
    }

    /**
     * パス1（悉皆生成、設計ドラフトv2 §3）。一次素材は次の2つの**和集合**（§3パス1「重要」、
     * 2026-07-15セッション#17の実例から確定）：
     * 1. マーカー付きLORESフレーム（`timelapse_frame.kind='LORES'` かつ `stop_card_id IS NOT NULL`）
     *    → `frameId` のみの点（`cardId=null`, `eventId=null`。パス1はローレゾをカード化しない）。
     * 2. `stop_visit_event`（`trigger_type='MANUAL'`）→ 対応するLORESフレームが無い場合（カメラが
     *    動かなかったセッション）でも `eventId` のみの点（`frameId=null`, `cardId=null`）として起こす。
     *    セッション#17（77分・20.6km、映像0枚・MANUALイベント24件）はこの経路が無いと丸ごと
     *    失われる（設計ドラフト§10.1）。
     *
     * **2026-07-16改（誤吸着の是正）**: `stop_visit_event.stop_card_id` は記録時に `onManualStopMark`
     * が距離を問わず最近傍カードへ仮吸着した結果であり、実機セッション#17では24件中21件が
     * 300m〜3.3kmの誤吸着だった。以前は `cardId = event.stopCardId` としてこれをそのまま
     * `course_stop.stop_card_id` に引き継いでいたが、frame由来点（`timelapse_frame.stop_card_id`も
     * 無視して `cardId=null` で起こす）と扱いが逆転していた不整合だった。本改訂で
     * event由来点も `cardId=null, eventId=event.id` として起こし、**記録時の誤吸着を無視**する
     * よう frame由来点と揃える。カードの判定は一律パス2に委ねる。位置は `stop_visit_event.lat/lon`
     * （押下瞬間の正しい実測GPS fix）をそのまま使う（[resolveStopPosition]参照）。
     *
     * 重複防止: 2つの素材が同じ停留所を指す場合（`onManualStopMark` は同一ハンドラ内で
     * `stop_visit_event` 記録→直後に `findClosestLoresFrameId(before=true)` でLORESへのマーカー
     * 付与を試みるため、対応する場合は両者の時刻がほぼ同時刻・同一 `stop_card_id` になる）は、
     * マーカー付きLORESフレームを優先し、対応するMANUALイベントからは重ねて起こさない。
     * 「同一card_id・時刻が[MANUAL_EVENT_FRAME_MATCH_WINDOW_MS]以内」を対応判定に使う（時刻での
     * 対応付け。厳密な1:1ペアリングではなく簡易ヒューリスティックだが、通知ボタンのデバウンス
     * （連打防止）もあり実運用では十分に妥当、設計ドラフト§3パス1参照）。この重複判定自体は
     * 「同じ停留所を指しているか」の照合であり、`event.stop_card_id` を `course_stop` に書き込む
     * わけではないため、上記の「誤吸着を引き継がない」方針とは独立している。
     *
     * 順序はセッションの時系列（撮影時刻／イベント時刻）＝一筆書き。
     */
    private suspend fun generatePass1RawStops(sessionId: Long): List<DraftCourseStop> {
        val markedLoresFrames = timelapseFrameDao.getMarkedFrames(sessionId)
            .filter { it.kind == FrameKind.LORES.name && it.latitude != null && it.longitude != null }

        val manualEvents = stopVisitEventDao.getBySession(sessionId)
            .filter { it.triggerType == StopVisitTriggerType.MANUAL.name && it.lat != null && it.lon != null }

        // 対応するマーカー付きLORESフレームが既にあるMANUALイベントは重複として除外する
        val orphanEvents = manualEvents.filter { event ->
            markedLoresFrames.none { frame ->
                frame.stopCardId == event.stopCardId &&
                    kotlin.math.abs(frame.capturedAt - event.eventTs) <= MANUAL_EVENT_FRAME_MATCH_WINDOW_MS
            }
        }

        val framePoints = markedLoresFrames.map { frame ->
            val (lat, lon) = requireNotNull(
                resolveStopPosition(frameLatitude = frame.latitude, frameLongitude = frame.longitude)
            ) { "getMarkedFramesはlatitude!=null/longitude!=nullで絞っているはず" }
            DraftCourseStop(
                capturedAt = frame.capturedAt,
                frameId = frame.id,
                cardId = null, // 記録時のframe自身のstop_card_idは無視する。カードの判定はパス2に委ねる
                eventId = null,
                latitude = lat,
                longitude = lon,
            )
        }
        val eventPoints = orphanEvents.map { event ->
            val (lat, lon) = requireNotNull(
                resolveStopPosition(eventLatitude = event.lat, eventLongitude = event.lon)
            ) { "manualEventsはlat!=null/lon!=nullで絞っているはず" }
            DraftCourseStop(
                capturedAt = event.eventTs,
                frameId = null,
                cardId = null, // 記録時の誤吸着（event.stopCardId）は無視する。frame由来点と扱いを揃える
                eventId = event.id,
                latitude = lat,
                longitude = lon,
            )
        }

        return (framePoints + eventPoints).sortedBy { it.capturedAt }
    }

    /**
     * パス2（吸着・昇格、設計ドラフトv2 §3）。パス1の各点について、**その点の真の位置**
     * （[DraftCourseStop.latitude]/[DraftCourseStop.longitude]。frame座標があればそれ、無ければ
     * event座標。[resolveStopPosition]参照）から軌跡コリドー内（[findNearbyCardsForCorridor]、
     * 半径は通常/拠点で変数。拠点は広く判定）にある既存カードを探して `cardId` を埋める。
     *
     * **2026-07-16改**: パス1が誤吸着（記録時の `stop_card_id`）を一切引き継がなくなったため
     * （[generatePass1RawStops]参照）、パス2に渡る全点は常に `cardId=null` で入ってくる。
     * よって「既にcardIdがある点は素通りする」特別扱いは不要になった（旧実装にあった
     * `if (point.cardId != null) return point` は削除）。パス2は常に**真の位置から**コリドー内の
     * カードを探し直すため、記録時の吸着結果に一切依存しない＝誤吸着は自己修正される
     * （実機セッション#17は24件中21件が誤吸着だったが、真の位置から探し直せば正しいカードが
     * 既に登録されていれば正しく吸着できる）。
     *
     * 候補が1枚だけなら自動で吸着（昇格）。複数ある場合も**最も近い1枚を吸着**する
     * （[nearbyCardsWithinCorridor] が距離順に返す先頭を採用）。1:N候補の一覧そのものはDBに保存しない
     * （[findNearbyCardsForCorridor]のKDoc「公開関数にしている理由」参照。編集画面がフレーム/イベント
     * 座標から都度再計算できるため、先回りして保存する必要が無い）。コリドー内に候補が無ければ
     * `cardId` はnullのまま（＝映像／イベントはあるがカードが無い点。表示名は `previewCourseCreation`
     * が `S{sessionId}-{通番}` をその場で導出する）。
     */
    private suspend fun attachPass2Cards(points: List<DraftCourseStop>): List<DraftCourseStop> {
        // セッション内の全点で使い回すため、アクティブカード一覧はここで1回だけ取得する
        // （[nearbyCardsWithinCorridor]参照。点数ぶんクエリを繰り返さない）。
        val activeCards = busStopCardDao.getAllActive()
        return points.map { point ->
            val nearest = nearbyCardsWithinCorridor(point.latitude, point.longitude, activeCards).firstOrNull()
            if (nearest == null) point else point.copy(cardId = nearest.cardId)
        }
    }

    /** パス1＋パス2をまとめて実行する（[previewCourseCreation]・[createCoursesFromSession]で共用）。 */
    private suspend fun buildPass1Pass2Stops(sessionId: Long): List<DraftCourseStop> =
        attachPass2Cards(generatePass1RawStops(sessionId))

    /** [DraftCourseStop] のリストを表示用の [CourseCreationStopPreview] へ変換する（カード名解決込み）。 */
    private suspend fun resolvePreviewStops(sessionId: Long, draftStops: List<DraftCourseStop>): List<CourseCreationStopPreview> {
        val cardCache = mutableMapOf<Long, BusStopCardEntity?>()
        suspend fun cardFor(id: Long) = cardCache.getOrPut(id) { busStopCardDao.getById(id) }
        // パス2でカードが吸着しなかった点（映像のみ／イベントのみ）の仮表示名の通番。
        // 2026-07-16改: event由来点もパス1ではcardId=nullのため、この通番の対象はもはや
        // 「frameのみ」に限らない（変数名を実態に合わせて改名）。
        var cardlessSeq = 1
        return draftStops.map { p ->
            val card = p.cardId?.let { cardFor(it) }
            val displayName = when {
                card != null -> card.name
                p.cardId != null -> "停留所#${p.cardId}" // カード取得失敗（アーカイブ済み等）の保険
                else -> "S$sessionId-${"%03d".format(cardlessSeq++)}" // カード未吸着の点。DBには保存しない仮の表示名
            }
            CourseCreationStopPreview(
                frameId = p.frameId,
                cardId = p.cardId,
                eventId = p.eventId,
                displayName = displayName,
                capturedAt = p.capturedAt,
                latitude = p.latitude,
                longitude = p.longitude,
                isHubCandidate = card?.isHub == true,
            )
        }
    }

    /**
     * コース創設プレビュー（パス1＋パス2、読み取り専用）。UI（[com.istech.buscourse.ui.CourseCreateScreen]）
     * が拠点選択・断片プレビュー・コース名入力を表示するための、時系列順・拠点分割前の全点。
     * 拠点分割は [splitCourseCreationStops] にこの返り値と選択拠点集合を渡してUI側（純Kotlin、
     * DBアクセス無し）で行う想定（拠点選択のたびに読み取り専用の重い解析をやり直さないため）。
     */
    suspend fun previewCourseCreation(sessionId: Long): List<CourseCreationStopPreview> = withContext(Dispatchers.IO) {
        resolvePreviewStops(sessionId, buildPass1Pass2Stops(sessionId))
    }

    /**
     * パス1＋パス2で確定した点列を `course_stop` へ直接書き込む。[setCourseStops] は「必ずカードのみの
     * 点として作る」契約（同メソッドのKDoc参照）のため frame_id・event_id を書けず使えない。
     * [requireCoordinateSource] で frame_id/event_id/stop_card_id の少なくとも一つが非nullである
     * 不変条件をここでも担保する（2026-07-16、event_id追加で2択から3択に拡張）。
     */
    private suspend fun insertCourseStopsFromPreview(courseId: Long, stops: List<CourseCreationStopPreview>) {
        if (stops.isEmpty()) return
        courseStopDao.insertAll(
            stops.mapIndexed { index, stop ->
                requireCoordinateSource(
                    stopCardId = stop.cardId,
                    frameId = stop.frameId,
                    eventId = stop.eventId,
                    context = "courseId=$courseId, index=$index（コース創設）",
                )
                CourseStopEntity(
                    courseId = courseId,
                    stopCardId = stop.cardId,
                    frameId = stop.frameId,
                    eventId = stop.eventId,
                    sequenceIndex = index,
                    expectedChainageM = null, // 成熟（regenerateCourseSegments）後にRoutePreprocessorが再計算
                )
            }
        )
    }

    /**
     * S8「再創設ガード」（読み取り専用、設計ドラフトv2 §9、2026-07-18追加）。指定セッションから
     * 既に創設済みのコースを検出する。[createCoursesFromSession] は意図的に非冪等（同メソッドのKDoc
     * 「冪等ではない」参照）であり、同じセッションから2回創設すると重複コースができてしまう
     * （実データ実例: セッション#8からS8-1/S8-2/S8-3が2組、course id 6/7/8 と 9/10/11）。
     *
     * オーナー確定の設計方針（2026-07-18）: この検出結果は**作成をブロックしない**。作り直したい
     * 正当なケース（上記の#9/#10/#11がまさにそれ）を塞がないため、UI
     * （[com.istech.buscourse.ui.CourseCreateScreen]）が創設前に見せる警告バナー用の「読み取り専用の
     * 事前確認」としてのみ使う。上書き・マージ（重複コースの統合）はフェーズ4スコープ
     * （設計ドラフトv2 §9「思いつき9」）のため本メソッドの対象外。
     */
    suspend fun findExistingCoursesFromSession(sessionId: Long): List<CourseEntity> =
        courseDao.getBySourceSession(sessionId)

    /**
     * コース創設（トップダウン、パス1＋パス2、2026-07-15全面改訂）。[sessionId] からパス1（悉皆生成）
     * →パス2（吸着・昇格）で点列を作り、[hubStopCardIds] で拠点分割（[splitCourseCreationStops]）して
     * 断片ごとに1コースを作る。[courseNames] は断片indexに対応するコース名（[courseNames]が短い・
     * 空文字の断片は既定名 `"S{sessionId}-{断片番号}"`）。
     *
     * 断片ごとに: [createCourse] → [insertCourseStopsFromPreview]（course_stop直挿し）→
     * [regenerateCourseSegments]（カードを持つ隣接ペアだけ区間を作る。同メソッドのKDoc参照）→
     * [confirmCourseRouteFromSession]（route_point生成。同メソッドのKDoc参照、カード無し点も安全）。
     *
     * **冪等ではない**：呼ぶたびに新しいコースを作成する（創設＝新規作成という操作の意味論上、
     * 意図的に非冪等）。二重生成ガード（S8、2026-07-18実装）は[findExistingCoursesFromSession]
     * による読み取り専用の事前検出＋UI側の警告バナーのみで、このメソッド自体には手を加えない
     * （オーナー確定：ブロックせず警告に留める。作り直したい正当なケースを塞がないため）。
     * パス1はbus_stop_cardを一切作らない（[CourseCreationStopPreview]のクラスKDoc参照）ため、
     * 既存コース・既存カードは一切変更しない（新規追加のみ）。
     */
    suspend fun createCoursesFromSession(
        sessionId: Long,
        hubStopCardIds: Set<Long>,
        courseNames: List<String> = emptyList(),
        kind: CourseKind = CourseKind.STANDARD,
    ): CourseCreationResult = withContext(Dispatchers.IO) {
        val previewStops = resolvePreviewStops(sessionId, buildPass1Pass2Stops(sessionId))
        val fragments = splitCourseCreationStops(previewStops, hubStopCardIds)

        val createdCourseIds = mutableListOf<Long>()
        var totalStopCount = 0
        var cardAttachedStopCount = 0
        var frameOnlyStopCount = 0
        for ((index, fragment) in fragments.withIndex()) {
            if (fragment.stops.isEmpty()) continue
            val name = courseNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "S$sessionId-${index + 1}"
            val courseId = createCourse(name, kind)
            insertCourseStopsFromPreview(courseId, fragment.stops)
            regenerateCourseSegments(courseId)
            confirmCourseRouteFromSession(courseId, sessionId)
            createdCourseIds += courseId
            totalStopCount += fragment.stops.size
            cardAttachedStopCount += fragment.stops.count { it.cardId != null }
            frameOnlyStopCount += fragment.stops.count { it.cardId == null }
        }

        CourseCreationResult(
            createdCourseIds = createdCourseIds,
            totalStopCount = totalStopCount,
            cardAttachedStopCount = cardAttachedStopCount,
            frameOnlyStopCount = frameOnlyStopCount,
        )
    }

    // ------------------------------------------------------------------
    // パス3: 停車推定の示唆（トップダウン創設 S3、設計ドラフトv2 §3「パス3」・§4.2（軌跡コリドー）・
    // §6（速度ヒート地図）・§10.1、2026-07-18追加、読み取り専用）
    //
    // マーカー（パス1）が無くても、gps_pointの車速から「停車していそうな点」を検出する。ただし
    // 1セッションだけでは停留所・信号待ち・（園児欠席等で）減速しただけの通過かを断定できないため、
    // course_stopには一切書き込まず、[StopEstimate] という「示唆」だけを返す（採否＝格上げは人が
    // 後で行う、UIは後続S4）。パス1で既に点になっている座標の近傍は二重提示しないよう除外する。
    // ------------------------------------------------------------------

    /**
     * 停車推定の解析（トップダウン創設 S3・パス3、読み取り専用）。[sessionId] の `gps_point` を
     * 時系列（seq順＝`ts_epoch_ms`順）に走査し、次の手順で停車推定の示唆リストを作る。
     *
     * 1. **低速クラスタ抽出**: 速度が [DWELL_SPEED_MPS] 未満の点が連続する区間をひとまとめにする。
     *    速度未計測（`speedMps=null`）の点は「低速」と断定できないため非低速として扱い、クラスタを
     *    断ち切る（未知を停車と誤認しない安全側の判断）。連続の判定は隣接するGPS点そのものであり、
     *    [analyzeCourseCoverage] 等の `COVERAGE_PASS_GAP_MS`（半径内の点を時間間隔で別パスに分割）
     *    のような時間ギャップ判定は行わない（設計ドラフト§3パス3の記述どおり「速度が…連続する区間」
     *    をそのまま実装した。低速点が疎らな場合に離れた2つの停車が誤って1クラスタに繋がる懸念は
     *    残るため、実データで問題が出れば時間ギャップ判定の追加を検討する）。
     * 2. クラスタの滞在秒数（末尾ts − 先頭ts）が [DWELL_MIN_SEC] 以上のものを候補とする（信号待ち等の
     *    短い減速を拾いすぎないための足切り）。
     * 3. 候補クラスタの代表座標（クラスタ内GPS点の緯度・経度の単純平均＝重心）と滞在秒数から
     *    [StopEstimate] を作る。
     * 4. **パス1で確定済みの点との重複除外**: [generatePass1RawStops] が返す点（マーカー付きLORES
     *    フレーム／MANUALイベント由来、いずれも実測座標）の近傍 [STOP_ESTIMATE_EXCLUSION_RADIUS_M]
     *    以内にあるクラスタは、既に停留所として確定済みとみなし示唆から除外する（二重提示しない、
     *    設計ドラフト§3パス3）。パス1の点はカード（`card_id`）を一切引き継がない設計
     *    （[generatePass1RawStops]のKDoc参照）のため、[findOrCreateRadiusFor] のような拠点/通常の
     *    半径使い分けはできず、単一の通常半径のみを使う。
     *
     * 空セッション・低速クラスタなしの場合は空リストを返す（例外を投げない）。
     */
    suspend fun analyzeStopEstimates(sessionId: Long): List<StopEstimate> = withContext(Dispatchers.IO) {
        val points = gpsPointDao.getBySession(sessionId) // seq順（≒ts_epoch_ms順）

        val clusters = mutableListOf<List<GpsPointEntity>>()
        var current = mutableListOf<GpsPointEntity>()
        for (point in points) {
            val isSlow = point.speedMps != null && point.speedMps < DWELL_SPEED_MPS
            if (isSlow) {
                current += point
            } else if (current.isNotEmpty()) {
                clusters += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) clusters += current

        // 除外判定に使う「パス1で既に点になっている座標」。パス1自体は書き込みを伴わない純粋な
        // 素材収集のため、ここで呼んでも副作用は無い（[createCoursesFromSession]と同じ関数を再利用）。
        val pass1Points = generatePass1RawStops(sessionId)

        clusters.mapNotNull { cluster ->
            val dwellSec = (cluster.last().tsEpochMs - cluster.first().tsEpochMs) / 1000.0
            if (dwellSec < DWELL_MIN_SEC) return@mapNotNull null

            val latitude = cluster.map { it.lat }.average()
            val longitude = cluster.map { it.lon }.average()

            val alreadyConfirmed = pass1Points.any {
                GeoMath.haversineM(latitude, longitude, it.latitude, it.longitude) <= STOP_ESTIMATE_EXCLUSION_RADIUS_M
            }
            if (alreadyConfirmed) return@mapNotNull null

            StopEstimate(
                latitude = latitude,
                longitude = longitude,
                dwellSec = dwellSec,
                startAt = cluster.first().tsEpochMs,
                endAt = cluster.last().tsEpochMs,
            )
        }
    }

    /**
     * 完了済みセッションから停留所間の区間を切り出して `segment_track` へUPSERTする（設計書§3.9）。
     *
     * 訪問停留所列は §3.9 疑似コードの引数 `visitedStopsInOrder` を、そのセッションの
     * `stop_visit_event`（event_type=ARRIVED、event_ts順。連続重複は除去）から機械的に復元する
     * （2026-07-09オーナー確定の実装方針。ARRIVED イベントはフェーズ1記録エンジンの
     * ハイブリッド検知＝AUTO/MANUAL の両方を含む）。各停留所の到着インデックスは、
     * `event_ts` と `gps_point.ts_epoch_ms` という独立した壁時計列同士の突き合わせ（NTP補正等の
     * 不連続に弱い）ではなく、§3.9本来のジオフェンス方式どおり、対象停留所座標への haversine
     * 距離走査（半径内に最初に入った点、無ければ最も近い点）で求める（フェーズ2レビュー#3）。
     * `event_ts` はあくまで「訪問順序」（`visited` の並び）の決定にのみ用いる。
     *
     * 同一有向ペアが1セッション内で複数回走行された場合は後勝ち（最後の1本をUPSERT。
     * 候補経路比較UIはフェーズ2では扱わない、2026-07-09オーナー確定）。
     */
    suspend fun extractSegmentsFromSession(sessionId: Long): SegmentExtractionResult = withContext(Dispatchers.IO) {
        val session = recordingSessionDao.getById(sessionId)
            ?: throw IllegalArgumentException("セッションが見つかりません: id=$sessionId")
        check(session.status == RecordingSessionStatus.COMPLETED.name) {
            "完了済み（COMPLETED）セッションのみ抽出できます（現在: ${session.status}）"
        }
        // UI側（ExtractionScreenの一覧）フィルタとは別に、リポジトリ層の不変条件としても
        // 抽出可能なセッション種別を強制する（設計書§3.9、フェーズ2レビュー#2）。
        check(session.type in EXTRACTABLE_SESSION_TYPES) {
            "抽出可能なセッション種別ではありません（現在: ${session.type}）"
        }

        // 到着イベント（時系列順）→ 訪問停留所列。連続する同一停留所は1回とみなす
        val arrivals = stopVisitEventDao.getBySession(sessionId)
            .filter { it.eventType == StopVisitEventType.ARRIVED.name }
            .sortedBy { it.eventTs }
        val visited = mutableListOf<Pair<Long, Long>>() // (stopCardId, eventTs)
        for (ev in arrivals) {
            if (visited.isEmpty() || visited.last().first != ev.stopCardId) {
                visited += ev.stopCardId to ev.eventTs
            }
        }
        check(visited.size >= 2) {
            "区間を構成できる到着イベント（ARRIVED）が2停留所分ありません。再試走または停留所マーキングの見直しが必要"
        }

        val points = gpsPointDao.getBySession(sessionId) // seq順（D4で一括インポート済み）
        check(points.isNotEmpty()) { "このセッションにはGPS点列（gps_point）がありません" }

        // 各到着に対応するGPS点インデックスは、対象停留所座標へのhaversine距離走査（ジオフェンス、
        // 設計書§3.9本来の方式）で求める。stop_visit_event.event_ts と gps_point.ts_epoch_ms は
        // 独立した壁時計列で、NTP補正等の不連続に弱いため、event_ts は「訪問順序」（visited の並び）
        // の決定にのみ使い、区間内の正確な到着点特定には使わない（走査本体は[computeArrivalIndices]、
        // [extractSegmentsForCourse]と共用、2026-07-10）。
        val (arrivalIdx, unmatchedStops) = computeArrivalIndices(visited.map { it.first }, points)

        var extracted = 0
        var skipped = 0
        val affectedEdges = mutableListOf<Pair<Long, Long>>()
        val skippedPairs = mutableListOf<Pair<String, String>>()
        for (i in 0 until visited.size - 1) {
            val fromId = visited[i].first
            val toId = visited[i + 1].first
            val slice = points.subList(arrivalIdx[i], arrivalIdx[i + 1] + 1)
            if (slice.size < 2) {
                Log.w(TAG, "GPS点が2点未満のため区間をスキップします: $fromId -> $toId")
                skipped++
                skippedPairs += stopDisplayName(fromId) to stopDisplayName(toId)
                continue
            }
            upsertSegmentTrackFromPoints(
                fromStopCardId = fromId,
                toStopCardId = toId,
                points = slice.map { GpxPoint(lat = it.lat, lon = it.lon, eleM = it.altM, timeEpochMs = it.tsEpochMs) },
                distanceM = polylineLengthM(slice),
                durationSec = (slice.last().elapsedRealtimeNanos - slice.first().elapsedRealtimeNanos) / 1_000_000_000,
                recordedSessionId = sessionId,
            )
            affectedEdges += fromId to toId
            extracted++
        }

        // このセッションで確定した区間を参照している全コースの course_segment / route_point を再評価（§3.9）
        val affectedCourses = affectedEdges
            .flatMap { (from, to) -> courseSegmentDao.getCourseIdsReferencingEdge(from, to) }
            .toSortedSet()
        affectedCourses.forEach { regenerateCourseSegments(it) }

        SegmentExtractionResult(
            extractedSegmentCount = extracted,
            affectedCourseCount = affectedCourses.size,
            skippedPairCount = skipped,
            skippedPairs = skippedPairs,
            unmatchedStops = unmatchedStops,
        )
    }

    /**
     * 順序付き停留所列に対して、GPS点列上の到着インデックスをジオフェンス走査（§3.9本来の方式、
     * 対象停留所座標へのhaversine距離走査）で求める。半径内に最初に入った点、無ければ最も近い点に
     * フォールバックする。単調増加になるよう、各探索は直前の到着インデックス以降に限定する。
     * [extractSegmentsFromSession]（イベント起点の訪問列）と[extractSegmentsForCourse]
     * （コース定義起点の順列、2026-07-10追加、P0-1）の両方から使う共通ロジック。
     */
    private suspend fun computeArrivalIndices(
        orderedStopCardIds: List<Long>,
        points: List<GpsPointEntity>,
        radiusM: Double = StopMaster.DEFAULT_ARRIVAL_RADIUS_M,
    ): Pair<IntArray, List<UnmatchedStop>> {
        val arrivalIdx = IntArray(orderedStopCardIds.size)
        val unmatched = mutableListOf<UnmatchedStop>()
        var prevIdx = 0
        for (i in orderedStopCardIds.indices) {
            val card = busStopCardDao.getById(orderedStopCardIds[i])
            val displayName = card?.name ?: "ID:${orderedStopCardIds[i]}"
            val searchSpace = points.subList(prevIdx, points.size)
            val idx = if (card == null || searchSpace.isEmpty()) {
                unmatched += UnmatchedStop(displayName, Double.NaN)
                prevIdx
            } else {
                val withinRadius = searchSpace.indexOfFirst {
                    GeoMath.haversineM(it.lat, it.lon, card.latitude, card.longitude) <= radiusM
                }
                if (withinRadius >= 0) {
                    prevIdx + withinRadius
                } else {
                    val nearestRelIdx = searchSpace.indices.minByOrNull {
                        GeoMath.haversineM(searchSpace[it].lat, searchSpace[it].lon, card.latitude, card.longitude)
                    } ?: 0
                    val nearestDistanceM = GeoMath.haversineM(
                        searchSpace[nearestRelIdx].lat, searchSpace[nearestRelIdx].lon, card.latitude, card.longitude,
                    )
                    unmatched += UnmatchedStop(displayName, nearestDistanceM)
                    prevIdx + nearestRelIdx
                }
            }
            arrivalIdx[i] = idx
            prevIdx = idx
        }
        return arrivalIdx to unmatched
    }

    /** カードIDからの表示名解決（未存在なら "ID:{id}"）。skippedPairsの診断表示用。 */
    private suspend fun stopDisplayName(id: Long): String = busStopCardDao.getById(id)?.name ?: "ID:$id"

    /**
     * コースを指定した遡及区間抽出（設計書§3.9の拡張、2026-07-10追加、P0-1）。
     *
     * [extractSegmentsFromSession] は `stop_visit_event`（ARRIVED イベント）に依存するため、
     * 記録開始時点で当該停留所カードが未登録・未マーキングだった場合はイベントが得られず抽出できない
     * （2026-07-10実車テスト第2回で発生・確認）。本関数はイベントを一切使わず、[courseId] の
     * 停留所順列（`course_stop.sequence_index` 順）と [sessionId] のGPS点列だけを
     * ジオフェンス走査（[computeArrivalIndices]）で突き合わせて区間を復元する。欠席等で停車せず
     * 通過しただけの停留所も、走行経路が半径圏内を通っていれば抽出できる（停車の有無は判定に無関係）。
     */
    suspend fun extractSegmentsForCourse(
        courseId: Long,
        sessionId: Long,
        radiusM: Double = StopMaster.DEFAULT_ARRIVAL_RADIUS_M,
    ): SegmentExtractionResult = withContext(Dispatchers.IO) {
        val session = recordingSessionDao.getById(sessionId)
            ?: throw IllegalArgumentException("セッションが見つかりません: id=$sessionId")
        check(session.status == RecordingSessionStatus.COMPLETED.name) {
            "完了済み（COMPLETED）セッションのみ抽出できます（現在: ${session.status}）"
        }
        check(session.type in EXTRACTABLE_SESSION_TYPES) {
            "抽出可能なセッション種別ではありません（現在: ${session.type}）"
        }

        // 区間抽出（ジオフェンス走査）はカードのみの点を前提とする現状のスコープのため、
        // regenerateCourseSegmentsと同様にrequireStopCardId（非null実質保証）で明示する
        val orderedStopCardIds = courseStopDao.getOrderedStops(courseId).map { it.requireStopCardId }
        check(orderedStopCardIds.size >= 2) { "このコースには2つ以上の停留所が必要です" }

        val points = gpsPointDao.getBySession(sessionId)
        check(points.isNotEmpty()) { "このセッションにはGPS点列（gps_point）がありません" }

        val (arrivalIdx, unmatchedStops) = computeArrivalIndices(orderedStopCardIds, points, radiusM)

        var extracted = 0
        var skipped = 0
        val affectedEdges = mutableListOf<Pair<Long, Long>>()
        val skippedPairs = mutableListOf<Pair<String, String>>()
        for (i in 0 until orderedStopCardIds.size - 1) {
            val fromId = orderedStopCardIds[i]
            val toId = orderedStopCardIds[i + 1]
            val slice = points.subList(arrivalIdx[i], arrivalIdx[i + 1] + 1)
            if (slice.size < 2) {
                Log.w(TAG, "GPS点が2点未満のため区間をスキップします: $fromId -> $toId")
                skipped++
                skippedPairs += stopDisplayName(fromId) to stopDisplayName(toId)
                continue
            }
            upsertSegmentTrackFromPoints(
                fromStopCardId = fromId,
                toStopCardId = toId,
                points = slice.map { GpxPoint(lat = it.lat, lon = it.lon, eleM = it.altM, timeEpochMs = it.tsEpochMs) },
                distanceM = polylineLengthM(slice),
                durationSec = (slice.last().elapsedRealtimeNanos - slice.first().elapsedRealtimeNanos) / 1_000_000_000,
                recordedSessionId = sessionId,
            )
            affectedEdges += fromId to toId
            extracted++
        }

        // 対象コース自身は必ず再評価する（course_segment が未確定＝PENDINGのみだった場合、
        // getCourseIdsReferencingEdge に引っかからない可能性があるための安全策）
        val affectedCourses = (affectedEdges
            .flatMap { (from, to) -> courseSegmentDao.getCourseIdsReferencingEdge(from, to) } + courseId)
            .toSortedSet()
        affectedCourses.forEach { regenerateCourseSegments(it) }

        SegmentExtractionResult(
            extractedSegmentCount = extracted,
            affectedCourseCount = affectedCourses.size,
            skippedPairCount = skipped,
            skippedPairs = skippedPairs,
            unmatchedStops = unmatchedStops,
        )
    }

    /**
     * 点列を `segments/{from}_{to}.gpx` へ書き出し、`segment_track` へUPSERTする（§3.9 `writeSegmentGpx` 相当）。
     *
     * 注: `SegmentTrackDao.upsert`（`@Upsert`）の競合判定は主キーのみで、UNIQUE(from, to) 違反は
     * 解決しない。そのため既存の有向ペア行があればそのIDを引き継いだエンティティでUPSERTし、
     * 「有向ペアにつき常に最新の1本」（§3.5）を保つ。
     */
    private suspend fun upsertSegmentTrackFromPoints(
        fromStopCardId: Long,
        toStopCardId: Long,
        points: List<GpxPoint>,
        distanceM: Double,
        durationSec: Long,
        recordedSessionId: Long?,
    ): SegmentTrackEntity {
        val relPath = "${BusCourseStorage.DIR_SEGMENTS}/${fromStopCardId}_${toStopCardId}.gpx"
        val file = BusCourseStorage.resolve(context, relPath)
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            GpxCodec.writeSegmentTrack(out, fromStopCardId, toStopCardId, points)
        }

        // findByDirectedEdge → upsert の間にトランザクションが無いと、同一有向ペアへの並行呼び出しで
        // 後着側の @Upsert が UNIQUE(from_stop_card_id, to_stop_card_id) 違反から主キー0のUPDATEに
        // フォールバックし0行ヒットで静かに消える競合状態になる（フェーズ2レビュー#4）。
        // 読み取りから書き込みまでを1トランザクションに直列化して防ぐ
        return database.withTransaction {
            val existing = segmentTrackDao.findByDirectedEdge(fromStopCardId, toStopCardId)
            val entity = SegmentTrackEntity(
                id = existing?.id ?: 0,
                fromStopCardId = fromStopCardId,
                toStopCardId = toStopCardId,
                trackFileRelPath = relPath,
                distanceM = distanceM,
                durationSec = durationSec,
                pointCount = points.size,
                isInterpolated = false,
                recordedSessionId = recordedSessionId,
                recordedAt = System.currentTimeMillis(),
            )
            val rowId = segmentTrackDao.upsert(entity)
            if (entity.id != 0L) entity else entity.copy(id = rowId)
        }
    }

    private fun polylineLengthM(points: List<GpsPointEntity>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += GeoMath.haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        return total
    }

    // ------------------------------------------------------------------
    // GPXエクスポート/インポート（§3.11.3）
    // ------------------------------------------------------------------

    /**
     * コース全体を `exports/{courseId}_{yyyyMMdd_HHmmss}.gpx` へ書き出す（§3.3・§3.11.3 `exportCourse`）。
     * 停留所（wpt）・順列（rte）・CONFIRMED区間の実測軌跡（trkseg）を1ファイルに含める（§3.11.1）。
     * 共有はユーザー操作の SAF（ACTION_CREATE_DOCUMENT）経由のみ（[copyExportToUri]）。
     */
    suspend fun exportCourse(courseId: Long): File = withContext(Dispatchers.IO) {
        val details = courseDao.getWithDetails(courseId)
            ?: throw IllegalArgumentException("コースが見つかりません: id=$courseId")
        val orderedStops = details.stops.sortedBy { it.courseStop.sequenceIndex }
        val orderedSegments = details.segments.sortedBy { it.sequenceIndex }

        val waypoints = orderedStops.map { stopWithCard ->
            val card = stopWithCard.requireCard
            GpxWaypoint(
                lat = card.latitude,
                lon = card.longitude,
                eleM = card.altitudeM,
                name = card.name,
                desc = card.notes,
                stopCardId = card.id,
                photoRef = "${BusCourseStorage.DIR_STOPCARDS}/${card.id}/${BusCourseStorage.FILE_STOPCARD_PHOTO_ORIG}",
            )
        }
        val segmentExports = orderedSegments.mapNotNull { seg ->
            if (seg.status != CourseSegmentStatus.CONFIRMED.name || seg.segmentTrackId == null) return@mapNotNull null
            val track = segmentTrackDao.getById(seg.segmentTrackId) ?: return@mapNotNull null
            val file = BusCourseStorage.resolve(context, track.trackFileRelPath)
            if (!file.exists()) return@mapNotNull null
            val gpxPoints = try {
                GpxCodec.readTrack(file).points
            } catch (e: GpxParseException) {
                Log.w(TAG, "区間GPXを読めないためエクスポートから除外します: ${track.trackFileRelPath}", e)
                return@mapNotNull null
            }
            GpxCourseSegmentExport(
                fromStopCardId = seg.fromStopCardId,
                toStopCardId = seg.toStopCardId,
                status = seg.status,
                points = gpxPoints,
            )
        }

        val now = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val exportsDir = BusCourseStorage.resolve(context, BusCourseStorage.DIR_EXPORTS)
        exportsDir.mkdirs()
        val outFile = File(exportsDir, "${courseId}_$timestamp.gpx")
        outFile.outputStream().buffered().use { out ->
            GpxCodec.writeCourse(
                out,
                GpxCourseExport(
                    courseName = details.course.name,
                    exportedAtEpochMs = now,
                    stops = waypoints,
                    segments = segmentExports,
                ),
            )
        }
        outFile
    }

    /** エクスポート成果物を SAF（ACTION_CREATE_DOCUMENT）で選ばれた [target] へ複製する（§3.11.3）。 */
    suspend fun copyExportToUri(exportFile: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { out ->
            exportFile.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("書き出し先を開けませんでした: $target")
    }

    /**
     * 他アプリ産GPXの区間取り込み（§3.11.3 `importAsSegmentTrack`）。信頼して取り込むのは
     * `<trk><trkseg><trkpt>` のみ（`<wpt>` は自動でカード化しない）。自アプリ発ファイル
     * （`bc:segment` 拡張あり）の場合は、指定の有向ペア (from, to) に一致する trkseg があれば
     * その1区間だけを採用する。無い場合（一般GPX）は全 trkseg の trkpt を連結して1区間とする。
     * 取り込み後、影響するコースの course_segment / route_point を再評価する。
     */
    suspend fun importAsSegmentTrack(gpxFile: Uri, from: Long, to: Long): SegmentTrackEntity =
        withContext(Dispatchers.IO) {
            val document = context.contentResolver.openInputStream(gpxFile)?.buffered()?.use { input ->
                GpxCodec.readDocument(input)
            } ?: throw IOException("GPXファイルを開けませんでした: $gpxFile")

            val allSegments = document.tracks.flatMap { it.segments }
            val matched = allSegments.firstOrNull {
                it.extension?.fromStopCardId == from && it.extension?.toStopCardId == to
            }
            val points = (matched?.points ?: allSegments.flatMap { it.points })
            check(points.size >= 2) { "取り込めるトラック点（trkpt）が2点未満です" }

            // 時刻情報があれば所要秒を採用（外部ログにはelapsedRealtimeが無いため壁時計差分で代用）
            val times = points.mapNotNull { it.timeEpochMs }
            val durationSec = if (times.size >= 2) ((times.last() - times.first()) / 1000).coerceAtLeast(0) else 0L

            var distance = 0.0
            for (i in 1 until points.size) {
                distance += GeoMath.haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            }

            val entity = upsertSegmentTrackFromPoints(
                fromStopCardId = from,
                toStopCardId = to,
                points = points,
                distanceM = distance,
                durationSec = durationSec,
                recordedSessionId = null, // 供給元セッションなし（他アプリ産ログ）
            )
            courseSegmentDao.getCourseIdsReferencingEdge(from, to).forEach { regenerateCourseSegments(it) }
            entity
        }

    companion object {
        private const val TAG = "CourseRepository"

        /** サムネイル仕様（§3.3: 長辺320px, JPEG q80）。 */
        private const val THUMB_LONG_EDGE_PX = 320
        private const val THUMB_JPEG_QUALITY = 80

        /**
         * セッション解析レポート（フェーズA-1）のダブり検出しきい値。同一停留所への連続マーカーの
         * captured_at 間隔がこれ未満なら「誤って連打・多重マークした」統合候補とみなす。
         */
        private const val DUPLICATE_GROUP_INTERVAL_MS = 120_000L

        /** 欠損/割り込みレポート（フェーズA-2）: カード座標からのdwell判定半径（実データ校正済み）。 */
        private const val COVERAGE_RADIUS_M = 70.0

        /**
         * セッション全体レポート（トップダウン創設フェーズ、[analyzeSessionCoverage]）: 軌跡コリドー
         * （ポリライン最短距離での近傍判定）半径。当面 [COVERAGE_RADIUS_M] と同値だが、トップダウン
         * 走査は用途が異なるため将来別調整できるよう独立定数化しておく。**暫定値・後で調整可**。
         */
        private const val COVERAGE_CORRIDOR_R_M = 70.0

        /** 欠損/割り込みレポート（フェーズA-2）: 半径内の点列を別パスに分割する時間間隔しきい値。 */
        private const val COVERAGE_PASS_GAP_MS = 60_000L

        /** 欠損/割り込みレポート（フェーズA-2）: 停車確定とみなす最小dwell秒数。 */
        private const val COVERAGE_DWELL_MIN_SEC = 15.0

        /** 欠損/割り込みレポート（フェーズA-2）: 停車確定とみなす最大速度（m/s、≒3.6km/h）。 */
        private const val COVERAGE_MIN_SPEED_MPS = 1.0

        /**
         * find-or-create候補（フェーズB(c)）: マーカーコマの撮影位置と現在割り当てられている
         * 停留所カード座標との距離がこれを超えると誤吸着の疑いとみなす。**暫定値・実データ由来
         * （session8実測での誤吸着距離を参考に [COVERAGE_RADIUS_M] と同じ値を採用）・後で調整可**。
         * 同じ値をコマ位置近傍の「別の既存カードが無いか」の判定半径にも流用する。
         */
        private const val FIND_OR_CREATE_RADIUS_M = 70.0

        /**
         * find-or-create候補（フェーズB(c)）: 拠点（[BusStopCardEntity.isHub]）カード用の判定半径。
         * 拠点（園）は敷地が広く、マーカーが登録カードから[FIND_OR_CREATE_RADIUS_M]を超えても同じ拠点
         * のことがあるため、拠点カードだけ半径を広げて誤って新規カード化候補に出さないようにする。
         * **暫定値・後で調整可**。
         */
        private const val FIND_OR_CREATE_HUB_RADIUS_M = 180.0

        /** find-or-create適用（フェーズB(c)）で作成する新規カード名 "候補NNN" のパース用。 */
        private val CANDIDATE_NAME_REGEX = Regex("^候補(\\d{3})$")

        /**
         * コース創設パス1（[generatePass1RawStops]）: MANUALイベントを、対応するマーカー付きLORES
         * フレームと同一停留所の重複として除外するかどうかの時刻近接判定しきい値。`onManualStopMark`
         * は同一ハンドラ内で `stop_visit_event` 記録→直後に `findClosestLoresFrameId(before=true)` で
         * LORESへマーカー付与を試みるため、対応する場合は両者の時刻がほぼ同時刻になる。
         * **暫定値・後で調整可**。
         */
        private const val MANUAL_EVENT_FRAME_MATCH_WINDOW_MS = 15_000L

        /**
         * コース確定（フェーズC-1、[confirmCourseRouteFromSession]）: クリップ窓決定の一次経路
         * （拠点フラグでのフラグメント化）で選んだフラグメントを採用してよいと判定する、コース停留所
         * との重なり数（マーク件数ベース）の最小しきい値。これ未満なら誤用（拠点分割が実態と噛み合って
         * いない等）とみなし、フォールバック（クラスタリング）経路へ切り替える。2026-07-14暫定値。
         */
        private const val HUB_FRAGMENT_MIN_COURSE_STOPS = 2

        /**
         * コース確定（フェーズC-1、[confirmCourseRouteFromSession]）: クリップ窓決定のフォールバック
         * 経路（拠点マーク無し／一次が誤用ガードで無効）で、コース停留所マーカー列を連続クラスタに
         * 分割する際の時間ギャップしきい値。これを超える間隔が空いたら別クラスタとみなす
         * （共有停留所が別コース走行中に単発でマークされた場合の孤立を切り離すため）。2026-07-14暫定値。
         */
        private const val CLUSTER_GAP_MS = 600_000L

        /**
         * パス3（停車推定、[analyzeStopEstimates]）: 停車・徐行とみなす速度しきい値（m/s）。
         * **実データ由来**: 本番セッション#8で「速度<1.5m/s」かつ「15秒以上」の低速クラスタが23個
         * 観測された（設計ドラフトv2 §3パス3・§10）。1.5m/s（≒5.4km/h）を採用した初期値。
         * **暫定値・後で調整可**。
         */
        private const val DWELL_SPEED_MPS = 1.5

        /**
         * パス3（停車推定、[analyzeStopEstimates]）: 停車推定の候補とみなす最小滞在秒数。実データの
         * 観測しきい値は15秒（上記23クラスタの内訳、最長271秒＝拠点・短いものは信号待ちの可能性）
         * だったが、信号待ち（青信号までの待ち時間としてしばしば十数秒〜を要する）を拾いすぎない
         * よう、設計ドラフトv2 §3パス3の指示（「信号長交差点…で1セッションでは断定できない」）を
         * 踏まえ余裕を持たせて20秒とした。**暫定値・後で調整可**（設計ドラフト§12「半径・
         * corridor_r・dwell校正は青バス4台分データが揃った段階で再チューニング」）。
         */
        private const val DWELL_MIN_SEC = 20.0

        /**
         * パス3（停車推定、[analyzeStopEstimates]）: クラスタの代表座標が、パス1で既に確定済みの点
         * （[generatePass1RawStops]）の近傍にあるとみなす除外半径（m）。「既に停留所」として二重
         * 提示しないための判定であり、パス1生の点はカード（拠点フラグ）を一切引き継がない設計
         * （[generatePass1RawStops]参照）のため [findOrCreateRadiusFor] のような拠点/通常の
         * 半径使い分けはできず、単一の通常半径のみを使う。[FIND_OR_CREATE_RADIUS_M]・
         * [COVERAGE_RADIUS_M] と同じ値（実データ由来の校正済み値）を採用した。**暫定値・後で調整可**。
         */
        private const val STOP_ESTIMATE_EXCLUSION_RADIUS_M = 70.0

        /**
         * 区間抽出対象のセッション種別（§3.9）。設計書は PARTIAL_RUN を主対象に記述しているが、
         * FULL_RUN / TEST_DRIVE も同じ構造の走行ログであり、2026-07-09オーナー指示
         * （TEST_DRIVE/FULL_RUN からの抽出）に PARTIAL_RUN（§3.9本文の主対象）を加えた3種とする。
         * LIVE_GUIDANCE（実運行・案内）は抽出対象にしない。
         */
        private val EXTRACTABLE_SESSION_TYPES = setOf(
            RecordingSessionType.FULL_RUN.name,
            RecordingSessionType.PARTIAL_RUN.name,
            RecordingSessionType.TEST_DRIVE.name,
        )
    }
}
