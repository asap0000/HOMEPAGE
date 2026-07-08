package com.istech.buscourse.core.geo

/**
 * 測地ユーティリティ（設計書§2.1）。Location.distanceBetween ラッパー、ENU近似投影、Haversine系距離計算。
 * Androidコンポーネントに依存しない純粋ロジックとして保ち、JVM単体テスト可能にする（§2.2）。
 */
object GeoMath
