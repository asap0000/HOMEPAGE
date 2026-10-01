package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.distkit.CourseBundleImportException
import com.istech.buscourse.map.MapPackageImportException
import org.junit.Test

class BundleUiMessagesTest {
    @Test fun topStatusAndNavigationGateCoverAllFourStates() {
        val none = naviTopState(null, 0)
        assertThat(none.mapLine).isEqualTo("まだ")
        assertThat(none.courseLine).isEqualTo("まだ")
        assertThat(none.canNavigate).isFalse()
        assertThat(none.prompt).isEqualTo("地図の束とコースの束を入れてください")

        val mapOnly = naviTopState("架空地図", 0)
        assertThat(mapOnly.mapLine).isEqualTo("入っている（架空地図）")
        assertThat(mapOnly.courseLine).isEqualTo("まだ")
        assertThat(mapOnly.canNavigate).isFalse()

        val coursesOnly = naviTopState(null, 2)
        assertThat(coursesOnly.courseLine).isEqualTo("入っている（2 本）")
        assertThat(coursesOnly.canNavigate).isFalse()

        val both = naviTopState("架空地図", 2)
        assertThat(both.canNavigate).isTrue()
        assertThat(both.prompt).isNull()
    }

    @Test fun confirmationAndCompletionCopyDependsOnBundleAndExistingData() {
        assertThat(bundleConfirmMessage(BundleKind.COURSE, false)).isEqualTo("いま入っているコースはありません。")
        assertThat(bundleConfirmMessage(BundleKind.COURSE, true)).isEqualTo("いま入っているコースと映像は全部置き換わります。地図はそのままです。")
        assertThat(bundleConfirmMessage(BundleKind.MAP, false)).isEqualTo("いま入っている地図はありません。")
        assertThat(bundleConfirmMessage(BundleKind.MAP, true)).isEqualTo("いま入っている地図は置き換わります。コースはそのままです。")
        assertThat(bundleCompletedMessage(true)).isEqualTo("入りました")
        assertThat(bundleCompletedMessage(false)).isEqualTo("入れ替わりました")
    }

    @Test fun coverageCopyDistinguishesNoMapAndOutsidePoints() {
        assertThat(coverageMessage(null, false)).isEqualTo("地図がまだ無いので、はみ出しの確認は地図を入れたときに行います")
        assertThat(coverageMessage(0, true)).isEqualTo("地図の範囲内です")
        assertThat(coverageMessage(3, true)).isEqualTo("地図の範囲から外れる所があります（3 点）")
    }

    @Test fun failureCopyMapsReasonsAndRoundsRequiredGigabytes() {
        assertThat(bundleFailureMessage(failure(CourseBundleImportException.Reason.CORRUPT))).isEqualTo("束が壊れています")
        assertThat(bundleFailureMessage(failure(CourseBundleImportException.Reason.UNSUPPORTED_SCHEMA))).isEqualTo("新しすぎる版です。アプリを更新してください")
        assertThat(bundleFailureMessage(failure(CourseBundleImportException.Reason.INSUFFICIENT_SPACE, 1_260_000_000))).isEqualTo("空きが足りません（あと 1.3 GB）")
        assertThat(bundleFailureMessage(MapPackageImportException("details", java.util.zip.ZipException("broken"))))
            .isEqualTo("取り込みに失敗しました: ZIPが壊れています")
    }

    private fun failure(reason: CourseBundleImportException.Reason, bytes: Long = 0) = CourseBundleImportException(reason, bytes, "internal")
}
