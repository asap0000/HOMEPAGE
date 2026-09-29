package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 復元の可否ゲート（[RestoreCompatibility]）の単体テスト（タスク指示書§4の2点目・3点目）。
 * 整数比較のみの純関数のためRobolectric不要（[BackupInventoryTest]と同じ慣習）。
 */
class RestoreCompatibilityTest {

    @Test
    fun `schema compatibility follows registered migration edges`() {
        val edges = listOf(17 to 19, 19 to 20, 20 to 21, 21 to 22, 22 to 23)
        assertThat(RestoreCompatibility.isSchemaAcceptable(17, 23, edges)).isTrue()
        assertThat(RestoreCompatibility.isSchemaAcceptable(18, 23, edges)).isFalse()
        assertThat(RestoreCompatibility.isSchemaAcceptable(23, 23, edges)).isTrue()
        assertThat(RestoreCompatibility.isSchemaAcceptable(24, 23, edges)).isFalse()
    }

    @Test
    fun `required restore space includes 256 MiB reserve`() {
        val total = 3L * 1024 * 1024 * 1024
        assertThat(RestoreCompatibility.requiredFreeBytes(total)).isEqualTo(total + 256L * 1024 * 1024)
        val check = RestoreCompatibility.checkStorage(total, total)
        assertThat(check.enough).isFalse()
        assertThat(check.shortageBytes).isEqualTo(256L * 1024 * 1024)
        assertThat(RestoreCompatibility.storageMessage(check)).contains("空きが ")
        assertThat(RestoreCompatibility.storageMessage(check)).contains("必要 ")
        assertThat(RestoreCompatibility.storageMessage(check)).contains(" GB・空き ")
    }

    @Test
    fun `failure messages distinguish restore phases`() {
        assertThat(RestoreCompatibility.restoreFailureMessage(false, true))
            .isEqualTo("端末の状態は変更されていません。もう一度お試しください。")
        assertThat(RestoreCompatibility.restoreFailureMessage(true, true))
            .contains("書き戻す前の状態に戻しました")
        assertThat(RestoreCompatibility.restoreFailureMessage(true, false))
            .contains("データが半端な状態です")
    }

    @Test fun `corrupt zip has a fixed localized message`() {
        assertThat(RestoreCompatibility.CORRUPT_ZIP_MESSAGE)
            .isEqualTo("ファイルが途中で切れているか、壊れています。別の ZIP を選んでください。")
    }

    @Test
    fun `device is empty only when all three counts are zero`() {
        assertThat(RestoreCompatibility.isDeviceEmpty(0, 0, 0)).isTrue()
    }

    @Test
    fun `device is not empty when course count is non-zero`() {
        assertThat(RestoreCompatibility.isDeviceEmpty(1, 0, 0)).isFalse()
    }

    @Test
    fun `device is not empty when recording_session count is non-zero`() {
        assertThat(RestoreCompatibility.isDeviceEmpty(0, 1, 0)).isFalse()
    }

    @Test
    fun `device is not empty when bus_stop_card count is non-zero`() {
        assertThat(RestoreCompatibility.isDeviceEmpty(0, 0, 1)).isFalse()
    }

    @Test
    fun `device is not empty when all three counts are non-zero`() {
        assertThat(RestoreCompatibility.isDeviceEmpty(3, 5, 121)).isFalse()
    }
}
