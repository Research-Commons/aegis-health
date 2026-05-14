package com.aegis.health

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SAFETY-05 — manifest permission audit gate for the ReportReader milestone.
 *
 * Phase 3 mandate (ROADMAP §Phase 3 SC-4): "adb shell dumpsys package
 * com.aegis.health | grep permission audit produces output identical to
 * the pre-milestone baseline — zero new permissions added, offline
 * guarantee preserved".
 *
 * Implementation: read the merged-manifest <uses-permission> set at runtime
 * via PackageManager.GET_PERMISSIONS, load the committed baseline from
 * androidTest assets, normalize both (strip comments + blanks, sort),
 * assertEquals.
 *
 * Why this works without `adb shell`: PackageManager.getPackageInfo is
 * available to the instrumentation process and returns the same data
 * dumpsys would print. No shell access needed. Portable across CI / dev.
 *
 * Updating the baseline (intentional permission addition):
 *   1. Update AndroidManifest.xml with the new permission.
 *   2. Update android/app/src/androidTest/assets/permission_baseline.txt
 *      with a sorted insertion of the new permission name.
 *   3. Document the addition in REGULATORY.md (Phase 5 audit) and the
 *      milestone STATE.md.
 *
 * Run individually (memory pin feedback_gradle_connected_androidtest_filter):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.aegis.health.PermissionAuditTest
 */
@RunWith(AndroidJUnit4::class)
class PermissionAuditTest {

    @Test
    fun manifest_permission_set_matches_baseline() {
        val inst = InstrumentationRegistry.getInstrumentation()

        // (1) Live permission set — read from PackageManager.
        val targetContext = inst.targetContext
        val pm: PackageManager = targetContext.packageManager
        val info = pm.getPackageInfo(targetContext.packageName, PackageManager.GET_PERMISSIONS)
        val live: List<String> = (info.requestedPermissions ?: emptyArray())
            .toList()
            .sorted()

        // (2) Baseline — read from androidTest assets. The test process has
        // its own context distinct from targetContext; assets live there.
        val testContext = inst.context
        val baselineRaw = testContext.assets
            .open("permission_baseline.txt")
            .bufferedReader()
            .use { it.readText() }
        val baseline: List<String> = baselineRaw
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .sorted()
            .toList()

        // (3) Assert equality. If this fails, either:
        //   - A new permission was added intentionally (update the baseline
        //     file alongside the manifest), OR
        //   - A transitive dependency injected a permission AGP did not
        //     strip (add tools:node="remove" in AndroidManifest.xml).
        assertEquals(
            "SAFETY-05 permission drift detected. " +
                "Merged manifest permissions: $live. " +
                "Baseline: $baseline. " +
                "See PermissionAuditTest KDoc for the update procedure.",
            baseline,
            live,
        )
    }
}
