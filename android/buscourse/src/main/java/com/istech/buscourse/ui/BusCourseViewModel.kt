package com.istech.buscourse.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.MapDataPackageEntity
import com.istech.buscourse.core.data.SegmentTrackEntity
import com.istech.buscourse.core.data.WorkLogCategory
import com.istech.buscourse.course.ApplyApprovedResult
import com.istech.buscourse.course.CourseCreationResult
import com.istech.buscourse.course.CourseKind
import com.istech.buscourse.course.CourseRepository
import com.istech.buscourse.course.DuplicateFrameCandidate
import com.istech.buscourse.course.FindOrCreateCandidate
import com.istech.buscourse.course.SegmentExtractionResult
import com.istech.buscourse.map.MapDataPackageRepository
import com.istech.buscourse.map.MapPackageImporter
import kotlinx.coroutines.launch
import java.io.File

/**
 * フェーズ2 UI 共有 ViewModel（設計書§2.1 course パッケージのUI面）。
 * :app（PrivacyCamera）と同様に Activity スコープで1つだけ生成し、全画面から
 * [repository]（コース管理機能の窓口）を共有する。画面ごとの一覧状態は各 Composable が
 * `LaunchedEffect` で都度ロードする（DAO が suspend ベースのため）。
 *
 * リポジトリの変更系（書き込み）関数は本 ViewModel の [viewModelScope] 管理下の関数として集約する
 * （フェーズ2レビュー#13）。各画面が個別に `rememberCoroutineScope()` でリポジトリの書き込み
 * suspend関数を直接呼ぶと、Compose Navigation でコンポーザブルが破棄された際にそのスコープが
 * キャンセルされ、複数DAO操作にまたがる書き込み（例: `createStopCard` の2回のupsert＋写真移動）が
 * 中断して孤児レコードが残りうる。`viewModelScope` はActivity破棄まで生存するため、画面遷移で
 * キャンセルされずに最後まで完了する。:app の `PhotoViewModel`（`viewModelScope.launch` に
 * `Result` コールバックを添える方式）に倣う。
 *
 * 読み取り専用の関数（`getActiveStopCards` 等）はこの集約対象に含めない。各画面が
 * `viewModel.repository` 経由で直接 `LaunchedEffect`/`rememberCoroutineScope` から呼び出してよい
 * （読み取りは画面破棄でキャンセルされても不変条件を壊さないため）。
 */
class BusCourseViewModel(application: Application) : AndroidViewModel(application) {
    val repository: CourseRepository by lazy {
        CourseRepository(application, (application as BusCourseApplication).database)
    }

    /**
     * mapパッケージ（`.iscmap`）関連の窓口2つ（フェーズ3、設計書§5.6.4・§9次工程）。
     * [repository]（[CourseRepository]）とは別系統のため、`database`は[getApplication]経由で
     * 個別に取得する（`application`はプライマリコンストラクタのパラメータのため、[repository]の
     * `lazy`初期化ブロックの外＝通常のメンバ関数からは直接参照できない。[getApplication]は
     * `AndroidViewModel`が提供するジェネリックメソッドで、どの文脈からでも呼べる）。
     */
    private val database: BusCourseDatabase by lazy { getApplication<BusCourseApplication>().database }

    /** 取り込み済み地図パッケージの一覧・選択状態（読み取りは画面から直接呼んでよい、既存方針どおり）。 */
    val mapRepository: MapDataPackageRepository by lazy { MapDataPackageRepository(database) }

    private val mapPackageImporter: MapPackageImporter by lazy {
        MapPackageImporter(getApplication<BusCourseApplication>(), mapRepository)
    }

    /**
     * courseIdごとの編成下書き（画面破棄・戻る操作で失われないようViewModelに保持する）。
     * cardIdの並び順だけを持つ。name/riderCountは他画面でのカード編集を反映できるよう、
     * 復元時に呼び出し側（CourseDetailScreen）が都度最新のカードデータから引き直す。
     */
    private val courseStopDrafts = mutableMapOf<Long, List<Long>>()

    /**
     * 作業進捗ログ（依頼３ 2026-07-11）の共通記録。書き込み操作の成否確定後に呼ぶ。
     * 成功時は [successMessage]（nullなら成功は記録しない）、失敗時は ERROR として例外要約を残す。
     * ログ記録自体の失敗は logWork 側で握りつぶされるため、onResult への影響はない。
     */
    private suspend fun <T> logOutcome(
        result: Result<T>,
        category: WorkLogCategory,
        opName: String,
        successMessage: (suspend (T) -> String)? = null,
    ) {
        result.onSuccess { value ->
            if (successMessage != null) repository.logWork(category, successMessage(value))
        }.onFailure { e ->
            repository.logWork(WorkLogCategory.ERROR, "$opName に失敗しました", e.toString())
        }
    }

    fun getCourseStopDraft(courseId: Long): List<Long>? = courseStopDrafts[courseId]

    fun setCourseStopDraft(courseId: Long, cardIdOrder: List<Long>) {
        courseStopDrafts[courseId] = cardIdOrder
    }

    fun clearCourseStopDraft(courseId: Long) {
        courseStopDrafts.remove(courseId)
    }

    /** 停留所カードの新規作成（§3.3。StopCardCreateScreen「保存」）。 */
    fun createStopCard(
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
        onResult: (Result<Long>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching {
                repository.createStopCard(
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    altitudeM = altitudeM,
                    notes = notes,
                    riderCount = riderCount,
                    photoTempFile = photoTempFile,
                    voiceMemoTempFile = voiceMemoTempFile,
                    needsMaturation = needsMaturation,
                    gardenColor = gardenColor,
                )
            }
            logOutcome(result, WorkLogCategory.STOP_CARD, "停留所カードの作成") {
                "停留所カード『$name』を作成" + if (voiceMemoTempFile != null) "（音声メモ付き）" else ""
            }
            onResult(result)
        }
    }

    /** 停留所カードの手動修正（StopCardEditScreen「保存」）。 */
    fun updateStopCard(
        id: Long,
        name: String,
        latitude: Double,
        longitude: Double,
        altitudeM: Double?,
        notes: String?,
        riderCount: Int,
        gardenColor: String? = null,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching {
                repository.updateStopCard(id, name, latitude, longitude, altitudeM, notes, riderCount, gardenColor)
            }
            logOutcome(result, WorkLogCategory.STOP_CARD, "停留所カードの編集") { "停留所カード『$name』を編集" }
            onResult(result)
        }
    }

    /** 停留所カードのアーカイブ（論理削除、StopCardEditScreen）。 */
    fun archiveStopCard(id: Long, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val cardName = runCatching { repository.getStopCard(id)?.name }.getOrNull()
            val result = runCatching { repository.archiveStopCard(id) }
            logOutcome(result, WorkLogCategory.STOP_CARD, "停留所カードのアーカイブ") {
                "停留所カード『${cardName ?: "ID:$id"}』をアーカイブ"
            }
            onResult(result)
        }
    }

    /** 写真・座標だけの撮り直し（StopCardRetakeScreen「保存」、P2-1）。 */
    fun retakePhotoAndLocation(
        cardId: Long,
        latitude: Double,
        longitude: Double,
        altitudeM: Double?,
        photoTempFile: File,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching {
                repository.retakePhotoAndLocation(cardId, latitude, longitude, altitudeM, photoTempFile)
            }
            logOutcome(result, WorkLogCategory.STOP_CARD, "写真・座標の撮り直し") {
                val cardName = runCatching { repository.getStopCard(cardId)?.name }.getOrNull()
                "停留所カード『${cardName ?: "ID:$cardId"}』の写真・座標を撮り直し"
            }
            onResult(result)
        }
    }

    /** 録音済み音声メモの添付確定（StopCardEditScreen、P2-2）。 */
    fun attachVoiceMemo(cardId: Long, recordedTempFile: File, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.attachVoiceMemo(cardId, recordedTempFile) }
            logOutcome(result, WorkLogCategory.STOP_CARD, "音声メモの添付") {
                val cardName = runCatching { repository.getStopCard(cardId)?.name }.getOrNull()
                "停留所カード『${cardName ?: "ID:$cardId"}』に音声メモを添付"
            }
            onResult(result)
        }
    }

    /** 順列の書き換え確定（CourseDetailScreen「編成を確定」、§3.8）。 */
    fun setCourseStops(courseId: Long, stopCardIds: List<Long>, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.setCourseStops(courseId, stopCardIds) }
            logOutcome(result, WorkLogCategory.COURSE, "編成の確定") {
                val courseName = runCatching { repository.getCourseWithDetails(courseId)?.course?.name }.getOrNull()
                "コース『${courseName ?: "ID:$courseId"}』の編成を確定（停留所${stopCardIds.size}件・区間再構築）"
            }
            onResult(result)
        }
    }

    /** 他アプリ産GPXの区間取り込み（CourseDetailScreen、§3.11.3）。 */
    fun importAsSegmentTrack(
        gpxFile: Uri,
        from: Long,
        to: Long,
        onResult: (Result<SegmentTrackEntity>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching { repository.importAsSegmentTrack(gpxFile, from, to) }
            logOutcome(result, WorkLogCategory.GPX, "GPX取り込み") {
                "GPXを区間（カードID $from→$to）として取り込み"
            }
            onResult(result)
        }
    }

    /** コース全体のGPXエクスポート（CourseDetailScreen、§3.11.3）。 */
    fun exportCourse(courseId: Long, onResult: (Result<File>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.exportCourse(courseId) }
            logOutcome(result, WorkLogCategory.GPX, "GPXエクスポート") { file ->
                val courseName = runCatching { repository.getCourseWithDetails(courseId)?.course?.name }.getOrNull()
                "コース『${courseName ?: "ID:$courseId"}』をGPXエクスポート（${file.name}）"
            }
            onResult(result)
        }
    }

    /** 試走ログからの区間自動抽出（ExtractionScreen、§3.9）。 */
    fun extractSegmentsFromSession(
        sessionId: Long,
        onResult: (Result<SegmentExtractionResult>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching { repository.extractSegmentsFromSession(sessionId) }
            logOutcome(result, WorkLogCategory.EXTRACTION, "区間抽出") { r ->
                "セッション#${sessionId}から区間抽出（作成${r.extractedSegmentCount}件・スキップ${r.skippedPairCount}件）"
            }
            onResult(result)
        }
    }

    /**
     * コースを指定した遡及区間抽出（ExtractionScreen、2026-07-10追加、P0-1）。
     * 記録時に停留所カード未登録等で `stop_visit_event` が得られなかったセッションでも、
     * コースの停留所順列とGPS点列だけで区間を復元する。
     */
    fun extractSegmentsForCourse(
        courseId: Long,
        sessionId: Long,
        onResult: (Result<SegmentExtractionResult>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching { repository.extractSegmentsForCourse(courseId, sessionId) }
            logOutcome(result, WorkLogCategory.EXTRACTION, "コース指定の区間抽出") { r ->
                "セッション#${sessionId}からコース指定で区間抽出（作成${r.extractedSegmentCount}件・スキップ${r.skippedPairCount}件・未達${r.unmatchedStops.size}件）"
            }
            onResult(result)
        }
    }

    /**
     * 承認キューの一括適用（②「コース編成(抽出)」フェーズB、SessionAnalysisDialog「承認して適用」、
     * 2026-07-14追加）。ダブり統合・割り込み・find-or-create・拠点フラグの4種を1回のViewModel呼び出しで
     * まとめて適用する。書き込み系のため他の関数と同様に[viewModelScope]管理下で実行する
     * （フェーズ2レビュー#13の方針をフェーズBにも適用。初のDBマイグレーション＋書き込み実装のため
     * 画面遷移によるスコープキャンセルで中途半端な適用状態を残さないことが特に重要）。
     */
    fun applyApprovedCandidates(
        sessionId: Long,
        duplicateGroups: List<List<DuplicateFrameCandidate>>,
        interruptionStopIds: List<Long>,
        findOrCreateCandidates: List<FindOrCreateCandidate>,
        hubStopIds: List<Long>,
        onResult: (Result<ApplyApprovedResult>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching {
                repository.applyApprovedCandidates(
                    sessionId = sessionId,
                    duplicateGroups = duplicateGroups,
                    interruptionStopIds = interruptionStopIds,
                    findOrCreateCandidates = findOrCreateCandidates,
                    hubStopIds = hubStopIds,
                )
            }
            logOutcome(result, WorkLogCategory.EXTRACTION, "承認キューの適用") { r ->
                "セッション#${sessionId}の承認キューを適用（統合${r.duplicatesMerged}・割り込み${r.interruptionsApplied}" +
                    "・新規${r.findOrCreateApplied}・拠点${r.hubFlagsApplied}）"
            }
            onResult(result)
        }
    }

    /**
     * コース確定→route_point生成（②「コース編成(抽出)」フェーズC-1、SessionAnalysisDialog
     * 「欠損/割り込みレポート」の「このセッションでコースを確定（ルート生成）」ボタン、2026-07-14追加）。
     * 承認済み（フェーズB）のセッションから、そのコースのナビ用連続トラックを拠点→拠点にクリップして
     * 確定し、`route_point` へ保存する。書き込み系のため他の関数と同様に[viewModelScope]管理下で実行する。
     */
    fun confirmCourseRoute(
        courseId: Long,
        sessionId: Long,
        onResult: (Result<Int>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching { repository.confirmCourseRouteFromSession(courseId, sessionId) }
            logOutcome(result, WorkLogCategory.EXTRACTION, "コース確定（ルート生成）") { count ->
                "セッション#${sessionId}からコース#${courseId}を確定（route_point ${count}点）"
            }
            onResult(result)
        }
    }

    /**
     * コース創設（トップダウン、3パス成熟モデルのパス1＋パス2、S4「コース創設」画面
     * [CourseCreateScreen] 「創設」ボタン、2026-07-14追加・2026-07-15全面改訂）。
     * [hubStopCardIds] は拠点分割の選択拠点、[courseNames] は断片indexに対応するコース名（空なら
     * 既定名）。書き込み（course_stop等の複数DAO操作）を伴うため、他の書き込み系関数と同様に
     * [viewModelScope] 管理下で実行する（画面遷移によるスコープキャンセルで中途半端な創設状態を
     * 残さないため）。
     */
    fun createCoursesFromSession(
        sessionId: Long,
        hubStopCardIds: Set<Long>,
        courseNames: List<String> = emptyList(),
        onResult: (Result<CourseCreationResult>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching { repository.createCoursesFromSession(sessionId, hubStopCardIds, courseNames) }
            logOutcome(result, WorkLogCategory.COURSE, "コース創設") { r ->
                "セッション#${sessionId}からコース創設（作成${r.createdCourseIds.size}件・停留所${r.totalStopCount}件・" +
                    "カード吸着${r.cardAttachedStopCount}件・映像のみ${r.frameOnlyStopCount}件）"
            }
            onResult(result)
        }
    }

    /**
     * find-or-create候補選択UI（思いつき2、[CourseCreateScreen] 「創設」処理のa2段、2026-07-14追加）で
     * 「付替え」を選んだフレームを、既存カードへ再吸着する。[reassignments] は frameId → 付替え先
     * stop_card_id。書き込み系のため他の関数と同様に[viewModelScope]管理下で実行する。
     */
    fun reassignMarkerFrames(reassignments: Map<Long, Long>, onResult: (Result<Int>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.reassignMarkerFrames(reassignments) }
            logOutcome(result, WorkLogCategory.EXTRACTION, "find-or-create候補の付替え") { count ->
                "find-or-create候補選択で${count}件のマーカーを付替え"
            }
            onResult(result)
        }
    }

    /** セッションメモの更新（ExtractionScreen、2026-07-11追加）。 */
    fun updateSessionMemo(sessionId: Long, memo: String?, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.updateSessionMemo(sessionId, memo) }
            logOutcome(result, WorkLogCategory.EXTRACTION, "セッションメモの更新") {
                if (memo.isNullOrBlank()) "セッション#${sessionId}のメモを削除" else "セッション#${sessionId}のメモを更新"
            }
            onResult(result)
        }
    }

    /**
     * 全アクティブ停留所カードのサムネイル一括再生成（StopCardListScreen、2026-07-10追加）。
     * EXIF回転バグ修正前に生成された既存サムネイルの是正用。
     */
    fun regenerateAllThumbnails(onResult: (Result<Int>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.regenerateAllThumbnails() }
            onResult(result)
        }
    }

    /** コース新規作成（CourseListScreen「作成」）。 */
    fun createCourse(
        name: String,
        kind: CourseKind,
        baseCourseId: Long? = null,
        onResult: (Result<Long>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching { repository.createCourse(name, kind, baseCourseId) }
            logOutcome(result, WorkLogCategory.COURSE, "コースの作成") {
                "コース『$name』を作成" + if (kind == CourseKind.TEMPORARY) "（臨時）" else ""
            }
            onResult(result)
        }
    }

    /**
     * コース削除（CourseDetailScreen「削除」、コース削除機能、2026-07-14追加）。コースは参照リストの
     * ため物理削除。course_stop / route_point / course_segment はFK ON DELETE CASCADEで連動削除
     * されるが、停留所カード・記録セッションには一切触れない（[CourseRepository.deleteCourse]参照）。
     */
    fun deleteCourse(courseId: Long, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val courseName = runCatching { repository.getCourseWithDetails(courseId)?.course?.name }.getOrNull()
            val result = runCatching { repository.deleteCourse(courseId) }
            logOutcome(result, WorkLogCategory.COURSE, "コースの削除") {
                "コース『${courseName ?: "ID:$courseId"}』を削除"
            }
            onResult(result)
        }
    }

    /**
     * `.iscmap`地図パッケージの取り込み（MapImportScreen、設計書§5.6.3・§9次工程）。
     * ZIP展開・SHA-256照合・DB UPSERTと複数の工程にまたがる書き込みのため、他の書き込み系関数と
     * 同様に[viewModelScope]管理下で実行する（画面破棄で中断させない、フェーズ2レビュー#13の方針を
     * mapパッケージ取り込みにも適用）。
     */
    fun importMapPackage(uri: Uri, onResult: (Result<MapDataPackageEntity>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { mapPackageImporter.import(uri) }
            logOutcome(result, WorkLogCategory.MAP, "地図パッケージの取り込み") { pkg ->
                "地図パッケージ『${pkg.displayName}』（${pkg.regionId}）を取り込み"
            }
            onResult(result)
        }
    }

    /** 使用中の地図パッケージの切り替え（MapImportScreen「選択」、設計書§5.6.4 selectPackage）。 */
    fun selectMapPackage(regionId: String, displayName: String, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { mapRepository.selectPackage(regionId) }
            logOutcome(result, WorkLogCategory.MAP, "地図パッケージの選択切替") {
                "使用中の地図パッケージを『$displayName』に切り替え"
            }
            onResult(result)
        }
    }
}
