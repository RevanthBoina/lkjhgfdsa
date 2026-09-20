package com.aniob.app.learning

import com.aniob.app.db.AniobDatabase
import com.aniob.app.db.AppKnowledgeBaseEntity
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.TaskContext
import com.aniob.core.learning.LearningPipeline
import com.aniob.core.tools.AniobFingerprint
import com.aniob.core.tools.AniobReplayEngine
import com.aniob.core.tools.AniobTrajectoryCodec

/**
 * The single learning pipeline for the APK (finding #8, track D core).
 *
 * Five learning stores used to accumulate knowledge independently. This implementation is
 * stage-owned and reuses the Engine + Room that already exist:
 *
 * ```
 * verified trajectory -> fastpath entry (now)
 *                     -> distill candidacy (signature match count, >= 3)
 * ```
 *
 * Only verified outcomes are recorded; a failure can never become a fastpath entry.
 */
class AniobLearningPipelineImpl(
    private val database: AniobDatabase,
    private val replayEngine: AniobReplayEngine,
    /** Called when a signature reaches the distillation threshold; PROMPT D promotes the draft. */
    private val onDistillCandidate: (taskSignature: String, trajectory: List<AniobAction>) -> Unit = { _, _ -> }
) : LearningPipeline {

    /** Per-task rehearsal buffer: the screen fingerprint observed *before* each verified step. */
    private val rehearsal = mutableMapOf<String, MutableList<ReplayPoint>>()
    private val signatureCounts = mutableMapOf<String, Int>()

    private data class ReplayPoint(
        val action: AniobAction,
        val screenFingerprint: String
    )

    override fun onVerifiedStep(ctx: TaskContext, action: AniobAction, screen: AniobScreenState) {
        rehearsal.getOrPut(ctx.instruction.lowercase()) { mutableListOf() }
            .add(ReplayPoint(action, AniobFingerprint.computeScreenFingerprint(screen)))
    }

    override fun onTaskSuccess(ctx: TaskContext, trajectory: List<AniobAction>) {
        val signature = ctx.instruction.lowercase()
        val points = rehearsal[signature].orEmpty()
        if (points.isEmpty()) return

        // 1. FastPath: persist an executable, fingerprinted trajectory so a cold start replays it
        //    with zero model tokens.
        val replayTrajectory = AniobReplayEngine.ReplayTrajectory(
            taskSignature = signature,
            packageName = ctx.lastScreenState?.packageName ?: "",
            steps = points.map { AniobReplayEngine.ReplayStep(it.screenFingerprint, it.action) }
        )
        replayEngine.registerTrajectory(replayTrajectory)

        val count = (signatureCounts[signature] ?: 0) + 1
        signatureCounts[signature] = count

        kotlinx.coroutines.runBlocking {
            val existing = database.appKnowledgeBaseDao().findBySignature(signature)
            database.appKnowledgeBaseDao().saveMacro(
                AppKnowledgeBaseEntity(
                    taskSignature = signature,
                    packageName = replayTrajectory.packageName,
                    macroStepsJson = AniobTrajectoryCodec.encode(replayTrajectory),
                    successCount = (existing?.successCount ?: 0) + 1
                )
            )
        }

        // 2. Distill candidacy: three matching verified runs are the threshold for a skill draft.
        if (count >= DISTILL_THRESHOLD) {
            onDistillCandidate(signature, points.map { it.action })
        }
        endTask(signature)
    }

    /** Rehearsal is per-task working memory; it is cleared once the task has been persisted. */
    fun endTask(instruction: String) {
        rehearsal.remove(instruction.lowercase())
    }

    /** Loads every persisted trajectory back into the replay engine. Returns how many loaded. */
    suspend fun hydrate(): Int {
        val entries = database.appKnowledgeBaseDao().getAllKnowledgeOnce()
        var loaded = 0
        entries.forEach { entry ->
            AniobTrajectoryCodec.decode(entry.macroStepsJson).forEach {
                replayEngine.registerTrajectory(it)
                loaded++
            }
        }
        lastHydratedCount = loaded
        return loaded
    }

    /** Number of trajectories restored by the most recent [hydrate]. */
    var lastHydratedCount: Int = 0
        private set

    companion object {
        const val DISTILL_THRESHOLD = 3
    }
}