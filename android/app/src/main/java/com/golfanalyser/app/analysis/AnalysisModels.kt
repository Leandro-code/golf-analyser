package com.golfanalyser.app.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VideoMetadata(
    val sourcePath: String,
    val fps: Double,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
)

@Serializable
data class LandmarkPoint(
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double? = null,
    val visibility: Double? = null,
    val pixelX: Double? = null,
    val pixelY: Double? = null,
)

@Serializable
data class LandmarkFrame(
    val frameIndex: Int,
    val timestampSeconds: Double,
    val poseDetected: Boolean,
    val landmarks: List<LandmarkPoint> = emptyList(),
)

@Serializable
data class SwingPhase(
    val name: String,
    val frameIndex: Int,
    val timestampSeconds: Double,
    val confidence: Double = 0.0,
    val detectionMethod: String,
)

@Serializable
data class MetricValue(
    val name: String,
    val value: JsonElement? = null,
    val unit: String? = null,
    val description: String,
    val frameIndex: Int? = null,
)

@Serializable
data class MetricSet(
    val metrics: Map<String, MetricValue> = emptyMap(),
    val quality: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class AnalysisContext(
    val handedness: String,
    val cameraView: String,
    val clubFamily: String,
    val swingType: String = "full_swing",
)
