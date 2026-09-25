package com.istech.buscourse.navimap

import android.content.Context
import com.istech.buscourse.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** debug ビルドの先読み調査記録。座標は記録しない。 */
internal object NaviLeadDiagnostic {
    suspend fun append(
        context: Context,
        elapsedRealtimeMs: Long,
        chainageM: Double,
        onCourse: Boolean,
        searchAll: Boolean,
        speedMps: Double?,
        leadSec: Double,
        lookupChainageM: Double,
        reset: Boolean,
        cue: NaviFrameCue?,
        frameFile: File?,
    ) {
        if (!BuildConfig.DEBUG) return
        withContext(Dispatchers.IO) {
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val directory = File(context.filesDir, "buscourse/diag")
            directory.mkdirs()
            val row = listOf(
                elapsedRealtimeMs, chainageM, onCourse, searchAll, speedMps ?: "", leadSec,
                lookupChainageM, if (reset) 1 else 0, cue?.sessionId ?: "", cue?.capturedAtMs ?: "",
                frameFile?.name ?: "",
            ).joinToString("\t") + "\n"
            File(directory, "navi_lead_$day.tsv").appendText(row)
        }
    }
}
