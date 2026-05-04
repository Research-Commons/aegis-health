package com.aegis.health.inference

import android.content.Context

/**
 * Common contract for on-device LLM backends. ToolDispatcher drives the
 * agentic loop against this interface so the dispatcher and UI layer don't
 * depend on a specific runtime.
 */
interface InferenceEngine {
    val isReady: Boolean

    /** Load model weights. Safe to call repeatedly; no-op once initialized. */
    suspend fun initialize(context: Context)

    /** Reset KV cache and seed the mode-specific system prompt. */
    suspend fun startConversation(mode: String)

    /**
     * Send [userTurn] as the next user message and return the full response.
     * Must honor coroutine cancellation — if the caller cancels, stop decoding.
     */
    suspend fun inferSync(userTurn: String): String
}
