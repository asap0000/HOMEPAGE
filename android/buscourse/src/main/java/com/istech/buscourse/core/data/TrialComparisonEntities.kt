package com.istech.buscourse.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 試走とコース正規ルートの比較実行1回分のサマリ。 */
@Entity(
    tableName = "test_run_comparison",
    indices = [Index("course_id"), Index("candidate_session_id")],
    foreignKeys = [
        ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["course_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RecordingSessionEntity::class, parentColumns = ["id"], childColumns = ["candidate_session_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TestRunComparisonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "course_id") val courseId: Long,
    @ColumnInfo(name = "baseline_source") val baselineSource: String,
    @ColumnInfo(name = "candidate_session_id") val candidateSessionId: Long,
    @ColumnInfo(name = "computed_at_epoch_ms") val computedAtEpochMs: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = 1,
    @ColumnInfo(name = "params_json") val paramsJson: String,
)

/** 比較実行内の停留所ごとの判定。causeは5aでは手動タグ用の器としてのみ保持する。 */
@Entity(
    tableName = "test_run_comparison_stop_diff",
    indices = [Index("comparison_id")],
    foreignKeys = [
        ForeignKey(entity = TestRunComparisonEntity::class, parentColumns = ["id"], childColumns = ["comparison_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TestRunComparisonStopDiffEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "comparison_id") val comparisonId: Long,
    @ColumnInfo(name = "stop_card_id") val stopCardId: Long?,
    @ColumnInfo(name = "sequence_index") val sequenceIndex: Int,
    val status: String,
    @ColumnInfo(name = "position_error_m") val positionErrorM: Double?,
    @ColumnInfo(name = "matched_point_seq") val matchedPointSeq: Int?,
    @ColumnInfo(name = "nearest_approach_m") val nearestApproachM: Double?,
    @ColumnInfo(defaultValue = "'UNSET'") val cause: String = "UNSET",
)

/** 連続したコース逸脱区間。 */
@Entity(
    tableName = "test_run_comparison_deviation_segment",
    indices = [Index("comparison_id")],
    foreignKeys = [
        ForeignKey(entity = TestRunComparisonEntity::class, parentColumns = ["id"], childColumns = ["comparison_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TestRunComparisonDeviationSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "comparison_id") val comparisonId: Long,
    @ColumnInfo(name = "start_chainage_m") val startChainageM: Double,
    @ColumnInfo(name = "end_chainage_m") val endChainageM: Double,
    @ColumnInfo(name = "start_point_seq") val startPointSeq: Int,
    @ColumnInfo(name = "end_point_seq") val endPointSeq: Int,
    @ColumnInfo(name = "max_lateral_offset_m") val maxLateralOffsetM: Double,
    @ColumnInfo(name = "mean_lateral_offset_m") val meanLateralOffsetM: Double,
    @ColumnInfo(name = "duration_sec") val durationSec: Int,
)
