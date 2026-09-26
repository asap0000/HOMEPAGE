package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [NaviCameraState.toCameraPosition]の値対応テスト（(c2-b)、NaviScreen §2.1）。
 * `CameraPosition`/`LatLng`はMapLibreのJava POJO（ネイティブ呼び出しを伴わない）のため、
 * Robolectric(JVM)上でも既存`NaviMapGeneratorTest`と同じ流儀で生成・検証できる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviCameraProjectionTest {

    @Test
    fun toCameraPositionMapsAllFieldsUnchanged() {
        val state = NaviCameraState(
            lat = 35.681,
            lon = 139.767,
            bearingDeg = 123.4,
            pitchDeg = 45.0,
            zoomLevel = 16.0,
        )

        val camera = state.toCameraPosition()

        assertThat(camera.target?.latitude).isEqualTo(35.681)
        assertThat(camera.target?.longitude).isEqualTo(139.767)
        assertThat(camera.bearing).isEqualTo(123.4)
        assertThat(camera.tilt).isEqualTo(45.0)
        assertThat(camera.zoom).isEqualTo(16.0)
    }
}
