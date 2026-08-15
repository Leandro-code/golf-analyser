package com.golfanalyser.app.data

const val MAX_COACHING_GOALS = 3
const val MAX_COACHING_NOTE_LENGTH = 240

data class CoachingFocusOption(
    val key: String,
    val label: String,
)

val COACHING_FOCUS_OPTIONS = listOf(
    CoachingFocusOption("reduce_fade_or_slice", "Reduce a fade or slice"),
    CoachingFocusOption("reduce_draw_or_hook", "Reduce a draw or hook"),
    CoachingFocusOption("improve_consistency", "Improve consistency"),
    CoachingFocusOption("improve_tempo_and_transition", "Improve tempo and transition"),
    CoachingFocusOption("improve_posture_and_balance", "Improve posture and balance"),
    CoachingFocusOption("improve_rotation_and_turn", "Improve rotation and turn"),
)

private val knownFocusKeys = COACHING_FOCUS_OPTIONS.mapTo(linkedSetOf()) { it.key }
private val shotShapeFocusKeys = setOf("reduce_fade_or_slice", "reduce_draw_or_hook")

fun CoachingFocusDto.normalized(): CoachingFocusDto {
    val normalizedGoals = goals
        .filter { it in knownFocusKeys }
        .distinct()
        .take(MAX_COACHING_GOALS)
        .toMutableList()
    if (normalizedGoals.count { it in shotShapeFocusKeys } > 1) {
        val firstShotShape = normalizedGoals.first { it in shotShapeFocusKeys }
        normalizedGoals.removeAll { it in shotShapeFocusKeys && it != firstShotShape }
    }
    return CoachingFocusDto(
        goals = normalizedGoals,
        customNote = customNote
            ?.trim()
            ?.take(MAX_COACHING_NOTE_LENGTH)
            ?.takeIf(String::isNotBlank),
    )
}

fun CoachingFocusDto.toggle(goal: String): CoachingFocusDto {
    if (goal !in knownFocusKeys) return normalized()
    val selected = normalized().goals.toMutableList()
    if (goal in selected) {
        selected.remove(goal)
    } else {
        if (goal in shotShapeFocusKeys) selected.removeAll { it in shotShapeFocusKeys }
        if (selected.size < MAX_COACHING_GOALS) selected += goal
    }
    return copy(goals = selected).normalized()
}

fun CoachingFocusDto.isEmpty(): Boolean = goals.isEmpty() && customNote.isNullOrBlank()

fun CoachingFocusDto.displayLines(): List<String> = buildList {
    val labels = goals.mapNotNull { key -> COACHING_FOCUS_OPTIONS.find { it.key == key }?.label }
    if (labels.isNotEmpty()) add(labels.joinToString(", "))
    customNote?.takeIf(String::isNotBlank)?.let(::add)
}

fun formatPracticeAdvice(assessment: LlmAssessmentDto): String = buildString {
    assessment.coachingFocus?.normalized()?.takeUnless(CoachingFocusDto::isEmpty)?.let { focus ->
        appendLine("Coaching focus")
        focus.displayLines().forEach { appendLine(it) }
        appendLine()
    }
    appendLine("Practice advice")
    assessment.content.priorities.forEachIndexed { index, priority ->
        if (index > 0) appendLine()
        appendLine("${index + 1}. ${priority.title}")
        priority.rationale.takeIf(String::isNotBlank)?.let(::appendLine)
        priority.practiceCue.takeIf(String::isNotBlank)?.let { appendLine("Practice cue: $it") }
        if (priority.drills.any(String::isNotBlank)) {
            appendLine("Drills:")
            priority.drills.filter(String::isNotBlank).forEach { appendLine("- $it") }
        }
        if (priority.practicePlan.any(String::isNotBlank)) {
            appendLine("Practice plan:")
            priority.practicePlan.filter(String::isNotBlank).forEachIndexed { step, item ->
                appendLine("${step + 1}. $item")
            }
        }
    }
}.trim()
