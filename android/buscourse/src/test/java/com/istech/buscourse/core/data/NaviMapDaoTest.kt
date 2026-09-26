package com.istech.buscourse.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviMapDaoTest {
    private lateinit var database: BusCourseDatabase
    private lateinit var dao: NaviMapDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BusCourseDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.naviMapDao()
    }

    @After fun tearDown() = database.close()

    @Test
    fun insertsReadsSoftReferencesAndCascadesOwnedRows() = runTest {
        val mapId = dao.insertMap(map())
        dao.insertBranches(listOf(NaviBranchEntity(naviMapId = mapId, parentChainageM = 20.0, label = "支線")))
        dao.insertSegments(listOf(
            NaviSegmentEntity(naviMapId = mapId, seq = 0, kind = "TRACK", chainageStartM = 0.0, chainageEndM = 100.0, sessionId = 999_999),
            NaviSegmentEntity(naviMapId = mapId, seq = 1, kind = "GAP", gapKind = "MEDIA_GAP", chainageStartM = 100.0, chainageEndM = 120.0),
        ))
        val track = dao.getSegments(mapId).first()
        dao.insertTrackPoints(listOf(
            NaviTrackPointEntity(segmentId = track.id, seq = 0, chainageM = 0.0, tRelS = 0.0, lat = 35.0, lon = 139.0),
            NaviTrackPointEntity(segmentId = track.id, seq = 1, chainageM = 100.0, tRelS = 10.0, lat = 35.001, lon = 139.001),
        ))
        dao.insertEvents(listOf(NaviEventEntity(
            naviMapId = mapId, templateId = "stop", category = "BUS_STOP",
            anchorType = "STOP_EVENT", scope = "STATIC", priority = "GUIDANCE",
            stopCardId = 888_888,
        )))
        val event = dao.getEvents(mapId).single()
        dao.insertOutputs(listOf(NaviEventOutputEntity(eventId = event.id, outputKind = "TEXT", payloadJson = "{\"text\":\"到着\"}")))

        assertThat(dao.getMapById(mapId)?.title).isEqualTo("青1")
        assertThat(dao.getBranches(mapId)).hasSize(1)
        assertThat(dao.getSegments(mapId).map { it.kind }).containsExactly("TRACK", "GAP").inOrder()
        assertThat(dao.getTrackPoints(track.id)).hasSize(2)
        assertThat(dao.getOutputs(event.id)).hasSize(1)

        dao.deleteMap(mapId)
        assertThat(dao.getBranches(mapId)).isEmpty()
        assertThat(dao.getSegments(mapId)).isEmpty()
        assertThat(dao.getEvents(mapId)).isEmpty()
        assertThat(count("navi_track_point")).isEqualTo(0)
        assertThat(count("navi_event_output")).isEqualTo(0)
    }

    @Test
    fun identityAllowsActiveAndArchivedMaps() = runTest {
        val activeId = dao.insertMap(map(title = "簡易"))
        val archivedId = dao.insertMap(map(title = "完成形"))
        dao.archiveMap(archivedId, 1234L)

        val maps = dao.findMapsByIdentity("青", 1, 2026)
        assertThat(maps.map { it.id }).containsExactly(activeId, archivedId).inOrder()
        assertThat(maps.first { it.id == activeId }.archivedAt).isNull()
        assertThat(maps.first { it.id == archivedId }.archivedAt).isEqualTo(1234L)
    }

    private fun map(title: String = "青1") = NaviMapEntity(
        schemaVersion = "1.0", profile = "app_simple", busId = "青", courseNo = 1,
        year = 2026, title = title, displayOrientation = "heading_up", displayPitchDeg = 30.0,
        mediaMode = "embedded", mediaCount = 0, createdAt = 1, updatedAt = 1,
    )

    private fun count(table: String): Int = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM $table").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
}
