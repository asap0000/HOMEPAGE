package com.istech.buscourse

import android.app.Application
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.map.FailClosedNetworkInterceptor
import com.istech.buscourse.recording.StorageRotationWorker
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
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

        // MapLibre本体の初期化。`HttpRequestUtil.setOkHttpClient(...)`（下記）は
        // `org.maplibre.android.module.http.HttpRequestImpl`のクラス初期化(<clinit>)を誘発し、
        // その中で`MapLibre.getApplicationContext()`→`MapLibre.validateMapLibre()`が走るため、
        // **先に`MapLibre.getInstance()`を呼んでおく必要がある**（呼んでいないと
        // `MapLibreConfigurationException`で起動時クラッシュする。実機検証で確認済み）。
        // 単一引数の`getInstance(Context)`はAPIキー不要・`WellKnownTileServer.MapLibre`を既定値に
        // するだけの軽量オーバーロード（AAR実測で確認）。本アプリはmbtiles://のみを参照し実際の
        // タイルサーバへは接続しないため、この既定値で問題ない。
        MapLibre.getInstance(this)

        // MapLibre内部の`ConnectivityReceiver`はCONNECTIVITY_CHANGE受信時に
        // `ConnectivityManager.getActiveNetworkInfo()`を呼ぶが、本アプリはオフライン厳守方針
        // （istech/app-android CLAUDE.md、§5.3.1 Layer1）により`ACCESS_NETWORK_STATE`を
        // AndroidManifest.xmlから`tools:node="remove"`で除去しているため、これを呼ぶと
        // `SecurityException`で地図画面表示時に必ずクラッシュする（実機/エミュレータ検証で確認済み）。
        // `MapLibre.setConnected(Boolean)`はMapLibre/Mapbox公式が「オフラインを自前管理するアプリ」
        // 向けに用意したAPIで、システムの接続状態問い合わせをバイパスして手動値を強制する
        // （AAR実測: `MapLibre.setConnected(java.lang.Boolean)`。引数はBoxed Booleanのためfalse
        // リテラルをそのまま渡せる）。本アプリは`mbtiles://`のローカルタイルのみを参照し実際の
        // ネットワーク接続を必要としないため、常時`false`固定でよい。
        // `ACCESS_NETWORK_STATE`をマニフェストに再追加することは絶対にしない
        // （オフライン厳守方針・CI Layer1検査の両方に違反するため）。
        MapLibre.setConnected(false)

        // 補助防御：MapLibreのOkHttpClientをフェイルクローズ実装へ差し替える（設計書§5.5、D8二次防御）。
        // 一次防御（§5.3.1 AndroidManifest.xmlのINTERNET権限除去、Layer1）に次ぐ二段目の砦であり、
        // Layer1が効いている限り本Interceptorは正常経路では呼ばれない（FailClosedNetworkInterceptor
        // のKDoc参照）。
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor(FailClosedNetworkInterceptor())
                .build()
        )
    }
}
