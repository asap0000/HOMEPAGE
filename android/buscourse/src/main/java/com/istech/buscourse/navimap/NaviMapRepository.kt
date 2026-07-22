package com.istech.buscourse.navimap

import androidx.room.withTransaction
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.NaviMapEntity

/** ナビ用マップの登録と、同一course_identity内の簡易版ライフサイクルを扱う。 */
class NaviMapRepository(private val database: BusCourseDatabase) {
    private val dao = database.naviMapDao()

    /**
     * ヘッダを登録する。EX完成形を登録した場合だけ、同一identityのアクティブな
     * app_simpleをアーカイブする。子テーブルの投入は呼び出し側の責務とする。
     */
    suspend fun registerMap(
        map: NaviMapEntity,
        now: Long = System.currentTimeMillis(),
    ): Long = database.withTransaction {
        val id = dao.insertMap(map)
        if (map.profile == "ex_full") {
            dao.archiveSupersededAppSimple(map.busId, map.courseNo, map.year, now)
        }
        id
    }

    suspend fun activeMapFor(busId: String, courseNo: Int, year: Int): NaviMapEntity? =
        dao.getActiveMapsByIdentity(busId, courseNo, year).firstOrNull()

    suspend fun archivedMapsFor(busId: String, courseNo: Int, year: Int): List<NaviMapEntity> =
        dao.getArchivedMapsByIdentity(busId, courseNo, year)
}
