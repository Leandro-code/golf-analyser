package com.golfanalyser.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun parsesAnalysisResultWithAiAssessment() {
        val payload = """
            {
              "run_id": "swing_test",
              "status": "completed",
              "context": {
                "handedness": "right",
                "camera_view": "face_on",
                "club_family": "iron",
                "swing_type": "full_swing"
              },
              "phases": [
                {
                  "name": "Address",
                  "frame_index": 0,
                  "timestamp_seconds": 0.0,
                  "confidence": 0.9,
                  "detection_method": "automatic"
                }
              ],
              "metrics_summary": {
                "tempo_ratio": {
                  "name": "Tempo ratio",
                  "value": 2.0,
                  "unit": "backswing:downswing"
                }
              },
              "quality_flags": {
                "phase_scoped_metrics": true
              },
              "assessment": {
                "findings": []
              },
              "llm_assessment": {
                "schema_version": "1.0.0",
                "prompt_version": "1.0.0",
                "model": "test-model",
                "generated_at": "2026-06-21T00:00:00Z",
                "submitted_frames": [],
                "content": {
                  "overview": "Grounded overview",
                  "strengths": [],
                  "observations": [],
                  "priorities": [],
                  "limitations": []
                }
              },
              "llm_assessment_current": true,
              "llm_assessment_stale": false,
              "artifact_urls": {
                "annotated_video": "/analyses/swing_test/artifacts/annotated_video"
              }
            }
        """.trimIndent()

        val result = json.decodeFromString<AnalysisResultResponse>(payload)

        assertEquals("swing_test", result.runId)
        assertEquals("face_on", result.context?.cameraView)
        assertEquals("Address", result.phases.single().name)
        assertEquals("Grounded overview", result.llmAssessment?.content?.overview)
        assertTrue(result.llmAssessmentCurrent)
    }
}
