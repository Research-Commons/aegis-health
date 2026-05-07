package com.aegis.health.ui.deferral

import com.aegis.health.models.AegisResponse

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
