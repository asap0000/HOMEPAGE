package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.course.CourseKind
import org.junit.Test

class CourseListScreenTest {
    @Test
    fun courseKindBadgeLabel_mapsKinds() {
        assertThat(courseKindBadgeLabel(CourseKind.DRAFT.name)).isEqualTo("予約")
        assertThat(courseKindBadgeLabel(CourseKind.TEMPORARY.name)).isEqualTo("臨時")
        assertThat(courseKindBadgeLabel(CourseKind.STANDARD.name)).isEqualTo("正規")
    }
}
