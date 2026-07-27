package com.istech.buscourse.backup

/**
 * 復元の可否ゲート（タスク指示書§2「オーナー承認済みの振る舞い（復唱3行）」・§3「判定と手順の具体」）。
 * いずれもAndroid非依存の純関数（整数の比較のみ）。
 */
object RestoreCompatibility {

    /**
     * バックアップのDBスキーマ版がアプリより新しい場合は断る（復唱3行目。Roomは下げられないため
     * 戻すと壊れる）。等しい・古いは受け入れる（古い場合は復元後の起動でRoomのマイグレーションが走る。
     * v12→v17は実測確認済み、タスク指示書§3）。
     */
    fun isSchemaAcceptable(backupDbSchemaVersion: Int, appDbSchemaVersion: Int): Boolean =
        backupDbSchemaVersion <= appDbSchemaVersion

    /**
     * 「データなし」の判定（タスク指示書§3）。主要テーブル（course・recording_session・
     * bus_stop_card）が全て空であることを指す。DBファイルの有無では判定しない――初回起動でRoomが
     * 空DBを作るため、ファイルの有無で判定するとすぐ偽になる（タスク指示書§3の注記）。
     */
    fun isDeviceEmpty(courseCount: Int, recordingSessionCount: Int, busStopCardCount: Int): Boolean =
        courseCount == 0 && recordingSessionCount == 0 && busStopCardCount == 0
}
