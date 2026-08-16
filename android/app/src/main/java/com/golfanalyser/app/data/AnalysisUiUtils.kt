package com.golfanalyser.app.data

import kotlin.math.roundToInt

fun buildPhaseConfirmationPayload(frameMap: Map<String, String>, phaseOrder: List<String>): List<Int> =
    phaseOrder.map { phase ->
        frameMap[phase]?.toIntOrNull()
            ?: error("Missing frame for $phase")
    }

fun confidenceLabel(confidence: Double): String = "${(confidence * 100).roundToInt()}%"
