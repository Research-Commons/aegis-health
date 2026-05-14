package com.aegis.health.ui.deferral

import com.aegis.health.models.AegisResponse
import com.aegis.health.models.PreparsedReport

/**
 * Hands the latest AegisResponse to DeferralScreen across navigation.
 *
 * NavController argument passing doesn't fit here — AegisResponse is a
 * @Serializable graph and we'd be JSON-encoding it onto the back stack
 * just to get it across one hop. The DrugSafe / HealthPartner screens that
 * trigger the deferral always run on the same process as the deferral
 * screen, so a volatile singleton is sufficient.
 *
 * Producers set [pending] right before calling onDefer(). DeferralScreen
 * reads it once on entry and clears it so a later "tap from history" or
 * back/forward sequence doesn't show stale flags for an unrelated check.
 *
 * Phase 4 extends this with two extra slots for the lazy-synthesis ReportReader
 * flow (D-06): [pendingReport] is the marker the ReportReader top CTA sets
 * BEFORE inference runs; DeferralScreen's LaunchedEffect consumes it and
 * eventually fills [pending] with the resolved AegisResponse. On the
 * D-05 fallback path the LaunchedEffect builds the AegisResponse via
 * AegisResponseBuilder.build(report) and sets [synthesisAvailable]=false,
 * which renders a muted banner above the response.
 *
 * DrugSafe / HealthPartner producers are unaffected — they continue to set
 * [pending] directly and never touch [pendingReport].
 */
object DeferralStore {
    @Volatile
    var pending: AegisResponse? = null

    /** Phase 4 D-06 — synthesis-pending marker set by ReportReader top CTA. */
    @Volatile
    var pendingReport: PreparsedReport? = null

    /** Phase 4 D-05 — false signals the muted banner in DeferralScreen. */
    @Volatile
    var synthesisAvailable: Boolean = true

    /** Read-and-clear; returns null if nothing was staged. */
    fun consume(): AegisResponse? {
        val r = pending
        pending = null
        return r
    }
}
