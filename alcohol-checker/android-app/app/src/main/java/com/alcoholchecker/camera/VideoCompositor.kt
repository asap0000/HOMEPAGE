package com.alcoholchecker.camera

import android.graphics.*
import android.media.*
import android.opengl.*
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** スーパーインポーズに使うメタデータ */
data class OverlayData(
    val driverName: String,
    val driverId: String,
    val checkType: String,
    val recordingStartMs: Long,
    val locationName: String,
    val latLng: String,
    val healthStatus: String,
    val weather: String,
    val weatherTemp: Float?,
    val finalBac: Float,
    val isPassed: Boolean,
)

/**
 * 後処理スーパーインポーズ合成器。
 * 動画フレームごとに OpenGL ES 経由でテキストオーバーレイを焼き付ける。
 *
 * 処理フロー:
 *   MediaExtractor → MediaCodec(decoder, Surface出力)
 *       → SurfaceTexture(OES) → OpenGL合成 + Canvas描画
 *       → MediaCodec(encoder, Surface入力) → MediaMuxer → 出力MP4
 */
class VideoCompositor {

    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN)

    suspend fun compose(
        inputPath: String,
        outputPath: String,
        data: OverlayData,
        onProgress: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {

        // ── 入力解析 ─────────────────────────────────────────
        val extractor = MediaExtractor().apply { setDataSource(inputPath) }
        val videoTrackIdx = (0 until extractor.trackCount).first { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        }
        extractor.selectTrack(videoTrackIdx)
        val inFmt   = extractor.getTrackFormat(videoTrackIdx)
        val width   = inFmt.getInteger(MediaFormat.KEY_WIDTH)
        val height  = inFmt.getInteger(MediaFormat.KEY_HEIGHT)
        val durationUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION))
            inFmt.getLong(MediaFormat.KEY_DURATION) else 10_000_000L
        val fps = if (inFmt.containsKey(MediaFormat.KEY_FRAME_RATE))
            inFmt.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

        // ── エンコーダ設定 ────────────────────────────────────
        val outFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        // ── EGL セットアップ ──────────────────────────────────
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1)

        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, configs, 0, 1, IntArray(1), 0)
        val eglConfig = configs[0]!!

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
        val winAttr    = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, winAttr, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // ── OES テクスチャ（デコーダ出力受け取り用）──────────
        val oesTex = IntArray(1)
        GLES20.glGenTextures(1, oesTex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val surfaceTexture = android.graphics.SurfaceTexture(oesTex[0])
        var frameAvailable = false
        surfaceTexture.setOnFrameAvailableListener { frameAvailable = true }
        val decoderSurface = Surface(surfaceTexture)

        // ── デコーダ設定 ──────────────────────────────────────
        val decoder = MediaCodec.createDecoderByType(
            inFmt.getString(MediaFormat.KEY_MIME)!!
        )
        decoder.configure(inFmt, decoderSurface, null, 0)
        decoder.start()

        // ── GL シェーダ ───────────────────────────────────────
        val program = buildShaderProgram()
        GLES20.glUseProgram(program)

        // ── オーバーレイ Bitmap（静的部分）──────────────────
        val overlayBmp = buildOverlayBitmap(width, height, data)
        val overlayTex = IntArray(1)
        GLES20.glGenTextures(1, overlayTex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBmp, 0)
        overlayBmp.recycle()

        // ── デコード ↔ エンコード ループ ─────────────────────
        val timeout = 10_000L
        val bufInfo = MediaCodec.BufferInfo()
        var inputDone  = false
        var outputDone = false
        var processedUs = 0L

        while (!outputDone) {
            // デコーダへ入力
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(timeout)
                if (inIdx >= 0) {
                    val buf  = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val pts = extractor.sampleTime
                        decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                        extractor.advance()
                    }
                }
            }

            // デコーダ出力 → Surface (SurfaceTexture) へ render
            val outIdx = decoder.dequeueOutputBuffer(bufInfo, timeout)
            if (outIdx >= 0) {
                val render = bufInfo.size > 0
                decoder.releaseOutputBuffer(outIdx, render)  // render=true → SurfaceTexture へ送出

                if (render) {
                    // SurfaceTexture が更新されるまでスピン（最大 100ms）
                    val wait = System.currentTimeMillis()
                    while (!frameAvailable && System.currentTimeMillis() - wait < 100) {
                        Thread.sleep(1)
                    }
                    frameAvailable = false
                    surfaceTexture.updateTexImage()

                    // OpenGL で合成描画
                    GLES20.glViewport(0, 0, width, height)
                    drawComposited(program, oesTex[0], overlayTex[0],
                        surfaceTexture.getTransformMatrix())
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                    processedUs = bufInfo.presentationTimeUs
                    onProgress(processedUs.coerceAtMost(durationUs).toFloat() / durationUs)
                }

                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    encoder.signalEndOfInputStream()
                    outputDone = true
                }
            }
        }

        // ── エンコーダ出力 → MediaMuxer ──────────────────────
        val muxer   = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxStarted = false
        val encBufInfo = MediaCodec.BufferInfo()
        var encDone = false

        while (!encDone) {
            val encIdx = encoder.dequeueOutputBuffer(encBufInfo, timeout)
            when {
                encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxTrack   = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxStarted = true
                }
                encIdx >= 0 -> {
                    val encBuf = encoder.getOutputBuffer(encIdx)!!
                    if (encBufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        encBufInfo.size = 0
                    }
                    if (muxStarted && encBufInfo.size > 0) {
                        muxer.writeSampleData(muxTrack, encBuf, encBufInfo)
                    }
                    encoder.releaseOutputBuffer(encIdx, false)
                    if (encBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encDone = true
                    }
                }
            }
        }

        // ── リソース解放 ──────────────────────────────────────
        muxer.stop(); muxer.release()
        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        decoderSurface.release()
        surfaceTexture.release()
        extractor.release()

        onProgress(1f)
    }

    // ── オーバーレイ Bitmap 生成 ─────────────────────────────
    private fun buildOverlayBitmap(w: Int, h: Int, d: OverlayData): Bitmap {
        val bmp    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val barH   = (h * 0.15f).toInt()     // 上下バーの高さ
        val bgPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }
        val txtPaint = Paint().apply {
            color     = Color.WHITE
            textSize  = (h * 0.035f)
            isAntiAlias = true
        }
        val smallTxt = Paint(txtPaint).apply { textSize = h * 0.028f }
        val boldTxt  = Paint(txtPaint).apply {
            textSize  = h * 0.042f
            isFakeBoldText = true
            color = if (d.isPassed) Color.rgb(100, 220, 100) else Color.rgb(255, 100, 100)
        }

        // ── 上部バー ─────────────────────────────────────────
        canvas.drawRect(0f, 0f, w.toFloat(), barH.toFloat(), bgPaint)
        val margin = w * 0.02f
        var y = barH * 0.38f
        canvas.drawText("${d.driverName}  [${d.driverId}]  ${d.checkType}", margin, y, txtPaint)
        y += barH * 0.40f
        canvas.drawText(
            fmt.format(Date(d.recordingStartMs)) + "  ${d.locationName}",
            margin, y, smallTxt
        )

        // ── 下部バー ─────────────────────────────────────────
        val barTop = (h - barH).toFloat()
        canvas.drawRect(0f, barTop, w.toFloat(), h.toFloat(), bgPaint)
        y = barTop + barH * 0.38f
        val weatherStr = if (d.weatherTemp != null)
            "${d.weather}  ${"%.1f".format(d.weatherTemp)}℃" else d.weather
        canvas.drawText("健康状態: ${d.healthStatus}   天候: $weatherStr", margin, y, smallTxt)
        y += barH * 0.40f

        // BAC 数値 + 判定（色分け）
        val bacStr   = "BAC: ${"%.3f".format(d.finalBac)} mg/L"
        val judgStr  = if (d.isPassed) "合格 ✓" else "不合格 ✗"
        canvas.drawText(bacStr, margin, y, txtPaint)
        canvas.drawText(judgStr, w - margin - boldTxt.measureText(judgStr), y, boldTxt)

        // ── GPS 座標 (右上) ──────────────────────────────────
        val coordPaint = Paint(smallTxt).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(d.latLng, w - margin, barH * 0.38f, coordPaint)

        return bmp
    }

    // ── GL シェーダプログラム ─────────────────────────────────
    private fun buildShaderProgram(): Int {
        val vertSrc = """
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying   vec2 vUv;
            uniform   mat4 uTexMatrix;
            void main() {
                gl_Position = aPos;
                vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val fragSrc = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uCamera;
            uniform sampler2D          uOverlay;
            void main() {
                vec4 cam = texture2D(uCamera,  vUv);
                vec4 ov  = texture2D(uOverlay, vec2(vUv.x, 1.0 - vUv.y));
                gl_FragColor = mix(cam, ov.rgb1, ov.a);
            }
        """.trimIndent()

        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vertSrc))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fragSrc))
        GLES20.glLinkProgram(prog)
        return prog
    }

    // ── フルスクリーンクワッド描画 ────────────────────────────
    private val quadVerts = floatArrayOf(
        -1f, -1f,  0f, 0f,
         1f, -1f,  1f, 0f,
        -1f,  1f,  0f, 1f,
         1f,  1f,  1f, 1f,
    )

    private fun drawComposited(prog: Int, camTex: Int, ovTex: Int, texMatrix: FloatArray) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUv  = GLES20.glGetAttribLocation(prog, "aUv")

        val buf = java.nio.ByteBuffer.allocateDirect(quadVerts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(quadVerts); position(0) }

        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buf.apply { position(0) })
        GLES20.glVertexAttribPointer(aUv,  2, GLES20.GL_FLOAT, false, 16, buf.apply { position(2) })
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)

        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(prog, "uTexMatrix"), 1, false, texMatrix, 0
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uCamera"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ovTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uOverlay"), 1)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        /** 合成後の最終ファイルパスを返す。rawPath の "raw_" プレフィクスを除去する。 */
        fun finalPathFrom(rawPath: String): String =
            rawPath.replace("/raw_", "/final_")
    }
}
