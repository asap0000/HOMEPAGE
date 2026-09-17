package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 「使う／使わない」の覚えの鍵（増分O・2026-09-17）。 */
class NaviCourseVisibilityTest {

    @Test
    fun `identity の3つをそのまま並べた鍵になる`() {
        assertThat(NaviCourseVisibility.keyOf("POC", 37, 2026)).isEqualTo("POC|37|2026")
    }

    @Test
    fun `busId に区切り文字が入っても別の identity と衝突しない`() {
        // 逃がさないと "A|B" + courseNo=1 と "A" + courseNo=B ... のように、別のコースが同じ鍵になりうる。
        assertThat(NaviCourseVisibility.keyOf("A|B", 1, 2026))
            .isNotEqualTo(NaviCourseVisibility.keyOf("A", 1, 2026))
        assertThat(NaviCourseVisibility.keyOf("A|B", 1, 2026)).doesNotContain("A|B")
    }

    @Test
    fun `逃がした形をそのまま名前に持つバスと衝突しない`() {
        // ★`%` を先に逃がしていないと、"A%7CB" と "A|B" が同じ鍵に潰れる（二重変換）。
        assertThat(NaviCourseVisibility.keyOf("A%7CB", 1, 2026))
            .isNotEqualTo(NaviCourseVisibility.keyOf("A|B", 1, 2026))
    }

    @Test
    fun `コース番号と年が違えば別の鍵になる`() {
        val base = NaviCourseVisibility.keyOf("青バス", 123, 2026)
        assertThat(base).isNotEqualTo(NaviCourseVisibility.keyOf("青バス", 124, 2026))
        assertThat(base).isNotEqualTo(NaviCourseVisibility.keyOf("青バス", 123, 2025))
    }
}
