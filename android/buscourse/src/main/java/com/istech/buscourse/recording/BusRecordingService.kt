package com.istech.buscourse.recording

import androidx.lifecycle.LifecycleService

/**
 * 記録エンジンFGS本体（設計書§4.1）。foregroundServiceType = "camera|location"。
 * 各Controller（CameraCaptureController / GnssLocationSource / StopDetector / ShockDetector / ThermalGuard）の
 * 起動・停止を統括する。起動は必ずフォアグラウンドUIの「運行開始」操作を起点にし、START_NOT_STICKY とする（§4.3・§4.4）。
 * フェーズ1で実装。
 */
class BusRecordingService : LifecycleService()
