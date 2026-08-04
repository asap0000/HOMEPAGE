package com.istech.buscourse.course

private val NUMBERED_COURSE_NAME = Regex("^(.+)\\((\\d+)\\)$")

/**
 * 作業の名前を「無印→(1)→(2)」で重複回避する（design-gate B-3改 y×5・2026-08-04）。
 * 欠番は詰めず、同じ幹の現存最大番号の次を使う。**無印も再利用しない**——同じ幹の (n) が
 * 1つでも現存すれば無印は過去に使われた証拠なので、無印が消えていても最大番号の次を付ける
 * （新しい無印が古い (1) より新しいのに古く見える、という転倒を防ぐ）。
 */
fun resolveUniqueCourseName(desired: String, existingNames: Collection<String>): String {
    val base = NUMBERED_COURSE_NAME.matchEntire(desired)?.groupValues?.get(1) ?: desired
    val maxSuffix = existingNames.asSequence().mapNotNull { name ->
        val match = NUMBERED_COURSE_NAME.matchEntire(name) ?: return@mapNotNull null
        match.groupValues[2].toIntOrNull()?.takeIf { match.groupValues[1] == base }
    }.maxOrNull()

    if (base !in existingNames && maxSuffix == null) return base
    return "$base(${(maxSuffix ?: 0) + 1})"
}
