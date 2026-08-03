package com.golfanalyser.app.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import kotlin.math.max
import kotlin.math.roundToLong

class MediaPipePoseExtractor(private val context: Context) {
    fun extract(
        videoFile: File,
        metadata: VideoMetadata,
        onProgress: (processedFrames: Int, totalFrames: Int) -> Unit = { _, _ -> },
    ): List<LandmarkFrame> {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()

        PoseLandmarker.createFromOptions(context, options).use { landmarker ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val totalFrames = metadata.frameCount.coerceAtLeast(1)
                val progressInterval = max(1, totalFrames / 100)
                return (0 until totalFrames).map { index ->
                    val timestampSeconds = index / metadata.fps.coerceAtLeast(1e-9)
                    val bitmap = retriever.getFrameAtTime(
                        (timestampSeconds * 1_000_000).roundToLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    )
                    val frame = if (bitmap == null) {
                        LandmarkFrame(index, timestampSeconds, poseDetected = false)
                    } else {
                        detectFrame(landmarker, bitmap, index, timestampSeconds)
                    }
                    val processedFrames = index + 1
                    if (processedFrames == 1 || processedFrames == totalFrames || processedFrames % progressInterval == 0) {
                        onProgress(processedFrames, totalFrames)
                    }
                    frame
                }
            } finally {
                retriever.release()
            }
        }
    }

    private fun detectFrame(
        landmarker: PoseLandmarker,
        bitmap: Bitmap,
        frameIndex: Int,
        timestampSeconds: Double,
    ): LandmarkFrame {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val result = landmarker.detectForVideo(
            BitmapImageBuilder(argb).build(),
            (timestampSeconds * 1_000).roundToLong(),
        )
        val landmarks = result.landmarks().firstOrNull().orEmpty()
        return LandmarkFrame(
            frameIndex = frameIndex,
            timestampSeconds = timestampSeconds,
            poseDetected = landmarks.isNotEmpty(),
            landmarks = landmarks.mapIndexed { index, landmark ->
                LandmarkPoint(
                    name = LANDMARK_NAMES.getOrElse(index) { "landmark_$index" },
                    x = landmark.x().toDouble(),
                    y = landmark.y().toDouble(),
                    z = landmark.z().toDouble(),
                    visibility = landmark.visibility().orElse(0f).toDouble(),
                    pixelX = landmark.x().toDouble() * argb.width,
                    pixelY = landmark.y().toDouble() * argb.height,
                )
            },
        )
    }

    companion object {
        private const val MODEL_ASSET = "pose_landmarker_lite.task"

        private val LANDMARK_NAMES = listOf(
            "nose",
            "left_eye_inner",
            "left_eye",
            "left_eye_outer",
            "right_eye_inner",
            "right_eye",
            "right_eye_outer",
            "left_ear",
            "right_ear",
            "mouth_left",
            "mouth_right",
            "left_shoulder",
            "right_shoulder",
            "left_elbow",
            "right_elbow",
            "left_wrist",
            "right_wrist",
            "left_pinky",
            "right_pinky",
            "left_index",
            "right_index",
            "left_thumb",
            "right_thumb",
            "left_hip",
            "right_hip",
            "left_knee",
            "right_knee",
            "left_ankle",
            "right_ankle",
            "left_heel",
            "right_heel",
            "left_foot_index",
            "right_foot_index",
        )
    }
}
