package com.golfanalyser.app.analysis

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object SwingPhases {
    const val ADDRESS_PHASE = "Address"
    const val TAKEAWAY_PHASE = "Takeaway"
    const val P3_PHASE = "Lead arm parallel backswing (P3)"
    const val TOP_PHASE = "Top (P4)"
    const val P5_PHASE = "Lead arm parallel downswing (P5)"
    const val P6_PHASE = "Shaft parallel downswing (P6)"
    const val IMPACT_PHASE = "Impact approximation (P7)"
    const val P8_PHASE = "Shaft parallel follow-through (P8)"
    const val FINISH_PHASE = "Finish"

    val phaseNames = listOf(
        ADDRESS_PHASE,
        TAKEAWAY_PHASE,
        P3_PHASE,
        TOP_PHASE,
        P5_PHASE,
        P6_PHASE,
        IMPACT_PHASE,
        P8_PHASE,
        FINISH_PHASE,
    )

    private val legacyAliases = mapOf(
        "Top of backswing" to TOP_PHASE,
        "Downswing" to P6_PHASE,
        "Impact approximation" to IMPACT_PHASE,
        "Follow-through" to P8_PHASE,
    )

    fun canonicalPhaseName(name: String): String = legacyAliases[name] ?: name

    fun byName(phases: List<SwingPhase>): Map<String, SwingPhase> {
        val result = linkedMapOf<String, SwingPhase>()
        phases.forEach { phase -> result.putIfAbsent(canonicalPhaseName(phase.name), phase) }
        return result
    }

    fun detect(landmarkFrames: List<LandmarkFrame>, fps: Double): List<SwingPhase> {
        val points = smoothPoints(interpolateShortGaps(wristPoints(landmarkFrames)))
        if (points.isEmpty()) return fallbackPhases(landmarkFrames, fps, "no_pose_landmarks")

        val addressIndex = activeAddressIndex(points)
        val active = points.filter { it.frameIndex >= addressIndex }
        val address = active.first()
        val topResult = firstBackswingReversal(active, address)
            ?: return fallbackPhases(landmarkFrames, fps, "no_ordered_backswing_reversal")
        val impact = firstImpactReturn(active, address, topResult.point)
            ?: return fallbackPhases(landmarkFrames, fps, "no_ordered_impact_return")
        val finishIndex = firstFinishPosition(active, impact.frameIndex, address, topResult.point)
        val p6Index = firstDownswingDeliveryIndex(active, topResult.point, impact, address)

        val indices = listOf(
            addressIndex,
            nearestDetectedIndex(active, between(addressIndex, topResult.point.frameIndex, 0.25)),
            nearestDetectedIndex(active, between(addressIndex, topResult.point.frameIndex, 0.65)),
            topResult.point.frameIndex,
            nearestDetectedIndex(active, between(topResult.point.frameIndex, impact.frameIndex, 0.32)),
            p6Index,
            impact.frameIndex,
            nearestDetectedIndex(active, between(impact.frameIndex, finishIndex, 0.45)),
            finishIndex,
        )
        val methods = listOf(
            "detected_stable_address_before_motion",
            "pose_proxy_ordered_address_to_top",
            "pose_proxy_ordered_address_to_top",
            topResult.method,
            "pose_proxy_ordered_top_to_impact",
            "pose_proxy_hand_return_to_delivery",
            "first_post_top_strike_region_transition",
            "pose_proxy_ordered_impact_to_finish",
            "first_post_impact_high_hands_position",
        )
        val confidences = listOf(0.72, 0.62, 0.64, topResult.confidence, 0.64, 0.66, 0.72, 0.64, 0.68)
        return phaseNames.indices.map { index ->
            SwingPhase(
                name = phaseNames[index],
                frameIndex = indices[index],
                timestampSeconds = timestamp(indices[index], fps),
                confidence = confidences[index],
                detectionMethod = methods[index],
            )
        }
    }

    fun buildConfirmed(addressIndex: Int, markerIndices: List<Int>, fps: Double): List<SwingPhase> {
        val confirmedNames: Set<String>
        val indices = when (markerIndices.size) {
            3 -> {
                val topIndex = markerIndices[0]
                val impactIndex = markerIndices[1]
                val finishIndex = markerIndices[2]
                confirmedNames = setOf(ADDRESS_PHASE, TOP_PHASE, IMPACT_PHASE, FINISH_PHASE)
                listOf(
                    addressIndex,
                    between(addressIndex, topIndex, 0.25),
                    between(addressIndex, topIndex, 0.65),
                    topIndex,
                    between(topIndex, impactIndex, 0.32),
                    between(topIndex, impactIndex, 0.62),
                    impactIndex,
                    between(impactIndex, finishIndex, 0.45),
                    finishIndex,
                )
            }
            phaseNames.size - 1 -> {
                confirmedNames = phaseNames.toSet()
                listOf(addressIndex) + markerIndices
            }
            else -> error("Expected either 4 legacy markers or all 9 phase markers.")
        }
        require(strictlyOrdered(indices)) {
            "Phase markers must be ordered: " + phaseNames.joinToString(" < ") { shortPhaseName(it) } + "."
        }
        return phaseNames.zip(indices).map { (name, index) ->
            SwingPhase(
                name = name,
                frameIndex = index,
                timestampSeconds = timestamp(index, fps),
                confidence = 1.0,
                detectionMethod = if (name in confirmedNames) {
                    "user_confirmed_marker"
                } else {
                    "interpolated_from_user_confirmed_markers"
                },
            )
        }
    }

    fun qualityIssues(phases: List<SwingPhase>, fps: Double): List<String> {
        val byName = byName(phases)
        val required = phaseNames.map { byName[it] }
        if (required.any { it == null }) return listOf("Required swing phase markers are missing.")
        if (!strictlyOrdered(required.filterNotNull().map { it.frameIndex })) {
            return listOf("Swing phases are not in chronological order.")
        }
        if (phases.any { it.detectionMethod.startsWith("no_") }) {
            return listOf("Automatic detection could not establish an ordered swing sequence.")
        }
        val manuallyConfirmed = phases.any { it.detectionMethod == "user_confirmed_marker" }
        val address = byName.getValue(ADDRESS_PHASE)
        val top = byName.getValue(TOP_PHASE)
        val impact = byName.getValue(IMPACT_PHASE)
        val backswing = (top.frameIndex - address.frameIndex) / max(fps, 1e-9)
        val downswing = (impact.frameIndex - top.frameIndex) / max(fps, 1e-9)
        if (!manuallyConfirmed && downswing > backswing) {
            return listOf(
                "Automatic timing appears implausible because the detected downswing is longer than the backswing. Confirm the phase markers.",
            )
        }
        return emptyList()
    }

    private data class WristPoint(val frameIndex: Int, val x: Double, val y: Double)
    private data class TopResult(val point: WristPoint, val method: String, val confidence: Double)

    private fun wristPoints(frames: List<LandmarkFrame>): List<WristPoint> = frames.mapNotNull { frame ->
        val wrists = listOfNotNull(landmark(frame, "left_wrist"), landmark(frame, "right_wrist"))
            .filter { it.visibility == null || it.visibility >= 0.2 }
        if (wrists.isEmpty()) {
            null
        } else {
            WristPoint(frame.frameIndex, wrists.sumOf { it.x } / wrists.size, wrists.sumOf { it.y } / wrists.size)
        }
    }

    private fun interpolateShortGaps(points: List<WristPoint>, maxGap: Int = 3): List<WristPoint> {
        if (points.size < 2) return points
        val completed = mutableListOf<WristPoint>()
        points.zipWithNext().forEach { (first, second) ->
            completed += first
            val gap = second.frameIndex - first.frameIndex - 1
            if (gap in 1..maxGap) {
                for (offset in 1..gap) {
                    val ratio = offset.toDouble() / (gap + 1)
                    completed += WristPoint(
                        frameIndex = first.frameIndex + offset,
                        x = first.x + (second.x - first.x) * ratio,
                        y = first.y + (second.y - first.y) * ratio,
                    )
                }
            }
        }
        completed += points.last()
        return completed
    }

    private fun smoothPoints(points: List<WristPoint>, window: Int = 5): List<WristPoint> {
        if (points.size < 10) return points
        val radius = window / 2
        return points.mapIndexed { index, point ->
            val slice = points.subList(max(0, index - radius), min(points.size, index + radius + 1))
            WristPoint(
                frameIndex = point.frameIndex,
                x = slice.sumOf { it.x } / slice.size,
                y = slice.sumOf { it.y } / slice.size,
            )
        }
    }

    private fun activeAddressIndex(points: List<WristPoint>): Int {
        if (points.size < 10) return points.first().frameIndex
        val velocities = points.zipWithNext().map { (previous, current) ->
            hypot(current.x - previous.x, current.y - previous.y)
        }
        val idle = velocities.take(min(15, velocities.size)).sorted()
        val baseline = idle.getOrElse(idle.size / 2) { 0.0 }
        val threshold = max(0.003, baseline * 4)
        for (index in 1 until velocities.size - 4) {
            val window = velocities.subList(index, index + 4)
            if (window.count { it > threshold } >= 3 && window.sum() > threshold * 5) {
                return points[min(points.lastIndex, index + 1)].frameIndex
            }
        }
        return points.first().frameIndex
    }

    private fun firstBackswingReversal(points: List<WristPoint>, address: WristPoint): TopResult? {
        if (points.size < 5) {
            val candidate = points.minByOrNull { it.y } ?: return null
            return TopResult(candidate, "minimum_wrist_y_from_short_sequence", 0.62)
        }
        for (index in 2 until points.size - 2) {
            val candidate = points[index]
            if (address.y - candidate.y < 0.04) continue
            val before = ((index - 1)..index).sumOf { points[it].y - points[it - 1].y }
            val after = (index..index + 1).sumOf { points[it + 1].y - points[it].y }
            if (before < 0 && after > 0.006) {
                return TopResult(candidate, "first_qualified_wrist_direction_reversal", 0.78)
            }
        }
        return firstSustainedBackswingReversal(points, address)
    }

    private fun firstSustainedBackswingReversal(
        points: List<WristPoint>,
        address: WristPoint,
        lookahead: Int = 6,
    ): TopResult? {
        if (points.size <= lookahead + 2) return null
        for (index in 2 until points.size - lookahead) {
            val candidate = points[index]
            if (address.y - candidate.y < 0.04) continue
            val before = (max(1, index - 4)..index).sumOf { points[it].y - points[it - 1].y }
            val futureHighestY = points.subList(index + 1, index + lookahead + 1).maxOf { it.y }
            if (before < -0.01 && futureHighestY - candidate.y >= 0.003) {
                return TopResult(candidate, "first_sustained_wrist_reversal", 0.72)
            }
        }
        return null
    }

    private fun firstImpactReturn(points: List<WristPoint>, address: WristPoint, top: WristPoint): WristPoint? {
        val excursion = max(address.y - top.y, 0.04)
        val afterTop = points.filter { it.frameIndex > top.frameIndex }
        for (index in 2 until afterTop.size - 2) {
            val candidate = afterTop[index]
            if (candidate.y - top.y < excursion * 0.35) continue
            val before = ((index - 1)..index).sumOf { afterTop[it].y - afterTop[it - 1].y }
            val after = (index..index + 1).sumOf { afterTop[it + 1].y - afterTop[it].y }
            if (before > 0 && after < -0.006) return candidate
        }
        val returnThreshold = address.y - excursion * 0.32
        return afterTop.firstOrNull { it.y >= returnThreshold }
    }

    private fun firstFinishPosition(
        points: List<WristPoint>,
        impactIndex: Int,
        address: WristPoint,
        top: WristPoint,
    ): Int {
        val afterImpact = points.filter { it.frameIndex > impactIndex }
        if (afterImpact.isEmpty()) return impactIndex + 1
        val highThreshold = address.y - max(address.y - top.y, 0.04) * 0.55
        val minCompletionIndex = impactIndex + max(1, impactIndex - top.frameIndex)
        for (index in 1 until afterImpact.size - 1) {
            val candidate = afterImpact[index]
            if (
                candidate.frameIndex >= minCompletionIndex &&
                candidate.y <= highThreshold &&
                candidate.y <= afterImpact[index - 1].y &&
                candidate.y <= afterImpact[index + 1].y + 0.006
            ) {
                return candidate.frameIndex
            }
        }
        val lateHigh = afterImpact.filter { it.frameIndex >= minCompletionIndex && it.y <= highThreshold }
        return lateHigh.lastOrNull()?.frameIndex ?: afterImpact.last().frameIndex
    }

    private fun firstDownswingDeliveryIndex(
        points: List<WristPoint>,
        top: WristPoint,
        impact: WristPoint,
        address: WristPoint,
    ): Int {
        val excursion = max(address.y - top.y, 0.04)
        val targetY = top.y + excursion * 0.45
        val p5Floor = top.frameIndex + max(1, ((impact.frameIndex - top.frameIndex) * 0.32).roundToInt())
        return points.firstOrNull {
            it.frameIndex > p5Floor && it.frameIndex < impact.frameIndex && it.y >= targetY
        }?.frameIndex ?: nearestDetectedIndex(points, between(top.frameIndex, impact.frameIndex, 0.82))
    }

    private fun landmark(frame: LandmarkFrame, name: String): LandmarkPoint? =
        frame.landmarks.firstOrNull { it.name == name }

    private fun between(start: Int, end: Int, fraction: Double): Int =
        (start + (end - start) * fraction).roundToInt()

    private fun strictlyOrdered(indices: List<Int>): Boolean =
        indices.zipWithNext().all { (first, second) -> first < second }

    private fun shortPhaseName(name: String): String = when {
        name == IMPACT_PHASE -> "Impact"
        name.startsWith("Lead arm parallel backswing") -> "P3"
        name.startsWith("Lead arm parallel downswing") -> "P5"
        name.startsWith("Shaft parallel downswing") -> "P6"
        name.startsWith("Shaft parallel follow-through") -> "P8"
        else -> name.replace(" (P4)", "")
    }

    private fun nearestDetectedIndex(points: List<WristPoint>, target: Int): Int =
        points.minBy { abs(it.frameIndex - target) }.frameIndex

    private fun timestamp(frameIndex: Int, fps: Double): Double =
        if (fps > 0) kotlin.math.round((frameIndex.toDouble() / fps) * 10_000.0) / 10_000.0 else 0.0

    private fun fallbackPhases(landmarkFrames: List<LandmarkFrame>, fps: Double, method: String): List<SwingPhase> {
        val maxIndex = max(0, landmarkFrames.size - 1)
        val indices = if (landmarkFrames.size <= 1) {
            List(phaseNames.size) { 0 }
        } else {
            listOf(0.0, 0.12, 0.26, 0.4, 0.5, 0.6, 0.72, 0.84, 1.0)
                .map { (maxIndex * it).roundToInt() }
        }
        return phaseNames.zip(indices).map { (name, index) ->
            SwingPhase(
                name = name,
                frameIndex = index,
                timestampSeconds = timestamp(index, fps),
                confidence = 0.1,
                detectionMethod = method,
            )
        }
    }
}
