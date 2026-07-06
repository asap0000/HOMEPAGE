package com.privacycamera.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.privacycamera.testutil.TestImages
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 低ストレージ等で書き込みが失敗した際の回帰テスト(§16-3)。
 * 「失敗を通知し、整合性が壊れない」= 例外を投げる かつ 部分書き込みを残さない、を検証する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecurePhotoStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun jpegBytes(): ByteArray {
        val bmp = TestImages.gradient()
        return ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
    }

    @Test
    fun save_whenMaskedPreviewWriteFails_deletesTheAlreadyWrittenOriginal() {
        val store = SecurePhotoStore(context)
        val secureDir = File(context.filesDir, "secure")
        val originalsDir = File(secureDir, "originals")
        val maskedDir = File(secureDir, "masked")

        // originalsDir へは書けるが maskedDir へは書けない状況を作り、途中失敗を再現する。
        assertThat(maskedDir.setWritable(false)).isTrue()
        try {
            assertThrows(IOException::class.java) { store.save(jpegBytes()) }
        } finally {
            maskedDir.setWritable(true)
        }

        // 原本(.enc)が孤児として残っていないこと = 全部書けたか、何も残らないかのどちらか。
        assertThat(originalsDir.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun save_whenOriginalWriteFails_leavesNoPartialFiles() {
        val store = SecurePhotoStore(context)
        val secureDir = File(context.filesDir, "secure")
        val originalsDir = File(secureDir, "originals")
        val maskedDir = File(secureDir, "masked")
        val metaDir = File(secureDir, "meta")

        assertThat(originalsDir.setWritable(false)).isTrue()
        try {
            assertThrows(IOException::class.java) { store.save(jpegBytes()) }
        } finally {
            originalsDir.setWritable(true)
        }

        assertThat(maskedDir.listFiles()?.toList().orEmpty()).isEmpty()
        assertThat(metaDir.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun save_succeeds_whenStorageIsHealthy() {
        val store = SecurePhotoStore(context)
        val item = store.save(jpegBytes())
        assertThat(item.maskedFile.exists()).isTrue()
        assertThat(store.list().map { it.id }).contains(item.id)
    }
}
