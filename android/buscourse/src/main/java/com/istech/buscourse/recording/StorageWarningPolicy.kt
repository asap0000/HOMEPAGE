package com.istech.buscourse.recording

/**
 * 「設計」「データ出力」を開く前に空き容量の窓を出すかの判定（2026-10-20 委託指示書 K・純関数）。
 *
 * 発端は OPPO の本番走行で、停留所マーカーを押している最中に端末の「空き容量不足」の警告が画面に重なったこと。
 * 端末が自分で警告を出す線（機種ごとに違う。OPPO は約 6.8GB）より手前で、先に知らせる。
 *
 * - microSD あり: 本体の空き < 端末の警告の線 + 1GB、または microSD の空き < 2GB（運行1本分）。
 *   （運行の写真と位置は microSD に載るので、本体の目安は低くてよい。10GB を毎回空けるのは難しい）
 * - microSD なし: 本体の空き < 本体の全容量の 10%。
 *
 * GB は 1024³ バイト（画面の MB 表示と同じ 2 進の単位）。
 */
object StorageWarningPolicy {

    const val GIB = 1024L * 1024 * 1024

    /** microSD ありのとき、本体の目安は「端末の警告の線」にこの余裕を足した値。 */
    const val BODY_MARGIN_OVER_DEVICE_LINE_BYTES = 1L * GIB

    /** microSD の空きがこれを切ったら窓を出す（運行1本分。約 1.6GB・3時間に余裕を足した値）。 */
    const val SD_MIN_FREE_BYTES = 2L * GIB

    /** microSD なしのとき、本体の空きが全容量のこの割合（%）を切ったら窓を出す。 */
    const val BODY_FREE_PERCENT_WITHOUT_SD = 10L

    /** 窓に出す運行1本の目安。 */
    const val RUN_ESTIMATE_TEXT = "約 1.6GB（3時間）"

    /** 端末の警告の線に入れられる値の範囲（GB）。打ち間違い（0 や 600）を弾く。 */
    const val MIN_DEVICE_WARNING_LINE_GB = 0.5f
    const val MAX_DEVICE_WARNING_LINE_GB = 64f

    /**
     * 判定の結果。[bodyThresholdBytes] は本体の目安（窓に「目安 7.8GB」と出す値）。
     * [bodyLow]・[sdLow] は窓の見出しを出し分けるために別々に持つ。
     */
    data class Check(val bodyLow: Boolean, val sdLow: Boolean, val bodyThresholdBytes: Long) {
        val shouldWarn: Boolean get() = bodyLow || sdLow
    }

    /**
     * @param sdFreeBytes microSD の空き。microSD が無ければ null（＝「microSD なし」の判定になる）。
     * @param deviceWarningLineBytes 端末の警告の線（設定値）。
     */
    fun check(
        bodyFreeBytes: Long,
        bodyTotalBytes: Long,
        sdFreeBytes: Long?,
        deviceWarningLineBytes: Long,
    ): Check {
        if (sdFreeBytes == null) {
            val threshold = bodyTotalBytes * BODY_FREE_PERCENT_WITHOUT_SD / 100
            return Check(bodyLow = bodyFreeBytes < threshold, sdLow = false, bodyThresholdBytes = threshold)
        }
        val threshold = deviceWarningLineBytes + BODY_MARGIN_OVER_DEVICE_LINE_BYTES
        return Check(
            bodyLow = bodyFreeBytes < threshold,
            sdLow = sdFreeBytes < SD_MIN_FREE_BYTES,
            bodyThresholdBytes = threshold,
        )
    }

    fun gbToBytes(gb: Float): Long = (gb * GIB).toLong()

    /** 設定画面の入力欄の文字を GB の値にする。数でない・範囲外なら null（保存しない）。 */
    fun parseDeviceWarningLineGb(text: String): Float? =
        text.trim().toFloatOrNull()?.takeIf { it in MIN_DEVICE_WARNING_LINE_GB..MAX_DEVICE_WARNING_LINE_GB }
}
