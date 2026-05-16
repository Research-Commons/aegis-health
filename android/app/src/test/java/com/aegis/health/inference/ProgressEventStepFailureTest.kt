package com.aegis.health.inference

import com.aegis.health.inference.ToolDispatcher.ProgressEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * JVM contract test for [ToolDispatcher.ProgressEvent.StepFailure] (Phase 7 D-04 + D-04c).
 *
 * Pins the Path A no-op `applyTo` contract decision:
 *   - The planner-locked Path A overrides 07-CONTEXT.md D-04's sentinel-prefix wording.
 *     Sanctioned by D-04c (UI routes StepFailure via a SnapshotStateMap side channel,
 *     so applyTo need not emit anything into the steps list) and the planning-context
 *     Pitfall #1 atomicity concern (a sentinel-prefix string would render verbatim in
 *     the placeholder ToolStepper body's `Text(it)` between Plan 07-01 ship and
 *     Plan 07-02 ship). [FlagPreview.applyTo] already establishes the no-op precedent
 *     in [ToolDispatcher.ProgressEvent].
 *
 * Also pins data-class equality semantics (Phase 7 D-04 — `data class` shape).
 *
 * JUnit 4 + per-test methods only (Shared Pattern F — project's only test idiom;
 * junit:junit:4.13.2 is the only test dep).
 */
class ProgressEventStepFailureTest {

    @Test fun applyTo_is_a_noop_on_empty_steps_list() {
        val steps = mutableListOf<String>()
        ProgressEvent.StepFailure(label = "any label", reason = "any reason").applyTo(steps)
        assertEquals(0, steps.size)
    }

    @Test fun applyTo_is_a_noop_on_nonempty_steps_list_preserving_contents() {
        val steps = mutableListOf("Reading 12 lab values", "Checking warfarin")
        ProgressEvent.StepFailure(label = "Checking warfarin", reason = "DB closed").applyTo(steps)
        assertEquals(2, steps.size)
        assertEquals("Reading 12 lab values", steps[0])
        assertEquals("Checking warfarin", steps[1])
    }

    @Test fun data_class_equality_holds_for_identical_label_and_reason() {
        val a = ProgressEvent.StepFailure(label = "Checking warfarin", reason = "DB closed")
        val b = ProgressEvent.StepFailure(label = "Checking warfarin", reason = "DB closed")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun data_class_equality_distinguishes_different_reason() {
        val a = ProgressEvent.StepFailure(label = "Checking warfarin", reason = "DB closed")
        val b = ProgressEvent.StepFailure(label = "Checking warfarin", reason = "timeout")
        assertNotEquals(a, b)
    }
}
