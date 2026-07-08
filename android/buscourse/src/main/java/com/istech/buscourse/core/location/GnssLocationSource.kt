package com.istech.buscourse.core.location

/**
 * 位置情報取得の統一実装（設計書§2.1・§4.8、D1）。`android.location.LocationManager` の GPS_PROVIDER のみを使用し、
 * FusedLocationProviderClient / play-services-location は一切採用しない（オフライン厳守）。
 * recording・guidance・map の3機能から共有される唯一の位置情報ソース。フェーズ1で実装。
 */
class GnssLocationSource
