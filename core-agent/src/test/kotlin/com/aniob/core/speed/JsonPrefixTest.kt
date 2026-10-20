package com.aniob.core.speed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The early-exit scanner that lets a streamed reply be acted on before the model stops talking.
 * Every case here is one that occurs in real replies: prose before the object, braces in a
 * `thought`, escaped quotes, nested `target` blocks and truncated streams.
 */
class JsonPrefixTest {

    @Test
    fun returnsTheObjectAsSoonAsItCloses() {
        assertEquals("""{"tool":"tap"}""", JsonPrefix.firstCompleteObject("""{"tool":"tap"}"""))
        assertEquals("""{"a":1}""", JsonPrefix.firstCompleteObject("""thinking... {"a":1} and more"""))
    }

    @Test
    fun aTruncatedObjectIsNotComplete() {
        assertNull(JsonPrefix.firstCompleteObject("""{"tool":"tap","target":{"kind":"som""""))
        // An empty object is balanced; it is the schema parser that rejects it, not this scanner.
        assertTrue(JsonPrefix.hasCompleteObject("{ }"))
        assertTrue(JsonPrefix.hasCompleteObject("{}"))
    }

    @Test
    fun nestedObjectsAndArraysKeepDepth() {
        val text = """{"thought":"x","tool":"tap","target":{"kind":"som_index","value":3},"tags":[1,2,3]}"""
        assertEquals(text, JsonPrefix.firstCompleteObject(text))
    }

    @Test
    fun bracesAndEscapedQuotesInsideStringsDoNotCloseTheObject() {
        val text = """{"thought":"tap the {weird} \"button\"","tool":"tap","value":"}"}"""
        assertEquals(text, JsonPrefix.firstCompleteObject(text))
    }

    @Test
    fun markdownFencesAndLeadingProseAreIgnored() {
        val text = "```json\n{\"tool\":\"tap\"}\n```"
        assertEquals("""{"tool":"tap"}""", JsonPrefix.firstCompleteObject(text))
    }

    @Test
    fun noBraceYetMeansKeepWaiting() {
        assertNull(JsonPrefix.firstCompleteObject(""))
        assertNull(JsonPrefix.firstCompleteObject("sure,"))
    }

    @Test
    fun theBoundedBufferKeepsTheOpeningBrace() {
        val buffer = StringBuilder()
        JsonPrefix.appendBounded(buffer, """{"tool":"tap","thought":"""")
        // Enough deltas to reach the cap and try to run past it.
        repeat(1_000) { JsonPrefix.appendBounded(buffer, "x".repeat(64)) }
        assertEquals('{', buffer[0])
        assertTrue(buffer.length >= JsonPrefix.MAX_BUFFER_CHARS)
        // The window stops growing instead of truncating the head and losing the brace.
        assertTrue(buffer.length <= JsonPrefix.MAX_BUFFER_CHARS + 64)
        val frozen = buffer.length
        JsonPrefix.appendBounded(buffer, "\"}")
        assertEquals(frozen, buffer.length)
        assertFalse(JsonPrefix.hasCompleteObject(buffer))
    }
}
