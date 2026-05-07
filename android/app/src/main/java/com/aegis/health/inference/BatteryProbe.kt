package com.aegis.health.inference

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.aegis.health.AegisApp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileWriter
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Per-call battery instrumentation for the inference path.
 *
 * Each `around()` block snapshots BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
 * (µAh, monotonic over a discharge) plus voltage and temperature from the
 * sticky ACTION_BATTERY_CHANGED intent at start and end, and writes one JSONL
 * line to /sdcard/Android/data/com.aegis.health/files/battery_log.jsonl.
 *
 * Disabled by default. Enable from BatteryBenchScreen before a measurement run.
 *
 * Caveats baked into the record so analysis can filter:
 *   - plugged != 0 → charging masks discharge; analysis must drop these rows
 *   - charge-counter resolution on Samsung is ~1 mAh; per-call ΔµAh for short
 *     spans (~5 s) is in the noise floor. Trust per-token / per-turn aggregates,
 *     not single-query mWh.
 *   - voltage sag during long calls is partially absorbed by reporting mWh
 *     against (V_start + V_end) / 2.
 */
object BatteryProbe {

    private const val TAG = "BatteryProbe"
    private const val LOG_FILE = "battery_log.jsonl"
    private const val NO_SPAN = "_disabled_"

    @Volatile
    var enabled: Boolean = false

    private val ioMutex = Mutex()
    private val json = Json { encodeDefaults = true }

    data class Sample(
        val epochMs: Long,
        val chargeUah: Long,
        val currentNowUa: Long,
        val voltageMv: Int,
        val tempDeciC: Int,
        val plugged: Int,
    )

    class SpanContext internal constructor(
        val spanId: String,
        val parentSpanId: String?,
    ) {
        internal val metadata = mutableMapOf<String, JsonElement>()

        fun put(key: String, value: Number) {
            metadata[key] = JsonPrimitive(value)
        }

        fun put(key: String, value: String) {
            metadata[key] = JsonPrimitive(value)
        }

        fun put(key: String, value: Boolean) {
            metadata[key] = JsonPrimitive(value)
        }
    }

    private class SpanElement(val spanId: String) :
        AbstractCoroutineContextElement(SpanElement) {
        companion object Key : CoroutineContext.Key<SpanElement>
    }

    suspend fun <T> around(
        label: String,
        initialMetadata: Map<String, JsonElement> = emptyMap(),
        block: suspend (SpanContext) -> T,
    ): T {
        if (!enabled) {
            return block(SpanContext(NO_SPAN, null))
        }

        val parentSpanId = coroutineContext[SpanElement.Key]?.spanId
        val spanId = UUID.randomUUID().toString().substring(0, 8)
        val before = snapshot()
        val context = SpanContext(spanId, parentSpanId).apply {
            metadata.putAll(initialMetadata)
        }

        return try {
            withContext(SpanElement(spanId)) {
                block(context)
            }
        } finally {
            val after = snapshot()
            runCatching { emit(label, context, before, after) }
                .onFailure { Log.w(TAG, "emit failed for label=$label", it) }
        }
    }

    private fun snapshot(): Sample {
        val app = AegisApp.instance
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val chargeUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val currentNowUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val sticky: Intent? = app.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val tempDeciC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        return Sample(
            epochMs = System.currentTimeMillis(),
            chargeUah = chargeUah,
            currentNowUa = currentNowUa,
            voltageMv = voltageMv,
            tempDeciC = tempDeciC,
            plugged = plugged,
        )
    }

    private suspend fun emit(
        label: String,
        ctx: SpanContext,
        before: Sample,
        after: Sample,
    ) {
        // CHARGE_COUNTER counts down while discharging, so before > after.
        // Report delta as a positive µAh consumed value.
        val deltaUah = before.chargeUah - after.chargeUah
        val avgVoltageV = ((before.voltageMv + after.voltageMv) / 2.0) / 1000.0
        val mwh = (deltaUah / 1000.0) * avgVoltageV

        val record = buildJsonObject {
            put("schema", JsonPrimitive(1))
            put("ts_start", JsonPrimitive(before.epochMs))
            put("ts_end", JsonPrimitive(after.epochMs))
            put("duration_ms", JsonPrimitive(after.epochMs - before.epochMs))
            put("label", JsonPrimitive(label))
            put("span_id", JsonPrimitive(ctx.spanId))
            put("parent_span_id", ctx.parentSpanId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("plugged", JsonPrimitive(maxOf(before.plugged, after.plugged)))
            put("charge_uah_start", JsonPrimitive(before.chargeUah))
            put("charge_uah_end", JsonPrimitive(after.chargeUah))
            put("charge_uah_delta", JsonPrimitive(deltaUah))
            put("current_now_ua_start", JsonPrimitive(before.currentNowUa))
            put("current_now_ua_end", JsonPrimitive(after.currentNowUa))
            put("voltage_v_avg", JsonPrimitive(avgVoltageV))
            put("temp_c_start", JsonPrimitive(before.tempDeciC / 10.0))
            put("temp_c_end", JsonPrimitive(after.tempDeciC / 10.0))
            put("mwh", JsonPrimitive(mwh))
            put("metadata", JsonObject(ctx.metadata))
        }

        val file = logFile() ?: return
        ioMutex.withLock {
            FileWriter(file, true).use { writer ->
                writer.appendLine(json.encodeToString(JsonElement.serializer(), record))
            }
        }
    }

    fun reset() {
        runCatching { logFile()?.delete() }
            .onFailure { Log.w(TAG, "reset failed", it) }
    }

    fun logPath(): String? = logFile()?.absolutePath

    private fun logFile(): File? {
        val dir = AegisApp.instance.getExternalFilesDir(null) ?: return null
        return File(dir, LOG_FILE)
    }
}
