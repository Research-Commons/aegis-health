package com.aegis.health.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.LocalAegisColors
import com.valentinilk.shimmer.LocalShimmerTheme
import com.valentinilk.shimmer.ShimmerBounds
import com.valentinilk.shimmer.defaultShimmerTheme
import com.valentinilk.shimmer.rememberShimmer
import com.valentinilk.shimmer.shimmer

/**
 * Live-tools stepper. Renders a vertical list of tool-call rows fed by
 * `ProgressEvent.Step` arrivals (Phase 5 D-09) via the same `steps: List<String>`
 * consumer pattern `LoadingPanel` already uses, plus a side-channel
 * `failures: Map<Int, FailureInfo>` for `ProgressEvent.StepFailure` events
 * (Phase 7 D-04c — no UI-side sentinel parsing).
 *
 * Visual model (Phase 7 D-03 / D-03a / D-03b / D-03c / D-05):
 *  - The last element of `steps` renders as Running ↻; all prior elements
 *    render as Done ✓. No Pending ○ in steady state (D-03 — list grows
 *    reactively; there are no future labels to mark Pending).
 *  - When both `steps.isEmpty()` and `failures.isEmpty()`, a single
 *    `compose-shimmer` skeleton row renders the SKEL-02 copy "Preparing…"
 *    at 1800ms LinearEasing cycle (SKEL-01 + D-03a).
 *  - When `failures[idx]` is set, row `idx` renders as Failed ⚠ in calm-tone
 *    amber via `LocalAegisColors.current.warningFg` / `.warningBg` — NEVER
 *    the red severity-critical palette (STEP-06: calm-tone, NOT red panic
 *    copy).
 *  - State transitions are wrapped in `AnimatedContent(tween(350))`; new-row
 *    reveal is wrapped in `AnimatedVisibility(fadeIn + expandVertically,
 *    tween(350))`. Compose's framework auto-honors
 *    `Settings.Global.ANIMATOR_DURATION_SCALE` so reduced-motion users see no
 *    animation (SKEL-05; no manual lookup needed).
 *  - A single latency-honest subline "running on your phone — ~5 minutes"
 *    renders at the bottom of the composable (D-05 — single source of truth,
 *    inherited by every stepper-bearing screen; SKEL-04).
 *
 * Signature pin (Phase 5 D-08): `(label: String, steps: List<String>,
 * modifier: Modifier = Modifier)` MUST be preserved byte-identical. The 4th
 * parameter `failures: Map<Int, FailureInfo> = emptyMap()` is additive with a
 * default value so the existing 3-param call sites (e.g. `ToolStepperSmokeTest`
 * lines 55-58) continue to compile unchanged.
 *
 * See `.planning/phases/07-toolstepper-ui-latency-honest-skeletons/07-CONTEXT.md`
 * D-03..D-05 + 07-01-SUMMARY.md (StepFailure subtype + calm-tone tokens
 * prerequisite). Consumer contract pinned by `ToolStepperSmokeTest` (label +
 * step-string presence only — no visual-style or order assertions).
 */
@Composable
fun ToolStepper(
    label: String,
    steps: List<String>,
    modifier: Modifier = Modifier,
    failures: Map<Int, FailureInfo> = emptyMap(),
) {
    val colors = LocalAegisColors.current

    CompositionLocalProvider(LocalShimmerTheme provides aegisShimmerTheme) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(18.dp))
                .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (steps.isEmpty() && failures.isEmpty()) {
                    // D-03a — pre-first-Step shimmer skeleton row at 1800ms
                    // LinearEasing cycle (SKEL-01). First copy of the SKEL-02
                    // four-string sequence.
                    ShimmerSkeletonRow(label = "Preparing…")
                } else {
                    steps.forEachIndexed { idx, rowLabel ->
                        val state = when {
                            failures.containsKey(idx) -> ToolStepperState.Failed
                            idx < steps.lastIndex -> ToolStepperState.Done
                            else -> ToolStepperState.Running
                        }
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(350)) + expandVertically(tween(350)),
                            exit = fadeOut(tween(350)) + shrinkVertically(tween(350)),
                        ) {
                            AnimatedContent(
                                targetState = state,
                                transitionSpec = {
                                    fadeIn(tween(350)) togetherWith fadeOut(tween(350))
                                },
                                label = "step-state-$idx",
                            ) { rendered ->
                                StepRow(
                                    text = rowLabel,
                                    state = rendered,
                                    failureReason = failures[idx]?.reason,
                                    index = idx,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // D-05 — single source of truth for the latency-honest subline.
            // All three stepper-bearing screens inherit this copy via
            // composable inclusion (SKEL-04; C4 fake-typing mitigation).
            Text(
                text = "running on your phone — ~5 minutes",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

/**
 * Side-channel payload carrying a failed `ProgressEvent.StepFailure(label,
 * reason)` to the stepper (Phase 7 D-04c — no UI-side sentinel-string
 * parsing). `label` is the original step label (same as `Step.label` for the
 * tool call that errored); `reason` is `e.message` from the dispatcher's
 * catch block.
 *
 * Public so screens in `ui/drugsafe/`, `ui/healthpartner/`, `ui/reportreader/`
 * can import this type when they wire their `failures: SnapshotStateMap<Int,
 * FailureInfo>` state holder (Plans 07-03 / 07-04).
 */
data class FailureInfo(
    val label: String,
    val reason: String,
)

private enum class ToolStepperState { Running, Done, Failed }

private val aegisShimmerTheme = defaultShimmerTheme.copy(
    animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 1800, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    ),
)

@Composable
private fun ShimmerSkeletonRow(label: String) {
    val colors = LocalAegisColors.current
    val shimmer = rememberShimmer(shimmerBounds = ShimmerBounds.View)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shimmer(shimmer),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(colors.hairline, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun StepRow(
    text: String,
    state: ToolStepperState,
    failureReason: String?,
    index: Int,
) {
    val colors = LocalAegisColors.current

    when (state) {
        ToolStepperState.Failed -> {
            // D-03c — calm-tone ⚠ chip. Uses warningFg / warningBg tokens
            // (Plan 07-01). NEVER the red severity-critical palette (STEP-06).
            // failureReason?.take(64) clamps ASVS V7 / T-07-05.
            Row(
                modifier = Modifier
                    .testTag("step-row-${state.name}-$index")
                    .background(colors.warningBg, RoundedCornerShape(10.dp))
                    .let { base ->
                        // WR-03 (Plan 07-08): light-mode-only 1dp warningFg@0.32f
                        // border lifts the cream-on-white chip contrast from ~1.06:1
                        // up to WCAG AA's 3:1 non-text-component threshold. Dark
                        // mode's 0x1F-alpha WarningBgDark already has visible chroma
                        // against AegisCanvasDark, so the border is light-only.
                        if (!colors.isDark) {
                            base.border(1.dp, colors.warningFg.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
                        } else {
                            base
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "⚠",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.warningFg,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = failureReason?.take(64) ?: text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.warningFg,
                )
            }
        }
        ToolStepperState.Running, ToolStepperState.Done -> {
            Row(
                modifier = Modifier.testTag("step-row-${state.name}-$index"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            color = if (state == ToolStepperState.Done) colors.accent else Color.Transparent,
                            shape = CircleShape,
                        )
                        .border(
                            width = 1.5.dp,
                            color = colors.accent,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state == ToolStepperState.Done) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = colors.accentInk,
                            modifier = Modifier.size(9.dp),
                        )
                    }
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
            }
        }
    }
}
