package com.alcoholchecker.data

import org.json.JSONObject

data class HealthAnswer(val normal: Boolean)   // true = 正常, false = 異常あり

data class HealthQuestionnaire(
    val fever: HealthAnswer       = HealthAnswer(true),   // 体温
    val sleep: HealthAnswer       = HealthAnswer(true),   // 睡眠
    val headache: HealthAnswer    = HealthAnswer(true),   // 頭痛・めまい
    val nausea: HealthAnswer      = HealthAnswer(true),   // 吐き気・腹痛
    val fatigue: HealthAnswer     = HealthAnswer(true),   // 倦怠感
    val medication: HealthAnswer  = HealthAnswer(true),   // 服薬（眠気）
) {
    // 自動評価ロジック（フローチャート準拠）
    val status: String get() = when {
        !fever.normal || !medication.normal          -> "乗務不可"
        !sleep.normal || !headache.normal ||
        !nausea.normal || !fatigue.normal            -> "要注意"
        else                                          -> "良好"
    }

    val isBlocked: Boolean get() = status == "乗務不可"

    fun toJson(): String = JSONObject().apply {
        put("fever",      fever.normal)
        put("sleep",      sleep.normal)
        put("headache",   headache.normal)
        put("nausea",     nausea.normal)
        put("fatigue",    fatigue.normal)
        put("medication", medication.normal)
    }.toString()
}
