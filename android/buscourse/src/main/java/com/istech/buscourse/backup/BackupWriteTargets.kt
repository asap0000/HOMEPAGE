package com.istech.buscourse.backup

/** Pure list of supported DataStore files the ZIP writer knows how to collect. */
object BackupWriteTargets {
    val DATASTORE_PATHS: List<String> = listOf(
        "navi_settings.preferences_pb",
        "navi_course_visibility.preferences_pb",
    )

    fun datastorePathsFromInventory(included: List<BackupInventory.Category>): List<String> =
        included.mapNotNull { category ->
            category.label.takeIf { it.startsWith("files/datastore/") }
                ?.removePrefix("files/datastore/")
        }

    fun sourceOriginId(recordedSourceOriginId: String?): String? = recordedSourceOriginId
}
