package com.istech.buscourse.storage

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.archive.ArchiveStore
import com.istech.buscourse.archive.RunLocation
import com.istech.buscourse.backup.BackupExporter
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.StorageSettings
import com.istech.buscourse.recording.RecordingSessionRepository
import com.istech.buscourse.recording.RecordingSessionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/**
 * microSD を挿した端末での運行の置き場所（2026-10-20 委託指示書 K）。
 * Robolectric の外部ボリューム（取り外し可能・マウント済み）を1つ挿した状態を作って確かめる。
 */
/**
 * Robolectric は `getExternalFilesDirs` に primary しか返さないため、microSD を挿した端末をこのアプリケーションで作る
 * （[cardDir] が null なら抜けている。非 null でも、状態は [ShadowEnvironment] の MEDIA_MOUNTED／UNMOUNTED で決まる）。
 */
class SdCardTestApplication : Application() {
    @Volatile var cardDir: File? = null

    /** `getExternalFilesDirs` が呼ばれた回数（[BusCourseStorage] の控えが効いているかを数える）。 */
    @Volatile var queries = 0

    override fun getExternalFilesDirs(type: String?): Array<File?> {
        queries++
        val primary = super.getExternalFilesDirs(type)
        return arrayOf(primary.firstOrNull(), cardDir)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SdCardTestApplication::class)
class SdCardRecordingTest {
    private lateinit var context: Context
    private lateinit var db: BusCourseDatabase
    private lateinit var card: File

    // 控え（2秒）の時計を差し替える。進めるまで時間は止まっている。
    private var clock = 0L
    private val realClock = BusCourseStorage.nowMillis

    /** 控えの有効時間を過ぎさせる（抜き差しの結果を読み直させる）。 */
    private fun pastCache() { clock += BusCourseStorage.SD_CACHE_MILLIS }

    @Before
    fun setUp() {
        BusCourseStorage.clearSdCaches()
        BusCourseStorage.nowMillis = { clock }
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java).allowMainThreadQueries().build()
        insertCard()
    }

    @After
    fun tearDown() {
        db.close()
        ShadowEnvironment.reset()
        BusCourseStorage.nowMillis = realClock
        BusCourseStorage.clearSdCaches()
    }

    /** 取り外し可能な外部ボリュームを1つ挿す。 */
    private fun insertCard() {
        card = ShadowEnvironment.addExternalDir("sdcard-card")
        ShadowEnvironment.setExternalStorageRemovable(card, true)
        ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_MOUNTED)
        (context as SdCardTestApplication).cardDir = card
    }

    private suspend fun startOne(repository: RecordingSessionRepository) =
        repository.startSession(null, RecordingSessionType.TEST_DRIVE, null, null)

    @Test
    fun `microSDが挿さっていれば新しい運行はmicroSDに作られDBの相対パスは本体と同じ形`() = runTest {
        val repository = RecordingSessionRepository(context, db)
        try {
            val session = startOne(repository)
            val sdFiles = BusCourseStorage.sdFilesDir(context)
            assertThat(sdFiles).isNotNull()
            val ownerId = StorageSettings(context).sdOwnerIdOrNull()
            assertThat(ownerId).isNotNull()
            val sdSession = File(BusCourseStorage.sdRootFor(sdFiles!!, ownerId!!), "sessions/${session.id}")
            assertThat(File(sdSession, "meta.json").isFile).isTrue()
            assertThat(File(sdSession, "frames").isDirectory).isTrue()
            // 本体には作らない。
            assertThat(File(BusCourseStorage.root(context), "sessions/${session.id}").exists()).isFalse()
            // DB の相対パスは本体と同じ形のまま。
            assertThat(session.gpsRawLogRelPath).isEqualTo("sessions/${session.id}/gps_raw.jsonl")
            assertThat(session.frameDirRelPath).isEqualTo("sessions/${session.id}/frames")
            // 解くと microSD 側になる。
            assertThat(BusCourseStorage.resolve(context, session.gpsRawLogRelPath))
                .isEqualTo(File(sdSession, "gps_raw.jsonl"))
            // 覚え: この端末は microSD に記録した／この運行はカードの中にある。
            assertThat(StorageSettings(context).usedSd).isTrue()
            assertThat(StorageSettings(context).sdSessionIds()).contains(session.id)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `microSDが無ければ本体に作り何も覚えない`() = runTest {
        ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_UNMOUNTED)
        pastCache()
        val repository = RecordingSessionRepository(context, db)
        try {
            val session = startOne(repository)
            assertThat(File(BusCourseStorage.root(context), "sessions/${session.id}/meta.json").isFile).isTrue()
            assertThat(StorageSettings(context).usedSd).isFalse()
            assertThat(StorageSettings(context).sdOwnerIdOrNull()).isNull()
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `microSDは取り外し可能でマウント済みのときだけ見つける`() {
        assertThat(BusCourseStorage.sdFilesDir(context)).isEqualTo(card)
        // 端末によっては、抜けると一覧の要素が null になる。
        val app = context as SdCardTestApplication
        app.cardDir = null
        pastCache()
        assertThat(BusCourseStorage.sdFilesDir(context)).isNull()
        // 挿さっていても、読み取り専用・取り外し不可（内蔵扱い）の領域は記録先にしない。
        app.cardDir = card
        pastCache()
        ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_MOUNTED_READ_ONLY)
        pastCache()
        assertThat(BusCourseStorage.sdFilesDir(context)).isNull()
        ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_MOUNTED)
        pastCache()
        ShadowEnvironment.setExternalStorageRemovable(card, false)
        pastCache()
        assertThat(BusCourseStorage.sdFilesDir(context)).isNull()
    }

    @Test
    fun `控えの時間内は問い合わせが1回だけで 時間を過ぎたら読み直す`() {
        val app = context as SdCardTestApplication
        val before = app.queries
        repeat(5) { assertThat(BusCourseStorage.sdFilesDir(context)).isEqualTo(card) }
        assertThat(app.queries - before).isEqualTo(1)

        // 抜いても、控えの時間内は前の結果のまま（ナビの描画の通り道を重くしない代わりの割り切り）。
        ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_UNMOUNTED)
        clock += BusCourseStorage.SD_CACHE_MILLIS - 1
        assertThat(BusCourseStorage.sdFilesDir(context)).isEqualTo(card)
        assertThat(app.queries - before).isEqualTo(1)

        // 時間を過ぎたら読み直す。
        clock += 1
        assertThat(BusCourseStorage.sdFilesDir(context)).isNull()
        assertThat(app.queries - before).isEqualTo(2)
        assertThat(BusCourseStorage.sdFilesDir(context)).isNull()
        assertThat(app.queries - before).isEqualTo(2)
    }

    @Test
    fun `運行のパスを解くたびには問い合わせず 運行以外のパスは問い合わせない`() = runTest {
        val repository = RecordingSessionRepository(context, db)
        try {
            val session = startOne(repository)
            val app = context as SdCardTestApplication
            val before = app.queries
            repeat(20) { BusCourseStorage.resolve(context, "sessions/${session.id}/frames/a.jpg") }
            repeat(20) { BusCourseStorage.resolve(context, "stopcards/1/photo_thumb.jpg") }
            // 開始時の読み直しが控えに入っているので、時間内なら追加の問い合わせは無い。
            assertThat(app.queries - before).isEqualTo(0)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `記録の開始の判定は控えを使わず必ず読み直す`() {
        val app = context as SdCardTestApplication
        assertThat(BusCourseStorage.sdFilesDir(context)).isEqualTo(card) // 控えに「挿さっている」を入れる
        // 抜く。控えの時間は過ぎていない。
        ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_UNMOUNTED)
        assertThat(BusCourseStorage.sdFilesDir(context)).isEqualTo(card) // 控えは古いまま
        val before = app.queries
        assertThat(BusCourseStorage.sdFilesDirFresh(context)).isNull()
        assertThat(BusCourseStorage.recordingTarget(context).onSd).isFalse()
        assertThat(BusCourseStorage.recordingFilesDir(context)).isEqualTo(context.filesDir)
        assertThat(app.queries - before).isEqualTo(3)
        // 読み直した結果は控えにも入る（以後の解決も「抜けた」を見る）。
        assertThat(BusCourseStorage.sdFilesDir(context)).isNull()

        // 挿し直した直後も、控えの時間を待たずに記録先が microSD になる。
        ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_MOUNTED)
        assertThat(BusCourseStorage.recordingTarget(context).onSd).isTrue()
        assertThat(BusCourseStorage.sdFilesDir(context)).isEqualTo(card)
    }

    @Test
    fun `sdOwnerIdは作られた後は控えたまま 作られるまでは毎回読み直す`() {
        // まだ作っていない間は null を控えない。
        assertThat(BusCourseStorage.sdRoot(context)).isNull()
        val id = StorageSettings(context).ensureSdOwnerId()
        // 作った直後から解ける（null を掴み続けない）。
        val sdFiles = BusCourseStorage.sdFilesDir(context)!!
        assertThat(BusCourseStorage.sdRoot(context)).isEqualTo(BusCourseStorage.sdRootFor(sdFiles, id))
        // 一度読んだ後は控えたまま（保存値を書き換えても、このプロセスでは前の値を使う）。
        context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE).edit().putString("sd_owner_id", "changed").commit()
        assertThat(BusCourseStorage.sdRoot(context)).isEqualTo(BusCourseStorage.sdRootFor(sdFiles, id))
    }

    @Test
    fun `microSDの運行を消すとmicroSD側のフォルダが消える`() = runTest {
        val repository = RecordingSessionRepository(context, db)
        try {
            val session = startOne(repository)
            repository.endSession()
            val dir = BusCourseStorage.sessionDir(context, session.id)
            assertThat(dir.isDirectory).isTrue()
            assertThat(dir.path).contains("sdcard-card")
            repository.deleteSession(session.id)
            assertThat(dir.exists()).isFalse()
            assertThat(BusCourseStorage.existingSessionDirs(context, session.id)).isEmpty()
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `別の端末のカードの運行は解かず本体として扱う`() = runTest {
        val repository = RecordingSessionRepository(context, db)
        try {
            val session = startOne(repository)
            repository.endSession()
            // このカードを、別の端末（別の sdOwnerId）が使った状態にする＝フォルダ名がこの端末の乱数と違う。
            val sdFiles = BusCourseStorage.sdFilesDir(context)!!
            val mine = StorageSettings(context).sdOwnerIdOrNull()!!
            val theirs = File(File(sdFiles, "buscourse"), "another-device")
            assertThat(File(File(sdFiles, "buscourse"), mine).renameTo(theirs)).isTrue()
            assertThat(BusCourseStorage.sessionDir(context, session.id).path)
                .isEqualTo(File(BusCourseStorage.root(context), "sessions/${session.id}").path)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `microSDの運行は保管庫の書き出し先も同じカードで 抜けていれば書き出さない`() = runTest {
        val repository = RecordingSessionRepository(context, db)
        val store = ArchiveStore(context, db)
        try {
            val session = startOne(repository)
            val ended = repository.endSession()!!
            assertThat(store.location(ended)).isEqualTo(RunLocation.SD)
            assertThat(store.outDirFor(ended).path).isEqualTo(store.sdOutDir!!.path)
            assertThat(store.outDirFor(ended).path).contains("sdcard-card")
            assertThat(store.export(ended)).isTrue()
            assertThat(File(File(store.sdOutDir!!, store.runKey(ended)), "DONE").isFile).isTrue()
            // 本体の archive_out には作らない。
            assertThat(File(store.outDir, store.runKey(ended)).exists()).isFalse()
            assertThat(store.isExported(ended)).isTrue()
            store.deleteExport(ended)
            assertThat(store.isExported(ended)).isFalse()

            // カードを抜く。中身が見えない走行は書き出さず、空の写しも作らない。
            ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_UNMOUNTED)
            pastCache()
            assertThat(store.location(ended)).isEqualTo(RunLocation.SD_MISSING)
            assertThat(store.isUnavailable(ended)).isTrue()
            assertThat(store.export(ended)).isFalse()
            assertThat(File(store.outDir, store.runKey(ended)).exists()).isFalse()
            assertThat(store.pending().map { it.id }).doesNotContain(session.id)
            assertThat(store.unavailable().map { it.id }).containsExactly(session.id)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `全量バックアップはmicroSDの運行も入れ カードが抜けていれば入らない運行を数える`() = runTest {
        val repository = RecordingSessionRepository(context, db)
        val exporter = BackupExporter(context, db)
        try {
            val session = startOne(repository)
            repository.endSession()
            val withCard = exporter.estimate()
            assertThat(withCard.fileCount).isAtLeast(1)
            assertThat(exporter.missingSdRunCount()).isEqualTo(0)

            ShadowEnvironment.setExternalStorageState(card, Environment.MEDIA_UNMOUNTED)
            pastCache()
            assertThat(exporter.estimate().fileCount).isLessThan(withCard.fileCount)
            assertThat(exporter.missingSdRunCount()).isEqualTo(1)
            assertThat(StorageSettings(context).sdSessionIds()).contains(session.id)
        } finally {
            repository.shutdown()
        }
    }
}
