package com.istech.buscourse.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 空き容量の窓を出す判定（2026-10-20 委託指示書 K）。数は画面と同じ 1024³ の GB。
 * 例の数字は架空の端末（全容量 106GB・警告の線 6.8GB）。
 */
class StorageWarningPolicyTest {
    private val gib = StorageWarningPolicy.GIB
    private fun gb(value: Double) = (value * gib).toLong()
    private val total = gb(106.0)
    private val line = StorageWarningPolicy.gbToBytes(6.8f)

    @Test
    fun `test_microSDありで本体の空きが警告の線プラス1GBを切ると窓が出る`() {
        // 目安は 6.8GB + 1GB = 7.8GB。
        val below = StorageWarningPolicy.check(bodyFreeBytes = gb(7.7), bodyTotalBytes = total, sdFreeBytes = gb(98.0), deviceWarningLineBytes = line)
        assertThat(below.shouldWarn).isTrue()
        assertThat(below.bodyLow).isTrue()
        assertThat(below.sdLow).isFalse()
        assertThat(below.bodyThresholdBytes).isEqualTo(line + gb(1.0))

        // 目安ちょうど・それより多ければ出ない。
        val atLine = StorageWarningPolicy.check(bodyFreeBytes = line + gb(1.0), bodyTotalBytes = total, sdFreeBytes = gb(98.0), deviceWarningLineBytes = line)
        assertThat(atLine.shouldWarn).isFalse()
        val above = StorageWarningPolicy.check(bodyFreeBytes = gb(12.0), bodyTotalBytes = total, sdFreeBytes = gb(98.0), deviceWarningLineBytes = line)
        assertThat(above.shouldWarn).isFalse()

        // 全容量の 10%（10.6GB）には引っ張られない。microSD があるときは警告の線＋1GB だけが目安。
        val tenPercentButAboveLine = StorageWarningPolicy.check(bodyFreeBytes = gb(8.0), bodyTotalBytes = total, sdFreeBytes = gb(98.0), deviceWarningLineBytes = line)
        assertThat(tenPercentButAboveLine.shouldWarn).isFalse()

        // 警告の線を変えると目安も動く（機種ごとに違う線）。
        val moved = StorageWarningPolicy.check(bodyFreeBytes = gb(8.0), bodyTotalBytes = total, sdFreeBytes = gb(98.0), deviceWarningLineBytes = StorageWarningPolicy.gbToBytes(9f))
        assertThat(moved.shouldWarn).isTrue()
    }

    @Test
    fun `test_microSDの空きが2GBを切ると窓が出る`() {
        val below = StorageWarningPolicy.check(bodyFreeBytes = gb(20.0), bodyTotalBytes = total, sdFreeBytes = gb(1.9), deviceWarningLineBytes = line)
        assertThat(below.shouldWarn).isTrue()
        assertThat(below.sdLow).isTrue()
        assertThat(below.bodyLow).isFalse()

        val atTwo = StorageWarningPolicy.check(bodyFreeBytes = gb(20.0), bodyTotalBytes = total, sdFreeBytes = 2 * gib, deviceWarningLineBytes = line)
        assertThat(atTwo.shouldWarn).isFalse()

        // 両方足りないときは両方が立つ（窓の見出しを出し分ける）。
        val both = StorageWarningPolicy.check(bodyFreeBytes = gb(3.0), bodyTotalBytes = total, sdFreeBytes = gb(0.5), deviceWarningLineBytes = line)
        assertThat(both.bodyLow).isTrue()
        assertThat(both.sdLow).isTrue()
    }

    @Test
    fun `test_microSDなしは本体の全容量の10パーセントで窓が出る`() {
        // 全容量 106GB の 10% は 10.6GB。警告の線（6.8GB）は使わない。
        val below = StorageWarningPolicy.check(bodyFreeBytes = gb(10.5), bodyTotalBytes = total, sdFreeBytes = null, deviceWarningLineBytes = line)
        assertThat(below.shouldWarn).isTrue()
        assertThat(below.bodyLow).isTrue()
        assertThat(below.sdLow).isFalse()
        assertThat(below.bodyThresholdBytes).isEqualTo(total * 10 / 100)

        val above = StorageWarningPolicy.check(bodyFreeBytes = gb(10.7), bodyTotalBytes = total, sdFreeBytes = null, deviceWarningLineBytes = line)
        assertThat(above.shouldWarn).isFalse()

        // 警告の線（6.8GB）より多くても、10% を切っていれば出る。
        val aboveLineBelowTenPercent = StorageWarningPolicy.check(bodyFreeBytes = gb(9.0), bodyTotalBytes = total, sdFreeBytes = null, deviceWarningLineBytes = line)
        assertThat(aboveLineBelowTenPercent.shouldWarn).isTrue()
    }

    @Test
    fun `端末の警告の線の入力は範囲内の数だけ受け取る`() {
        assertThat(StorageWarningPolicy.parseDeviceWarningLineGb("6.8")).isEqualTo(6.8f)
        assertThat(StorageWarningPolicy.parseDeviceWarningLineGb(" 7 ")).isEqualTo(7f)
        assertThat(StorageWarningPolicy.parseDeviceWarningLineGb("")).isNull()
        assertThat(StorageWarningPolicy.parseDeviceWarningLineGb("6.")).isEqualTo(6f)
        assertThat(StorageWarningPolicy.parseDeviceWarningLineGb("abc")).isNull()
        assertThat(StorageWarningPolicy.parseDeviceWarningLineGb("0")).isNull()
        assertThat(StorageWarningPolicy.parseDeviceWarningLineGb("-3")).isNull()
        assertThat(StorageWarningPolicy.parseDeviceWarningLineGb("600")).isNull()
        assertThat(StorageWarningPolicy.parseDeviceWarningLineGb("NaN")).isNull()
    }
}
