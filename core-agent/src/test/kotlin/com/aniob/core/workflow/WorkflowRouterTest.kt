package com.aniob.core.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRouterTest {

    @Test
    fun testPreservesUppercaseUrlPathAndQuery() {
        val prompt = "Open https://example.com/MyPath/SubPath?QueryParam=UPPER"
        val decision = WorkflowRouter.route(prompt)

        assertTrue("Expected DirectAction for valid HTTPS link", decision is WorkflowDecision.DirectAction)
        val action = (decision as WorkflowDecision.DirectAction).action
        assertTrue(action is WorkflowAction.OpenWebsite)
        val openWebsite = action as WorkflowAction.OpenWebsite
        assertEquals("https://example.com/MyPath/SubPath?QueryParam=UPPER", openWebsite.destination.url)
    }

    @Test
    fun testDisambiguatesExplanationFromCreation() {
        // Informational question -> delegate to existing Q&A
        val explanationDecision = WorkflowRouter.route("explain how to create a website")
        assertTrue(explanationDecision is WorkflowDecision.DelegateToExisting)

        val howToDecision = WorkflowRouter.route("how do i create a website")
        assertTrue(howToDecision is WorkflowDecision.DelegateToExisting)

        // Actual creation command -> needs builder chooser
        val creationDecision = WorkflowRouter.route("build a website for my bakery")
        assertTrue(creationDecision is WorkflowDecision.NeedsChooser)
        val chooser = creationDecision as WorkflowDecision.NeedsChooser
        assertEquals(3, chooser.candidates.size)
    }

    @Test
    fun testBuilderChooserReturnsNoDefault() {
        val decision = WorkflowRouter.route("create a website")
        assertTrue(decision is WorkflowDecision.NeedsChooser)
        val candidates = (decision as WorkflowDecision.NeedsChooser).candidates

        // The candidates are nominated options, not pre-approved
        assertTrue(candidates.none { it.isApproved })
        assertEquals(listOf("lovable.dev", "bolt.new", "replit.com"), candidates.map { it.handler })
    }

    @Test
    fun testHostedReasoningRoutesToChatGPT() {
        val decision = WorkflowRouter.route("ask chatgpt to solve this logic puzzle")
        assertTrue(decision is WorkflowDecision.DirectAction)
        val action = (decision as WorkflowDecision.DirectAction).action as WorkflowAction.OpenWebsite
        assertEquals("https://chatgpt.com/", action.destination.url)
    }

    @Test
    fun testNoteCreationRoutesToNoteApp() {
        val decision = WorkflowRouter.route("create a note to buy groceries")
        assertTrue(decision is WorkflowDecision.DirectAction)
        val action = (decision as WorkflowDecision.DirectAction).action as WorkflowAction.OpenApp
        assertEquals("com.google.android.keep", action.destination.handler)
    }
}
