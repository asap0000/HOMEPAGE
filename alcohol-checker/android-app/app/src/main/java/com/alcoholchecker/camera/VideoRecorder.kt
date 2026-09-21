package com.alcoholchecker.camera

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class VideoRecorder(private val context: Context) {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    /**
     * インカメラを [previewView] にバインドし、録画の準備をする。
     * 返り値はバインドに使った ProcessCameraProvider（後で unbind に使用可能）。
     */
    @SuppressLint("MissingPermission")
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                videoCapture
            )
            cont.resume(provider)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 録画を開始して生の MP4 ファイルパスを返す。
     * 録画は [stopRecording] を呼ぶまで継続する。
     */
    @SuppressLint("MissingPermission")
    fun startRecording(outputDir: File, sessionId: String): String {
        val file = File(outputDir, "raw_$sessionId.mp4")
        val options = FileOutputOptions.Builder(file).build()

        activeRecording = videoCapture!!.output
            .prepareRecording(context, options)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                // 録画イベントは ViewModel で必要に応じて監視
                if (event is VideoRecordEvent.Finalize && event.hasError()) {
                    android.util.Log.e("VideoRecorder", "Recording error: ${event.error}")
                }
            }
        return file.absolutePath
    }

    /** 録画を停止する。完了まで非同期で行われる。 */
    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun isRecording() = activeRecording != null
}
