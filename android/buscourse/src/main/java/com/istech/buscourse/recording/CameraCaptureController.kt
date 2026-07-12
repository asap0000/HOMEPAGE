package com.istech.buscourse.recording

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.guava.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * 高解像度単写の撮影理由（設計書§4.5.2・§4.8.3・§4.9.1）。
 * ファイル名プレフィックス（§3.3の例: `hires_stop_0001_....jpg` / `hires_shock_0001_....jpg`）は
 * STOP_AUTO / STOP_MANUAL のいずれも "hires_stop" に統一する（停留所マーキング撮影という意味では同一種別のため）。
 */
enum class HiResReason { STOP_AUTO, STOP_MANUAL, SHOCK }

/**
 * CameraXバインド管理（設計書§4.1・§4.5）。
 *
 * `ImageAnalysis`（低fps連写、§4.5.1a の低FPSレンジ指定込み）と `ImageCapture`
 * （停留所・衝撃イベント時の高解像度単写）の二本立てで構成する。`Preview` は通常時バインドしない
 * （設計書§4.5.1）。
 *
 * `start()` はCameraXの `bindToLifecycle` を呼ぶため、メインスレッド（`BusRecordingService`の
 * `lifecycleScope`）から呼び出すこと。
 */
class CameraCaptureController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner, // BusRecordingService自身（LifecycleService）
    private val sessionRepository: RecordingSessionRepository,
) {
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var imageCapture: ImageCapture
    private val analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-analysis") }

    /**
     * 直近のGPS位置。連写フレームのタグ付けに使う（設計書§4.5.2の擬似コードが参照する `lastKnownLocation`）。
     * `BusRecordingService` がGnssLocationSourceのコールバックから更新する。
     */
    @Volatile var lastKnownLocation: Location? = null

    /**
     * 低fpsストリーム／高解像度単写のUseCaseをバインドして記録を開始する。
     *
     * @param intervalProvider 連写間隔（ミリ秒）を返す関数。速度連動fps（§4.5.3）と
     *   ThermalGuardによるデグレード（§4.10.2）は呼び出し元（BusRecordingService）が合成して渡す。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun start(intervalProvider: () -> Long) {
        cameraProvider = ProcessCameraProvider.getInstance(context).await()
        // ↑ ListenableFuture#await() は kotlinx-coroutines-guava 依存（設計書§2.3・§4.5.2）

        // ProcessCameraProvider には CameraSelector を直接渡す getCameraInfo が無いため、
        // CameraSelector.filter(availableCameraInfos) で対象カメラの CameraInfo を得る。
        val cameraInfo = CameraSelector.DEFAULT_BACK_CAMERA.filter(cameraProvider.availableCameraInfos).firstOrNull()
        val lowFpsRange = cameraInfo?.let { resolveLowFpsRange(it, desiredMaxFps = 2) } // §4.5.1a

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .applyLowFpsRangeIfSupported(lowFpsRange) // §4.5.1a：センサー駆動レート自体を低減（対応機種のみ）
            .build().apply {
                setAnalyzer(analysisExecutor, LoresFrameAnalyzer(intervalProvider) { jpeg, ts ->
                    sessionRepository.enqueueLoresFrame(jpeg, ts, lastKnownLocation)
                })
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER)
                    ).build()
            )
            .setJpegQuality(92)
            .build()

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis, imageCapture
        )
    }

    /** カメラのバインドを解除し、解析用Executorを停止する（`BusRecordingService.onDestroy`から呼ぶ）。 */
    fun stop() {
        if (::cameraProvider.isInitialized) {
            cameraProvider.unbindAll()
        }
        analysisExecutor.shutdown()
    }

    /**
     * 高解像度単写を1枚撮影する（設計書§4.5.2）。失敗時は1回だけリトライし、それでも失敗した場合は
     * ログに記録するのみに留める（設計書「リトライ1回 → 失敗ログ」）。
     */
    fun captureHiRes(reason: HiResReason, location: Location?, onSaved: (File) -> Unit) {
        captureHiResInternal(reason, location, retryCount = 0, onSaved)
    }

    private fun captureHiResInternal(reason: HiResReason, location: Location?, retryCount: Int, onSaved: (File) -> Unit) {
        takePictureInternal(sessionRepository.newHiResFile(reason), location, retryCount, onFailure = {}, onSaved = onSaved)
    }

    /**
     * 記録中に既にバインド済みの[imageCapture]を再利用して、任意の出力ファイルへ単写する
     * （P1-1 クイック採取モード。新規CameraXセッションは開かない）。リトライ方針は[captureHiRes]と同じ。
     * [onFailure] はリトライも尽きた最終失敗時にのみ呼ばれる（呼び出し元が待機カウンタ等を
     * 確実に解放できるようにするため、2026-07-11レビュー指摘の修正）。
     */
    fun captureToFile(file: File, location: Location?, onFailure: () -> Unit = {}, onSaved: (File) -> Unit) {
        takePictureInternal(file, location, retryCount = 0, onFailure = onFailure, onSaved = onSaved)
    }

    private fun takePictureInternal(
        file: File,
        location: Location?,
        retryCount: Int,
        onFailure: () -> Unit = {},
        onSaved: (File) -> Unit,
    ) {
        val metadata = ImageCapture.Metadata().apply { this.location = location }
        val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
        imageCapture.takePicture(options, analysisExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(file)
            override fun onError(exc: ImageCaptureException) {
                if (retryCount < MAX_HIRES_RETRY) {
                    Log.w(TAG, "撮影に失敗しました。リトライします file=${file.name}", exc)
                    takePictureInternal(file, location, retryCount + 1, onFailure = onFailure, onSaved = onSaved)
                } else {
                    Log.e(TAG, "撮影に失敗しました（リトライ済み） file=${file.name}", exc)
                    onFailure()
                }
            }
        })
    }

    companion object {
        private const val TAG = "CameraCaptureController"
        private const val MAX_HIRES_RETRY = 1

        /**
         * 速度連動fps間隔（設計書§4.5.3）。
         * ```
         * interval(speed_kmh) =
         *     speed_kmh < 15  → 1000ms（約1fps）  … 停留所付近・乗降時は密に記録
         *     speed_kmh >= 15 → 2000ms（約0.5fps） … 走行中は間引いて容量節約
         * ```
         */
        fun intervalMsForSpeed(speedKmh: Double): Long = if (speedKmh < 15.0) 1_000L else 2_000L

        /**
         * 機種がサポートする `CONTROL_AE_TARGET_FPS_RANGE` の中から、`desiredMaxFps`
         * 以下で上限が最も高いレンジを選ぶ（設計書§4.5.1a）。Camera2Interop非対応機種等では
         * `null` を返し、呼び出し元でフォールバック（明示指定スキップ）させる。
         */
        @OptIn(ExperimentalCamera2Interop::class)
        fun resolveLowFpsRange(cameraInfo: CameraInfo, desiredMaxFps: Int = 2): Range<Int>? {
            val characteristics = Camera2CameraInfo.extractCameraCharacteristics(cameraInfo)
            val availableRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: return null
            return availableRanges.filter { it.upper <= desiredMaxFps }.maxByOrNull { it.upper }
                ?: availableRanges.minByOrNull { it.upper }
        }

        /**
         * [resolveLowFpsRange] の結果を `Camera2Interop.Extender` 経由で `ImageAnalysis.Builder` に
         * 適用する（設計書§4.5.1a）。`range` が `null` の場合は明示指定をスキップし、機種既定レートでの
         * ストリーム＋ソフトウェア間引きに留める（§4.10.2 ThermalGuardのデグレード判定がこの
         * フォールバック経路の主なセーフティネットになる）。
         */
        @OptIn(ExperimentalCamera2Interop::class)
        fun ImageAnalysis.Builder.applyLowFpsRangeIfSupported(range: Range<Int>?): ImageAnalysis.Builder = apply {
            if (range != null) {
                Camera2Interop.Extender(this).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range
                )
            }
        }
    }
}

/**
 * 低fps連写ストリームの `ImageAnalysis.Analyzer`（設計書§4.5.2）。
 * `intervalMsProvider()` が返す間隔以上経過したフレームだけをNV21→JPEG変換して [onFrame] へ渡す。
 */
class LoresFrameAnalyzer(
    private val intervalMsProvider: () -> Long,
    private val onFrame: (ByteArray, Long) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastCaptureElapsed = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCaptureElapsed >= intervalMsProvider()) {
                lastCaptureElapsed = now
                // センサーは横向き(1280x960)固定でストリームされるため、端末の物理的な向きに
                // 応じて`rotationDegrees`（正立に必要な時計回り回転角）が付与される。
                // YuvImage経由のJPEG化はEXIFを書かないため、ここで回転を適用しないと
                // 保存フレームが倒れたまま残る（既存フレームはこの修正では直らない）。
                val rotation = image.imageInfo.rotationDegrees
                val jpeg = image.toNv21JpegByteArray(quality = 75, rotationDegrees = rotation)
                onFrame(jpeg, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e("LoresFrameAnalyzer", "フレーム変換に失敗しました", e)
        } finally {
            image.close() // 必ずクローズ（未クローズはバッファ枯渇でストール）
        }
    }
}

/**
 * `ImageProxy`（YUV_420_888）をNV21 JPEGバイト列へ変換する（設計書§4.5.2、YuvImage#compressToJpeg利用）。
 *
 * ★feasibilityレビュー反映（設計書1027行目、軽微指摘5）：YUV_420_888のU/V平面は
 * row stride・pixel strideが機種依存で不定であり、単純なバイトコピーではNV21として不正になりうる。
 * そのため単純な `ByteBuffer.get(ByteArray)` によるバルクコピーは行わず、
 * [ImageProxy.PlaneProxy.rowStride] / [ImageProxy.PlaneProxy.pixelStride] を都度参照して
 * 行・画素単位で正しい位置から読み出し、NV21（Y全面 → V,U 1画素おきに交互）へ再配置する。
 *
 * [rotationDegrees] はセンサー向き画像を正立させるための時計回り回転角
 * （`ImageProxy.imageInfo.rotationDegrees`）。YuvImage経由のJPEG化はEXIF Orientationを
 * 書かないため、タグ付与ではなくピクセル自体をここで正立させる（②の位置同期・映像用途では
 * EXIF非対応の消費側でも正しく表示され、DBのwidth/heightも実寸法と一致させられるため）。
 * 0の場合は再デコード・再エンコードのコストを避けてセンサー向きJPEGバイト列をそのまま返す。
 * 連写は約1〜2fpsのため、回転が必要な場合のみ発生する再デコード＋回転＋再エンコードのコストは許容する。
 */
fun ImageProxy.toNv21JpegByteArray(quality: Int, rotationDegrees: Int = 0): ByteArray {
    require(format == ImageFormat.YUV_420_888) { "unexpected image format: $format" }
    val nv21 = toNv21ByteArray()
    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, width, height, null)
        .compressToJpeg(Rect(0, 0, width, height), quality, out)
    val sensorOrientedJpeg = out.toByteArray()
    if (rotationDegrees == 0) return sensorOrientedJpeg

    val sensorBitmap = BitmapFactory.decodeByteArray(sensorOrientedJpeg, 0, sensorOrientedJpeg.size)
        ?: return sensorOrientedJpeg
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val rotatedBitmap = Bitmap.createBitmap(
        sensorBitmap, 0, 0, sensorBitmap.width, sensorBitmap.height, matrix, true
    )
    try {
        val rotatedOut = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, rotatedOut)
        return rotatedOut.toByteArray()
    } finally {
        sensorBitmap.recycle()
        if (rotatedBitmap !== sensorBitmap) rotatedBitmap.recycle()
    }
}

private fun ImageProxy.toNv21ByteArray(): ByteArray {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val ySize = width * height
    val uvSize = (width / 2) * (height / 2)
    val nv21 = ByteArray(ySize + uvSize * 2)

    var pos = copyPlaneRowMajor(yPlane, width, height, nv21, 0)
    pos = interleaveVu(vPlane, uPlane, width / 2, height / 2, nv21, pos)
    check(pos == nv21.size) { "NV21変換後のサイズが不一致です: pos=$pos, expected=${nv21.size}" }
    return nv21
}

/**
 * 1平面をrow-majorの連続バイト列へコピーする（Y平面用。pixelStride==1かつrowStride==widthなら
 * バルクコピー、そうでなければ行・画素単位で `rowStride`/`pixelStride` を参照して読み出す）。
 */
private fun copyPlaneRowMajor(plane: ImageProxy.PlaneProxy, width: Int, height: Int, dest: ByteArray, destOffset: Int): Int {
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    var pos = destOffset

    if (pixelStride == 1 && rowStride == width) {
        buffer.rewind()
        buffer.get(dest, pos, width * height)
        pos += width * height
        return pos
    }

    val rowBuf = ByteArray(rowStride)
    for (row in 0 until height) {
        buffer.position(row * rowStride)
        val remaining = buffer.remaining().coerceAtMost(rowStride)
        buffer.get(rowBuf, 0, remaining)
        if (pixelStride == 1) {
            System.arraycopy(rowBuf, 0, dest, pos, width)
            pos += width
        } else {
            for (col in 0 until width) {
                dest[pos++] = rowBuf[col * pixelStride]
            }
        }
    }
    return pos
}

/**
 * U/V 2平面をNV21のVU交互配置へ変換する。`vPlane`/`uPlane` それぞれの `rowStride`/`pixelStride` を
 * 個別に参照する（機種によりU/V平面のストライドが異なり得るため、揃っている前提を置かない）。
 */
private fun interleaveVu(
    vPlane: ImageProxy.PlaneProxy,
    uPlane: ImageProxy.PlaneProxy,
    chromaWidth: Int,
    chromaHeight: Int,
    dest: ByteArray,
    destOffset: Int,
): Int {
    val vBuffer = vPlane.buffer
    val uBuffer = uPlane.buffer
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride
    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride

    var pos = destOffset
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val vIndex = row * vRowStride + col * vPixelStride
            val uIndex = row * uRowStride + col * uPixelStride
            dest[pos++] = vBuffer.get(vIndex)
            dest[pos++] = uBuffer.get(uIndex)
        }
    }
    return pos
}
