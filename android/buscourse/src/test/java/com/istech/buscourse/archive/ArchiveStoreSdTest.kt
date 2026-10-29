package com.istech.buscourse.archive

import android.content.Context
import android.os.Environment
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.StorageSettings
import com.istech.buscourse.core.data.TimelapseFrameEntity
import com.istech.buscourse.storage.SdCardTestApplication
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.security.MessageDigest

/**
 * microSD にある走行の書き出し（新形: 写真を写さず、目録・指紋・元の場所だけを作る。2026-10-29 委託指示書 M）。
 * 本体の走行は旧形のまま（[ArchiveStoreTest]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SdCardTestApplication::class)
class ArchiveStoreSdTest {
    private lateinit var context: Context
    private lateinit var db: BusCourseDatabase
    private lateinit var store: ArchiveStore
    private lateinit var run: RecordingSessionEntity
    private lateinit var card: File
    private lateinit var receiptFile: File

    private val sessionDir: File get() = BusCourseStorage.sessionDir(context, run.id)

    @Before fun setUp() = runTest {
        BusCourseStorage.clearSdCaches()
        context = ApplicationProvider.getApplicationContext()
        card = ShadowEnvironment.addExternalDir("sdcard-card")
        ShadowEnvironment.setExternalStorageRemovable(card, true)
        ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_MOUNTED)
        (context as SdCardTestApplication).cardDir = card
        db = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java).allowMainThreadQueries().build()
        store = ArchiveStore(context, db)
        receiptFile = File(context.getExternalFilesDir(null), "archive_receipt.json")
        receiptFile.delete()
        cleanFiles()
        run = insertSdRun(1000000L, bytes = byteArrayOf(1, 2, 3))
    }

    /** テスト間で microSD・本体の外部領域は使い回されるので、前のテストの走行・書き出し・作り置きを消す。 */
    private fun cleanFiles() {
        File(card, BusCourseStorage.ROOT_DIR_NAME).deleteRecursively()
        File(card, "archive_out").deleteRecursively()
        store.outDir.deleteRecursively()
        store.prepDir.deleteRecursively()
    }

    @After fun tearDown() {
        db.close()
        receiptFile.delete()
        cleanFiles()
        ShadowEnvironment.reset()
        BusCourseStorage.clearSdCaches()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val realFrameSha get() = sha(byteArrayOf(1, 2, 3))

    /**
     * microSD に走行のフォルダ（sessions/<id>/frames）を作り、写真を1枚（[bytes]）置いて、その行を足す。
     * [relOf] で写真の相対パスを変えられる（frames の外に置くテスト用）。[bytes] が null なら写真の行は足さない。
     */
    private suspend fun insertSdRun(
        startedAt: Long, status: String = "COMPLETED", bytes: ByteArray? = null,
        relOf: (Long) -> String = { "sessions/$it/frames/synthetic.jpg" },
    ): RecordingSessionEntity {
        val id = db.recordingSessionDao().insert(RecordingSessionEntity(
            courseId = null, type = "FULL_RUN", targetFromStopCardId = null, targetToStopCardId = null,
            vehicleId = null, driverId = null, deviceModel = "SyntheticDevice", startedAt = startedAt,
            endedAt = startedAt + 10000, gpsRawLogRelPath = "sessions/x/gps_raw.jsonl",
            frameDirRelPath = "sessions/x/frames", baseFrameIntervalMs = 1000L,
            totalDistanceM = 500.0, status = status))
        val sdRoot = BusCourseStorage.sdRootFor(BusCourseStorage.sdFilesDir(context)!!, StorageSettings(context).ensureSdOwnerId())
        File(sdRoot, "sessions/$id/frames").mkdirs()
        val row = db.recordingSessionDao().getById(id)!!
        if (bytes != null) {
            val rel = relOf(id)
            val file = BusCourseStorage.resolve(context, rel)
            file.parentFile!!.mkdirs(); file.writeBytes(bytes)
            db.timelapseFrameDao().insert(TimelapseFrameEntity(sessionId = id, seq = 0, kind = "LORES",
                fileRelPath = rel, capturedAt = startedAt, latitude = null, longitude = null,
                width = null, height = null, sizeBytes = bytes.size.toLong()))
        }
        assertThat(store.location(row)).isEqualTo(RunLocation.SD)
        return row
    }

    @Test fun `書き出しは写真を写さず_目録と指紋と元の場所だけを作る`() = runTest {
        File(sessionDir, "sensors.jsonl.gz").writeBytes(byteArrayOf(31, 32, 33))
        File(sessionDir, "meta.json").writeBytes(byteArrayOf(41, 42))
        val progress = mutableListOf<Long>()
        assertThat(store.export(run) { progress += it }).isTrue()

        // 書き出し先は同じ microSD の archive_out。作るのはこの4つだけ。写真・記録の写しは無い。
        val folder = File(store.sdOutDir!!, store.runKey(run))
        assertThat(folder.path).contains("sdcard-card")
        assertThat(File(store.outDir, store.runKey(run)).exists()).isFalse()
        assertThat(folder.list()!!.toSet()).containsExactly("run.json", "manifest.sha256", "source.json", "DONE")
        assertThat(File(folder, "frames").exists()).isFalse()

        // 目録: 旧形と同じ相対パス・並び・書式。パスの sha は元ファイルと一致する（source.json と DONE は入れない）。
        val lines = File(folder, "manifest.sha256").readLines()
        assertThat(lines.map { it.substring(66) }).containsExactly(
            "frames/synthetic.jpg", "meta.json", "run.json", "sensors.jsonl.gz").inOrder()
        for (line in lines) {
            assertThat(line.substring(64, 66)).isEqualTo("  ")
            val name = line.substring(66)
            val file = if (name == "run.json") File(folder, name) else File(sessionDir, name)
            assertThat(line.substring(0, 64)).isEqualTo(sha(file.readBytes()))
        }
        assertThat(File(folder, "manifest.sha256").readText().endsWith("\n")).isTrue()

        // source.json: 元の場所の指し（端末から見た絶対パス）・目録の run.json 以外のパス・その合計。
        val source = JSONObject(File(folder, "source.json").readText())
        assertThat(source.getString("schema")).isEqualTo("buscourse-archive-source/1")
        assertThat(source.getString("sessionDir")).isEqualTo(BusCourseStorage.sessionDir(context, run.id).absolutePath)
        assertThat(source.getLong("sessionId")).isEqualTo(run.id)
        val files = source.getJSONArray("files").let { array -> (0 until array.length()).map { array.getString(it) } }
        assertThat(files).containsExactly("frames/synthetic.jpg", "meta.json", "sensors.jsonl.gz").inOrder()
        assertThat(source.getLong("totalBytes")).isEqualTo(3L + 2L + 3L)

        // DONE が最後（run.json → manifest → source.json → DONE の順）。
        val order = listOf("run.json", "manifest.sha256", "source.json", "DONE").map { File(folder, it).lastModified() }
        assertThat(order).isInOrder()
        // 指紋を作る間、読んだバイト数が通知され、最後は全量。
        assertThat(progress).isNotEmpty()
        assertThat(progress.last()).isEqualTo(8L)
        // 2回目は何も変えない。
        val first = File(folder, "run.json").readText()
        assertThat(store.export(run)).isTrue()
        assertThat(File(folder, "run.json").readText()).isEqualTo(first)
    }

    @Test fun `書き出しのrun_jsonは写真が無ければmissingFilesに載る`() = runTest {
        BusCourseStorage.resolve(context, "sessions/${run.id}/frames/synthetic.jpg").delete()
        assertThat(store.export(run)).isTrue()
        val folder = File(store.sdOutDir!!, store.runKey(run))
        val json = JSONObject(File(folder, "run.json").readText())
        assertThat(json.getString("schema")).isEqualTo("buscourse-archive-run/1")
        assertThat(json.getJSONArray("missingFiles").toString()).isEqualTo(JSONArray().put("synthetic.jpg").toString())
        // 実在しない写真は目録にも source.json にも入れない。
        assertThat(File(folder, "manifest.sha256").readLines().map { it.substring(66) }).containsExactly("run.json")
        assertThat(JSONObject(File(folder, "source.json").readText()).getJSONArray("files").length()).isEqualTo(0)
    }

    @Test fun `写真がframesの外にあるmicroSDの走行は旧形で書き出す`() = runTest {
        val outside = insertSdRun(2000000L, bytes = byteArrayOf(7, 8, 9)) { "sessions/$it/other/outside.jpg" }
        File(BusCourseStorage.sessionDir(context, outside.id), "meta.json").writeBytes(byteArrayOf(41, 42))
        // PC がフォルダごと持っていけないので、作り置きも作らない。
        assertThat(store.prepare(outside)).isFalse()
        assertThat(store.export(outside)).isTrue()

        val folder = File(store.sdOutDir!!, store.runKey(outside))
        assertThat(File(folder, "source.json").exists()).isFalse()
        assertThat(File(folder, "frames/outside.jpg").readBytes()).isEqualTo(byteArrayOf(7, 8, 9))
        assertThat(File(folder, "meta.json").readBytes()).isEqualTo(byteArrayOf(41, 42))
        // 目録は写しと一致する。
        val lines = File(folder, "manifest.sha256").readLines()
        assertThat(lines.map { it.substring(66) }).containsExactly("frames/outside.jpg", "meta.json", "run.json").inOrder()
        for (line in lines) assertThat(line.substring(0, 64)).isEqualTo(sha(File(folder, line.substring(66)).readBytes()))
        assertThat(File(folder, "DONE").isFile).isTrue()
        // 標準の位置の写真だけの走行は新形のまま。
        assertThat(store.export(run)).isTrue()
        assertThat(File(File(store.sdOutDir!!, store.runKey(run)), "source.json").isFile).isTrue()
    }

    @Test fun `作り置きがあれば指紋を読み直さない`() = runTest {
        assertThat(store.prepare(run)).isTrue()
        val prepFile = store.prepFileOf(run)
        val prep = ArchivePrep.read(prepFile)!!
        assertThat(prep.startedAt).isEqualTo(run.startedAt)
        assertThat(prep.frames).isEqualTo(1)
        assertThat(prep.entries.map { it.path }).containsExactly("frames/synthetic.jpg")
        assertThat(prep.entries.single().sha256).isEqualTo(realFrameSha)
        assertThat(prep.totalBytes).isEqualTo(3L)

        // 作り置きの sha を偽物に替える。元のファイルを読み直したら本物の sha が出るはずなので、偽物が出れば作り置きを使った証拠。
        val fake = "ab".repeat(32)
        prepFile.writeText(prep.copy(entries = listOf(prep.entries.single().copy(sha256 = fake)), totalBytes = 999L).toJson())
        assertThat(store.size(run)).isEqualTo(999L) // 大きさも作り置きの合計
        assertThat(store.export(run)).isTrue()
        val folder = File(store.sdOutDir!!, store.runKey(run))
        assertThat(File(folder, "manifest.sha256").readLines().first()).isEqualTo("$fake  frames/synthetic.jpg")

        // 作り置きの frames が今の DB と違えば作り直す（偽物は使わない）。startedAt が違っても同じ。
        for (stale in listOf(prep.copy(frames = 5, entries = listOf(prep.entries.single().copy(sha256 = fake))),
                prep.copy(startedAt = run.startedAt + 1, entries = listOf(prep.entries.single().copy(sha256 = fake))))) {
            store.deleteExport(run)
            prepFile.writeText(stale.toJson())
            assertThat(store.export(run)).isTrue()
            assertThat(File(folder, "manifest.sha256").readLines().first()).isEqualTo("$realFrameSha  frames/synthetic.jpg")
            // 作り直した指紋は作り置きとして保存し直される。
            val saved = ArchivePrep.read(prepFile)!!
            assertThat(saved.frames).isEqualTo(1)
            assertThat(saved.startedAt).isEqualTo(run.startedAt)
            assertThat(saved.entries.single().sha256).isEqualTo(realFrameSha)
        }

        // 作り置きが無い走行は、押したときに作り、作り置きとしても保存する。
        store.deleteExport(run)
        prepFile.delete()
        assertThat(store.export(run)).isTrue()
        assertThat(ArchivePrep.read(prepFile)!!.entries.single().sha256).isEqualTo(realFrameSha)
    }

    @Test fun `記録中は作り置きをしない`() = runTest {
        db.recordingSessionDao().update(run.copy(status = "RECORDING"))
        val recording = db.recordingSessionDao().getById(run.id)!!
        val other = insertSdRun(2000000L)

        // 記録中の走行そのものは作らない。記録中の走行が1本でもあれば、ほかの走行の作り置きもしない。
        assertThat(store.prepare(recording)).isFalse()
        assertThat(store.prepareAll()).isEqualTo(0)
        assertThat(store.prepFileOf(recording).exists()).isFalse()
        assertThat(store.prepFileOf(other).exists()).isFalse()
        // 記録中の走行を書き出しても作り置きとして残さない（増え続けるため）。
        assertThat(store.export(recording)).isTrue()
        assertThat(store.prepFileOf(recording).exists()).isFalse()
        // 記録中の走行の作り置きは、あっても有効ではない。
        store.prepDir.mkdirs()
        store.prepFileOf(recording).writeText(ArchivePrep(recording.startedAt, 1,
            listOf(ArchiveFingerprint("frames/synthetic.jpg", "ab".repeat(32), 3)), 3).toJson())
        assertThat(store.hasPrep(recording)).isFalse()
        store.prepFileOf(recording).delete()
        store.deleteExport(recording)

        // 記録が終わったら（記録中が0本）作る。止められたら途中でやめる。
        db.recordingSessionDao().update(recording.copy(status = "COMPLETED"))
        assertThat(store.prepareAll { true }).isEqualTo(0)
        assertThat(store.prepareAll()).isEqualTo(2)
        assertThat(store.prepFileOf(run).isFile).isTrue()
        assertThat(store.prepFileOf(other).isFile).isTrue()
        assertThat(store.prepareAll()).isEqualTo(0) // 作り置きがある走行は作り直さない
    }

    @Test fun `作り置きはPCに受け取られた走行と書き出し済みの走行には作らない`() = runTest {
        val exported = insertSdRun(2000000L)
        assertThat(store.export(exported)).isTrue()
        store.prepFileOf(exported).delete()
        val received = run
        receiptFile.writeText("""{"schema":"buscourse-archive-receipt/1","issuedAt":1,"runs":[{"startedAt":${received.startedAt},"deviceModel":"SyntheticDevice","frames":1,"archivedAt":2,"startupTestDeletable":false}]}""")
        assertThat(store.prepareAll()).isEqualTo(0)
        assertThat(store.prepFileOf(received).exists()).isFalse()
        assertThat(store.prepFileOf(exported).exists()).isFalse()
    }

    @Test fun `作り置きの後に写真の中身と大きさを変えたら 書き出しは読み直した指紋を使う`() = runTest {
        assertThat(store.prepare(run)).isTrue()
        val frame = File(sessionDir, "frames/synthetic.jpg")
        // 中身も大きさも変わった（作り置きの bytes と合わない）。
        val changed = byteArrayOf(9, 9, 9, 9, 9)
        frame.writeBytes(changed)
        assertThat(store.export(run)).isTrue()
        val folder = File(store.sdOutDir!!, store.runKey(run))
        assertThat(File(folder, "manifest.sha256").readLines().first()).isEqualTo("${sha(changed)}  frames/synthetic.jpg")
        assertThat(JSONObject(File(folder, "source.json").readText()).getLong("totalBytes")).isEqualTo(5L)
        // 作り直した指紋で作り置きも更新される。
        val saved = ArchivePrep.read(store.prepFileOf(run))!!
        assertThat(saved.entries.single().sha256).isEqualTo(sha(changed))
        assertThat(saved.entries.single().bytes).isEqualTo(5L)
    }

    @Test fun `作り置きの後にmeta_jsonを変えたら 書き出しは読み直した指紋を使う`() = runTest {
        val meta = File(sessionDir, "meta.json")
        meta.writeBytes(byteArrayOf(41, 42))
        assertThat(store.prepare(run)).isTrue()
        assertThat(ArchivePrep.read(store.prepFileOf(run))!!.entries.map { it.path }).containsExactly("frames/synthetic.jpg", "meta.json")
        // 大きさが変わった。
        meta.writeBytes(byteArrayOf(51, 52, 53))
        assertThat(store.export(run)).isTrue()
        val folder = File(store.sdOutDir!!, store.runKey(run))
        val line = File(folder, "manifest.sha256").readLines().first { it.endsWith("  meta.json") }
        assertThat(line.substring(0, 64)).isEqualTo(sha(byteArrayOf(51, 52, 53)))

        // 一覧が変わった（作り置きの後に sensors.jsonl.gz が増えた）。
        store.deleteExport(run)
        File(sessionDir, "sensors.jsonl.gz").writeBytes(byteArrayOf(1))
        // 作り置きは meta.json の旧い指紋のまま。偽物に替えて、使われないことを見る。
        val prep = ArchivePrep.read(store.prepFileOf(run))!!
        store.prepFileOf(run).writeText(prep.copy(entries = prep.entries.map { it.copy(sha256 = "cd".repeat(32)) }).toJson())
        assertThat(store.export(run)).isTrue()
        val lines = File(folder, "manifest.sha256").readLines()
        assertThat(lines.map { it.substring(66) }).containsExactly("frames/synthetic.jpg", "meta.json", "run.json", "sensors.jsonl.gz").inOrder()
        assertThat(lines.none { it.startsWith("cd".repeat(32)) }).isTrue()
    }

    @Test fun `archive_retryに載った新形の書き出しは消えて書き出し待ちに戻り 作り置きも消える_旧形の写しは消さない`() = runTest {
        val outside = insertSdRun(2000000L, bytes = byteArrayOf(7, 8, 9)) { "sessions/$it/other/outside.jpg" }
        assertThat(store.export(run)).isTrue()      // 新形（作り置きも保存される）
        assertThat(store.export(outside)).isTrue()  // 旧形（frames の外の写真）
        val newForm = File(store.sdOutDir!!, store.runKey(run))
        val oldForm = File(store.sdOutDir!!, store.runKey(outside))
        assertThat(File(newForm, "source.json").isFile).isTrue()
        assertThat(store.prepFileOf(run).isFile).isTrue()
        assertThat(store.isExported(run)).isTrue()

        val retry = File(context.getExternalFilesDir(null), "archive_retry.json")
        fun row(startedAt: Long, model: String) = JSONObject().put("startedAt", startedAt).put("deviceModel", model)
        retry.writeText(JSONObject().put("schema", "buscourse-archive-retry/1").put("runs", JSONArray()
            .put(row(run.startedAt, "SyntheticDevice")).put(row(outside.startedAt, "SyntheticDevice")).put(row(1L, "Other"))).toString())

        assertThat(store.applyRetry()).isEqualTo(3)
        // 新形の書き出しと作り置きは消え、書き出し待ちに戻る。旧形の写しは自己完結なので残る。
        assertThat(newForm.exists()).isFalse()
        assertThat(store.prepFileOf(run).exists()).isFalse()
        assertThat(store.isExported(run)).isFalse()
        assertThat(oldForm.isDirectory).isTrue()
        assertThat(File(oldForm, "frames/outside.jpg").isFile).isTrue()
        assertThat(store.isExported(outside)).isTrue()
        // 全部済んだのでファイルは消える。
        assertThat(retry.exists()).isFalse()
        val waiting = store.pending().filterNot(store::isExported).map { it.id }
        assertThat(waiting).containsExactly(run.id)

        // 押すと指紋を読み直して作る（写真が変わっていれば新しい指紋）。
        val changed = byteArrayOf(5, 5, 5, 5)
        File(sessionDir, "frames/synthetic.jpg").writeBytes(changed)
        assertThat(store.export(run)).isTrue()
        assertThat(File(newForm, "manifest.sha256").readLines().first()).isEqualTo("${sha(changed)}  frames/synthetic.jpg")
    }

    @Test fun `走行を消すとき作り置きも消え 消えた走行の作り置きと新形の書き出しは片付く`() = runTest {
        val second = insertSdRun(2000000L)
        assertThat(store.prepare(run)).isTrue()
        assertThat(store.prepare(second)).isTrue()
        assertThat(store.export(second)).isTrue()
        val folder = File(store.sdOutDir!!, store.runKey(second))
        assertThat(File(folder, "source.json").isFile).isTrue()
        // DB から走行が消え（元のフォルダも消える）たら、新形の書き出しと作り置きを片付ける。
        db.recordingSessionDao().deleteById(second.id)
        BusCourseStorage.existingSessionDirs(context, second.id).forEach { it.deleteRecursively() }
        val remaining = db.recordingSessionDao().getAll()
        assertThat(store.deleteDanglingExports(remaining)).isEqualTo(1)
        assertThat(folder.exists()).isFalse()
        assertThat(store.deleteOrphanPrep(remaining)).isEqualTo(1)
        assertThat(store.prepFileOf(second).exists()).isFalse()
        assertThat(store.prepFileOf(run).isFile).isTrue()
        // 走行を消すときは deletePrep（StorageRotationWorker が呼ぶ）。
        store.deletePrep(run)
        assertThat(store.prepFileOf(run).exists()).isFalse()
    }
}
