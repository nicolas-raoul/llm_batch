package io.github.nicolasraoul.llmbatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel as MlkitGenerativeModel
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentRequest
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
class BatchService : Service() {

    companion object {
        const val ACTION_PROGRESS = "io.github.nicolasraoul.llmbatch.PROGRESS"
        const val ACTION_STOP = "io.github.nicolasraoul.llmbatch.STOP"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_IS_DONE = "is_done"
        const val EXTRA_ERROR = "error"
        const val EXTRA_FOLDER_FILE_INDEX = "folder_file_index"
        const val EXTRA_FOLDER_TOTAL_FILES = "folder_total_files"

        const val LOCAL_ML_KIT_PROMPT_API = "(local) ML Kit Prompt API"
        const val LOCAL_EDGE_AI_SDK = "(local) Edge AI SDK"
        const val LOCAL_EDGE_AI_SDK_NO_SAFETY = "(local) Edge AI SDK (no safety)"
        const val REMOTE_GEMINI = "(remote) Gemini 2.5 Flash Lite API"

        private const val USE_PREFIX_CACHING = false
        private const val INITIAL_WAIT_TIME = 100L
    }

    private var serviceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())


    private var mlkitModel: MlkitGenerativeModel? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        initMlkitGenerativeModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRun()
            return START_NOT_STICKY
        }

        val folderUriStr = intent?.getStringExtra("folderUri")
        val promptsUriStr = intent?.getStringExtra("promptsUri")
        val resultsUriStr = intent?.getStringExtra("resultsUri")
        val modelName = intent?.getStringExtra("modelName") ?: LOCAL_EDGE_AI_SDK
        val apiKey = intent?.getStringExtra("apiKey")

        if (folderUriStr != null) {
            val folderUri = Uri.parse(folderUriStr)
            val notification = buildNotification(0, 100, "Starting folder...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else {
                startForeground(1, notification)
            }

            if (serviceJob == null || serviceJob?.isActive != true) {
                serviceJob = scope.launch {
                    processFolder(folderUri, modelName, apiKey)
                    stopSelf()
                }
            }
        } else if (promptsUriStr != null && resultsUriStr != null) {
            val promptsUri = Uri.parse(promptsUriStr)
            val resultsUri = Uri.parse(resultsUriStr)

            val notification = buildNotification(0, 100, "Starting...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else {
                startForeground(1, notification)
            }

            if (serviceJob == null || serviceJob?.isActive != true) {
                serviceJob = scope.launch {
                    processPrompts(promptsUri, resultsUri, modelName, apiKey)
                    stopSelf()
                }
            }
        }

        return START_REDELIVER_INTENT
    }

    private suspend fun processFolder(folderUri: Uri, modelName: String, apiKey: String?) {
        try {
            val folder = DocumentFile.fromTreeUri(this, folderUri)
            if (folder == null) {
                broadcastProgress(0, 1, isDone = true, error = "Invalid folder")
                return
            }

            val filesToProcess = folder.listFiles().filter { it.isFile && !it.name.orEmpty().startsWith("results_") }
            val overallTotal = filesToProcess.size

            for ((fileIndex, file) in filesToProcess.withIndex()) {
                if (!kotlin.coroutines.coroutineContext.isActive) break
                val fileName = file.name.orEmpty()
                val resultsFileName = "results_$fileName"
                var resultsFile = folder.findFile(resultsFileName)
                if (resultsFile == null) {
                    resultsFile = folder.createFile("text/plain", resultsFileName)
                }

                if (resultsFile != null) {
                    processPrompts(file.uri, resultsFile.uri, modelName, apiKey, true, fileIndex + 1, overallTotal)
                }
            }

            if (kotlin.coroutines.coroutineContext.isActive) {
                updateNotification(100, 100, "Folder processing complete")
                broadcastProgress(100, 100, isDone = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            broadcastProgress(0, 1, isDone = true, error = e.message)
        } finally {
            if (modelName == LOCAL_ML_KIT_PROMPT_API) {
                mlkitModel?.clearCaches()
            }
        }
    }

    private fun stopRun() {
        serviceJob?.cancel()
        scope.launch { mlkitModel?.clearCaches() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob?.cancel()
        ModelFactory.close()
        mlkitModel?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "batch_channel",
                "Batch Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs LLM Batch Processing"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Int, total: Int, text: String): Notification {
        return NotificationCompat.Builder(this, "batch_channel")
            .setContentTitle("LLM Batch Processing")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setProgress(total, progress, false)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Int, total: Int, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, buildNotification(progress, total, text))
    }

    private fun broadcastProgress(progress: Int, total: Int, isDone: Boolean = false, error: String? = null, folderFileIndex: Int = 0, folderTotalFiles: Int = 0) {
        val intent = Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_TOTAL, total)
            putExtra(EXTRA_IS_DONE, isDone)
            putExtra(EXTRA_ERROR, error)
            putExtra(EXTRA_FOLDER_FILE_INDEX, folderFileIndex)
            putExtra(EXTRA_FOLDER_TOTAL_FILES, folderTotalFiles)
        }
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun initMlkitGenerativeModel() {
        try {
            mlkitModel = Generation.getClient()
        } catch (e: Exception) {
            mlkitModel = null
            e.printStackTrace()
        }
    }



    private suspend fun processPrompts(promptsUri: Uri, resultsUri: Uri, modelName: String, apiKey: String?, isFolder: Boolean = false, folderFileIndex: Int = 0, folderTotalFiles: Int = 0) {
        try {
            // Read all prompts and parse them immediately
            val rawPrompts = readPromptsFromFile(promptsUri)
            val prompts = rawPrompts.map { prompt ->
                val parsed = parseCsvLine(prompt)
                if (parsed.isNotEmpty()) parsed[0].replace("\\n", "\n").replace("\\r", "\r") else prompt.replace("\\n", "\n").replace("\\r", "\r")
            }
            val totalPrompts = prompts.size

            // Count existing lines in results to skip already processed ones (so we can resume)
            val alreadyProcessed = withContext(Dispatchers.IO) {
                var count = 0
                try {
                    contentResolver.openInputStream(resultsUri)?.use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream)).use { reader ->
                            while (reader.readLine() != null) count++
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
                count
            }

            val commonPrefixLength = findCommonPrefixLength(prompts)
            val commonPrefix = if (commonPrefixLength > 0) prompts[0].substring(0, commonPrefixLength) else ""

            // Must open in append mode ("wa") to resume!
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(resultsUri, "wa")?.use { fileOutputStream ->

                    if (modelName == LOCAL_EDGE_AI_SDK || modelName == LOCAL_EDGE_AI_SDK_NO_SAFETY) {
                        ModelFactory.init(applicationContext)
                        try { listFeaturesAndLog(applicationContext) } catch (e: Exception) {}
                    }

                    val geminiModel = if (modelName == REMOTE_GEMINI && apiKey != null) {
                        GenerativeModel(
                            modelName = "gemini-2.5-flash-lite",
                            apiKey = apiKey,
                            generationConfig = generationConfig {
                                temperature = 0.2f
                                topK = 16
                                maxOutputTokens = 1024
                            }
                        )
                    } else null

                    for (index in alreadyProcessed until totalPrompts) {
                        if (!kotlin.coroutines.coroutineContext.isActive) break

                        val prompt = prompts[index]
                        val progressMsg = "Processing ${index + 1}/$totalPrompts"
                        if (isFolder) {
                            updateNotification(index, totalPrompts, "File $folderFileIndex/$folderTotalFiles: ${index + 1}/$totalPrompts")
                        } else {
                            updateNotification(index, totalPrompts, progressMsg)
                        }
                        broadcastProgress(index, totalPrompts, isDone = false, error = null, folderFileIndex = folderFileIndex, folderTotalFiles = folderTotalFiles)

                        val combinedPrompt = prompt
                        val dynamicSuffix = if (commonPrefixLength > 0 && combinedPrompt.length >= commonPrefixLength) combinedPrompt.substring(commonPrefixLength) else combinedPrompt

                        val (result, timeTaken) = when (modelName) {
                            LOCAL_EDGE_AI_SDK -> realEdgeLlmCall(combinedPrompt, noSafety = false)
                            LOCAL_EDGE_AI_SDK_NO_SAFETY -> realEdgeLlmCall(combinedPrompt, noSafety = true)
                            REMOTE_GEMINI -> realGeminiApiCall(geminiModel!!, combinedPrompt)
                            LOCAL_ML_KIT_PROMPT_API -> realMlkitLlmCall(commonPrefix, dynamicSuffix)
                            else -> Pair("Error: Unknown model", 0L)
                        }

                        val singleLinePromptText = combinedPrompt.replace("\r", "\\r").replace("\n", "\\n")
                        val singleLineResult = result.replace("\r", "\\r").replace("\n", "\\n")

                        val csvRecord = listOf(
                            escapeCsvField(singleLinePromptText),
                            escapeCsvField(singleLineResult),
                            "\"$timeTaken milliseconds\""
                        ).joinToString(separator = ",") + "\n"

                        fileOutputStream.write(csvRecord.toByteArray())
                        fileOutputStream.flush()
                    }
                }
            }

            if (!isFolder && kotlin.coroutines.coroutineContext.isActive) {
                updateNotification(totalPrompts, totalPrompts, "Done")
                broadcastProgress(totalPrompts, totalPrompts, isDone = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            broadcastProgress(0, 1, isDone = true, error = e.message)
        } finally {
            if (!isFolder && modelName == LOCAL_ML_KIT_PROMPT_API) {
                mlkitModel?.clearCaches()
            }
        }
    }

    private fun escapeCsvField(data: String): String {
        return "\"" + data.replace("\"", "\"\"") + "\""
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = java.lang.StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '\"') {
                    current.append('\"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString())
                current = java.lang.StringBuilder()
            } else {
                current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private suspend fun readPromptsFromFile(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        val prompts = mutableListOf<String>()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { prompts.add(it) }
                }
            }
        }
        prompts
    }

    private suspend fun realEdgeLlmCall(prompt: String, noSafety: Boolean = false): Pair<String, Long> {
        var waitTime = INITIAL_WAIT_TIME
        while (true) {
            try {
                var responseText = ""
                val timeTaken = kotlin.system.measureTimeMillis {
                    responseText = ModelFactory.generateContent(prompt, noSafety)
                }
                waitTime = INITIAL_WAIT_TIME
                return Pair(if (responseText.isNotEmpty()) responseText else "Error: Empty response from model.", timeTaken)
            } catch (e: Exception) {
                // If it's a BUSY error, retry (though the LlmService api might throw different exceptions)
                if (e.message?.contains("BUSY") == true || e.message?.contains("17") == true) { // 17 is BUSY AI_CORE_ERROR
                    delay(waitTime)
                    waitTime *= 2
                } else {
                    return Pair("Error: ${e.javaClass.name} - ${e.message}", 0L)
                }
            }
        }
    }

    private suspend fun realGeminiApiCall(model: GenerativeModel, prompt: String): Pair<String, Long> = withContext(Dispatchers.IO) {
        return@withContext try {
            var response: com.google.ai.client.generativeai.type.GenerateContentResponse? = null
            val timeTaken = kotlin.system.measureTimeMillis {
                response = model.generateContent(prompt)
            }
            Pair(response?.text ?: "Error: Empty response from Gemini API.", timeTaken)
        } catch (e: Exception) {
            Pair("Error: ${e.message}", 0L)
        }
    }

    private suspend fun realMlkitLlmCall(prefix: String, suffix: String): Pair<String, Long> {
        var waitTime = INITIAL_WAIT_TIME
        while (true) {
            try {
                val request = if (USE_PREFIX_CACHING) {
                    generateContentRequest(TextPart(suffix)) {
                        promptPrefix = PromptPrefix(prefix)
                    }
                } else {
                    GenerateContentRequest.Builder(TextPart(prefix + suffix)).build()
                }
                var response: com.google.mlkit.genai.prompt.GenerateContentResponse? = null
                val timeTaken = kotlin.system.measureTimeMillis {
                    response = mlkitModel?.generateContent(request)
                }
                waitTime = INITIAL_WAIT_TIME
                return Pair(response?.candidates?.firstOrNull()?.text ?: "Error: Empty response from ML Kit API.", timeTaken)
            } catch (e: GenAiException) {
                if (e.errorCode == 9) {
                    delay(waitTime)
                    waitTime *= 2
                } else {
                    return Pair("Error: ML Kit API - ${e.message}", 0L)
                }
            } catch (e: Exception) {
                return Pair("Error: ${e.javaClass.name} - ${e.message}", 0L)
            }
        }
    }

    private fun findCommonPrefixLength(strings: List<String>): Int {
        if (strings.isEmpty()) return 0
        val firstString = strings[0]
        for (i in firstString.indices) {
            val charToCompare = firstString[i]
            for (j in 1 until strings.size) {
                val currentString = strings[j]
                if (i >= currentString.length || currentString[i] != charToCompare) {
                    return i
                }
            }
        }
        return firstString.length
    }
}
