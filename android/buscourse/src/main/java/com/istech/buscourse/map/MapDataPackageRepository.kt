package com.istech.buscourse.map

import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.MapDataPackageEntity
import kotlinx.coroutines.flow.Flow

/**
 * `map_data_package`のDAOラッパー（設計書§5.6.4 `MapDataPackageRepository`）。
 * 取り込み済みパッケージの一覧管理・選択状態の永続化を担う。
 *
 * [MapPackageImporter]がインポート成功時に[upsert]を呼ぶ。パッケージ選択UI（本タスクの対象外）は
 * [selectPackage]でユーザー操作による切り替えを反映する想定。
 */
class MapDataPackageRepository(database: BusCourseDatabase) {
    private val dao = database.mapDataPackageDao()

    /**
     * 現在地図表示に使用中のパッケージ（設計書§5.6.4）。Room標準のFlow返却クエリ
     * （`MapDataPackageDao.observeSelected`）に基づき、`map_data_package`テーブルの変更へ自動追従する
     * （他リポジトリの`WorkLogDao`等はsuspend関数のみで揃えているが、「選択中パッケージ」はUIが
     * 地図表示コンポーネントの生存期間中ずっと購読し続ける値のため、Room標準のFlow機構を素直に使う）。
     */
    val selectedPackage: Flow<MapDataPackageEntity?> = dao.observeSelected()

    suspend fun getAll(): List<MapDataPackageEntity> = dao.getAll()

    suspend fun getByRegionId(regionId: String): MapDataPackageEntity? = dao.getByRegionId(regionId)

    /** インポート成功時のUPSERT（[MapPackageImporter]から呼ばれる）。 */
    suspend fun upsert(pkg: MapDataPackageEntity) = dao.upsert(pkg)

    /** ユーザー操作によるパッケージ選択の切り替え（パッケージ選択UI用、本タスクの対象外）。 */
    suspend fun selectPackage(regionId: String) = dao.setSelected(regionId)

    suspend fun delete(regionId: String) = dao.delete(regionId)
}
