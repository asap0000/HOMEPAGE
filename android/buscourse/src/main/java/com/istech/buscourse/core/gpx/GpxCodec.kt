package com.istech.buscourse.core.gpx

/**
 * GPX 1.1 読み書き（設計書§2.1・§3.11）。`android.util.Xml.newPullParser()`（XmlPullParser）による自前実装とし、
 * 外部GPXライブラリは導入しない（オフライン方針監査対象を増やさない）。独自拡張は namespace `urn:istech:buscourse:gpx:1`。
 * フェーズ2で exportCourse / importAsSegmentTrack / readTrack を実装する（§3.11.3）。
 */
object GpxCodec
