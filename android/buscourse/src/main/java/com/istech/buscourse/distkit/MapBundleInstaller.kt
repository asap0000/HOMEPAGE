package com.istech.buscourse.distkit

import android.net.Uri
import com.istech.buscourse.BuildConfig
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.MapDataPackageEntity
import com.istech.buscourse.map.MapDataPackageRepository
import com.istech.buscourse.map.MapPackageImporter
import java.io.File

data class MapBundleInstallResult(
    val installed: MapDataPackageEntity,
    val coverage: MapCoverageCheck.Result,
)

/** Imports a map, selects it, and then removes other installed map packages. */
class MapBundleInstaller(
    private val importer: MapPackageImporter,
    private val repository: MapDataPackageRepository,
    private val database: BusCourseDatabase,
    private val mapsRoot: File,
    private val naviOnly: Boolean = BuildConfig.NAVI_ONLY,
) {
    suspend fun install(uri: Uri): MapBundleInstallResult {
        CourseBundle.requireNaviOnly(naviOnly)
        val imported = importer.import(uri)
        repository.selectPackage(imported.regionId)
        repository.getAll().filter { it.regionId != imported.regionId }.forEach { old ->
            repository.delete(old.regionId)
            File(mapsRoot, old.regionId).deleteRecursively()
        }
        val bounds = MapCoverageCheck.Bounds(imported.boundsWest, imported.boundsSouth, imported.boundsEast, imported.boundsNorth)
        val points = database.naviMapDao().getAllTrackPoints().map { MapCoverageCheck.Point(it.lat, it.lon) }
        return MapBundleInstallResult(imported.copy(isSelected = true), MapCoverageCheck.check(bounds, points))
    }
}
