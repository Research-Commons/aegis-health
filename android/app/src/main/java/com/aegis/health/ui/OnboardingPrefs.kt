package com.aegis.health.ui

import android.content.Context

/**
 * SharedPreferences-backed flag controlling first-run Onboarding screen.
 * Toggled by `OnboardingScreen.onDone`; readers should call [isFirstRun]
 * once at composition (it's cheap; no need for Flow).
 */
object OnboardingPrefs {
    private const val PREFS = "aegis_onboarding"
    private const val KEY_COMPLETED = "completed_v1"

    fun isFirstRun(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun markComplete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }
}
