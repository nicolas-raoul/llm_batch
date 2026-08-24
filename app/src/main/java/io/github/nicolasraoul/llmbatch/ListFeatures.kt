package io.github.nicolasraoul.llmbatch

import android.content.Context
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors

object ListFeatures {
    suspend fun dumpFeatures(context: Context) {
        try {
            val configBuilder = com.google.ai.edge.aicore.GenerationConfig.Builder()
            configBuilder.context = context
            val model = com.google.ai.edge.aicore.GenerativeModel(configBuilder.build())
            val createClientMethod = com.google.ai.edge.aicore.GenerativeModel::class.java.getDeclaredMethod("createAiCoreClient")
            createClientMethod.isAccessible = true
            val client = createClientMethod.invoke(model)
            
            val listFeaturesMethod = client::class.java.getDeclaredMethod("zza")
            val futureFeatures = listFeaturesMethod.invoke(client) as ListenableFuture<*>
            
            val features = suspendCancellableCoroutine<List<*>> { cont ->
                Futures.addCallback(futureFeatures, object : FutureCallback<Any?> {
                    override fun onSuccess(result: Any?) {
                        cont.resume(result as List<*>)
                    }
                    override fun onFailure(t: Throwable) {
                        cont.resumeWithException(t)
                    }
                }, MoreExecutors.directExecutor())
            }
            
            for (f in features) {
                if (f == null) continue
                val id = f::class.java.getDeclaredMethod("zze").invoke(f) as Int
                Log.e("LLM_BATCH_FEATURES", "Feature present: id=$id")
            }
        } catch (e: Exception) {
            Log.e("LLM_BATCH_FEATURES", "Failed to dump features", e)
        }
    }
}
