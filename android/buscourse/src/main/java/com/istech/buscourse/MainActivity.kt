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
import com.istech.buscourse.ui.CourseDetailScreen
import com.istech.buscourse.ui.CourseListScreen
import com.istech.buscourse.ui.ExtractionScreen
import com.istech.buscourse.ui.HomeScreen
import com.istech.buscourse.ui.RecordingScreen
import com.istech.buscourse.ui.StopCardCreateScreen
import com.istech.buscourse.ui.StopCardEditScreen
import com.istech.buscourse.ui.StopCardListScreen
import com.istech.buscourse.ui.theme.BusCourseTheme

/**
 * エントリポイント（フェーズ2、設計書§9）。Compose Navigation で以下の画面へ接続する
 * （:app PrivacyCamera の MainActivity / AppNavHost の方式に合わせる）:
 * - 運行記録FGSの開始/終了導線（RunSetupActivity 相当、§4.3。2026-07-10追加）
 * - 停留所カード CRUD（一覧・新規作成〔GPS＋CameraX撮影〕・編集/アーカイブ）
 * - コース編成（一覧・作成・DnD並べ替え・§3.8 regenerateCourseSegments・GPX入出力 §3.11）
 * - 試走ログからの区間自動抽出（§3.9）
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
    const val HOME = "home"
    const val RECORDING = "recording"
    const val STOP_CARDS = "stopcards"
    const val STOP_CARD_NEW = "stopcards/new"
    const val STOP_CARD_EDIT = "stopcards/{id}"
    const val COURSES = "courses"
    const val COURSE_DETAIL = "courses/{id}"
    const val EXTRACTION = "extraction"
    fun stopCardEdit(id: Long) = "stopcards/$id"
    fun courseDetail(id: Long) = "courses/$id"
}

@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    // Activityスコープの単一ViewModelで CourseRepository を全画面共有（:app と同じ方式）
    val viewModel: BusCourseViewModel = viewModel()
    val repository = viewModel.repository

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenRecording = { navController.navigate(Routes.RECORDING) },
                onOpenStopCards = { navController.navigate(Routes.STOP_CARDS) },
                onOpenCourses = { navController.navigate(Routes.COURSES) },
                onOpenExtraction = { navController.navigate(Routes.EXTRACTION) },
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
                repository = repository,
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
                )
            }
        }
        composable(Routes.EXTRACTION) {
            ExtractionScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
