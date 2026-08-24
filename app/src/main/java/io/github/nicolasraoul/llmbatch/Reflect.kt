package io.github.nicolasraoul.llmbatch

import com.google.ai.edge.aicore.GenerativeModel
import android.util.Log

fun testReflect() {
    val methods = GenerativeModel::class.java.declaredMethods
    for (m in methods) {
        Log.i("LLM_BATCH_REFLECT", "Method: \${m.name}")
    }
}
