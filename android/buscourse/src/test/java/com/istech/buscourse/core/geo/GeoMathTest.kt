package com.istech.buscourse.core.geo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GeoMath.haversineM] の既知距離テスト（純Kotlin、Android依存無し、Robolectric不要）。
 * BusCourse単体テスト基盤新設（②「コース編成(抽出)」解析ロジック、2026-07-14）の一部。
 *
 * 呼び出すのは `haversineM(lat1, lon1, lat2, lon2): Double` の4引数オーバーロードのみ。
 * GeoMath.ktは`android.location.Location`を import しているが、他のオーバーロード
 * （[Location]を受け取るもの・`distanceBetween`）を呼ばない限り実際にAndroidクラスの
 * メソッドは実行されないため、このテストはRobolectric無しのプレーンJUnitで動く。
 */
class GeoMathTest {

    @Test
    fun samePoint_distanceIsZero() {
        val d = GeoMath.haversineM(35.681236, 139.767125, 35.681236, 139.767125)
        assertThat(d).isWithin(1e-6).of(0.0)
    }

    @Test
    fun oneMilliDegreeLatitudeDifference_isApproximately111Meters() {
        // 緯度1度 ≈ 111.32km（地球はほぼ球体のため経度に依らずほぼ一定）なので
        // 0.001度 ≈ 111.32m。許容誤差は数値近似の範囲として1mとする。
        val d = GeoMath.haversineM(35.0, 139.0, 35.001, 139.0)
        assertThat(d).isWithin(1.0).of(111.32)
    }

    @Test
    fun oneMilliDegreeLongitudeDifferenceAtEquator_isApproximately111Meters() {
        // 赤道上では経度1度も≈111.32kmになる（緯度による短縮が無いため）。
        val d = GeoMath.haversineM(0.0, 139.0, 0.0, 139.001)
        assertThat(d).isWithin(1.0).of(111.32)
    }

    @Test
    fun longitudeDifferenceShrinksAtHigherLatitude() {
        // 緯度が高いほど同じ経度差の実距離は短くなる（cos(lat)で縮小）ことの回帰確認。
        val atEquator = GeoMath.haversineM(0.0, 139.0, 0.0, 139.01)
        val atHighLat = GeoMath.haversineM(60.0, 139.0, 60.0, 139.01)
        assertThat(atHighLat).isLessThan(atEquator)
    }

    @Test
    fun distanceIsSymmetric() {
        val ab = GeoMath.haversineM(35.681236, 139.767125, 35.690921, 139.700258)
        val ba = GeoMath.haversineM(35.690921, 139.700258, 35.681236, 139.767125)
        assertThat(ab).isWithin(1e-9).of(ba)
    }
}
