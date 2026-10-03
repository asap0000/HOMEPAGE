package com.istech.buscourse.e2e

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.MainActivity
import com.istech.buscourse.core.data.MapDataPackageEntity
import com.istech.buscourse.pipeline.FakeRunFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingToNaviE2ETest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app: BusCourseApplication get() = context.applicationContext as BusCourseApplication
    private var scenario: ActivityScenario<MainActivity>? = null
    private var fixtureSessionId: Long = 0L

    @Before
    fun prepareKensaApp() = runBlocking {
        app.database.clearAllTables()
        context.getSharedPreferences("course_state_filter", Context.MODE_PRIVATE)
            .edit().clear().apply()
        fixtureSessionId = FakeRunFixture.recordRun(app.database)
        assertThat(fixtureSessionId).isAtLeast(1L)
        seedSelectedOfflineMap()
    }

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun fakeRecordedRunCanBeWashedShapedCheckedSentAndCheckedFromNavi() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithText("設計").performClick()
        waitForText("運行の洗浄")
        composeRule.onNodeWithText("運行の洗浄").performClick()
        waitForText("FULL_RUN")
        composeRule.onNodeWithText("FULL_RUN", substring = true).performClick()
        waitForText("洗浄して予約")
        composeRule.onNodeWithText("洗浄して予約").performClick()
        waitForText("予約を作成しました（3箇所）")
        composeRule.onNodeWithText("OK").performClick()

        waitForText("運行の洗浄")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        waitForText("コースの成形")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        waitForText("BusCourse")
        composeRule.onNodeWithText("設計").performClick()
        waitForText("コースの成形")
        composeRule.onNodeWithText("コースの成形").performClick()
        waitForText("#$fixtureSessionId の予約")
        composeRule.onNodeWithText("#$fixtureSessionId の予約").performClick()
        composeRule.onNodeWithText("送る前に確かめる").performScrollTo().performClick()

        assertGuidanceAt(0f, "停留所", 150)
        assertGuidanceAt(200f, "右折", 100)
        assertGuidanceAt(600f, "左折", 100)

        composeRule.onNodeWithText("この内容でナビへ送る").performClick()
        waitForText("バス識別子")
        composeRule.onNodeWithText("バス識別子").performTextInput("テスト")
        composeRule.onNodeWithText("コース番号").performTextInput("1")
        composeRule.onNodeWithText("年度").performTextInput("2026")
        composeRule.onNodeWithText("送る").performClick()
        // 成功後、識別付きの送信済み行が一覧に現れる。
        waitForText("送り済み")
        waitForText("2026年 テスト1コース")
        backToTop()
        composeRule.onNodeWithText("ナビ").performClick()
        waitForText("2026年 テスト1コース")
        composeRule.onNodeWithText("確かめる").performClick()
        assertGuidanceAt(0f, "停留所", 150)
        assertGuidanceAt(200f, "右折", 100)
        assertGuidanceAt(600f, "左折", 100)
    }

    /**
     * スライダーを [meters] へ動かし、帯が「[kind] ◯m」で、◯ が [expectedM] から ±10m 以内になるまで待つ
     * （作り物の記録は 5m 地点から始まり、帯は 10m 単位なので、ぴったりの数字は求めない・指示書の許し幅 ±10m）。
     * スライダーは部品ごとの見え方（unmerged）でしか見つからない（まとめた見え方では隠れる・実機で確認）。
     */
    private fun assertGuidanceAt(meters: Float, kind: String, expectedM: Int) {
        waitForText("確認モード（現在地は使いません）")
        // ★探した結果を前もって作っておくと、あとから出てきたスライダーを拾わない（実機で確認）＝毎回探し直す。
        fun sliders() = composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
            useUnmergedTree = true,
        )
        try {
            composeRule.waitUntil(10_000) { sliders().fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("E2E")
            throw e
        }
        // 返り値（動いたか）は見ない＝すでに同じ値（0m で開いた直後の 0m）だと false が返る。効き目は下の帯で確かめる。
        sliders().onFirst().performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(meters) }
        val pattern = Regex("^" + Regex.escape(kind) + """ (\d+)m$""")
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(SemanticsMatcher("帯が $kind ${expectedM}m±10") { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text ->
                        pattern.find(text.text)?.groupValues?.get(1)?.toInt()?.let { kotlin.math.abs(it - expectedM) <= 10 } == true
                    }
                }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("E2E")
            throw e
        }
        composeRule.onNodeWithText("確認モード（現在地は使いません）").assertIsDisplayed()
    }

    /** トップ（「ナビ設定」のカードがある画面）が出るまで「戻る」を押す。送ったあとの画面の深さは決め打ちしない。 */
    private fun backToTop() {
        repeat(5) {
            composeRule.waitForIdle()
            val atTop = composeRule.onAllNodes(hasText("ナビ設定"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            if (atTop) return
            composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("戻る"), useUnmergedTree = true)
                .onFirst().performClick()
        }
        waitForText("ナビ設定")
    }

    private fun waitForText(text: String) {
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("E2E")
            throw AssertionError("画面に「$text」が出ない", e)
        }
    }

    private suspend fun seedSelectedOfflineMap() {
        val regionId = "fake-e2e"
        val mapDir = File(File(context.filesDir, "buscourse/maps"), regionId).apply { mkdirs() }
        val backgroundStyle =
            """{"version":8,"name":"fake","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#ffffff"}}]}"""
        File(mapDir, "style.json").writeText(backgroundStyle)
        // MapPackageImporter が style.json から生成する実行時用ファイルも、タイル無しで用意する。
        File(mapDir, "style.resolved.json").writeText(backgroundStyle)
        app.database.mapDataPackageDao().upsert(
            MapDataPackageEntity(
                regionId = regionId,
                displayName = "架空の確認用地図",
                preparedAt = "2026-10-03T00:00:00Z",
                preparedBy = "fixture",
                attribution = "fixture",
                schemaVersion = 1,
                mbtilesRelPath = "maps/$regionId/tiles.mbtiles",
                mbtilesSha256 = "0".repeat(64),
                minzoom = 0,
                maxzoom = 0,
                boundsWest = 0.0,
                boundsSouth = 0.0,
                boundsEast = 3.0,
                boundsNorth = 3.0,
                styleRelPath = "maps/$regionId/style.resolved.json",
                styleSha256 = "0".repeat(64),
                glyphsDirRelPath = "maps/$regionId/glyphs/",
                glyphFontstacksCsv = "",
                importedAt = 1_790_956_800_000L,
                isSelected = true,
            ),
        )
    }
}
