package com.istech.buscourse.course

/**
 * コース管理機能の窓口（設計書§2.1 course パッケージ）。停留所カードCRUD、コース編成（順列DnD確定時の
 * regenerateCourseSegments・§3.8）、試走ログからの区間自動抽出（§3.9）、GPXエクスポート/インポートUI（§3.11）を担う。
 * フェーズ2で実装。
 */
class CourseRepository

/**
 * `RoutePreprocessor`（設計書§2.1・§3.9・§7.5、2026-07-08決定でtrialからcourseへ再割当）。
 * コース確定/編集時（`regenerateCourseSegments`から呼ばれる）に1回だけ`route_point`と
 * `course_stop.expected_chainage_m`を算出・保存する。route_point生成自体はフェーズ2成果物。
 * フェーズ5（trial）はこのテーブルを読むのみで、生成主体ではない。
 */
class RoutePreprocessor
