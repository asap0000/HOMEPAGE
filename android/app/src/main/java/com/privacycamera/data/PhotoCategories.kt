package com.privacycamera.data

/** Predefined categories for classifying captured documents. */
object PhotoCategories {
    const val UNCLASSIFIED = "未分類"

    /** Selectable categories shown in the memo dialog. */
    val SELECTABLE = listOf(
        "運転免許証",
        "健康保険証",
        "マイナンバーカード",
        "預金通帳",
        "クレジットカード",
        "パスポート",
        "その他",
        UNCLASSIFIED
    )
}
