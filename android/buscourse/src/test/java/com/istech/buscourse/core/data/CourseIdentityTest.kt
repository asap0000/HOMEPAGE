package com.istech.buscourse.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CourseIdentityTest {
    private fun course(
        busId: String? = "青",
        courseNo: Int? = 1,
        year: Int? = 2026,
    ) = CourseEntity(
        name = "test",
        description = null,
        kind = "STANDARD",
        baseCourseId = null,
        createdAt = 1L,
        updatedAt = 1L,
        busId = busId,
        courseNo = courseNo,
        year = year,
    )

    @Test fun identityOrNull_returnsNullWhenBusIdIsMissing() {
        assertThat(course(busId = null).identityOrNull()).isNull()
    }

    @Test fun identityOrNull_returnsNullWhenCourseNoIsMissing() {
        assertThat(course(courseNo = null).identityOrNull()).isNull()
    }

    @Test fun identityOrNull_returnsNullWhenYearIsMissing() {
        assertThat(course(year = null).identityOrNull()).isNull()
    }

    @Test fun identityOrNull_returnsIdentityWhenComplete() {
        assertThat(course().identityOrNull()).isEqualTo(CourseIdentity("青", 1, 2026))
    }
}
