package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 復元の可否ゲート（[RestoreCompatibility]）の単体テスト（タスク指示書§4の2点目・3点目）。
 * 整数比較のみの純関数のためRobolectric不要（[BackupInventoryTest]と同じ慣習）。
 */
class RestoreCompatibilityTest {

    @Test
    fun `schema check accepts an equal version`() {
        assertThat(RestoreCompatibility.isSchemaAcceptable(17, 17)).isTrue()
    }

    @Test
    fun `schema check accepts an older backup version (migration runs on next launch)`() {
        assertThat(RestoreCompatibility.isSchemaAcceptable(12, 17)).isTrue()
    }

    @Test
    fun `schema check rejects a backup newer than the app (復唱3行目)`() {
        assertThat(RestoreCompatibility.isSchemaAcceptable(18, 17)).isFalse()
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
