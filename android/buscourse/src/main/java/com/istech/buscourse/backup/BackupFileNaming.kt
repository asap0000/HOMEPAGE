package com.istech.buscourse.backup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random

/**
 * 機種変更バックアップのファイル名の組み立て（設計ドラフト§2が正）。
 *
 * ```
 * buscourse_<originId>_<gen4桁>_<yyyyMMdd-HHmm>.zip
 * 例: buscourse_k7m2x9_0007_20260726-1432.zip
 * ```
 *
 * Android非依存の純関数のみを置く（`java.time`のみ使用。API26以降はネイティブ対応のため
 * デスシュガリング不要）。
 */
object BackupFileNaming {

    /** originId は英小文字＋数字ちょうど6文字（設計ドラフト§2）。 */
    const val ORIGIN_ID_LENGTH = 6
    private const val ORIGIN_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val ORIGIN_ID_REGEX = Regex("^[a-z0-9]{$ORIGIN_ID_LENGTH}\$")

    private val FILE_NAME_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** [originId] が「英小文字＋数字6文字」の書式かどうか。 */
    fun isValidOriginId(originId: String): Boolean = ORIGIN_ID_REGEX.matches(originId)

    /**
     * 新規 originId を生成する（初回起動時に一度だけ呼ぶ想定。外部の端末識別子は使わない、設計ドラフト§2★）。
     * [random] を差し替え可能にして単体テストで書式のみを検証できるようにする。
     */
    fun generateOriginId(random: Random = Random()): String =
        buildString(ORIGIN_ID_LENGTH) {
            repeat(ORIGIN_ID_LENGTH) {
                append(ORIGIN_ID_ALPHABET[random.nextInt(ORIGIN_ID_ALPHABET.length)])
            }
        }

    /** gen を4桁ゼロ埋めした文字列にする。[gen] は0以上を要求する。 */
    fun formatGen(gen: Int): String {
        require(gen >= 0) { "gen は0以上である必要があります: $gen" }
        return gen.toString().padStart(4, '0')
    }

    /** 「押すたびに+1」（設計ドラフト§2）。時計が狂っても順序が分かるよう常に単調増加させる。 */
    fun nextGen(currentGen: Int): Int {
        require(currentGen >= 0) { "currentGen は0以上である必要があります: $currentGen" }
        return currentGen + 1
    }

    /**
     * ファイル名本体を組み立てる。[originId]・[gen] の書式検査を行ってから
     * `buscourse_<originId>_<gen4桁>_<yyyyMMdd-HHmm>.zip` を返す。
     */
    fun buildFileName(originId: String, gen: Int, createdAt: LocalDateTime): String {
        require(isValidOriginId(originId)) {
            "originId の形式が不正です（英小文字＋数字ちょうど${ORIGIN_ID_LENGTH}文字）: $originId"
        }
        val genPart = formatGen(gen)
        val dateTimePart = createdAt.format(FILE_NAME_DATE_FORMATTER)
        return "buscourse_${originId}_${genPart}_${dateTimePart}.zip"
    }
}
