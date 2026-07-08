package com.istech.buscourse.recording

/**
 * `recording_session.type` の許容値（設計書§3.5・§4.1）。
 *
 * `core.data.RecordingSessionEntity.type` はRoom側では素の `String` 列として保持している
 * （TypeConverterを増やさない設計判断、フェーズ0）。起動Intentの受け渡しやサービス内部ロジックでは
 * 誤字による不正値を避けるため、この enum を介して扱い `.name` で文字列化する。
 */
enum class RecordingSessionType {
    /** コース全体を通しで走行する本番運行記録。 */
    FULL_RUN,

    /** コースの一部区間のみを対象にした走行記録（未走行区間の試走補完、§3.8）。 */
    PARTIAL_RUN,

    /** 案内モード中の走行記録（§6）。 */
    LIVE_GUIDANCE,

    /** 試走比較用の走行記録（§7）。 */
    TEST_DRIVE,
}
