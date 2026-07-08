package com.istech.buscourse.recording

/**
 * 古いセッションのローテーション削除（設計書§4.1・§4.10.3）。androidx.work の CoroutineWorker として
 * フェーズ1〜2で実装する（work依存はその時点で追加。ネットワーク系ライブラリではない）。
 */
class StorageRotationWorker
