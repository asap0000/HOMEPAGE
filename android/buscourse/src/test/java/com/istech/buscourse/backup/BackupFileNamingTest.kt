package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.util.Random

/**
 * ファイル名の組み立て（設計ドラフト§2）の単体テスト。Android非依存の純関数のためRobolectric不要。
 */
class BackupFileNamingTest {

    @Test
    fun `originId format accepts exactly 6 lowercase alnum chars`() {
        assertThat(BackupFileNaming.isValidOriginId("k7m2x9")).isTrue()
        assertThat(BackupFileNaming.isValidOriginId("000000")).isTrue()
        assertThat(BackupFileNaming.isValidOriginId("abcdez")).isTrue()
    }

    @Test
    fun `originId format rejects wrong length, uppercase, or symbols`() {
        assertThat(BackupFileNaming.isValidOriginId("k7m2x")).isFalse() // 5文字
        assertThat(BackupFileNaming.isValidOriginId("k7m2x99")).isFalse() // 7文字
        assertThat(BackupFileNaming.isValidOriginId("K7M2X9")).isFalse() // 大文字
        assertThat(BackupFileNaming.isValidOriginId("k7m2x-")).isFalse() // 記号
    }

    @Test
    fun `generateOriginId always produces a valid format`() {
        val random = Random(42)
        repeat(50) {
            val generated = BackupFileNaming.generateOriginId(random)
            assertThat(BackupFileNaming.isValidOriginId(generated)).isTrue()
        }
    }

    @Test
    fun `gen formatting zero-pads to 4 digits`() {
        assertThat(BackupFileNaming.formatGen(0)).isEqualTo("0000")
        assertThat(BackupFileNaming.formatGen(7)).isEqualTo("0007")
        assertThat(BackupFileNaming.formatGen(9999)).isEqualTo("9999")
    }

    @Test
    fun `gen formatting rejects negative values`() {
        assertThat(runCatching { BackupFileNaming.formatGen(-1) }.isFailure).isTrue()
    }

    @Test
    fun `nextGen is monotonically increasing`() {
        var gen = 0
        repeat(10) { gen = BackupFileNaming.nextGen(gen) }
        assertThat(gen).isEqualTo(10)
    }

    @Test
    fun `buildFileName matches the canonical pattern`() {
        val name = BackupFileNaming.buildFileName(
            originId = "k7m2x9",
            gen = 7,
            createdAt = LocalDateTime.of(2026, 7, 26, 14, 32),
        )
        assertThat(name).isEqualTo("buscourse_k7m2x9_0007_20260726-1432.zip")
    }

    @Test
    fun `buildFileName rejects an invalid originId`() {
        assertThat(
            runCatching {
                BackupFileNaming.buildFileName("BAD_ID", 1, LocalDateTime.now())
            }.isFailure,
        ).isTrue()
    }

    @Test
    fun `same device does not collide across generations because gen differs`() {
        val time = LocalDateTime.of(2026, 7, 26, 14, 32)
        val first = BackupFileNaming.buildFileName("k7m2x9", 1, time)
        val second = BackupFileNaming.buildFileName("k7m2x9", 2, time)
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `different origin ids do not collide even with the same gen and time`() {
        val time = LocalDateTime.of(2026, 7, 26, 14, 32)
        val a = BackupFileNaming.buildFileName("aaaaaa", 1, time)
        val b = BackupFileNaming.buildFileName("bbbbbb", 1, time)
        assertThat(a).isNotEqualTo(b)
    }
}
