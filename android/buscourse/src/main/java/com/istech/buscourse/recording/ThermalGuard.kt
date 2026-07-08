package com.istech.buscourse.recording

/**
 * 端末発熱ガード（設計書§4.1・§4.10.2）。PowerManager#addThermalStatusListener（API 29+）で熱状態を監視し、
 * 連写レート低減などの自動デグレードを行う最終セーフティネット。フェーズ1で実装。
 */
class ThermalGuard
