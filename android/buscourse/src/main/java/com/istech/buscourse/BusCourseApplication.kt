package com.istech.buscourse

import android.app.Application
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.map.FailClosedNetworkInterceptor
import com.istech.buscourse.recording.StorageRotationWorker
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * BusCourse アプリケーションクラス。
 * アプリ全体で共有するシングルトン（Room DB 等）の初期化起点。オフライン厳守（ネットワーク接続経路を持たない）。
 *
 * `WorkManager` によるストレージローテーション（`StorageRotationWorker`）の定期登録もここで行う
 * （設計書§4.10.3「登録（BusCourseApplication#onCreate）」）。`WorkManager` はネットワーク通信を
 * 一切伴わないローカルタスクスケジューラであり、オフライン方針に抵触しない。
 */
class BusCourseApplication : Application() {

    /** Room DB のアプリ全体シングルトン（設計書§3.2）。Activity/Service/Workerから共有して使う。 */
    val database: BusCourseDatabase by lazy { BusCourseDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        StorageRotationWorker.schedule(this)

        // 補助防御：MapLibreのOkHttpClientをフェイルクローズ実装へ差し替える（設計書§5.5、D8二次防御）。
        // 一次防御（§5.3.1 AndroidManifest.xmlのINTERNET権限除去、Layer1）に次ぐ二段目の砦であり、
        // Layer1が効いている限り本Interceptorは正常経路では呼ばれない（FailClosedNetworkInterceptor
        // のKDoc参照）。MapLibre.getInstance()等の初期化より前に呼ぶ必要はない
        // （HttpRequestUtilは静的な差し替えAPIで、後続の全リクエストに適用される）。
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor(FailClosedNetworkInterceptor())
                .build()
        )
    }
}
