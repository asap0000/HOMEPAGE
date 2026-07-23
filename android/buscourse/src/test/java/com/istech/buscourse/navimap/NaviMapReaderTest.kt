package com.istech.buscourse.navimap

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviMapReaderTest {
    private lateinit var database: BusCourseDatabase
    private lateinit var reader: NaviMapReader

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BusCourseDatabase::class.java)
            .allowMainThreadQueries().build()
        reader = NaviMapReader(database)
    }
    @After fun tearDown() = database.close()

    // ---- 正常系 ----

    @Test fun validArchiveIsMappedAndDegradesDisplay() = runTest {
        val id = reader.read(ByteArrayInputStream(zip(base())), now = 42)
        val map = database.naviMapDao().getMapById(id)!!
        assertThat(map.appSettingsJson).isEqualTo("{}")
        assertThat(map.displayOrientation).isEqualTo("north_up") // 未知 orientation → degrade
        assertThat(map.displayPitchDeg).isEqualTo(60.0)           // pitch 80 → clamp
        val segment = database.naviMapDao().getSegments(id).single { it.kind == "TRACK" }
        assertThat(database.naviMapDao().getTrackPoints(segment.id)).hasSize(2)
    }

    @Test fun schema11AppSettingsIsStoredRaw() = runTest {
        val manifest = MANIFEST.replace(""""media":{"mode":"none","count":0}""",
            """"media":{"mode":"none","count":0},"app_settings":{"approach_radius_m":300,"video_map_ratio":0.7}""")
            .replace(""""schema_version":"1.0"""", """"schema_version":"1.1"""")
        val id = reader.read(ByteArrayInputStream(zip(base(manifest = manifest))))
        assertThat(database.naviMapDao().getMapById(id)!!.appSettingsJson).contains("approach_radius_m")
    }

    // ---- security-critical（レビュアー名指しの最重要ケース）----

    @Test fun urlHiddenInEventPayloadIsRejected() = runTest {
        // manifest ではなく events.json の payload{} 奥に隠す（出荷テストが刺していなかった攻撃面）。
        val events = EVENTS.replace(""""payload":{}""", """"payload":{"href":"https://evil.example/x"}""")
        assertRejectedAndRoomEmpty(base(events = events), IsnaviValidationException.Reason.EXTERNAL_URL)
    }

    @Test fun bareNaNTokenInTrackIsRejectedAndRoomUnchanged() = runTest {
        val track = """[{"chainage_m":0,"t_rel_s":0,"lat":NaN,"lon":139},{"chainage_m":10,"t_rel_s":1,"lat":35.1,"lon":139.1}]"""
        val failure = readExpectingFailure(zip(base(trackT = track)))
        // org.json の版により NaN は例外(MALFORMED_JSON) か 非有限(NON_FINITE_NUMBER)。どちらも安全な拒否。
        assertThat(failure.reason).isAnyOf(
            IsnaviValidationException.Reason.MALFORMED_JSON,
            IsnaviValidationException.Reason.NON_FINITE_NUMBER,
        )
        assertAllNaviTablesEmpty()
    }

    @Test fun duplicateSegmentIdIsRejected_F_isnavi_01() = runTest {
        // 重複 navi_segment.id は後勝ちで track を取り違え INV-NAVIMAP-GPX を静かに破る → DUPLICATE_ID で拒否。
        val segments = """{"navi_branch":[],"navi_segment":[""" +
            """{"id":"t","seq":0,"kind":"TRACK","chainage_start_m":0,"chainage_end_m":10},""" +
            """{"id":"t","seq":1,"kind":"TRACK","chainage_start_m":10,"chainage_end_m":20}]}"""
        assertRejectedAndRoomEmpty(base(segments = segments), IsnaviValidationException.Reason.DUPLICATE_ID)
    }

    @Test fun danglingSegmentBranchRefIsRejected_F_isnavi_02() = runTest {
        val segments = """{"navi_branch":[],"navi_segment":[""" +
            """{"id":"t","seq":0,"kind":"TRACK","chainage_start_m":0,"chainage_end_m":10,"branch_id":"ghost"}]}"""
        assertRejectedAndRoomEmpty(base(segments = segments), IsnaviValidationException.Reason.DANGLING_BRANCH_REF)
    }

    @Test fun danglingEventBranchRefIsRejected_F_isnavi_02_eventSide() = runTest {
        // branch_id 参照整合は event 側にも効く（segment だけでなく）。
        val events = EVENTS.replace(""""variables":{}""", """"variables":{},"branch_id":"ghost"""")
        assertRejectedAndRoomEmpty(base(events = events), IsnaviValidationException.Reason.DANGLING_BRANCH_REF)
    }

    @Test fun trackWithSinglePointViolatesGpxInvariant() = runTest {
        assertRejectedAndRoomEmpty(
            base(trackT = """[{"chainage_m":0,"t_rel_s":0,"lat":35,"lon":139}]"""),
            IsnaviValidationException.Reason.GPX_INVARIANT,
        )
    }

    @Test fun zipSlipEntryNameIsRejected() = runTest {
        val bad = ByteArrayOutputStream().use { b ->
            ZipOutputStream(b).use { it.putNextEntry(ZipEntry("../../evil.txt")); it.write("x".toByteArray()); it.closeEntry() }
            b.toByteArray()
        }
        val failure = readExpectingFailure(bad)
        assertThat(failure.reason).isEqualTo(IsnaviValidationException.Reason.NOT_A_ZIP)
        assertAllNaviTablesEmpty()
    }

    @Test fun rejectionLeavesAllSixNaviTablesUnchanged() = runTest {
        // 検証は withTransaction の前に完結するので、いかなる拒否でも6表すべて0件のまま。
        assertRejectedAndRoomEmpty(base(profile = "EX_FULL"), IsnaviValidationException.Reason.INVALID_PROFILE)
        assertAllNaviTablesEmpty()
    }

    // ---- ③ライフサイクル（ex_full 到達で app_simple をアーカイブ）----

    @Test fun exFullImportArchivesExistingAppSimple() = runTest {
        val simpleId = reader.read(ByteArrayInputStream(zip(base(profile = "app_simple"))), now = 100)
        reader.read(ByteArrayInputStream(zip(base(profile = "ex_full"))), now = 200)
        assertThat(database.naviMapDao().getMapById(simpleId)!!.archivedAt).isEqualTo(200L)
    }

    // ---- ヘルパ ----

    private suspend fun assertRejectedAndRoomEmpty(files: Map<String, String>, reason: IsnaviValidationException.Reason) {
        val failure = readExpectingFailure(zip(files))
        assertThat(failure.reason).isEqualTo(reason)
        assertThat(database.naviMapDao().findMapsByIdentity("bus", 1, 2026)).isEmpty()
    }

    private suspend fun readExpectingFailure(bytes: ByteArray): IsnaviValidationException {
        val failure = runCatching { reader.read(ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IsnaviValidationException::class.java)
        return failure as IsnaviValidationException
    }

    private fun assertAllNaviTablesEmpty() {
        for (table in listOf("navi_map", "navi_branch", "navi_segment", "navi_track_point", "navi_event", "navi_event_output")) {
            database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
                c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(0)
            }
        }
    }

    private fun zip(files: Map<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        bytes.toByteArray()
    }

    /** 4ファイルの基本 .isnavi。各引数で個別ファイルを差し替えられる。 */
    private fun base(
        manifest: String = MANIFEST,
        segments: String = SEGMENTS,
        trackT: String = TRACK_T,
        events: String = EVENTS,
        profile: String? = null,
    ): Map<String, String> {
        val m = if (profile != null) manifest.replace(""""profile":"app_simple"""", """"profile":"$profile"""") else manifest
        return mapOf(
            "manifest.json" to m,
            "segments.json" to segments,
            "tracks/track_t.json" to trackT,
            "events.json" to events,
        )
    }

    private companion object {
        const val MANIFEST = """{"schema_version":"1.0","profile":"app_simple","course_identity":{"bus_id":"bus","course_no":1,"year":2026},"title":"title","display":{"orientation":"bad","pitch_deg":80},"media":{"mode":"none","count":0}}"""
        const val SEGMENTS = """{"navi_branch":[{"id":"b","parent_chainage_m":0,"label":"B"}],"navi_segment":[{"id":"t","seq":0,"kind":"TRACK","chainage_start_m":0,"chainage_end_m":10,"branch_id":"b"},{"id":"g","seq":1,"kind":"GAP","gap_kind":"REQUIRES_CAPTURE","chainage_start_m":10,"chainage_end_m":20}]}"""
        const val TRACK_T = """[{"chainage_m":0,"t_rel_s":0,"lat":35,"lon":139},{"chainage_m":10,"t_rel_s":1,"lat":35.1,"lon":139.1}]"""
        const val EVENTS = """{"navi_event":[{"id":"e","template_id":"x","category":"info","anchor_type":"CHAINAGE","scope":"MAP","priority":"NORMAL","variables":{}}],"navi_event_output":[{"event_id":"e","output_kind":"TEXT","payload":{}}]}"""
    }
}
