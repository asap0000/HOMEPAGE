package com.istech.buscourse.recording

/**
 * 停留所自動検知の状態機械（設計書§4.1・§5相当検知）。位置情報コールバックを購読するだけの純粋ロジックとして実装し、
 * ジオフェンス＋速度で停留所到着を判定して停留所マーキング撮影をトリガーする。フェーズ1で実装。
 */
class StopDetector
