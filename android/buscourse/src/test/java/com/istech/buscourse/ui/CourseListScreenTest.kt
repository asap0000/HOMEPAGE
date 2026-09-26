package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.course.CourseShapingState
import org.junit.Test

class CourseListScreenTest {
    @Test
    fun courseStateLabel_mapsStates() {
        assertThat(CourseShapingState.entries.map(::courseStateLabel))
            .containsExactly("予約", "成形中", "送り済み", "変更あり", "送れません").inOrder()
        assertThat(CourseShapingState.entries.map(::courseStateColorArgb).distinct()).hasSize(5)
    }
}
