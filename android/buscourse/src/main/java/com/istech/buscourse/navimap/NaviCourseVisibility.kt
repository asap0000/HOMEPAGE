package com.istech.buscourse.navimap

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ナビの選択一覧で「使わない」ことにしたコースの覚え（増分O・オーナー承認 y×2・2026-09-17）。
 *
 * **★覚えるのは course_identity であって `course.id` ではない。** 端末の採番は端末ごとに違うので、
 * id で覚えると**同じコースを入れ直しただけで設定が飛ぶ**（この先の「コースを配って受け入れる」増分で
 * 必ず踏む）。identity（バス・コース番号・年）なら、配り直しても同じコースは同じ鍵になる。
 *
 * **★コースのデータには触らない。** ここにあるのは「この端末で一覧に出すか」だけで、
 * `course` にも `navi_map` にも列を足さない（DB の版を上げない＝軽量レーンの手続きを起こさない）。
 * 同じコースを複数の端末へ配ったとき、**端末ごとに別々でよい**という性質そのものを表している。
 */
object NaviCourseVisibility {

    /**
     * 覚えの鍵。`busId` に区切り文字が混ざっても別の identity と衝突しないよう、
     * **`%` と `|` だけをパーセント記法へ逃がす**（`%` を先に逃がすこと。順序を逆にすると二重変換で壊れる）。
     *
     * 読み戻す関数は**作らない**——要るのは「この鍵が集合にあるか」だけで、
     * 使い道のない復元関数を置くと、誰も呼ばないまま腐る。
     */
    fun keyOf(busId: String, courseNo: Int, year: Int): String =
        "${escape(busId)}$FIELD_SEPARATOR$courseNo$FIELD_SEPARATOR$year"

    private fun escape(raw: String): String =
        raw.replace("%", "%25").replace(FIELD_SEPARATOR, "%7C")

    private const val FIELD_SEPARATOR = "|"
}

private val Context.naviCourseVisibilityDataStore by preferencesDataStore(name = DATA_STORE_NAME)

/** 本ファイルの DataStore 名。`BackupInventory` の棚卸し表と揃える（片方だけ変えないこと）。 */
internal const val DATA_STORE_NAME = "navi_course_visibility"

/**
 * 「使わない」に倒したコースの集合を持つ DataStore アダプタ（[NaviSettingsRepository] と同じ流儀）。
 *
 * **運転者設定（`navi_settings`）とは別ファイルにしてある**——表示の好みと、どのコースを使うかは別の話で、
 * 混ぜると「ナビ設定を初期化したらコースの選択まで消えた」になる。
 *
 * **★別ファイルにした以上、`BackupInventory` の棚卸し表へ必ず登録すること。**
 * あの表は**知らないファイル名を安全側で除外する**ので、登録を忘れると
 * **機種を変えたときにこの覚えだけが静かに消える**（バックアップを取った時点では気づけない）。
 */
class NaviCourseVisibilityRepository(private val context: Context) {

    /** 「使わない」に倒した identity の鍵の集合。空＝全部使う（＝増分O より前と同じ見え方）。 */
    val disabledKeysFlow: Flow<Set<String>> =
        context.naviCourseVisibilityDataStore.data.map { preferences ->
            preferences[KEY_DISABLED] ?: emptySet()
        }

    /** [key] のコースを使う（[enabled]=true）／使わない（false）に倒す。 */
    suspend fun setEnabled(key: String, enabled: Boolean) {
        context.naviCourseVisibilityDataStore.edit { preferences ->
            val current = preferences[KEY_DISABLED] ?: emptySet()
            preferences[KEY_DISABLED] = if (enabled) current - key else current + key
        }
    }

    private companion object {
        val KEY_DISABLED = stringSetPreferencesKey("disabled_course_keys")
    }
}
