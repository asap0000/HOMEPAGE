package com.istech.buscourse.distkit

/** Geographic coverage of a selected offline map against route track points. */
object MapCoverageCheck {
    data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
        init { require(west <= east && south <= north) }
        fun contains(point: Point): Boolean = point.longitude in west..east && point.latitude in south..north
    }

    data class Point(val latitude: Double, val longitude: Double)

    sealed interface Result {
        data object NoMap : Result
        data class Checked(val outsidePointCount: Int) : Result
    }

    fun check(bounds: Bounds?, trackPoints: List<Point>): Result {
        if (bounds == null) return Result.NoMap
        return Result.Checked(trackPoints.count { !bounds.contains(it) })
    }
}
