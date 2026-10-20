package com.aniob.core.intent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate decides whether a prompt reaches the automation ladder at all, so a politely worded
 * command must not fall through to the chat path.
 */
class AniobTaskIntentClassifierTest {

    @Test
    fun politeCommandsStillAutomate() {
        assertEquals(AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify("open settings"))
        assertEquals(
            AniobIntent.DEVICE_AUTOMATION,
            AniobTaskIntentClassifier.classify("Can you open Settings?")
        )
        assertEquals(
            AniobIntent.DEVICE_AUTOMATION,
            AniobTaskIntentClassifier.classify("please turn on wifi")
        )
        assertEquals(
            AniobIntent.DEVICE_AUTOMATION,
            AniobTaskIntentClassifier.classify("I need to send a message to mom")
        )
    }

    @Test
    fun imperativesThatWereMissingAreAutomation() {
        assertEquals(
            AniobIntent.DEVICE_AUTOMATION,
            AniobTaskIntentClassifier.classify("set an alarm for 7:30 am")
        )
        assertEquals(
            AniobIntent.DEVICE_AUTOMATION,
            AniobTaskIntentClassifier.classify("take a screenshot")
        )
        assertEquals(
            AniobIntent.DEVICE_AUTOMATION,
            AniobTaskIntentClassifier.classify("increase the volume")
        )
        assertEquals(
            AniobIntent.DEVICE_AUTOMATION,
            AniobTaskIntentClassifier.classify("reply to the last message")
        )
    }

    @Test
    fun realQuestionsStayQuestions() {
        assertEquals(
            AniobIntent.KNOWLEDGE_QA,
            AniobTaskIntentClassifier.classify("what is open source?")
        )
        assertEquals(
            AniobIntent.KNOWLEDGE_QA,
            AniobTaskIntentClassifier.classify("how do I open settings")
        )
        assertEquals(
            AniobIntent.KNOWLEDGE_QA,
            AniobTaskIntentClassifier.classify("explain how to send a message")
        )
        assertEquals(
            AniobIntent.EXTERNAL_AI_QUERY,
            AniobTaskIntentClassifier.classify("write a poem about rain")
        )
    }

    @Test
    fun theExistingSpecialRoutesStillWin() {
        assertEquals(
            AniobIntent.WEB_RESEARCH,
            AniobTaskIntentClassifier.classify("search the web for pizza")
        )
        assertEquals(
            AniobIntent.VAULT_QUERY,
            AniobTaskIntentClassifier.classify("what did I save about my birthday")
        )
    }

    @Test
    fun stackedOpenersAreRemovedBeforeTheVerbTest() {
        assertEquals(
            "open settings",
            AniobTaskIntentClassifier.stripPreamble("please can you just open settings")
        )
        assertEquals(
            "what is this",
            AniobTaskIntentClassifier.stripPreamble("what is this")
        )
    }
}
