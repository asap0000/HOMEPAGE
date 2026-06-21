package com.privacycamera.data

/** Categories for classifying captured documents. */
object PhotoCategories {
    const val UNCLASSIFIED = "未分類"

    /** Built-in categories. Users can add their own on top of these. */
    val PREDEFINED = listOf(
        "運転免許証",
        "健康保険証",
        "マイナンバーカード",
        "預金通帳",
        "クレジットカード",
        "パスポート",
        "その他"
    )

    /** True if [name] is one of the built-in names (predefined or unclassified). */
    fun isBuiltIn(name: String): Boolean =
        name == UNCLASSIFIED || name in PREDEFINED
}
