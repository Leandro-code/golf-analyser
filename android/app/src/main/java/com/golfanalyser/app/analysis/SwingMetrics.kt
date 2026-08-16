package com.golfanalyser.app.analysis

import com.golfanalyser.app.analysis.SwingPhases.ADDRESS_PHASE
import com.golfanalyser.app.analysis.SwingPhases.FINISH_PHASE
import com.golfanalyser.app.analysis.SwingPhases.IMPACT_PHASE
import com.golfanalyser.app.analysis.SwingPhases.P6_PHASE
import com.golfanalyser.app.analysis.SwingPhases.P8_PHASE
import com.golfanalyser.app.analysis.SwingPhases.TOP_PHASE
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SwingMetrics {
    fun calculate(
        landmarkFrames: List<LandmarkFrame>,
        phases: List<SwingPhase>,
        context: AnalysisContext? = null,
    ): MetricSet {
        val framesWithPose = landmarkFrames.filter { it.poseDetected }
        val addressFrame = phaseFrame(framesWithPose, phases, ADDRESS_PHASE)
        val bodyScale = shoulderWidth(addressFrame)
        val fps = inferFps(landmarkFrames)
        val quality = linkedMapOf<String, JsonElement>(
            "frames_total" to landmarkFrames.size.json(),
            "frames_with_pose" to framesWithPose.size.json(),
            "pose_detection_rate" to (if (landmarkFrames.isEmpty()) 0.0 else round4(framesWithPose.size.toDouble() / landmarkFrames.size)).json(),
            "body_scale_shoulder_width" to bodyScale?.let { round5(it).json() }.orJsonNull(),
            "phase_quality_issues" to SwingPhases.qualityIssues(phases, fps).json(),
            "phase_markers_confirmed" to phases.any { it.detectionMethod == "user_confirmed_marker" }.json(),
            "phase_scoped_metrics" to true.json(),
        )
        return MetricSet(
            metrics = linkedMapOf(
                "tempo_ratio" to tempoRatio(phases),
                "head_movement" to headMovement(framesWithPose, phases, addressFrame, bodyScale),
                "spine_angle_at_address" to spineAngleAtAddress(addressFrame),
                "lead_arm_angle" to leadArmAngle(framesWithPose, phases, context),
                "hip_sway" to hipSway(framesWithPose, phases, addressFrame, bodyScale),
                "knee_flex" to kneeFlex(addressFrame),
                "posture_retention" to postureRetention(framesWithPose, phases),
                "finish_stability" to finishStability(framesWithPose, phases, bodyScale),
                "wrist_path_trajectory" to wristPath(framesWithPose),
            ),
            quality = quality,
        )
    }

    private fun tempoRatio(phases: List<SwingPhase>): MetricValue {
        val byName = SwingPhases.byName(phases)
        val address = byName[ADDRESS_PHASE]
        val top = byName[TOP_PHASE]
        val impact = byName[IMPACT_PHASE]
        val value = if (address != null && top != null && impact != null) {
            val backswing = top.timestampSeconds - address.timestampSeconds
            val downswing = impact.timestampSeconds - top.timestampSeconds
            if (backswing > 0 && downswing > 0) round3(backswing / downswing) else null
        } else {
            null
        }
        return MetricValue(
            name = "Tempo ratio",
            value = value.nullableJson(),
            unit = "backswing:downswing",
            description = "Backswing duration divided by downswing duration.",
            frameIndex = top?.frameIndex,
        )
    }

    private fun headMovement(
        frames: List<LandmarkFrame>,
        phases: List<SwingPhase>,
        addressFrame: LandmarkFrame?,
        bodyScale: Double?,
    ): MetricValue {
        val impactFrame = phaseFrame(frames, phases, IMPACT_PHASE)
        val origin = addressFrame?.point("nose")
        val impact = impactFrame?.point("nose")
        val value = if (origin != null && impact != null && bodyScale != null) {
            round4(abs(impact.x - origin.x) / bodyScale)
        } else {
            null
        }
        return MetricValue(
            name = "Head movement at impact",
            value = value.nullableJson(),
            unit = "shoulder widths",
            description = "Lateral nose movement from address to impact approximation, scaled by shoulder width.",
            frameIndex = if (value != null) impactFrame?.frameIndex else null,
        )
    }

    private fun spineAngleAtAddress(frame: LandmarkFrame?): MetricValue {
        val value = spineAngle(frame)
        return MetricValue(
            name = "Spine angle at address",
            value = value.nullableJson(),
            unit = "degrees from vertical",
            description = "Angle between hip-to-shoulder centre line and vertical at address.",
            frameIndex = if (value != null) frame?.frameIndex else null,
        )
    }

    private fun leadArmAngle(
        frames: List<LandmarkFrame>,
        phases: List<SwingPhase>,
        context: AnalysisContext?,
    ): MetricValue {
        val side = if (context?.handedness == "left") "right" else "left"
        val topFrame = phaseFrame(frames, phases, TOP_PHASE)
        val topValue = topFrame?.jointAngle("${side}_shoulder", "${side}_elbow", "${side}_wrist")
        if (topValue != null) {
            return MetricValue(
                name = "Lead arm angle",
                value = round2(topValue).json(),
                unit = "degrees",
                description = "${side.replaceFirstChar { it.titlecase() }} lead shoulder-elbow-wrist angle at the top of the backswing.",
                frameIndex = topFrame.frameIndex,
            )
        }
        val candidates = frames.mapNotNull { frame ->
            frame.jointAngle("${side}_shoulder", "${side}_elbow", "${side}_wrist")?.let { frame.frameIndex to it }
        }
        val best = candidates.maxByOrNull { it.second }
        return MetricValue(
            name = "Lead arm angle",
            value = best?.second?.let { round2(it).json() },
            unit = "degrees",
            description = "Maximum $side lead shoulder-elbow-wrist angle detected.",
            frameIndex = best?.first,
        )
    }

    private fun hipSway(
        frames: List<LandmarkFrame>,
        phases: List<SwingPhase>,
        addressFrame: LandmarkFrame?,
        bodyScale: Double?,
    ): MetricValue {
        val topFrame = phaseFrame(frames, phases, TOP_PHASE)
        val origin = addressFrame?.midpoint("left_hip", "right_hip")
        val top = topFrame?.midpoint("left_hip", "right_hip")
        val value = if (origin != null && top != null && bodyScale != null) {
            round4(abs(top.x - origin.x) / bodyScale)
        } else {
            null
        }
        return MetricValue(
            name = "Hip sway at top",
            value = value.nullableJson(),
            unit = "shoulder widths",
            description = "Horizontal hip-centre movement from address to top of backswing, scaled by shoulder width.",
            frameIndex = if (value != null) topFrame?.frameIndex else null,
        )
    }

    private fun kneeFlex(frame: LandmarkFrame?): MetricValue {
        val angles = listOfNotNull(
            frame?.jointAngle("left_hip", "left_knee", "left_ankle"),
            frame?.jointAngle("right_hip", "right_knee", "right_ankle"),
        )
        val value = if (angles.isNotEmpty()) round2(angles.average()) else null
        return MetricValue(
            name = "Knee flex",
            value = value.nullableJson(),
            unit = "degrees",
            description = "Average knee angle at the first detected address frame.",
            frameIndex = if (value != null) frame?.frameIndex else null,
        )
    }

    private fun postureRetention(frames: List<LandmarkFrame>, phases: List<SwingPhase>): MetricValue {
        val address = phaseFrame(frames, phases, ADDRESS_PHASE)
        val downswing = phaseFrame(frames, phases, P6_PHASE)
        val addressAngle = spineAngle(address)
        val downswingAngle = spineAngle(downswing)
        val value = if (addressAngle != null && downswingAngle != null) round2(abs(downswingAngle - addressAngle)) else null
        return MetricValue(
            name = "Posture retention",
            value = value.nullableJson(),
            unit = "degrees change",
            description = "Change in spine inclination from address to shaft-parallel downswing proxy.",
            frameIndex = if (value != null) downswing?.frameIndex else null,
        )
    }

    private fun finishStability(
        frames: List<LandmarkFrame>,
        phases: List<SwingPhase>,
        bodyScale: Double?,
    ): MetricValue {
        val follow = phaseFrame(frames, phases, P8_PHASE)
        val finish = phaseFrame(frames, phases, FINISH_PHASE)
        val followHead = follow?.point("nose")
        val finishHead = finish?.point("nose")
        val value = if (followHead != null && finishHead != null && bodyScale != null) {
            round4(hypot(finishHead.x - followHead.x, finishHead.y - followHead.y) / bodyScale)
        } else {
            null
        }
        return MetricValue(
            name = "Finish stability",
            value = value.nullableJson(),
            unit = "shoulder widths",
            description = "Head displacement from follow-through to finish as a balance proxy.",
            frameIndex = if (value != null) finish?.frameIndex else null,
        )
    }

    private fun wristPath(frames: List<LandmarkFrame>): MetricValue {
        val trajectory = frames.mapNotNull { frame ->
            val wrists = listOfNotNull(frame.point("left_wrist"), frame.point("right_wrist"))
            if (wrists.isEmpty()) {
                null
            } else {
                buildJsonObject {
                    put("frame_index", frame.frameIndex)
                    put("timestamp_seconds", frame.timestampSeconds)
                    put("x", round5(wrists.sumOf { it.x } / wrists.size))
                    put("y", round5(wrists.sumOf { it.y } / wrists.size))
                }
            }
        }
        return MetricValue(
            name = "Wrist path trajectory",
            value = buildJsonArray { trajectory.forEach { add(it) } },
            unit = "normalised image coordinates",
            description = "Per-frame midpoint trajectory of detected wrists.",
        )
    }

    private fun phaseFrame(frames: List<LandmarkFrame>, phases: List<SwingPhase>, name: String): LandmarkFrame? {
        val phase = SwingPhases.byName(phases)[name] ?: return null
        return frames.minByOrNull { abs(it.frameIndex - phase.frameIndex) }
    }

    private fun shoulderWidth(frame: LandmarkFrame?): Double? {
        val left = frame?.point("left_shoulder")
        val right = frame?.point("right_shoulder")
        if (left == null || right == null) return null
        val width = hypot(left.x - right.x, left.y - right.y)
        return if (width > 1e-9) width else null
    }

    private fun spineAngle(frame: LandmarkFrame?): Double? {
        val shoulders = frame?.midpoint("left_shoulder", "right_shoulder")
        val hips = frame?.midpoint("left_hip", "right_hip")
        if (shoulders == null || hips == null) return null
        val dx = shoulders.x - hips.x
        val dy = shoulders.y - hips.y
        return round2(Math.toDegrees(acos(clamp(abs(dy) / max(hypot(dx, dy), 1e-9)))))
    }

    private fun LandmarkFrame.midpoint(first: String, second: String): LandmarkPoint? {
        val a = point(first)
        val b = point(second)
        if (a == null || b == null) return null
        return LandmarkPoint(
            name = "${first}_${second}_midpoint",
            x = (a.x + b.x) / 2,
            y = (a.y + b.y) / 2,
            z = if (a.z == null || b.z == null) null else (a.z + b.z) / 2,
        )
    }

    private fun LandmarkFrame.jointAngle(aName: String, bName: String, cName: String): Double? {
        val a = point(aName)
        val b = point(bName)
        val c = point(cName)
        if (a == null || b == null || c == null) return null
        val baX = a.x - b.x
        val baY = a.y - b.y
        val bcX = c.x - b.x
        val bcY = c.y - b.y
        val baLen = hypot(baX, baY)
        val bcLen = hypot(bcX, bcY)
        if (baLen == 0.0 || bcLen == 0.0) return null
        return Math.toDegrees(acos(clamp((baX * bcX + baY * bcY) / (baLen * bcLen))))
    }

    private fun LandmarkFrame.point(name: String): LandmarkPoint? =
        landmarks.firstOrNull { it.name == name && (it.visibility == null || it.visibility >= 0.2) }

    private fun inferFps(frames: List<LandmarkFrame>): Double {
        if (frames.size >= 2) {
            val delta = frames[1].timestampSeconds - frames[0].timestampSeconds
            if (delta > 0) return 1.0 / delta
        }
        return 30.0
    }

    private fun clamp(value: Double): Double = max(-1.0, minOf(1.0, value))
    private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
    private fun round3(value: Double): Double = kotlin.math.round(value * 1_000.0) / 1_000.0
    private fun round4(value: Double): Double = kotlin.math.round(value * 10_000.0) / 10_000.0
    private fun round5(value: Double): Double = kotlin.math.round(value * 100_000.0) / 100_000.0

    private fun Int.json(): JsonElement = JsonPrimitive(this)
    private fun Double.json(): JsonElement = JsonPrimitive(this)
    private fun Boolean.json(): JsonElement = JsonPrimitive(this)
    private fun String.json(): JsonElement = JsonPrimitive(this)
    private fun List<String>.json(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
    private fun Double?.nullableJson(): JsonElement? = this?.json()
    private fun JsonElement?.orJsonNull(): JsonElement = this ?: JsonNull
}
