package io.github.nicolasraoul.llmbatch

import android.util.Log

fun testAiCoreClientExists() {
    try {
        val clientClass = Class.forName("com.google.android.apps.aicore.client.api.AiCoreClient")
        Log.i("LLM_BATCH_TEST", "AiCoreClient exists!")
    } catch (e: Exception) {
        Log.e("LLM_BATCH_TEST", "AiCoreClient not found", e)
    }
}
