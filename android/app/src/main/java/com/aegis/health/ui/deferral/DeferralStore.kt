package com.aegis.health.ui.deferral

import com.aegis.health.models.AegisResponse

/**
 * Hands the latest AegisResponse to DeferralScreen across navigation.
 *
 * NavController argument passing doesn't fit here — AegisResponse is a
 * @Serializable graph and we'd be JSON-encoding it onto the back stack
 * just to get it across one hop. The DrugSafe / HealthPartner / ReportReader
 * screens that trigger the deferral always run on the same process as the
 * deferral screen, so a volatile singleton is sufficient.
 *
 * Producers set [pending] right before calling onDefer(). DeferralScreen
 * reads it once on entry and `consume()` reads-and-clears so a later
 * "tap from history" or back/forward sequence doesn't show stale flags
 * for an unrelated check.
 *
 * Plan 07-04 D-02a closure: the Phase 4 D-06 lazy-synthesis ReportReader
 * path is gone. ReportReaderScreen now runs `runReportReaderFastPath`
 * directly (mirroring DrugSafeScreen.kt:184-207) and sets [pending] to the
 * resolved response — or to a Phase-3-shape fallback envelope on synthesis
 * failure — before invoking onDefer(). The prior `pendingReport` marker
 * field and `synthesisAvailable` banner flag (Phase 4 D-05/D-06) are
 * deleted; all four DeferralScreen consumer sites and the single
 * ReportReaderScreen write site have been removed.
 */
object DeferralStore {
    @Volatile
    var pending: AegisResponse? = null

    /** Read-and-clear; returns null if nothing was staged. */
    fun consume(): AegisResponse? {
        val r = pending
        pending = null
        return r
    }
}
