package com.aniob.core.speed

/**
 * Finds the first complete top-level JSON object in a stream of model output.
 *
 * A streaming reply is consumed token by token, and the caller wants to act the moment the
 * action object is closed instead of waiting for the model to finish talking. The scan is a tiny
 * state machine, not a parser: it tracks brace depth and string context (with escapes), so a
 * brace inside a thought string never ends the object early.
 *
 * Returns the object including its braces, or `null` while the reply is still incomplete.
 */
object JsonPrefix {

    /** Longest prefix kept while waiting for the closing brace; guards a runaway stream. */
    const val MAX_BUFFER_CHARS = 8_192

    /**
     * The first balanced `{...}` block in [text], or null when no object has closed yet.
     *
     * Leading prose, markdown fences and whitespace are ignored. A `{` that never closes yields
     * null, so the caller keeps accumulating.
     */
    fun firstCompleteObject(text: CharSequence): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < text.length) {
            val c = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
            index++
        }
        return null
    }

    /** True once [text] holds a complete object; used to decide whether more deltas are useful. */
    fun hasCompleteObject(text: CharSequence): Boolean = firstCompleteObject(text) != null

    /**
     * Appends one delta while keeping a bounded window that still contains the opening brace.
     *
     * Leading prose before the object is dropped. Once the buffer is at the cap and already starts
     * with the object's brace, further deltas are ignored rather than truncating the head: losing
     * the opening brace (or half a string) would make the scan never close. A well-formed action
     * reply is a few hundred characters, so the cap is only a guard against a runaway stream.
     */
    fun appendBounded(buffer: StringBuilder, delta: String): StringBuilder {
        if (buffer.length >= MAX_BUFFER_CHARS && buffer.indexOf("{") == 0) return buffer
        buffer.append(delta)
        if (buffer.length > MAX_BUFFER_CHARS) {
            val start = buffer.indexOf("{")
            if (start > 0) buffer.delete(0, start)
        }
        return buffer
    }
}
