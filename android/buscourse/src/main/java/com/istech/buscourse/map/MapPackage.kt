// map パッケージ（設計書§2.1・§5）。
//
// 実装済み（2026-07-12、.iscmapインポート機構）:
//   MapDataPackage（manifest.jsonのパース結果）、MapPackageValidator（schemaVersion・SHA-256照合）、
//   StyleJsonResolver（{{MBTILES_ABS_PATH}}/{{GLYPHS_ABS_PATH}}解決＋外部スキーム再検査）、
//   MapPackageImporter（SAF Uri→展開・検証・登録の統括）、MapDataPackageRepository（DAOラッパー）。
//   実物フォーマット（sources.*.url単体・attribution/glyphsキー・glyphsの file:// 絶対パス解決等）と
//   その根拠は各クラスのKDoc、および core/data/MapDataPackageEntity.kt のKDoc参照。
//
// 実装済み（2026-07-12、地図オーバーレイ＋補助防御Interceptor、設計書§5.5・§5.7）:
//   FailClosedNetworkInterceptor（OkHttp Interceptorによるフェイルクローズ、D8二次防御。
//   BusCourseApplication#onCreateでHttpRequestUtil.setOkHttpClientへ差し替え済み）、
//   GpxToGeoJsonConverter（GpxTrack→LineString Feature変換。既存GpxCodec.readTrackを再利用し
//   XmlPullParserの二重実装は避けた）、RouteTrackOverlay（区間軌跡描画。データソースは
//   SegmentTrackDao→GpxCodec→GpxToGeoJsonConverterの実行時変換。事前キャッシュ化は未実装）、
//   StopSymbolOverlay（停留所ピン描画。SymbolManager使用、新規ic_map_stop_pin.xml使用）、
//   VehiclePositionSink / MapVehiclePositionOverlay / GnssBackedLocationEngineAdapter
//   （自車位置。LocationComponent.forceLocationUpdateへGnssLocationSourceの位置更新を橋渡し）。
//
// 実装済み（2026-07-12、地図表示・.iscmapインポートのUI画面、設計書§9次工程「アプリ側MapLibre組み込み」）:
//   ui.MapImportScreen（ActivityResultContracts.OpenDocumentでの.iscmap選択→
//   BusCourseViewModel.importMapPackage（内部でMapPackageImporter）呼び出し、
//   MapDataPackageRepository.getAll/selectedPackageによる取り込み済み一覧・使用中パッケージ表示、
//   「選択」操作でselectMapPackage）、ui.RouteMapScreen（AndroidView interopでMapViewをホストし、
//   LocalLifecycleOwnerへのLifecycleEventObserverでonStart/onResume/onPause/onStop/onDestroyを
//   橋渡し。選択中パッケージのstyle.resolved.jsonをfile://で読み込み、RouteTrackOverlay・
//   StopSymbolOverlay・LocationComponent＋MapVehiclePositionOverlay/GnssBackedLocationEngineAdapter
//   を適用。地図パッケージ未選択時はMapImportScreenへの導線を持つ空状態を表示）。
//   BusCourseViewModelにmapRepository・importMapPackage・selectMapPackageを追加
//   （書き込みはviewModelScope経由に統一する既存方針、フェーズ2レビュー#13を踏襲）。
//
// 未実装（引き続きフェーズ3スコープ）:
//   tracks/stopsの既存パイプライン（segment_track/bus_stop_card）への合流（設計書§5.6.3手順7）、
//   地図パッケージの削除UI（MapDataPackageRepository.deleteは実装済みだがUI未着手）、
//   StopSymbolOverlayタップ時のボトムシート表示は簡易AlertDialogで代用（Fragment/ネイティブViewでの
//   本格実装は未着手）。
//
// MapLibre依存（org.maplibre.gl:android-sdk・android-plugin-annotation）は本タスク着手前から
// build.gradle.kts に既に追加されていた（別セッションの作業、未コミット）。okhttp3（com.squareup.
// okhttp3:okhttp:4.12.0）はFailClosedNetworkInterceptor実装のため2026-07-12に本タスクで明示依存化した
// （android-sdkの推移依存としてAPKには既に同梱されていたが、Gradle module metadata上はruntime
// variantのみでcompile classpathに伝播しないため。build.gradle.ktsのコメント参照）。
// 設計書§9.1aのVulkanゲート条件の解消状況は本タスクでは確認していない（AndroidManifest.xmlの
// コメントに実機確認記録あり、§9.1a参照）。
package com.istech.buscourse.map
