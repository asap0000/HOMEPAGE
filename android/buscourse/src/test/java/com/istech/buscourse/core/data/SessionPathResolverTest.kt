package com.istech.buscourse.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 運行の相対パス（`sessions/{id}/...`）の解決の純関数テスト（2026-10-20 委託指示書 K）。
 * microSD 側の置き場所は `<sdFiles>/buscourse/<sdOwnerId>`。
 */
class SessionPathResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun body() = File(tmp.root, "body").also { it.mkdirs() }
    private fun sdFiles() = File(tmp.root, "sd-files").also { it.mkdirs() }

    @Test
    fun `test_microSDに運行のフォルダがあればそちらに解く_無ければ本体`() {
        val body = body()
        val sdRoot = BusCourseStorage.sdRootFor(sdFiles(), "mine")
        File(sdRoot, "sessions/7/frames").mkdirs()

        // microSD にフォルダがある運行（7）は microSD で解く。
        assertThat(SessionPathResolver.resolve(body, "sessions/7/frames/a.jpg") { sdRoot })
            .isEqualTo(File(sdRoot, "sessions/7/frames/a.jpg"))
        assertThat(SessionPathResolver.resolve(body, "sessions/7/gps_raw.jsonl") { sdRoot })
            .isEqualTo(File(sdRoot, "sessions/7/gps_raw.jsonl"))
        // microSD にフォルダが無い運行（8）は本体。
        assertThat(SessionPathResolver.resolve(body, "sessions/8/frames/a.jpg") { sdRoot })
            .isEqualTo(File(body, "sessions/8/frames/a.jpg"))
        // microSD が挿さっていなければ、microSD にあった運行も本体の側の名前になる（呼び出し側が覚えで見分ける）。
        assertThat(SessionPathResolver.resolve(body, "sessions/7/frames/a.jpg") { null })
            .isEqualTo(File(body, "sessions/7/frames/a.jpg"))
    }

    @Test
    fun `運行以外のパスは microSD を見ずに常に本体`() {
        val body = body()
        val sdRoot = BusCourseStorage.sdRootFor(sdFiles(), "mine")
        // microSD に同じ名前のフォルダがあっても、停留所カード・地図・naviruns は本体のまま。
        File(sdRoot, "stopcards/1").mkdirs()
        File(sdRoot, "maps/r1").mkdirs()
        var asked = false
        for (rel in listOf("stopcards/1/photo_thumb.jpg", "maps/r1/style.json", "naviruns/100.jsonl", "segments/1_2.gpx", "distkit/x/frames/0.jpg")) {
            assertThat(SessionPathResolver.resolve(body, rel) { asked = true; sdRoot }).isEqualTo(File(body, rel))
        }
        assertThat(asked).isFalse()
    }

    @Test
    fun `運行の番号の形でないパスは運行として扱わない`() {
        assertThat(SessionPathResolver.sessionFolderOf("sessions/12/frames/x.jpg")).isEqualTo("sessions/12")
        assertThat(SessionPathResolver.sessionFolderOf("sessions/12")).isEqualTo("sessions/12")
        assertThat(SessionPathResolver.sessionFolderOf("sessions")).isNull()
        assertThat(SessionPathResolver.sessionFolderOf("sessions/abc/x")).isNull()
        assertThat(SessionPathResolver.sessionFolderOf("sessions/12abc/x")).isNull()
        assertThat(SessionPathResolver.sessionFolderOf("mysessions/12/x")).isNull()
    }

    @Test
    fun `test_別の端末のカードの運行とは番号がぶつからない`() {
        val body = body()
        val sdFiles = sdFiles()
        // 別の端末（sdOwnerId = other）が使ったカード。そこにも運行 1 がある。
        val others = BusCourseStorage.sdRootFor(sdFiles, "other")
        File(others, "sessions/1/frames").mkdirs()
        File(others, "sessions/1/meta.json").writeText("{}")

        // この端末（sdOwnerId = mine）は、同じカードを挿されても別の端末の運行 1 を解かない。
        val mine = BusCourseStorage.sdRootFor(sdFiles, "mine")
        assertThat(SessionPathResolver.resolve(body, "sessions/1/meta.json") { mine })
            .isEqualTo(File(body, "sessions/1/meta.json"))
        // 自分の乱数で作った運行 1 は、同じカードの中でも別のフォルダにあり、混ざらない。
        File(mine, "sessions/1/frames").mkdirs()
        assertThat(SessionPathResolver.resolve(body, "sessions/1/meta.json") { mine })
            .isEqualTo(File(mine, "sessions/1/meta.json"))
        assertThat(mine).isNotEqualTo(others)
    }
}
