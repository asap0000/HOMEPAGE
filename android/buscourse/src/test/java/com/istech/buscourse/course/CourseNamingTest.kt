package com.istech.buscourse.course

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CourseNamingTest {
    @Test fun unusedNamePassesThrough() {
        assertThat(resolveUniqueCourseName("S18-1", listOf("S18-2"))).isEqualTo("S18-1")
    }

    @Test fun firstDuplicateGetsOne() {
        assertThat(resolveUniqueCourseName("S18-1", listOf("S18-1"))).isEqualTo("S18-1(1)")
    }

    @Test fun missingSuffixIsNotReused() {
        assertThat(resolveUniqueCourseName("S18-1", listOf("S18-1", "S18-1(1)", "S18-1(3)")))
            .isEqualTo("S18-1(4)")
    }

    @Test fun numberedDesiredIsReducedToItsStem() {
        assertThat(resolveUniqueCourseName("S18-1(3)", listOf("S18-1", "S18-1(1)")))
            .isEqualTo("S18-1(2)")
    }

    /** 無印が削除済みでも (n) が現存すれば無印を再利用しない（新しい無印が古い (1) より古く見える転倒の防止）。 */
    @Test fun deletedPlainNameIsNotReused() {
        assertThat(resolveUniqueCourseName("S18-1", listOf("S18-1(1)", "S18-1(3)")))
            .isEqualTo("S18-1(4)")
    }

    @Test fun consecutiveReservationsAvoidEachOther() {
        val existing = mutableListOf("S18-1")
        val first = resolveUniqueCourseName("S18-1", existing).also(existing::add)
        val second = resolveUniqueCourseName("S18-1", existing).also(existing::add)
        assertThat(listOf(first, second)).containsExactly("S18-1(1)", "S18-1(2)").inOrder()
    }
}
