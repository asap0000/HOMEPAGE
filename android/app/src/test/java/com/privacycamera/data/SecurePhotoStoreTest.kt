package com.privacycamera.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.privacycamera.testutil.TestImages
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
 *
 * [SecurePhotoStore.writePhotoFiles] を直接叩く(save() 経由にしない)のは、save() が
 * [com.privacycamera.crypto.CryptoManager] 経由で実機の AndroidKeyStore に依存しており、
 * Robolectric の JVM 上にはそのプロバイダが存在しないため。ここでは暗号化の正しさではなく
 * 「3ファイルの書き込みが全部成功するか、何も残らないか」だけを検証したいので、
 * ダミーの"暗号化済み"バイト列で十分。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecurePhotoStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dummyEncrypted = ByteArray(32) { it.toByte() }

    @Test
    fun writePhotoFiles_whenMaskedPreviewWriteFails_deletesTheAlreadyWrittenOriginal() {
        val store = SecurePhotoStore(context)
        val secureDir = File(context.filesDir, "secure")
        val originalsDir = File(secureDir, "originals")
        val maskedDir = File(secureDir, "masked")

        // originalsDir へは書けるが maskedDir へは書けない状況を作り、途中失敗を再現する。
        assertThat(maskedDir.setWritable(false)).isTrue()
        try {
            assertThrows(IOException::class.java) {
                store.writePhotoFiles(
                    "IMG_TEST", dummyEncrypted, TestImages.gradient(),
                    "uuid-1", "", PhotoCategories.UNCLASSIFIED, 0L
                )
            }
        } finally {
            maskedDir.setWritable(true)
        }

        // 原本(.enc)が孤児として残っていないこと = 全部書けたか、何も残らないかのどちらか。
        assertThat(originalsDir.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun writePhotoFiles_whenOriginalWriteFails_leavesNoPartialFiles() {
        val store = SecurePhotoStore(context)
        val secureDir = File(context.filesDir, "secure")
        val originalsDir = File(secureDir, "originals")
        val maskedDir = File(secureDir, "masked")
        val metaDir = File(secureDir, "meta")

        assertThat(originalsDir.setWritable(false)).isTrue()
        try {
            assertThrows(IOException::class.java) {
                store.writePhotoFiles(
                    "IMG_TEST", dummyEncrypted, TestImages.gradient(),
                    "uuid-1", "", PhotoCategories.UNCLASSIFIED, 0L
                )
            }
        } finally {
            originalsDir.setWritable(true)
        }

        assertThat(maskedDir.listFiles()?.toList().orEmpty()).isEmpty()
        assertThat(metaDir.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun writePhotoFiles_succeeds_whenStorageIsHealthy() {
        val store = SecurePhotoStore(context)
        val item = store.writePhotoFiles(
            "IMG_TEST", dummyEncrypted, TestImages.gradient(),
            "uuid-1", "memo", PhotoCategories.UNCLASSIFIED, 0L
        )
        assertThat(item.maskedFile.exists()).isTrue()
        assertThat(store.list().map { it.id }).contains(item.id)
    }
}
