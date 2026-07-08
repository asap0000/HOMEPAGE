package com.istech.buscourse.recording

/**
 * 記録セッションの書き込み窓口（設計書§4.1）。§3 正典スキーマ（recording_session / timelapse_frame / gps_point /
 * stop_visit_event / shock_event）への Room 書き込み、frames/・gps_raw.jsonl・meta.json のファイルI/Oを担う。
 * 記録中は JSONL 追記、終了時に gps_point へ一括インポート（D4）。フェーズ1で実装。
 */
class RecordingSessionRepository
