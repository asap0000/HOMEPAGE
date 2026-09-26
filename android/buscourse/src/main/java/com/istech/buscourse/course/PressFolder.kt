package com.istech.buscourse.course

import com.istech.buscourse.core.geo.GeoMath

/**
 * 押下の畳み込み（POC 段階2・v20、2026-08-02。design-gate 改訂復唱 y×5・官房再裁定で確定した規則）:
 *
 * > **「同一セッション内で、押下と押下の間に車がその場を離れていない限り（＝同じ停車の中）、
 * > 全体の広がりが 15m に収まるなら1つに畳む。収まらなければ1つも畳まず、件数と理由を添えて流す。」**
 *
 * - **畳む＝見せ方であって削除ではない**。押下イベントは全件 DB に残り、コース創設が停留所の
 *   数を減らして見せるだけ（確定裁定「拾って渡せば下流が判断できる。畳んでしまうと判断できない」）。
 * - **「同じ停車の中」の判定は両端の距離ではなく押下間の軌跡**で行う（間の最大離脱距離）。
 *   両端だけ見ると**周回で戻ってきた再訪**（両端 0m・間にループ1周）を誤って畳む——
 *   再訪は戸籍 1:N の N そのもので、EX が「落とすな」と名指しした材料（官房再裁定 2026-08-02）。
 * - **広がり 15m 超の停車鎖は1つも畳まない**（分割もしない＝切り方の決定には同一性の知識が要り、
 *   それは EX にしかない）。**畳まなかった事実（件数＋理由）も下流へ伝える**（畳まなかったことも出自の情報）。
 * - 時間の閾値は持たない（旧「間隔1秒」は撤廃済み——押し直し（31〜36秒・差し渡し0.1〜1.1m・実測4組）が
 *   1つも畳めなかったため）。
 * - [stayDepartM] の既定は**仮値**。実測は片側のみ（同じ停車66組＝最大離脱 6.4m。周回再訪側は
 *   実データ0件＝次の実走「周回再訪周」で確定する）。design-gate 復唱5「既定は測ってから決める」。
 *
 * Android 非依存の純ロジック（JVM 単体テスト可能）。
 */
object PressFolder {

    /** 畳み対象の押下（`stop_visit_event` の MANUAL・カードなし・座標つき行の最小表現）。 */
    data class Press(val eventId: Long, val ts: Long, val lat: Double, val lon: Double)

    /** 軌跡点（`gps_point` の最小表現）。 */
    data class TrackPoint(val ts: Long, val lat: Double, val lon: Double)

    /**
     * 1グループ＝停留所1つ分（または畳めなかった押下1件ずつ）。
     *
     * [folded] が true なら [presses] 全体で停留所1つ（代表＝**先頭の押下**。座標もイベント参照も
     * 先頭の実記録を使う——代表を計算で作らない。筆頭写真も先頭押下の `hires_frame_id` 参照から選ぶ）。
     * [spanM] はグループの差し渡し（最大ペア距離）＝`error_space_m` へそのまま書く値
     * （隣接距離は使わない＝約3倍過小・官房裁定）。
     */
    data class Group(
        val presses: List<Press>,
        val folded: Boolean,
        val spanM: Double,
        /** 畳まなかった理由（畳んだグループと単独押下は null）。 */
        val unfoldedReason: String? = null,
    ) {
        val representative: Press get() = presses.first()
    }

    data class Result(
        val groups: List<Group>,
        /** 畳んだ押下数（押下総数 − 停留所数）。画面と EX への報告値。 */
        val foldedPressCount: Int,
        /** 広がり超過で畳まなかった停車鎖の数（0 なら全部畳めた）。理由つきで下流へ伝える。 */
        val oversizeChainCount: Int,
    )

    /** 広がりの上限（確定規則の 15m）。EX の同定閾値と同じ値＝App が畳むのは EX の機械も束ねるものだけ。 */
    const val SPAN_LIMIT_M = 15.0

    /** 「その場を離れた」とみなす離脱距離の**仮**既定（実測で確定するまでの値。KDoc 参照）。 */
    const val DEFAULT_STAY_DEPART_M = 20.0

    /**
     * [presses]（同一セッション・時系列順であること）を確定規則で畳む。
     *
     * 手順: ①「同じ停車の中」の鎖を作る——連続する押下間の軌跡（[track] の該当区間）が
     * 2押下の中点から [stayDepartM] を超えて離れていなければ同じ停車とみなす
     * ②鎖ごとに差し渡しを測り、[SPAN_LIMIT_M] 以内なら畳む・超えたら**1つも畳まず**個別で流す。
     */
    fun fold(
        presses: List<Press>,
        track: List<TrackPoint>,
        stayDepartM: Double = DEFAULT_STAY_DEPART_M,
        spanLimitM: Double = SPAN_LIMIT_M,
    ): Result {
        if (presses.isEmpty()) return Result(emptyList(), 0, 0)

        // ① 同じ停車の鎖
        val chains = mutableListOf<MutableList<Press>>(mutableListOf(presses.first()))
        for (i in 1 until presses.size) {
            val prev = presses[i - 1]
            val next = presses[i]
            if (stayedTogether(prev, next, track, stayDepartM)) {
                chains.last() += next
            } else {
                chains += mutableListOf(next)
            }
        }

        // ② 鎖ごとに差し渡しで判定
        val groups = mutableListOf<Group>()
        var folded = 0
        var oversize = 0
        for (chain in chains) {
            val span = spanM(chain)
            when {
                chain.size == 1 -> groups += Group(chain, folded = false, spanM = 0.0)
                span <= spanLimitM -> {
                    groups += Group(chain, folded = true, spanM = span)
                    folded += chain.size - 1
                }
                else -> {
                    // 収まらなければ1つも畳まない（分割しない）。理由を全押下に添えて個別で流す
                    oversize++
                    val reason = "広がり%.1fm が上限 %.0fm を超過".format(span, spanLimitM)
                    chain.forEach { groups += Group(listOf(it), folded = false, spanM = 0.0, unfoldedReason = reason) }
                }
            }
        }
        return Result(groups, folded, oversize)
    }

    /**
     * [prev]→[next] の間、車が「その場」（2押下の中点）から [stayDepartM] を超えて離れなかったか。
     * 軌跡が1点も無い区間は座標差で代用する（旧 `minDistanceM=3f` 時代のデータ＝停車中に位置が来ない、
     * への保険。現行実装は 1Hz で必ず来る）。
     */
    private fun stayedTogether(prev: Press, next: Press, track: List<TrackPoint>, stayDepartM: Double): Boolean {
        val midLat = (prev.lat + next.lat) / 2
        val midLon = (prev.lon + next.lon) / 2
        val between = track.filter { it.ts in prev.ts..next.ts }
        if (between.isEmpty()) {
            return GeoMath.haversineM(prev.lat, prev.lon, next.lat, next.lon) <= stayDepartM
        }
        return between.all { GeoMath.haversineM(midLat, midLon, it.lat, it.lon) <= stayDepartM }
    }

    /** グループの差し渡し（最大ペア距離）。押下数は高々十数件なので O(n^2) で足りる。 */
    private fun spanM(presses: List<Press>): Double {
        var max = 0.0
        for (i in presses.indices) {
            for (j in i + 1 until presses.size) {
                val d = GeoMath.haversineM(presses[i].lat, presses[i].lon, presses[j].lat, presses[j].lon)
                if (d > max) max = d
            }
        }
        return max
    }
}
