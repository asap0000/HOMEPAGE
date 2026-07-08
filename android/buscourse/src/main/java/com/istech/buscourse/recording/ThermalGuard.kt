package com.istech.buscourse.recording

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executor

/**
 * 端末発熱ガード（設計書§4.10.2）。`PowerManager#addThermalStatusListener`（API 29+）で熱状態を監視し、
 * `THERMAL_STATUS_SEVERE`以上で [onDegrade] にtrueを通知する。呼び出し元（`BusRecordingService`）は
 * これを受けて `CameraCaptureController` の連写間隔を強制的に2倍へデグレードする想定。
 * `THERMAL_STATUS_LIGHT`以下への復帰でfalseを通知し、通常運転に戻す。
 *
 * API 29未満（発熱API非対応）の端末では [start] は何もしない（本ガードは無効化される）。
 * 設計書は「フレーム落ちを監視するフォールバック（連続する`analyze()`呼び出し間隔の異常な遅延を検知したら
 * 同様にデグレード）で代替する」としているが、実装は複雑になるためフェーズ1では骨格
 * （[FrameIntervalFallbackMonitor]）のみを用意し、判定ロジック自体はTODOとする（要確認・簡略化箇所）。
 */
class ThermalGuard(
    private val powerManager: PowerManager,
    private val onDegrade: (Boolean) -> Unit,
) {
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /** API29+ でのみ実際に監視を開始する。それ未満では何もしない（フォールバックは呼び出し元が別途構成）。 */
    fun start(executor: Executor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val l = PowerManager.OnThermalStatusChangedListener { status ->
                onDegrade(status >= PowerManager.THERMAL_STATUS_SEVERE)
            }
            listener = l
            powerManager.addThermalStatusListener(executor, l)
        }
    }

    fun stop() {
        val l = listener
        if (l != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.removeThermalStatusListener(l)
        }
        listener = null
    }

    /** API29+かつリスナー登録済みなら true。API29未満では常にfalse（フォールバック監視の要否判断に使用）。 */
    val isActive: Boolean
        get() = listener != null
}

/**
 * API 29未満向けのフォールバック監視の骨格（設計書§4.10.2、要確認・フェーズ1は骨格レベルの簡略実装）。
 *
 * TODO(§4.10.2): `LoresFrameAnalyzer.analyze()` の呼び出し間隔を計測し、期待間隔（`intervalProvider()`）に対して
 * 異常な遅延（例：期待値の数倍以上）が継続した場合に発熱等によるフレームレート低下とみなし
 * [onDegrade] へ通知する。誤検知（一時的なCPU busyやI/O待ちとの切り分け）を避けるための
 * 連続回数・時間窓のチューニングが必要なため、フェーズ1では呼び出し記録用のインターフェースのみを用意する。
 */
class FrameIntervalFallbackMonitor(private val onDegrade: (Boolean) -> Unit) {
    private var lastFrameElapsed: Long = 0L

    /** `LoresFrameAnalyzer` からフレーム到着のたびに呼ぶ想定（TODO: 実際の遅延判定ロジックは未実装）。 */
    fun onFrameObserved() {
        lastFrameElapsed = SystemClock.elapsedRealtime()
        // TODO(§4.10.2要確認): 期待間隔との比較によるデグレード判定を実装する。
    }
}
