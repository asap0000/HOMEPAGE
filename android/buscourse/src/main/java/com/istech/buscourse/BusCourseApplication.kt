package com.istech.buscourse

import android.app.Application
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.recording.StorageRotationWorker

/**
 * BusCourse アプリケーションクラス。
 * アプリ全体で共有するシングルトン（Room DB 等）の初期化起点。オフライン厳守（ネットワーク接続経路を持たない）。
 *
 * `WorkManager` によるストレージローテーション（`StorageRotationWorker`）の定期登録もここで行う
 * （設計書§4.10.3「登録（BusCourseApplication#onCreate）」）。`WorkManager` はネットワーク通信を
 * 一切伴わないローカルタスクスケジューラであり、オフライン方針に抵触しない。
 */
class BusCourseApplication : Application() {

    /** Room DB のアプリ全体シングルトン（設計書§3.2）。Activity/Service/Workerから共有して使う。 */
    val database: BusCourseDatabase by lazy { BusCourseDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        StorageRotationWorker.schedule(this)
    }
}
