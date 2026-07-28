package io.github.nicolasraoul.llmbatch

import android.content.Context
import android.util.Log
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.GenerationConfig
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.lang.reflect.Method

object ModelFactory {
    private var generativeModel: GenerativeModel? = null

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        Futures.addCallback(this, object : FutureCallback<T> {
            override fun onSuccess(result: T?) {
                if (result != null) {
                    cont.resume(result)
                } else {
                    cont.resumeWithException(NullPointerException("Future returned null"))
                }
            }
            override fun onFailure(t: Throwable) {
                cont.resumeWithException(t)
            }
        }, MoreExecutors.directExecutor())
    }

    suspend fun init(context: Context) {
        if (generativeModel != null) return
        try {
            val configBuilder = GenerationConfig.Builder()
            configBuilder.context = context
            configBuilder.temperature = 0.2f
            configBuilder.topK = 16
            configBuilder.maxOutputTokens = 256
            val config = configBuilder.build()
            generativeModel = GenerativeModel(config)
            // Call prepareInferenceEngine() via reflection to ensure it finishes startup?
            // Actually GenerativeModel natively has generateContent which prepares it.
            // Let's just create a dummy request to trigger loading.
            val method = GenerativeModel::class.java.getMethod("prepareInferenceEngine", kotlin.coroutines.Continuation::class.java)
            // It's a suspend function... wait, we don't strictly need to await prepare, generateContent handles it.
        } catch (e: Exception) {
            throw e
        }
    }
    
    suspend fun generateContent(prompt: String, noSafety: Boolean = false): String {
        val model = generativeModel ?: throw Exception("GenerativeModel not initialized")
        try {
            val contentBuilderClass = Class.forName("com.google.ai.edge.aicore.Content\$Builder")
            val contentBuilder = contentBuilderClass.getConstructor().newInstance()
            contentBuilderClass.getMethod("addText", String::class.java).invoke(contentBuilder, prompt)
            val content = contentBuilderClass.getMethod("build").invoke(contentBuilder)
            
            val contentArray = java.lang.reflect.Array.newInstance(Class.forName("com.google.ai.edge.aicore.Content"), 1)
            java.lang.reflect.Array.set(contentArray, 0, content)

            val createRequestMethod = GenerativeModel::class.java.getDeclaredMethod("createLlmRequest", contentArray.javaClass, Class.forName("com.google.android.gms.internal.aicore.zzdi"))
            createRequestMethod.isAccessible = true
            val request = createRequestMethod.invoke(model, contentArray, null)
            
            val requestClass = Class.forName("com.google.android.gms.internal.aicore.zzde")
            val builderMethod = requestClass.getMethod("zzm")
            val builder = builderMethod.invoke(null)
            val builderClass = Class.forName("com.google.android.gms.internal.aicore.zzdd")
            
            builderClass.getMethod("zza", Class.forName("java.util.List")).invoke(builder, requestClass.getMethod("zza").invoke(request))
            builderClass.getMethod("zzb", Float::class.javaPrimitiveType).invoke(builder, requestClass.getMethod("zzb").invoke(request))
            builderClass.getMethod("zzc", Int::class.javaPrimitiveType).invoke(builder, requestClass.getMethod("zzc").invoke(request))
            builderClass.getMethod("zzd", Class.forName("java.util.List")).invoke(builder, requestClass.getMethod("zzd").invoke(request))
            builderClass.getMethod("zze", Int::class.javaPrimitiveType).invoke(builder, requestClass.getMethod("zze").invoke(request))
            builderClass.getMethod("zzf", Int::class.javaPrimitiveType).invoke(builder, requestClass.getMethod("zzf").invoke(request))
            builderClass.getMethod("zzg", Int::class.javaPrimitiveType).invoke(builder, requestClass.getMethod("zzg").invoke(request))
            builderClass.getMethod("zzh", Class.forName("com.google.android.gms.internal.aicore.zzdi")).invoke(builder, requestClass.getMethod("zzh").invoke(request))
            
            // BYPASS SAFETY FILTER
            builderClass.getMethod("zzi", Boolean::class.javaPrimitiveType).invoke(builder, !noSafety)
            
            builderClass.getMethod("zzj", Int::class.javaPrimitiveType).invoke(builder, requestClass.getMethod("zzj").invoke(request))
            builderClass.getMethod("zzk", Int::class.javaPrimitiveType).invoke(builder, requestClass.getMethod("zzk").invoke(request))
            
            val customRequest = builderClass.getMethod("zzl").invoke(builder)
            
            val llmServiceField = GenerativeModel::class.java.getDeclaredField("llmService")
            llmServiceField.isAccessible = true
            var llmService = llmServiceField.get(model)
            
            if (llmService == null || noSafety) {
                try {
                    val createClientMethod = GenerativeModel::class.java.getDeclaredMethod("createAiCoreClient")
                    createClientMethod.isAccessible = true
                    val client = createClientMethod.invoke(model)
                    
                    val listFeaturesMethod = client::class.java.getDeclaredMethod("zza")
                    listFeaturesMethod.isAccessible = true
                    val futureFeatures = listFeaturesMethod.invoke(client) as ListenableFuture<*>
                    val features = futureFeatures.await() as List<*>
                    
                    var targetFeature: Any? = null
                    
                    for (f in features) {
                        if (f == null) continue
                        val id = f::class.java.getDeclaredMethod("zze").invoke(f) as Int
                        if (id == 19) { // LLM_IT_XS_GEM
                            targetFeature = f
                            break
                        }
                    }
                    if (targetFeature == null) {
                        for (f in features) {
                            if (f == null) continue
                            val id = f::class.java.getDeclaredMethod("zze").invoke(f) as Int
                            if (id == 1) { // LLM_CPU_GEM
                                targetFeature = f
                                break
                            }
                        }
                    }
                    
                    if (targetFeature != null) {
                        val downloadMethod = GenerativeModel::class.java.getDeclaredMethod("createAiCoreDownloadCallback", Class.forName("com.google.ai.edge.aicore.DownloadCallback"))
                        downloadMethod.isAccessible = true
                        val mockCallback = downloadMethod.invoke(model, null)
                        
                        val zzlMethod = Class.forName("com.google.android.gms.internal.aicore.zzdh").getDeclaredMethod(
                            "zzl", 
                            Class.forName("com.google.android.gms.internal.aicore.zzar"), 
                            Class.forName("com.google.android.gms.internal.aicore.zzaw"), 
                            Class.forName("com.google.android.gms.internal.aicore.zzbb")
                        )
                        zzlMethod.isAccessible = true
                        
                        val newService = zzlMethod.invoke(null, client, targetFeature, mockCallback)
                        llmServiceField.set(model, newService)
                        llmService = newService
                    }
                } catch (e: Exception) {
                    Log.e("LLM_BATCH", "Failed to init custom feature", e)
                }
            }
            
            if (llmService == null) {
                try {
                    model.generateContent("hello")
                } catch (e: Exception) {
                    Log.e("LLM_BATCH", "Dummy init failed", e)
                }
            }
            val llmServiceAfter = llmServiceField.get(model) ?: throw Exception("llmService is null")
            
            val zzcMethod = Class.forName("com.google.android.gms.internal.aicore.zzbg").getDeclaredMethod("zzc", Any::class.java)
            zzcMethod.isAccessible = true
            val future = zzcMethod.invoke(llmServiceAfter, customRequest) as ListenableFuture<*>
            
            val rawResponse = future.await()
            
            val createCandidateMethod = GenerativeModel::class.java.getDeclaredMethod("createCandidateList", Class.forName("com.google.android.gms.internal.aicore.zzdf"))
            createCandidateMethod.isAccessible = true
            val candidates = createCandidateMethod.invoke(model, rawResponse) as List<*>
            
            if (candidates.isEmpty()) return ""
            val cand = candidates[0]
            val contentField = cand!!::class.java.getDeclaredMethod("getContent")
            val returnedContent = contentField.invoke(cand)
            val partsMethod = returnedContent!!::class.java.getDeclaredMethod("getParts")
            val partsArray = partsMethod.invoke(returnedContent) as List<*>
            if (partsArray.isEmpty()) return ""
            val firstPart = partsArray[0]
            val textMethod = firstPart!!::class.java.getDeclaredMethod("getText")
            return textMethod.invoke(firstPart) as String
        } catch (e: Exception) {
            throw e
        }
    }
    
    fun close() {
        generativeModel?.close()
        generativeModel = null
    }
}
