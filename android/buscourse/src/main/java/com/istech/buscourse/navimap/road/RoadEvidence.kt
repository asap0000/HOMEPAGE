package com.istech.buscourse.navimap.road

import com.istech.buscourse.navimap.NaviGuidanceEngine
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.floor

data class RoadVisit(
    val nodeId: Long, val segmentId: String, val chainageM: Double, val lateralM: Double,
    val angleDeg: Double, val kind: String, val inWay: Long, val outWay: Long,
    val inStep: Int, val outStep: Int, val incomingArmM: Double, val outgoingArmM: Double,
    val incomingBearingDeg: Double, val outgoingBearingDeg: Double,
    val degree: Int, val controls: List<String>, val continuingRoad: Boolean,
    val roundabout: Boolean, val gpsAngles: Map<String, Double?>, val fit: Boolean,
    val busConditions: Map<String, String>, val onewaySource: List<Map<String, Any?>>,
    val meanLateralM: Double, val p95LateralM: Double, val outerChordM: Double,
    val decision: String = "", val crankPartner: Long? = null,
) {
    fun evidence(): Map<String, Any?> = linkedMapOf(
        "nodeId" to nodeId, "segmentId" to segmentId, "chainageM" to chainageM,
        "lateralM" to lateralM, "angleDeg" to angleDeg, "kind" to kind,
        "inWay" to inWay, "outWay" to outWay, "inStep" to inStep, "outStep" to outStep,
        "incomingArmM" to incomingArmM, "outgoingArmM" to outgoingArmM,
        "incomingBearingDeg" to incomingBearingDeg, "outgoingBearingDeg" to outgoingBearingDeg,
        "connectionOrder" to listOf(mapOf("wayId" to inWay, "step" to inStep), mapOf("wayId" to outWay, "step" to outStep)),
        "degree" to degree,
        "controls" to controls, "continuingRoad" to continuingRoad, "roundabout" to roundabout,
        "gpsAngles" to gpsAngles, "fit" to fit, "busConditions" to busConditions,
        "onewaySource" to onewaySource, "meanLateralM" to meanLateralM,
        "p95LateralM" to p95LateralM, "nodeLateralM" to lateralM, "outerChordM" to outerChordM,
        "decision" to decision, "crankPartner" to crankPartner,
        "source" to "map-connected-sequence-v1", "policyId" to "map-connected-guidance-v1",
    )
}

data class RoadEvidence(
    val regionId: String, val mapSha256: String, val indexSha256: String,
    val dataTimestampUtc: String?, val routeSha256: String,
    val visits: List<RoadVisit>, val proposals: List<RoadVisit>,
)

object RoadFingerprint {
    fun of(groups: List<NaviGuidanceEngine.TrackGroup>): String {
        val json = groups.joinToString(prefix = "[", postfix = "]", separator = ",") { group ->
            val points = group.points.joinToString(prefix = "[", postfix = "]", separator = ",") { p ->
                "[${floor(p.chainageM * 1000 + .5).toLong()},${floor(p.lat * 1e7 + .5).toLong()},${floor(p.lon * 1e7 + .5).toLong()}]"
            }
            "[${JSONObject.quote(group.id)},$points]"
        }
        return MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
