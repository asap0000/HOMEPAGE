package com.istech.buscourse.map

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import java.io.File

class MapDirectorySwapTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `failed promotion callback restores original map directory`() = runBlocking {
        val root = temp.newFolder("maps")
        val original = File(root, "region").apply { mkdirs(); File(this, "data").writeText("old") }
        val staging = File(root, ".staging-region-1").apply { mkdirs(); File(this, "data").writeText("new") }

        val result = MapDirectorySwap.replace(staging, original) { error("database write failed") }

        assertThat(result).isFalse()
        assertThat(File(original, "data").readText()).isEqualTo("old")
        assertThat(staging.exists()).isFalse()
    }

    @Test fun `successful replacement commits new directory and removes staging`() = runBlocking {
        val root = temp.newFolder("maps")
        val original = File(root, "region").apply { mkdirs(); File(this, "data").writeText("old") }
        val staging = File(root, ".staging-region-2").apply { mkdirs(); File(this, "data").writeText("new") }

        assertThat(MapDirectorySwap.replace(staging, original) {}).isTrue()
        assertThat(File(original, "data").readText()).isEqualTo("new")
        assertThat(staging.exists()).isFalse()
        assertThat(root.listFiles()!!.map { it.name }).containsExactly("region")
        Unit // runBlocking の値を返すと JUnit が「void でない」と断る
    }

    @Test fun `user import failure text is concise and contains no raw exception`() {
        val message = mapImportFailureMessage(IllegalStateException("secret details"))
        assertThat(message).isEqualTo("取り込みに失敗しました: ファイルを読み込めません")
        assertThat(message.length).isAtMost(40)
        assertThat(message).doesNotContain("secret")
    }

    @Test fun `reimport preserves current selection state`() {
        assertThat(MapDirectorySwap.preserveSelection(true)).isTrue()
        assertThat(MapDirectorySwap.preserveSelection(false)).isFalse()
        assertThat(MapDirectorySwap.preserveSelection(null)).isFalse()
    }

    @Test fun `nested zip errors receive a short user reason`() {
        val wrapped = MapPackageImportException("details", java.util.zip.ZipException("bad crc"))
        assertThat(mapImportFailureMessage(wrapped)).isEqualTo("取り込みに失敗しました: ZIPが壊れています")
    }
}
