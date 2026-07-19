package com.istech.buscourse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.istech.buscourse.ui.BusCourseViewModel
import com.istech.buscourse.ui.CourseCreateScreen
import com.istech.buscourse.ui.CourseDetailScreen
import com.istech.buscourse.ui.CourseListScreen
import com.istech.buscourse.ui.HomeScreen
import com.istech.buscourse.ui.MapImportScreen
import com.istech.buscourse.ui.RecordingScreen
import com.istech.buscourse.ui.RouteMapScreen
import com.istech.buscourse.ui.SpeedMapScreen
import com.istech.buscourse.ui.StopCardCreateScreen
import com.istech.buscourse.ui.StopCardEditScreen
import com.istech.buscourse.ui.StopCardListScreen
import com.istech.buscourse.ui.StopCardRetakeScreen
import com.istech.buscourse.ui.TopScreen
import com.istech.buscourse.ui.WorkLogScreen
import com.istech.buscourse.ui.theme.BusCourseTheme

/**
 * エントリポイント（フェーズ2、設計書§9）。Compose Navigation で以下の画面へ接続する
 * （:app PrivacyCamera の MainActivity / AppNavHost の方式に合わせる）:
 * - 運行記録FGSの開始/終了導線（RunSetupActivity 相当、§4.3。2026-07-10追加）
 * - 停留所カード CRUD（一覧・新規作成〔GPS＋CameraX撮影〕・編集/アーカイブ）
 * - コース編成（一覧・作成・DnD並べ替え・§3.8 regenerateCourseSegments・GPX入出力 §3.11）
 * - 試走ログからの区間自動抽出（§3.9）
 * - 地図データ管理（`.iscmap`インポート・使用パッケージ切替、§5.6）・コースの地図表示
 *   （§5.7 オーバーレイ一式の画面組み込み、フェーズ3、2026-07-12追加）
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BusCourseTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }
            }
        }
    }
}

private object Routes {
    const val TOP = "top"
    const val HOME = "home"
    const val WORK_LOG = "worklog"
    const val RECORDING = "recording"
    const val STOP_CARDS = "stopcards"
    const val STOP_CARD_NEW = "stopcards/new"
    const val STOP_CARD_EDIT = "stopcards/{id}"
    const val STOP_CARD_RETAKE = "stopcards/{id}/retake"
    const val COURSES = "courses"
    const val COURSE_DETAIL = "courses/{id}"
    const val COURSE_MAP = "courses/{id}/map"
    // コース創設（トップダウン、S4、2026-07-14追加。設計書は docs/00_ファクトブック_バス運行実態.md 参照）
    const val COURSE_CREATE = "course_create"
    // 地図（フェーズ3、設計書§9次工程「アプリ側MapLibre組み込み」、2026-07-12追加）
    const val MAP_IMPORT = "map_import"
    // 速度マップ（トップダウン創設 S4「速度ヒート地図レイヤ」、設計ドラフトv2§6、2026-07-18追加）。
    // コース創設前の生セッション単体を対象にするため courses/{id} 系ではなく sessions/{id} 系にする。
    const val SPEED_MAP = "sessions/{sessionId}/speedmap"
    fun stopCardEdit(id: Long) = "stopcards/$id"
    fun stopCardRetake(id: Long) = "stopcards/$id/retake"
    fun courseDetail(id: Long) = "courses/$id"
    fun courseMap(id: Long) = "courses/$id/map"
    fun speedMap(sessionId: Long) = "sessions/$sessionId/speedmap"
}

@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    // Activityスコープの単一ViewModelで CourseRepository を全画面共有（:app と同じ方式）
    val viewModel: BusCourseViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.TOP) {
        composable(Routes.TOP) {
            TopScreen(
                onOpenDesign = { navController.navigate(Routes.HOME) },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onBack = { navController.popBackStack() },
                onOpenRecording = { navController.navigate(Routes.RECORDING) },
                onOpenStopCards = { navController.navigate(Routes.STOP_CARDS) },
                onOpenCourses = { navController.navigate(Routes.COURSES) },
                onOpenCourseCreate = { navController.navigate(Routes.COURSE_CREATE) },
                onOpenWorkLog = { navController.navigate(Routes.WORK_LOG) },
                onOpenMapImport = { navController.navigate(Routes.MAP_IMPORT) },
            )
        }
        composable(Routes.WORK_LOG) {
            WorkLogScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.STOP_CARDS) {
            StopCardListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onCreate = { navController.navigate(Routes.STOP_CARD_NEW) },
                onEdit = { id -> navController.navigate(Routes.stopCardEdit(id)) },
            )
        }
        composable(Routes.STOP_CARD_NEW) {
            StopCardCreateScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
            )
        }
        composable(Routes.STOP_CARD_EDIT) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toLongOrNull()
            if (id != null) {
                StopCardEditScreen(
                    viewModel = viewModel,
                    stopCardId = id,
                    onBack = { navController.popBackStack() },
                    onRetake = { navController.navigate(Routes.stopCardRetake(id)) },
                )
            }
        }
        composable(Routes.STOP_CARD_RETAKE) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toLongOrNull()
            if (id != null) {
                StopCardRetakeScreen(
                    viewModel = viewModel,
                    stopCardId = id,
                    onBack = { navController.popBackStack() },
                    onRetaken = { navController.popBackStack() },
                )
            }
        }
        composable(Routes.COURSES) {
            CourseListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpen = { id -> navController.navigate(Routes.courseDetail(id)) },
            )
        }
        composable(Routes.COURSE_DETAIL) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toLongOrNull()
            if (id != null) {
                CourseDetailScreen(
                    viewModel = viewModel,
                    courseId = id,
                    onBack = { navController.popBackStack() },
                    onOpenMap = { navController.navigate(Routes.courseMap(id)) },
                )
            }
        }
        composable(Routes.COURSE_MAP) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toLongOrNull()
            if (id != null) {
                RouteMapScreen(
                    viewModel = viewModel,
                    courseId = id,
                    onBack = { navController.popBackStack() },
                    onOpenMapImport = { navController.navigate(Routes.MAP_IMPORT) },
                )
            }
        }
        composable(Routes.COURSE_CREATE) {
            CourseCreateScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenSpeedMap = { sessionId -> navController.navigate(Routes.speedMap(sessionId)) },
            )
        }
        composable(Routes.MAP_IMPORT) {
            MapImportScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SPEED_MAP) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId")?.toLongOrNull()
            if (sessionId != null) {
                SpeedMapScreen(
                    viewModel = viewModel,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onOpenMapImport = { navController.navigate(Routes.MAP_IMPORT) },
                )
            }
        }
    }
}
