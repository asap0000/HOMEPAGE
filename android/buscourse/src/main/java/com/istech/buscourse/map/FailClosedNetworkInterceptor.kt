package com.istech.buscourse.map

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 補助防御：MapLibreのHTTPスタック（OkHttp）に割り込み、http(s)スキームのリクエストを
 * 到達前に例外で落とすフェイルクローズ機構（設計書§5.5、D8の二次防御）。
 *
 * §5.2.3の実測どおり`mbtiles://`はネイティブ層で処理されOkHttpを一切経由しないため、
 * 地図描画の主経路としては本Interceptorは不要である。§5.3.1のマニフェスト除去
 * （`INTERNET`権限の`tools:node="remove"`、Layer1）が効いていれば、本Interceptorに到達する前に
 * Android OS側でソケットオープン自体が拒否される。それでも「スタイルJSONに誤って`http(s)://`が
 * 紛れ込んだ場合に、サイレントに何も起きない（＝気づかれない）」より「即座に失敗して顕在化する」方が
 * 安全という考え方から、**「Layer1が万一無効化された場合の追加の砦」**として追加する。
 *
 * §5.5冒頭の統合注記のとおり、`mbtiles://`をこのInterceptor側で解釈してローカルSQLiteから
 * 応答する`LocalMbtilesInterceptor`のような二重実装は行わない（役割は「mbtiles以外の
 * あらゆるリクエストを問答無用で落とすフェイルクローズ」に単純化する）。
 *
 * [com.istech.buscourse.BusCourseApplication.onCreate]で`HttpRequestUtil.setOkHttpClient(...)`
 * により差し替えて有効化する。
 */
class FailClosedNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        throw IOException("offline policy violation: blocked scheme=${url.scheme} host=${url.host}")
    }
}
