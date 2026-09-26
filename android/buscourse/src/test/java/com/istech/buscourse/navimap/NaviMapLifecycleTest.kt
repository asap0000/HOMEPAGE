package com.istech.buscourse.navimap

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.NaviMapEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviMapLifecycleTest {
    private lateinit var database: BusCourseDatabase
    private lateinit var repository: NaviMapRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BusCourseDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = NaviMapRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test
    fun appSimpleIsActiveUntilExFullIsRegistered() = runTest {
        val app = map("app_simple", "青", 1)
        repository.registerMap(app, now = 100L)

        assertThat(repository.activeMapFor("青", 1, 2026)).isEqualTo(app.copy(id = 1))
        assertThat(database.naviMapDao().getMapById(1)?.archivedAt).isNull()
    }

    @Test
    fun exFullArchivesOnlySameIdentityAppSimpleAndBecomesActive() = runTest {
        repository.registerMap(map("app_simple", "青", 1), now = 100L)
        repository.registerMap(map("ex_full", "青", 1), now = 200L)

        assertThat(database.naviMapDao().getMapById(1)?.archivedAt).isEqualTo(200L)
        assertThat(repository.activeMapFor("青", 1, 2026)?.profile).isEqualTo("ex_full")
        assertThat(repository.archivedMapsFor("青", 1, 2026).map { it.id }).containsExactly(1L)
    }

    @Test
    fun differentIdentityIsNotArchived() = runTest {
        repository.registerMap(map("ex_full", "青", 1), now = 100L)
        repository.registerMap(map("app_simple", "赤", 2), now = 200L)

        assertThat(repository.activeMapFor("赤", 2, 2026)?.profile).isEqualTo("app_simple")
        assertThat(repository.archivedMapsFor("赤", 2, 2026)).isEmpty()
    }

    @Test
    fun exFullWithoutPriorAppSimpleIsActive() = runTest {
        repository.registerMap(map("ex_full", "青", 1), now = 100L)

        assertThat(repository.activeMapFor("青", 1, 2026)?.profile).isEqualTo("ex_full")
        assertThat(repository.archivedMapsFor("青", 1, 2026)).isEmpty()
    }

    @Test
    fun appSimpleDoesNotArchiveWhenExFullAlreadyExists() = runTest {
        repository.registerMap(map("ex_full", "青", 1), now = 100L)
        repository.registerMap(map("app_simple", "青", 1), now = 200L)

        assertThat(database.naviMapDao().getMapById(2)?.archivedAt).isNull()
        assertThat(repository.activeMapFor("青", 1, 2026)?.profile).isEqualTo("ex_full")
    }

    @Test
    fun reRegisteringExFullDoesNotChangeAlreadyArchivedTimestamp() = runTest {
        repository.registerMap(map("app_simple", "青", 1), now = 100L)
        repository.registerMap(map("ex_full", "青", 1), now = 200L)
        repository.registerMap(map("ex_full", "青", 1), now = 300L)

        assertThat(database.naviMapDao().getMapById(1)?.archivedAt).isEqualTo(200L)
    }

    private fun map(profile: String, busId: String, courseNo: Int) = NaviMapEntity(
        schemaVersion = "1.0", profile = profile, busId = busId, courseNo = courseNo,
        year = 2026, title = "$busId$courseNo", displayOrientation = "heading_up",
        displayPitchDeg = 30.0, mediaMode = "embedded", mediaCount = 0,
        createdAt = 1, updatedAt = 1,
    )
}
