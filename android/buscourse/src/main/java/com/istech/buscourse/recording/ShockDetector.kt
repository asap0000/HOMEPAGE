package com.istech.buscourse.recording

/**
 * 衝撃検知（設計書§4.1）。SensorManager / TYPE_LINEAR_ACCELERATION を監視し、
 * 閾値超過で shock_event 記録＋高解像度バースト撮影をトリガーする。フェーズ1で実装。
 */
class ShockDetector
