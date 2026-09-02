package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.course.CourseKind
import org.junit.Test

/**
 * ナビの選択一覧に出す条件の回帰（増分J・2026-09-03）。
 *
 * 発端＝オーナー報告「ナビに送ったコース情報がナビ一覧に載らない」。原因は一覧が `kind != DRAFT` だけで
 * 弾いており、**「ナビ用に送る」に種別を書き換える経路が無い**ため、送っても予約のまま出てこなかったこと
 * （実機に3本実在。うち1本は実車走行から作った停留所54件のコース）。
 *
 * **旧実装（送り済みを見ない）なら [sentDraft_isShown] が落ちる。**
 */
class NaviCoursePickVisibilityTest {

    @Test fun sentDraft_isShown() {
        // ★これが今回の本体＝送ってある予約は出す。
        assertThat(naviPickShouldShow(kind = CourseKind.DRAFT.name, sent = true)).isTrue()
    }

    @Test fun unsentDraft_isHidden() {
        // まだ成形していない下書きは選ばせない（従来どおり）。
        assertThat(naviPickShouldShow(kind = CourseKind.DRAFT.name, sent = false)).isFalse()
    }

    @Test fun nonDraft_isShownRegardlessOfSent() {
        // 通常・臨時のコースは送信の有無によらず出す（未送信は画面側で灰色になる）。
        for (kind in listOf(CourseKind.STANDARD, CourseKind.TEMPORARY)) {
            assertThat(naviPickShouldShow(kind = kind.name, sent = false)).isTrue()
            assertThat(naviPickShouldShow(kind = kind.name, sent = true)).isTrue()
        }
    }
}
