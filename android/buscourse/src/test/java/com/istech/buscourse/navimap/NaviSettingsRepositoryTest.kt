package com.istech.buscourse.navimap

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviSettingsRepositoryTest {
    private lateinit var repository: NaviSettingsRepository

    @Before
    fun setUp() = runTest {
        repository = NaviSettingsRepository(ApplicationProvider.getApplicationContext())
        clearAll()
    }

    @Test
    fun settersClampAndRoundTripThroughPatchFlow() = runTest {
        repository.setTiltDeg(95.0)
        repository.setVideoAmountPct(101)
        repository.setVideoLateralPct(-1)
        repository.setSelfCarFwdBackPct(101)
        repository.setSelfCarLateralPct(-1)
        repository.setOrientation(NaviMapOrientation.NORTH_UP)
        repository.setTheme(NaviTheme.DAY)
        repository.setStopNameVisible(false)

        assertThat(repository.patchFlow.first()).isEqualTo(
            NaviSettingsPatch(
                tiltDeg = 90.0,
                videoAmountPct = 100,
                videoLateralPct = 0,
                selfCarFwdBackPct = 100,
                selfCarLateralPct = 0,
                orientation = NaviMapOrientation.NORTH_UP,
                theme = NaviTheme.DAY,
                stopNameVisible = false,
            ),
        )
    }

    @Test
    fun clearRemovesOnlyTheRequestedField() = runTest {
        repository.setTiltDeg(60.0)
        repository.setVideoAmountPct(80)
        repository.setOrientation(NaviMapOrientation.NORTH_UP)

        repository.clear(NaviSettingsField.VIDEO_AMOUNT_PCT)

        assertThat(repository.patchFlow.first()).isEqualTo(
            NaviSettingsPatch(
                tiltDeg = 60.0,
                orientation = NaviMapOrientation.NORTH_UP,
            ),
        )
    }

    @Test
    fun emptyStoreEmitsPatchWithAllFieldsNull() = runTest {
        assertThat(repository.patchFlow.first()).isEqualTo(NaviSettingsPatch())
    }

    @Test
    fun enumStorageValuesRoundTripToEnums() = runTest {
        repository.setOrientation(NaviMapOrientation.HEADING_UP)
        repository.setTheme(NaviTheme.NIGHT)

        val patch = repository.patchFlow.first()
        assertThat(patch.orientation).isEqualTo(NaviMapOrientation.HEADING_UP)
        assertThat(patch.theme).isEqualTo(NaviTheme.NIGHT)
    }

    private suspend fun clearAll() {
        NaviSettingsField.entries.forEach { field -> repository.clear(field) }
    }
}
