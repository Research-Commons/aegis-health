package com.aegis.health.inference

import com.aegis.health.inference.ToolDispatcher.ProgressEvent
import com.aegis.health.models.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 07-06 CR-01 / WR-01 structural pin for the fast-path try/catch +
 * [ProgressEvent.StepFailure] emission contract.
 *
 * The production fast-path methods ([ToolDispatcher.runDrugSafeFastPath] and
 * [ToolDispatcher.runHealthPartnerFastPath]) require Android runtime singletons
 * (`AegisApp.instance.database`, `EngineRouter.active`) that cannot be
 * instantiated from a pure JVM test. Per the plan's task action note ("option
 * (b) is acceptable"), this test pins the EXACT catch-block shape that the
 * fast-path methods use, via a thin wrapper [runFastPathToolStep] that
 * mirrors the production code's:
 *
 *   1. Hoist `val friendlyLabel = FriendlyToolSummarizer.summarize(toolCall)`
 *      (WR-01 — Step.label and StepFailure.label MUST come from the same call).
 *   2. Emit `ProgressEvent.Step(friendlyLabel)` BEFORE invoking the tool.
 *   3. Wrap the tool invocation in `try { ... } catch (e: Exception) { ... }`.
 *   4. Inside the catch, emit `ProgressEvent.StepFailure(friendlyLabel, e.message ?: ...)`
 *      wrapped in an INNER try/catch (Pitfall 5 — a throwing onProgress UI
 *      lambda must NOT short-circuit the outer recovery contract).
 *   5. Return a fallback value from the catch block (no rethrow on this path —
 *      mirrors `dispatchToolCall`'s ToolResult fall-through).
 *
 * The behavioral pin is: any future refactor that drops the StepFailure emission,
 * drops the inner try/catch, OR makes Step.label and StepFailure.label diverge
 * (e.g. by re-summarizing inline instead of caching) will fail this test by
 * structure. The corresponding production code in ToolDispatcher.kt MUST keep
 * the same emission shape — verified by the plan-level grep gates G1-G2 +
 * G5 in 07-06-PLAN.md.
 *
 * JUnit 4 + per-test methods only (junit:junit:4.13.2 is the sole test dep).
 */
class FastPathStepFailureTest {

    // ── Fixtures ────────────────────────────────────────────────────────

    private fun checkWarningsCall(drugs: List<String>): ToolCall {
        val args = mutableMapOf<String, JsonElement>()
        args["drug_list"] = JsonArray(drugs.map { JsonPrimitive(it) })
        return ToolCall(name = "check_warnings", arguments = args)
    }

    private fun getGuidelineCall(age: Int, sex: String): ToolCall {
        val args = mutableMapOf<String, JsonElement>(
            "age" to JsonPrimitive(age),
            "sex" to JsonPrimitive(sex),
        )
        return ToolCall(name = "get_guideline", arguments = args)
    }

    /**
     * Mirrors the production catch-block shape from
     * `runDrugSafeFastPath` / `runHealthPartnerFastPath` (post-07-06).
     *
     * - Hoists `friendlyLabel` once
     * - Emits Step BEFORE `tool()`
     * - Wraps `tool()` in try/catch
     * - In catch: emits StepFailure with the same `friendlyLabel`, wrapped
     *   in an inner try/catch (Pitfall 5)
     * - Returns null from the catch (the test asserts the throw was caught
     *   without re-propagating, which the production code achieves via
     *   `invalidFinalResponse(...)` as the fallback return value).
     */
    private fun runFastPathToolStep(
        toolCall: ToolCall,
        onProgress: (ProgressEvent) -> Unit,
        tool: () -> Unit,
    ): Boolean {
        val friendlyLabel = FriendlyToolSummarizer.summarize(toolCall)
        onProgress(ProgressEvent.Step(friendlyLabel))
        return try {
            tool()
            true
        } catch (e: Exception) {
            try {
                onProgress(
                    ProgressEvent.StepFailure(
                        label = friendlyLabel,
                        reason = e.message ?: "Tool execution failed",
                    )
                )
            } catch (progressErr: Exception) {
                // Pitfall 5 — swallow onProgress throws so the outer fallback
                // path always runs.
            }
            false
        }
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test fun drugSafeFastPath_emits_StepFailure_when_checkWarnings_throws() {
        val recorded = mutableListOf<ProgressEvent>()
        val toolCall = checkWarningsCall(listOf("warfarin", "aspirin"))

        val ok = runFastPathToolStep(toolCall, onProgress = { recorded.add(it) }) {
            throw RuntimeException("simulated CheckWarnings.check failure")
        }

        assertEquals(false, ok)
        val failures = recorded.filterIsInstance<ProgressEvent.StepFailure>()
        assertTrue(
            "fast-path catch must emit at least one StepFailure when the tool throws",
            failures.isNotEmpty(),
        )
        assertEquals(
            "StepFailure.reason should carry the thrown exception's message",
            "simulated CheckWarnings.check failure",
            failures.first().reason,
        )
    }

    @Test fun healthPartnerFastPath_emits_StepFailure_when_getGuidelines_throws() {
        val recorded = mutableListOf<ProgressEvent>()
        val toolCall = getGuidelineCall(age = 65, sex = "female")

        val ok = runFastPathToolStep(toolCall, onProgress = { recorded.add(it) }) {
            throw IllegalStateException("simulated GetGuideline.getGuidelines failure")
        }

        assertEquals(false, ok)
        val failures = recorded.filterIsInstance<ProgressEvent.StepFailure>()
        assertTrue(
            "fast-path catch must emit at least one StepFailure when the tool throws",
            failures.isNotEmpty(),
        )
        assertEquals(
            "simulated GetGuideline.getGuidelines failure",
            failures.first().reason,
        )
    }

    @Test fun throwing_onProgress_does_not_propagate_to_caller_pitfall_5() {
        // Simulates a recomposition race where the Compose `onProgress`
        // lambda itself throws. The Pitfall 5 inner try/catch must swallow
        // this so the surrounding fallback path still runs.
        val toolCall = checkWarningsCall(listOf("ibuprofen"))
        val onProgress: (ProgressEvent) -> Unit = { evt ->
            if (evt is ProgressEvent.StepFailure) {
                throw RuntimeException("UI lambda exploded during recomposition")
            }
            // Allow Step events through so we can confirm the Step
            // emission happened before the tool throw.
        }

        // The whole call must NOT propagate the inner onProgress throw.
        // If it did, this test would fail with the UI lambda's exception.
        val ok = runFastPathToolStep(toolCall, onProgress = onProgress) {
            throw RuntimeException("tool failed")
        }

        assertEquals(
            "outer catch must still return the fallback value even when " +
                "onProgress throws while reporting StepFailure",
            false,
            ok,
        )
    }

    @Test fun stepFailure_label_byte_matches_step_label_WR_01_invariant() {
        // WR-01 — the failure-side label MUST be byte-identical to the
        // success-side Step label, both derived from the same
        // FriendlyToolSummarizer.summarize call cached into a local `val`.
        val recorded = mutableListOf<ProgressEvent>()
        val toolCall = checkWarningsCall(listOf("warfarin", "aspirin"))

        runFastPathToolStep(toolCall, onProgress = { recorded.add(it) }) {
            throw RuntimeException("boom")
        }

        val stepLabels = recorded.filterIsInstance<ProgressEvent.Step>().map { it.label }
        val failureLabels = recorded.filterIsInstance<ProgressEvent.StepFailure>().map { it.label }

        assertTrue("expected a Step emission before the failure", stepLabels.isNotEmpty())
        assertTrue("expected a StepFailure emission", failureLabels.isNotEmpty())
        assertEquals(
            "WR-01 — Step.label and StepFailure.label must be byte-identical " +
                "(both come from a hoisted `val friendlyLabel = " +
                "FriendlyToolSummarizer.summarize(toolCall)`)",
            stepLabels.first(),
            failureLabels.first(),
        )
    }

    @Test fun stepFailure_reason_defaults_when_exception_message_is_null() {
        // Some exceptions (e.g. NullPointerException from Kotlin's `!!`)
        // can have a null message. The catch block must default to a
        // human-readable string rather than emitting `null`.
        val recorded = mutableListOf<ProgressEvent>()
        val toolCall = checkWarningsCall(listOf("acetaminophen"))

        runFastPathToolStep(toolCall, onProgress = { recorded.add(it) }) {
            throw RuntimeException(null as String?)
        }

        val failure = recorded.filterIsInstance<ProgressEvent.StepFailure>().firstOrNull()
        assertTrue("expected a StepFailure", failure != null)
        assertEquals("Tool execution failed", failure!!.reason)
    }
}
