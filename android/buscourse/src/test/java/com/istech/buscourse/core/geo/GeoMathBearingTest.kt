package com.istech.buscourse.core.geo

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class GeoMathBearingTest {

    @Test fun northAndSouth_haveExpectedBearings() {
        assertThat(GeoMath.bearingDeg(35.0, 139.0, 36.0, 139.0)).isWithin(1e-6).of(0.0)
        assertThat(GeoMath.bearingDeg(36.0, 139.0, 35.0, 139.0)).isWithin(1e-6).of(180.0)
    }

    @Test fun eastAndWest_haveExpectedBearings() {
        assertThat(GeoMath.bearingDeg(0.0, 10.0, 0.0, 11.0)).isWithin(1e-6).of(90.0)
        assertThat(GeoMath.bearingDeg(0.0, 11.0, 0.0, 10.0)).isWithin(1e-6).of(270.0)
    }

    @Test fun highLatitudeBearing_isNotPlanarApproximation() {
        val spherical = GeoMath.bearingDeg(69.0, 0.0, 69.0, 10.0)
        val planar = 90.0 // atan2(Δlon, Δlat) for equal latitudes
        assertThat(abs(spherical - planar)).isGreaterThan(1.0)
    }

    @Test fun crossingAntimeridian_remainsEastward() {
        assertThat(GeoMath.bearingDeg(0.0, 179.9, 0.0, -179.9)).isWithin(1e-6).of(90.0)
    }

    /** 西進の経度180またぎ（出荷テストは東進のみだった・レビュアー名指し1）。 */
    @Test fun crossingAntimeridian_westwardIsWest() {
        assertThat(GeoMath.bearingDeg(0.0, -179.9, 0.0, 179.9)).isWithin(1e-6).of(270.0)
    }

    /** 南半球高緯度でも平面近似から有意に外れる（φ の符号処理ミス検出・レビュアー名指し5）。 */
    @Test fun southernHemisphereHighLatitude_isNotPlanarApproximation() {
        val spherical = GeoMath.bearingDeg(-69.0, 0.0, -69.0, 10.0)
        assertThat(abs(spherical - 90.0)).isGreaterThan(1.0)
    }

    @Test fun samePoint_returnsNaN() {
        assertThat(GeoMath.bearingDeg(35.0, 139.0, 35.0, 139.0).isNaN()).isTrue()
    }
}
