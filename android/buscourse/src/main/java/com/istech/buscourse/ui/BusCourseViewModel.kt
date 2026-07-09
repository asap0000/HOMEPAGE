package com.istech.buscourse.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.SegmentTrackEntity
import com.istech.buscourse.course.CourseKind
import com.istech.buscourse.course.CourseRepository
import com.istech.buscourse.course.SegmentExtractionResult
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

    /** 停留所カードの新規作成（§3.3。StopCardCreateScreen「保存」）。 */
    fun createStopCard(
        name: String,
        latitude: Double,
        longitude: Double,
        altitudeM: Double?,
        notes: String?,
        photoTempFile: File?,
        onResult: (Result<Long>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching {
                repository.createStopCard(name, latitude, longitude, altitudeM, notes, photoTempFile)
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
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching {
                repository.updateStopCard(id, name, latitude, longitude, altitudeM, notes)
            }
            onResult(result)
        }
    }

    /** 停留所カードのアーカイブ（論理削除、StopCardEditScreen）。 */
    fun archiveStopCard(id: Long, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.archiveStopCard(id) }
            onResult(result)
        }
    }

    /** 順列の書き換え確定（CourseDetailScreen「編成を確定」、§3.8）。 */
    fun setCourseStops(courseId: Long, stopCardIds: List<Long>, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.setCourseStops(courseId, stopCardIds) }
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
            onResult(result)
        }
    }

    /** コース全体のGPXエクスポート（CourseDetailScreen、§3.11.3）。 */
    fun exportCourse(courseId: Long, onResult: (Result<File>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { repository.exportCourse(courseId) }
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
            onResult(result)
        }
    }
}
