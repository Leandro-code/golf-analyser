package com.golfanalyser.app.data

/**
 * Extracts human-readable fields from an incomplete structured-output JSON document.
 * The final response is still decoded strictly as [LlmContentDto]; this parser is only
 * used to render a provisional UI while response text deltas are arriving.
 */
internal class LlmDraftParser {
    fun parse(jsonPrefix: String): LlmContentDraft {
        val values = collectStringValues(jsonPrefix)
        return LlmContentDraft(
            overview = values["overview"].orEmpty(),
            strengths = indexedStrings(values, "strengths"),
            observations = objectIndices(values, "observations").map { index ->
                LlmObservationDraft(
                    title = values["observations[$index].title"].orEmpty(),
                    observation = values["observations[$index].observation"].orEmpty(),
                )
            },
            priorities = objectIndices(values, "priorities").map { index ->
                LlmPriorityDraft(
                    title = values["priorities[$index].title"].orEmpty(),
                    rationale = values["priorities[$index].rationale"].orEmpty(),
                    practiceCue = values["priorities[$index].practice_cue"].orEmpty(),
                    explanation = values["priorities[$index].explanation"].orEmpty(),
                    drills = indexedStrings(values, "priorities[$index].drills"),
                    practicePlan = indexedStrings(values, "priorities[$index].practice_plan"),
                )
            },
            limitations = indexedStrings(values, "limitations"),
        )
    }

    private fun collectStringValues(json: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val stack = mutableListOf<Container>()
        var index = 0
        while (index < json.length) {
            when (val character = json[index]) {
                '{' -> {
                    stack += ObjectContainer(valuePath(stack))
                    index += 1
                }
                '[' -> {
                    stack += ArrayContainer(valuePath(stack))
                    index += 1
                }
                '}' , ']' -> {
                    if (stack.isNotEmpty()) {
                        stack.removeAt(stack.lastIndex)
                        completeValue(stack)
                    }
                    index += 1
                }
                ',' -> {
                    (stack.lastOrNull() as? ObjectContainer)?.expectingKey = true
                    index += 1
                }
                ':' -> index += 1
                '"' -> {
                    val parsed = parseString(json, index + 1)
                    val objectContainer = stack.lastOrNull() as? ObjectContainer
                    if (objectContainer?.expectingKey == true) {
                        if (parsed.closed) {
                            objectContainer.pendingKey = parsed.value
                            objectContainer.expectingKey = false
                        }
                    } else {
                        val path = valuePath(stack)
                        if (path.isNotEmpty()) values[path.render()] = parsed.value
                        if (parsed.closed) completeValue(stack)
                    }
                    index = parsed.nextIndex
                    if (!parsed.closed) break
                }
                else -> {
                    if (character.isWhitespace()) {
                        index += 1
                    } else {
                        val end = json.indexOfFirstFrom(index) { it == ',' || it == '}' || it == ']' || it.isWhitespace() }
                        if (end == -1) break
                        completeValue(stack)
                        index = end
                    }
                }
            }
        }
        return values
    }

    private fun parseString(source: String, start: Int): ParsedString {
        val output = StringBuilder()
        var index = start
        while (index < source.length) {
            val character = source[index]
            when {
                character == '"' -> return ParsedString(output.toString(), index + 1, true)
                character != '\\' -> {
                    output.append(character)
                    index += 1
                }
                index + 1 >= source.length -> return ParsedString(output.toString(), source.length, false)
                source[index + 1] == 'u' -> {
                    if (index + 6 > source.length) {
                        return ParsedString(output.toString(), source.length, false)
                    }
                    val hex = source.substring(index + 2, index + 6)
                    val decoded = hex.toIntOrNull(16)
                        ?: return ParsedString(output.toString(), source.length, false)
                    output.append(decoded.toChar())
                    index += 6
                }
                else -> {
                    output.append(
                        when (source[index + 1]) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> source[index + 1]
                        },
                    )
                    index += 2
                }
            }
        }
        return ParsedString(output.toString(), source.length, false)
    }

    private fun valuePath(stack: List<Container>): List<PathPart> = when (val parent = stack.lastOrNull()) {
        null -> emptyList()
        is ObjectContainer -> {
            val key = parent.pendingKey
            if (key.isNullOrEmpty()) parent.path else parent.path + Key(key)
        }
        is ArrayContainer -> parent.path + ArrayIndex(parent.index)
    }

    private fun completeValue(stack: List<Container>) {
        when (val parent = stack.lastOrNull()) {
            is ObjectContainer -> parent.pendingKey = null
            is ArrayContainer -> parent.index += 1
            null -> Unit
        }
    }

    private fun indexedStrings(values: Map<String, String>, prefix: String): List<String> {
        val expression = Regex("^${Regex.escape(prefix)}\\[(\\d+)]$")
        return values.mapNotNull { (path, value) ->
            expression.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()?.let { it to value }
        }.sortedBy { it.first }.map { it.second }
    }

    private fun objectIndices(values: Map<String, String>, prefix: String): List<Int> {
        val expression = Regex("^${Regex.escape(prefix)}\\[(\\d+)]\\..+$")
        return values.keys.mapNotNull { path ->
            expression.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted()
    }

    private sealed interface Container {
        val path: List<PathPart>
    }

    private data class ObjectContainer(
        override val path: List<PathPart>,
        var pendingKey: String? = null,
        var expectingKey: Boolean = true,
    ) : Container

    private data class ArrayContainer(
        override val path: List<PathPart>,
        var index: Int = 0,
    ) : Container

    private sealed interface PathPart
    private data class Key(val value: String) : PathPart
    private data class ArrayIndex(val value: Int) : PathPart

    private fun List<PathPart>.render(): String = buildString {
        this@render.forEachIndexed { index, part ->
            when (part) {
                is Key -> {
                    if (index > 0) append('.')
                    append(part.value)
                }
                is ArrayIndex -> append("[${part.value}]")
            }
        }
    }

    private data class ParsedString(
        val value: String,
        val nextIndex: Int,
        val closed: Boolean,
    )
}

private inline fun String.indexOfFirstFrom(startIndex: Int, predicate: (Char) -> Boolean): Int {
    for (index in startIndex until length) {
        if (predicate(this[index])) return index
    }
    return -1
}
