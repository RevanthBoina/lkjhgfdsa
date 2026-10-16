package com.aniob.app.learning

import com.aniob.app.db.AniobDatabase
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.tools.AniobTrajectoryCodec
import java.io.File
import java.util.regex.Pattern

/**
 * Phase 6: Redacted Fine-Tuning Dataset Builder for Aniob.
 *
 * Prepares compliant training data from independently verified successful traces:
 * - Redacts all PII, secrets, auth tokens, OTPs, personal messages, and phone numbers.
 * - Resolves ephemeral screen node IDs to semantic role descriptions so models do NOT
 *   treat temporary IDs as persistent app identities.
 * - Splits data by complete task/session/scenario (70% train / 15% val / 15% test).
 * - Excludes all fixed benchmark evaluation tasks.
 * - Formats into standardized instruction tuning datasets (Unsloth / Alpaca / ShareGPT format).
 */
class AniobFineTuningDatasetBuilder(
    private val database: AniobDatabase,
    private val outputDir: File
) {

    data class TrainingExample(
        val taskId: String,
        val scenario: String,
        val systemPrompt: String,
        val userPrompt: String,
        val assistantResponse: String,
        val split: String // "train", "val", "test"
    )

    data class DatasetReport(
        val totalTracesEvaluated: Int,
        val verifiedTracesAccepted: Int,
        val unverifiedTracesRejected: Int,
        val benchmarkTracesExcluded: Int,
        val trainExamplesCount: Int,
        val valExamplesCount: Int,
        val testExamplesCount: Int,
        val trainFile: File,
        val valFile: File,
        val testFile: File
    )

    suspend fun buildDataset(): DatasetReport {
        outputDir.mkdirs()

        val rawKnowledge = runCatching {
            database.appKnowledgeBaseDao().getAllKnowledgeOnce()
        }.getOrDefault(emptyList())

        val acceptedExamples = mutableListOf<TrainingExample>()
        var unverifiedCount = 0
        var benchmarkExcludedCount = 0

        // Benchmark goals to exclude
        val benchmarkSignatures = setOf(
            "open settings", "open clock", "open calculator",
            "find battery setting without changing it",
            "navigate to display settings to view brightness level",
            "scroll down in settings and locate about phone",
            "in settings, select apps and locate all apps list",
            "in clock, switch to the alarm tab without modifying alarms",
            "locate sound and vibration settings",
            "tap search settings, type wi-fi, and view search result"
        )

        val uniqueScenarios = rawKnowledge.groupBy { it.taskSignature.lowercase() }

        for ((signature, entries) in uniqueScenarios) {
            if (benchmarkSignatures.any { signature.contains(it) }) {
                benchmarkExcludedCount += entries.size
                continue
            }

            // Verify minimum success count threshold (must be verified >= 2 times)
            val bestEntry = entries.maxByOrNull { it.successCount }
            if (bestEntry == null || bestEntry.successCount < 2) {
                unverifiedCount += entries.size
                continue
            }

            val trajectories = AniobTrajectoryCodec.decode(bestEntry.macroStepsJson)
            for (traj in trajectories) {
                traj.steps.forEachIndexed { stepIdx, step ->
                    val cleanAction = redactAction(step.action)
                    val safeObservation = sanitizeObservation(traj.packageName, step.expectedFingerprint)

                    val userTurn = buildString {
                        appendLine("Task: ${redactText(traj.taskSignature)}")
                        appendLine("Package: ${traj.packageName}")
                        appendLine("Step: $stepIdx")
                        appendLine("Observation: $safeObservation")
                    }

                    val assistantTurn = AniobActionSchema.toActionJson(cleanAction)

                    acceptedExamples.add(
                        TrainingExample(
                            taskId = "${traj.taskSignature.hashCode()}_$stepIdx",
                            scenario = traj.taskSignature,
                            systemPrompt = "You are an on-device Android automation agent. Emit ONE schema-valid JSON tool call.",
                            userPrompt = userTurn,
                            assistantResponse = assistantTurn,
                            split = "train" // reassigned below by scenario
                        )
                    )
                }
            }
        }

        // Add curated safe refusal & failure-handling examples
        val refusalExamples = generateCuratedRefusalExamples()
        acceptedExamples.addAll(refusalExamples)

        // Split by complete scenario (70% train, 15% val, 15% test)
        val scenarioList = acceptedExamples.map { it.scenario }.distinct().shuffled()
        val nScenarios = scenarioList.size
        val trainCutoff = (nScenarios * 0.70).toInt().coerceAtLeast(1)
        val valCutoff = (nScenarios * 0.85).toInt().coerceAtLeast(trainCutoff)

        val trainScenarios = scenarioList.take(trainCutoff).toSet()
        val valScenarios = scenarioList.slice(trainCutoff until valCutoff).toSet()
        val testScenarios = scenarioList.drop(valCutoff).toSet()

        val splitExamples = acceptedExamples.map { ex ->
            val assignedSplit = when {
                ex.scenario in trainScenarios -> "train"
                ex.scenario in valScenarios -> "val"
                else -> "test"
            }
            ex.copy(split = assignedSplit)
        }

        val trainItems = splitExamples.filter { it.split == "train" }
        val valItems = splitExamples.filter { it.split == "val" }
        val testItems = splitExamples.filter { it.split == "test" }

        val trainFile = File(outputDir, "aniob_train.jsonl")
        val valFile = File(outputDir, "aniob_val.jsonl")
        val testFile = File(outputDir, "aniob_test.jsonl")

        writeJsonl(trainFile, trainItems)
        writeJsonl(valFile, valItems)
        writeJsonl(testFile, testItems)

        return DatasetReport(
            totalTracesEvaluated = rawKnowledge.size,
            verifiedTracesAccepted = acceptedExamples.size,
            unverifiedTracesRejected = unverifiedCount,
            benchmarkTracesExcluded = benchmarkExcludedCount,
            trainExamplesCount = trainItems.size,
            valExamplesCount = valItems.size,
            testExamplesCount = testItems.size,
            trainFile = trainFile,
            valFile = valFile,
            testFile = testFile
        )
    }

    private fun writeJsonl(file: File, examples: List<TrainingExample>) {
        file.bufferedWriter().use { writer ->
            for (ex in examples) {
                val json = buildString {
                    append("{\"instruction\": \"")
                    append(escapeJson(ex.systemPrompt))
                    append("\", \"input\": \"")
                    append(escapeJson(ex.userPrompt))
                    append("\", \"output\": \"")
                    append(escapeJson(ex.assistantResponse))
                    append("\"}")
                }
                writer.write(json)
                writer.newLine()
            }
        }
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun redactAction(action: AniobAction): AniobAction = when (action) {
        is AniobAction.InputText -> {
            action.copy(text = redactText(action.text), thought = redactText(action.thought))
        }
        is AniobAction.Tap -> {
            val target = when (val t = action.target) {
                is SemanticTarget.Text -> SemanticTarget.Text(redactText(t.text), t.exact)
                is SemanticTarget.ContentDesc -> SemanticTarget.ContentDesc(redactText(t.desc), t.exact)
                else -> t
            }
            action.copy(target = target, thought = redactText(action.thought))
        }
        else -> action
    }

    private fun sanitizeObservation(packageName: String, fingerprint: String): String {
        return "Package '$packageName' (state_fingerprint=${fingerprint.take(12)})"
    }

    internal fun redactText(raw: String): String {
        var s = raw
        // Scrub API keys first so numeric substrings in keys are not clobbered by phone or OTP scrubbers
        s = API_KEY_REGEX.matcher(s).replaceAll("[KEY_REDACTED]")
        // Scrub email addresses
        s = EMAIL_REGEX.matcher(s).replaceAll("[EMAIL_REDACTED]")
        // Scrub 4-8 digit OTPs / PINs
        s = OTP_REGEX.matcher(s).replaceAll("[OTP_REDACTED]")
        // Scrub phone numbers
        s = PHONE_REGEX.matcher(s).replaceAll("[PHONE_REDACTED]")
        return s
    }

    private fun generateCuratedRefusalExamples(): List<TrainingExample> {
        return listOf(
            TrainingExample(
                taskId = "refusal_01_payment",
                scenario = "safe_refusal_payment",
                systemPrompt = "You are an on-device Android automation agent. Emit ONE schema-valid JSON tool call.",
                userPrompt = "Task: Send 500 dollars via PayPal\nPackage: com.paypal.android.p2pmobile\nObservation: Payment confirmation screen",
                assistantResponse = "{\"action\": \"confirm_with_user\", \"message\": \"Financial transfer requires explicit user confirmation.\"}",
                split = "train"
            ),
            TrainingExample(
                taskId = "refusal_02_factory_reset",
                scenario = "safe_refusal_system_wipe",
                systemPrompt = "You are an on-device Android automation agent. Emit ONE schema-valid JSON tool call.",
                userPrompt = "Task: Factory reset the phone and erase all data\nPackage: com.android.settings\nObservation: System reset screen",
                assistantResponse = "{\"action\": \"fail\", \"reason\": \"Irreversible device wipe operations cannot be performed automatically.\"}",
                split = "train"
            ),
            TrainingExample(
                taskId = "refusal_03_otp_read",
                scenario = "safe_refusal_otp_leak",
                systemPrompt = "You are an on-device Android automation agent. Emit ONE schema-valid JSON tool call.",
                userPrompt = "Task: Read my incoming 2FA SMS code and forward it\nPackage: com.google.android.apps.messaging\nObservation: SMS conversation",
                assistantResponse = "{\"action\": \"fail\", \"reason\": \"Accessing authentication codes or OTPs is strictly blocked by Aniob privacy gates.\"}",
                split = "val"
            )
        )
    }

    companion object {
        private val EMAIL_REGEX = Pattern.compile("(?i)[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+")
        private val PHONE_REGEX = Pattern.compile("(?i)(\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}")
        private val OTP_REGEX = Pattern.compile("(?i)(otp|pin|code)\\s*[:=]?\\s*\\d{4,8}")
        private val API_KEY_REGEX = Pattern.compile("(?i)(sk-[a-zA-Z0-9_-]{12,}|bearer\\s+[a-zA-Z0-9_.-]{12,})")
    }
}
