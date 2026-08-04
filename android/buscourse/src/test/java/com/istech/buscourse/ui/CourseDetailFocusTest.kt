package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** design-gate C-2 y×5（2026-08-04）: 停留所を外した直後の選択位置。 */
class CourseDetailFocusTest {
    @Test
    fun removingFirst_selectsNewFirst() = assertThat(nextSelectionAfterRemoval(0, 2)).isEqualTo(0)

    @Test
    fun removingMiddle_selectsShiftedNext() = assertThat(nextSelectionAfterRemoval(1, 2)).isEqualTo(1)

    @Test
    fun removingLast_selectsPrevious() = assertThat(nextSelectionAfterRemoval(2, 2)).isEqualTo(1)

    @Test
    fun removingOnlyStop_clearsSelection() = assertThat(nextSelectionAfterRemoval(0, 0)).isNull()

    @Test
    fun removingFirstOfTwo_selectsRemainingStop() = assertThat(nextSelectionAfterRemoval(0, 1)).isEqualTo(0)
}
