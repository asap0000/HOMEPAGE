package com.istech.buscourse.navimap

import com.istech.buscourse.navimap.road.RoadEvidence
import com.istech.buscourse.navimap.road.RoadVisit
import kotlin.math.abs
import kotlin.math.sign

/** 保存用案内。roadEvidence は次段の地図照合用入力。 */
object NaviGuidanceEngine {
    const val POLICY_ID = "ten-direction-28-trial-v1"
    const val STATUS_GPS_ONLY = "GPS_ONLY"
    const val STATUS_APPLIED = "APPLIED"
    const val STATUS_ROAD_ERROR = "ROAD_ERROR"
    const val SIGNAL_POLICY_ID = "signal-landmark-wording-trial-v1"
    const val SIGNAL_MAXIMUM_CUE_GAP_M = 30.0
    const val SIGNAL_NODE_LATERAL_M = 20.0
    const val SIGNAL_MEAN_LATERAL_M = 12.0
    const val SIGNAL_P95_LATERAL_M = 22.0
    const val SIGNAL_MINIMUM_ROAD_TURN_DEG = 28.0
    const val SIGNAL_EVIDENCE_STATUS = "MATCHED_INTERSECTION"
    data class DirectionPolicy(
        val id: String = POLICY_ID, val thresholdDeg: Double = 28.0, val headingArmM: Double = 50.0,
        val sampleStepM: Double = 2.0, val legacyDuplicateM: Double = 60.0,
        val sameDirectionSpacingM: Double = 60.0, val oppositeDirectionSpacingM: Double = 12.0,
        val minimumArmChordM: Double = 8.0, val minimumChainageM: Double = 70.0,
    )
    val DIRECTION_POLICY = DirectionPolicy()

    data class TrackGroup(val id: String, val points: List<NaviGuidanceCues.TrackPoint>)
    data class Guidance(
        val cue: NaviGuidanceCues.Cue,
        val role: String,
        val source: String,
        val evidence: Map<String, Any?>,
    )
    data class Result(
        val guidance: List<Guidance>, val policyId: String, val status: String,
        val regionId: String? = null, val mapSha256: String? = null, val routeSha256: String? = null,
        val summary: Map<String, Int>, val diagnostics: List<Map<String, Any?>> = emptyList(),
        val summaryDetails: Map<String, Any?> = emptyMap(),
    )
    private data class Peak(val group: TrackGroup, val chainage: Double, val angle: Double)

    fun build(
        trackGroups: List<TrackGroup>,
        stops: List<Double>,
        roadEvidence: RoadEvidence? = null,
    ): Result {
        val groups = trackGroups.map { it.copy(points = it.points.filter { p -> p.chainageM.isFinite() && p.lat.isFinite() && p.lon.isFinite() }.sortedBy { p -> p.chainageM }) }
        val points = groups.flatMap { it.points }.sortedBy { it.chainageM }
        val baseline = NaviGuidanceCues.build(points, stops)
        val detections = mutableListOf<Map<String, Any?>>()
        val baseWithEvidence = baseline.mapIndexed { index, cue ->
            val group = groups.firstOrNull { cue.chainageM in (it.points.firstOrNull()?.chainageM ?: Double.POSITIVE_INFINITY)..(it.points.lastOrNull()?.chainageM ?: Double.NEGATIVE_INFINITY) }
            val (d25, d50) = NaviGuidanceCues.directionGeometry(group?.points ?: points, cue.chainageM)
            val angle = d50 ?: d25
            val proposed = angle?.let { NaviGuidanceCues.directionKind(it) }
            val kind = if (cue.kind in setOf(NaviGuidanceCues.Kind.RIGHT, NaviGuidanceCues.Kind.LEFT, NaviGuidanceCues.Kind.STOP)) cue.kind else proposed ?: cue.kind
            val evidence = linkedMapOf<String, Any?>("source" to "existing", "baselineIndex" to index, "originalKind" to cue.kind.name,
                "tenDirection" to proposed?.name, "d25" to d25, "d50" to d50, "policyId" to POLICY_ID)
            detections += evidence
            Guidance(cue.copy(kind = kind), if (kind == NaviGuidanceCues.Kind.STOP) "stop" else "turn", "existing", evidence)
        }.toMutableList()

        val peaks = groups.flatMap { group -> scan(group).map { Peak(group, it.first, it.second) } }
            .sortedByDescending { abs(it.angle) }
        val added = mutableListOf<Guidance>()
        for (peak in peaks) {
            val pts = peak.group.points
            val (d25, d50) = NaviGuidanceCues.directionGeometry(pts, peak.chainage)
            val before = NaviGuidanceCues.pointAt(pts, peak.chainage - 50)
            val center = NaviGuidanceCues.pointAt(pts, peak.chainage)
            val after = NaviGuidanceCues.pointAt(pts, peak.chainage + 50)
            val chord = minOf(NaviGuidanceCues.distanceBetween(before, center), NaviGuidanceCues.distanceBetween(center, after))
            val geometryGap = pts.zipWithNext().any { (a, b) -> b.chainageM > peak.chainage - 50 && a.chainageM < peak.chainage + 50 && b.chainageM - a.chainageM > 50 }
            val existing = baseWithEvidence.firstOrNull { q -> q.cue.kind != NaviGuidanceCues.Kind.STOP && abs(q.cue.chainageM - peak.chainage) <= DIRECTION_POLICY.legacyDuplicateM && (q.cue.kind in setOf(NaviGuidanceCues.Kind.U_TURN, NaviGuidanceCues.Kind.RETURN) || cueSign(q) == sign(peak.angle)) }
            val conflict = baseWithEvidence.firstOrNull { it.cue.kind != NaviGuidanceCues.Kind.STOP && abs(it.cue.chainageM - peak.chainage) < DIRECTION_POLICY.oppositeDirectionSpacingM }
            val duplicate = added.firstOrNull { q -> q.evidence["segmentId"] == peak.group.id && abs(q.cue.chainageM - peak.chainage) < if (cueSign(q) == sign(peak.angle)) DIRECTION_POLICY.sameDirectionSpacingM else DIRECTION_POLICY.oppositeDirectionSpacingM }
            val decision = when { chord < DIRECTION_POLICY.minimumArmChordM -> "short-arm"; geometryGap -> "geometry-gap"; existing != null -> "existing-direction"; conflict != null -> "conflicting-position"; duplicate != null -> "duplicate-lobe"; else -> "added" }
            val evidence = linkedMapOf<String, Any?>("source" to "expanded-28", "segmentId" to peak.group.id, "chainageM" to peak.chainage, "d25" to d25, "d50" to d50,
                "armChordM" to chord, "tenDirection" to NaviGuidanceCues.directionKind(peak.angle).name, "decision" to decision, "policyId" to POLICY_ID)
            existing?.let { evidence["existingM"] = it.cue.chainageM }
            conflict?.let { evidence["conflictingM"] = it.cue.chainageM }
            duplicate?.let { evidence["duplicateM"] = it.cue.chainageM }
            detections += evidence
            if (decision == "added") {
                val cue = NaviGuidanceCues.Cue(peak.chainage, NaviGuidanceCues.directionKind(peak.angle), NaviGuidanceCues.Variant.V1,
                    0.0, 0.0, null, "", null, null)
                added += Guidance(cue, "turn", "expanded-28", evidence)
            }
        }
        val combined = (baseWithEvidence + added).sortedBy { it.cue.chainageM }
        val formatted = NaviGuidanceCues.applyVariantsToCues(combined.map { it.cue }, points)
        val result = combined.mapIndexed { i, g -> g.copy(cue = formatted[i]) }
        val road = if (roadEvidence == null) RoadResult(result, 0, 0, emptyList()) else applyRoad(result, roadEvidence, points)
        val signaled = if (roadEvidence == null) road.guidance to 0 else withSignalLandmarks(road.guidance, roadEvidence, groups)
        val counts = signaled.first.groupingBy { it.source }.eachCount() + mapOf(
            "diagnostics" to detections.count { it["decision"] != null }, "added" to (added.size + road.added),
            "roadAdded" to road.added, "reanchored" to road.reanchored, "signal" to signaled.second,
            "visits" to (roadEvidence?.visits?.size ?: 0), "proposals" to (roadEvidence?.proposals?.size ?: 0))
        val state = if (roadEvidence == null) STATUS_GPS_ONLY else STATUS_APPLIED
        return Result(signaled.first, POLICY_ID, state, regionId = roadEvidence?.regionId,
            mapSha256 = roadEvidence?.mapSha256, routeSha256 = roadEvidence?.routeSha256,
            summary = counts, diagnostics = detections.filter { it["decision"] != null } + road.decisions,
            summaryDetails = if (roadEvidence == null) emptyMap() else mapOf("indexSha256" to roadEvidence.indexSha256,
                "dataTimestampUTC" to roadEvidence.dataTimestampUtc, "mapSha256" to roadEvidence.mapSha256,
                "roadPolicyId" to "map-connected-guidance-v1"))
    }

    private data class RoadResult(val guidance: List<Guidance>, val added: Int, val reanchored: Int,
        val decisions: List<Map<String, Any?>>)

    private fun applyRoad(base: List<Guidance>, road: RoadEvidence, points: List<NaviGuidanceCues.TrackPoint>): RoadResult {
        val raw = base.toMutableList()
        val decisions = mutableListOf<Map<String, Any?>>()
        var added = 0; var reanchored = 0
        for (n in road.proposals) {
            val item = mapOf("nodeId" to n.nodeId, "chainageM" to n.chainageM, "kind" to n.kind,
                "angleDeg" to n.angleDeg, "reason" to n.decision)
            fun decision(value: String, extra: Map<String, Any?> = emptyMap()) { decisions += item + extra + ("decision" to value) }
            if (n.chainageM < 70) { decision("start-margin"); continue }
            val partner = road.proposals.firstOrNull { it.nodeId == n.crankPartner && it.segmentId == n.segmentId && abs(it.chainageM - n.chainageM) <= 45 }
            val first = if (partner != null && partner.chainageM < n.chainageM) partner else n
            val last = if (first === n) partner else n
            val connected = partner != null && last != null && first.outWay == last.inWay && n.angleDeg * partner.angleDeg < 0
            if (n.decision == "connected-crank" && !connected) { decision("crank-connection-unconfirmed"); continue }
            val sign = n.angleDeg.sign
            val same = raw.filter { it.cue.kind != NaviGuidanceCues.Kind.STOP && cueSign(it) == sign && abs(it.cue.chainageM - n.chainageM) <= 60 }
                .sortedBy { abs(it.cue.chainageM - n.chainageM) }
            val original = same.firstOrNull { it.source == "existing" }
            if (original != null) {
                val at = raw.indexOf(original)
                raw[at] = original.copy(evidence = original.evidence + mapOf(
                    "roadObservation" to n.evidence(), "roadDecision" to "original-retained"))
                decision("original-retained", mapOf("existingM" to original.cue.chainageM)); continue
            }
            val prior = same.firstOrNull { it.evidence["mapNode"] == null }
            val evidence = n.evidence() + mapOf("source" to "map-connected", "mapNode" to n.nodeId,
                "d50" to n.angleDeg, "tenDirection" to n.kind, "crankPartner" to if (connected) n.crankPartner else null,
                "reason" to n.decision, "policyId" to "map-connected-guidance-v1")
            val kind = NaviGuidanceCues.Kind.valueOf(n.kind)
            if (prior != null) {
                val old = prior.cue.chainageM; val index = raw.indexOf(prior)
                raw[index] = prior.copy(cue = prior.cue.copy(chainageM = n.chainageM, kind = kind), source = "map-connected",
                    evidence = evidence + ("previousGPSPeakM" to old))
                reanchored++; decision("expanded-peak-reanchored", mapOf("previousM" to old))
            } else if (same.any { it.evidence["mapNode"] != null }) decision("nearby-map-direction-retained")
            else {
                raw += Guidance(NaviGuidanceCues.Cue(n.chainageM, kind, NaviGuidanceCues.Variant.V1,
                    0.0, 0.0, null, "", null, null), "turn", "map-connected", evidence)
                added++; decision("added")
            }
        }
        val sorted = raw.sortedBy { it.cue.chainageM }
        val formatted = NaviGuidanceCues.applyVariantsToCues(sorted.map { it.cue }, points)
        return RoadResult(sorted.mapIndexed { i, g -> g.copy(cue = formatted[i]) }, added, reanchored, decisions)
    }

    fun withSignalLandmarks(guidance: List<Guidance>, road: RoadEvidence,
        groups: List<TrackGroup>): Pair<List<Guidance>, Int> {
        val qualifying = road.visits.filter { n -> "traffic_signals" in n.controls && n.fit && n.degree >= 3 &&
            n.inWay != n.outWay && !n.continuingRoad && !n.roundabout && abs(n.angleDeg) >= 28 &&
            n.lateralM <= 20 && n.meanLateralM <= 12 && n.p95LateralM <= 22 }
        val choices = guidance.map { q ->
            val segments = groups.filter { g -> g.points.isNotEmpty() && q.cue.chainageM >= g.points.first().chainageM &&
                q.cue.chainageM <= g.points.last().chainageM }.map { it.id }
            if (q.cue.kind !in setOf(NaviGuidanceCues.Kind.RIGHT, NaviGuidanceCues.Kind.LEFT) || segments.size != 1) emptyList()
            else qualifying.filter { n -> n.segmentId == segments.single() && n.angleDeg.sign == cueSign(q) &&
                abs(n.chainageM - q.cue.chainageM) <= SIGNAL_MAXIMUM_CUE_GAP_M &&
                (q.evidence["mapNode"] == null || q.evidence["mapNode"] == n.nodeId) }
        }
        val claims = choices.filter { it.size == 1 }.groupingBy { it.single().let { n -> Triple(n.nodeId, n.segmentId, n.chainageM) } }.eachCount()
        var changed = 0
        val result = guidance.mapIndexed { i, q ->
            val n = choices[i].singleOrNull()
            if (n == null || claims[Triple(n.nodeId, n.segmentId, n.chainageM)] != 1) q else {
                val evidence = mapOf("status" to SIGNAL_EVIDENCE_STATUS, "source" to "offline-connected-road-node",
                    "nodeId" to n.nodeId, "segmentId" to n.segmentId, "mapChainageM" to n.chainageM,
                    "cueChainageGapM" to abs(n.chainageM - q.cue.chainageM), "nodeLateralM" to n.lateralM,
                    "meanLateralM" to n.meanLateralM, "p95LateralM" to n.p95LateralM,
                    "regionId" to road.regionId, "routeSHA256" to road.routeSha256,
                    "nativeMapSHA256" to road.mapSha256, "policyId" to SIGNAL_POLICY_ID)
                changed++; withSignalWording(q, evidence)
            }
        }
        return result to changed
    }

    /** 信号根拠を後段から渡したときだけ予告・まとめ・帯の表現を変える。 */
    fun withSignalWording(cue: NaviGuidanceCues.Cue, evidence: Map<String, Any?>): NaviGuidanceCues.Cue {
        if (cue.kind != NaviGuidanceCues.Kind.RIGHT && cue.kind != NaviGuidanceCues.Kind.LEFT) return cue
        if (evidence["status"] != SIGNAL_EVIDENCE_STATUS) return cue
        fun replace(text: String?): String? = text?.replace(Regex("(\\d+メートル先、)(右折|左折)(?=です。|、)")) { m ->
            if (m.groupValues[2] == cue.kind.phrase()) m.groupValues[1] + "信号を" + m.groupValues[2] else m.value
        }
        val pre = replace(cue.preText)
        val group = replace(cue.groupText)
        val band = when { cue.bandText == null -> "信号を${cue.kind.phrase()}"; cue.bandText.startsWith(cue.kind.phrase()) -> "信号を${cue.bandText}"; else -> cue.bandText }
        return cue.copy(preText = pre, groupText = group, bandText = band)
    }

    fun withSignalWording(guidance: Guidance, evidence: Map<String, Any?>): Guidance {
        val cue = guidance.cue
        if (cue.kind != NaviGuidanceCues.Kind.RIGHT && cue.kind != NaviGuidanceCues.Kind.LEFT) return guidance
        if (evidence["status"] != SIGNAL_EVIDENCE_STATUS) return guidance
        val original = linkedMapOf("preText" to cue.preText, "groupText" to cue.groupText, "bandText" to cue.bandText)
        val rewritten = withSignalWording(cue, evidence)
        return guidance.copy(cue = rewritten, source = "signal", evidence = guidance.evidence + mapOf(
            "signalLandmark" to evidence, "originalPresentation" to original,
        ))
    }

    private fun scan(group: TrackGroup): List<Pair<Double, Double>> {
        val points = group.points
        if (points.size < 3) return emptyList()
        val peaks = mutableListOf<Pair<Double, Double>>()
        var best: Pair<Double, Double>? = null
        fun flush() { best?.let(peaks::add); best = null }
        var c = maxOf(DIRECTION_POLICY.minimumChainageM, points.first().chainageM + DIRECTION_POLICY.headingArmM)
        while (c <= points.last().chainageM - DIRECTION_POLICY.headingArmM) {
            val angle = NaviGuidanceCues.directionGeometry(points, c).second
            val qualified = angle != null && abs(angle) >= DIRECTION_POLICY.thresholdDeg
            if (!qualified) flush() else {
                if (best != null && sign(best!!.second) != sign(angle!!)) flush()
                if (best == null || abs(angle!!) > abs(best!!.second)) best = c to angle
            }
            c += DIRECTION_POLICY.sampleStepM
        }
        flush()
        return peaks
    }

    private fun cueSign(g: Guidance): Double = when (g.cue.kind) {
        NaviGuidanceCues.Kind.RIGHT -> 1.0
        NaviGuidanceCues.Kind.LEFT -> -1.0
        else -> ((g.evidence["d50"] as? Number)?.toDouble() ?: (g.evidence["d25"] as? Number)?.toDouble() ?: 0.0).sign
    }
}
