package io.github.nicolasraoul.llmbatch

import android.content.Context
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel as EdgeGenerativeModel

object ModelFactory {
    fun getEdgeGenerativeModel(context: Context): EdgeGenerativeModel? {
        return try {
            EdgeGenerativeModel(
                com.google.ai.edge.aicore.generationConfig {
                    this.context = context
                    temperature = 0.2f
                    topK = 16
                    maxOutputTokens = 256
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
