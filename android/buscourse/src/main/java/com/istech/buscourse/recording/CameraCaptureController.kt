package com.istech.buscourse.recording

/**
 * CameraX バインド管理（設計書§4.1・§4.5）。ImageAnalysis（低fps連写、低FPSレンジ指定 §4.5.1a）と
 * ImageCapture（停留所・衝撃イベント時の高解像度単写）の二本立て。Preview は通常時バインドしない。フェーズ1で実装。
 */
class CameraCaptureController
