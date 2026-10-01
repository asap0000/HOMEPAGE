package com.istech.buscourse.ui

import com.istech.buscourse.distkit.CourseBundleImportException
import com.istech.buscourse.map.mapImportFailureMessage
import java.util.Locale

internal data class NaviTopState(val mapLine: String, val courseLine: String, val canNavigate: Boolean, val prompt: String?)

internal fun naviTopState(mapName: String?, courseCount: Int): NaviTopState {
    val hasMap = !mapName.isNullOrBlank()
    val hasCourses = courseCount > 0
    return NaviTopState(
        mapLine = if (hasMap) "入っている（$mapName）" else "まだ",
        courseLine = if (hasCourses) "入っている（$courseCount 本）" else "まだ",
        canNavigate = hasMap && hasCourses,
        prompt = if (hasMap && hasCourses) null else "地図の束とコースの束を入れてください",
    )
}

internal enum class BundleKind { COURSE, MAP }

internal fun bundleConfirmMessage(kind: BundleKind, hasExisting: Boolean): String = when (kind) {
    BundleKind.COURSE -> if (!hasExisting) "いま入っているコースはありません。"
        else "いま入っているコースと映像は全部置き換わります。地図はそのままです。"
    BundleKind.MAP -> if (!hasExisting) "いま入っている地図はありません。"
        else "いま入っている地図は置き換わります。コースはそのままです。"
}

internal fun bundleCompletedMessage(firstImport: Boolean): String = if (firstImport) "入りました" else "入れ替わりました"

internal fun coverageMessage(outsidePoints: Int?, hasMap: Boolean): String = when {
    !hasMap -> "地図がまだ無いので、はみ出しの確認は地図を入れたときに行います"
    (outsidePoints ?: 0) > 0 -> "地図の範囲から外れる所があります（${outsidePoints} 点）"
    else -> "地図の範囲内です"
}

internal fun bundleFailureMessage(error: Throwable): String {
    val courseError = generateSequence(error) { it.cause }.filterIsInstance<CourseBundleImportException>().firstOrNull()
    if (courseError != null) return when (courseError.reason) {
        CourseBundleImportException.Reason.CORRUPT -> "束が壊れています"
        CourseBundleImportException.Reason.UNSUPPORTED_SCHEMA -> "新しすぎる版です。アプリを更新してください"
        CourseBundleImportException.Reason.INSUFFICIENT_SPACE -> "空きが足りません（あと ${formatAdditionalSpace(courseError.additionalBytes)} GB）"
        CourseBundleImportException.Reason.NAVI_ONLY_REQUIRED -> "ナビ専科でのみ使えます"
    }
    return mapImportFailureMessage(error)
}

internal fun formatAdditionalSpace(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes.coerceAtLeast(0L) / 1_000_000_000.0)
