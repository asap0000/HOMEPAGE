package com.istech.buscourse.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.course.CourseRepository

/**
 * フェーズ2 UI 共有 ViewModel（設計書§2.1 course パッケージのUI面）。
 * :app（PrivacyCamera）と同様に Activity スコープで1つだけ生成し、全画面から
 * [repository]（コース管理機能の窓口）を共有する。画面ごとの一覧状態は各 Composable が
 * `LaunchedEffect` で都度ロードする（DAO が suspend ベースのため）。
 */
class BusCourseViewModel(application: Application) : AndroidViewModel(application) {
    val repository: CourseRepository by lazy {
        CourseRepository(application, (application as BusCourseApplication).database)
    }
}
