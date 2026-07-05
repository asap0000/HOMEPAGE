package com.privacycamera.testutil

import org.junit.Assume.assumeTrue
import java.io.File

/** Roborazzi スクリーンショットテスト共通の前提チェック。 */
object Screenshots {

    const val DIR = "src/test/screenshots"

    /**
     * 基準画像が未記録(初回記録前)の場合はテストを skip する。
     * 記録モード(recordRoborazzi* タスク)では常に実行する。
     */
    fun assumeRecordedOrRecording() {
        val recording = System.getProperty("roborazzi.test.record") == "true"
        assumeTrue(
            "screenshots not recorded yet — run the record-goldens workflow first",
            recording || File(DIR).exists()
        )
    }
}
