package io.github.nicolasraoul.llmbatch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel as EdgeGenerativeModel
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig as cloudGenerationConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel as MlkitGenerativeModel
import io.github.nicolasraoul.llmbatch.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

private const val LOCAL_ML_KIT_PROMPT_API = "(local) ML Kit Prompt API"
private const val LOCAL_EDGE_AI_SDK = "(local) Edge AI SDK"
private const val REMOTE_GEMINI = "(remote) Gemini 2.5 Flash Lite API"

class MainActivity : AppCompatActivity() {

    companion object {
        private const val INITIAL_WAIT_TIME = 100L
    }

    private lateinit var binding: ActivityMainBinding
    private var promptsFileUri: Uri? = null
    private var resultsFileUri: Uri? = null
    private var edgeModel: EdgeGenerativeModel? = null
    private var mlkitModel: MlkitGenerativeModel? = null
    @Volatile
    private var isProcessing = false

    // Activity result launcher for picking the prompts file
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            promptsFileUri = it
            val fileName = getFileName(it)
            binding.selectFileButton.text = fileName
        }
    }

    // Activity result launcher for creating the results file
    private val fileCreatorLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri?.let {
            resultsFileUri = it
            val selectedModel = binding.modelSpinner.selectedItem.toString()
            if (selectedModel == REMOTE_GEMINI) {
                showApiKeyDialog(it)
            } else {
                lifecycleScope.launch {
                    processPrompts(selectedModel, outputUri = it)
                }
            }
        }
    }

    // Activity result launcher for creating the sample prompts file
    private val sampleFileCreatorLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri?.let {
            createSamplePromptsFile(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupSpinner()
        setupClickListeners()
        initEdgeGenerativeModel()
        initMlkitGenerativeModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        edgeModel?.close()
        mlkitModel?.close()
    }

    private fun initMlkitGenerativeModel() {
        try {
            mlkitModel = Generation.getClient()
        } catch (e: Exception) {
            mlkitModel = null
            e.printStackTrace()
        }
    }

    private fun initEdgeGenerativeModel() {
        try {
            edgeModel = EdgeGenerativeModel(
                com.google.ai.edge.aicore.generationConfig {
                    context = applicationContext
                    temperature = 0.2f
                    topK = 16
                    maxOutputTokens = 20
                }
            )
        } catch (e: Exception) {
            // Model initialization can fail if AI Core is not available.
            edgeModel = null
            e.printStackTrace()
        }
    }

    private fun setupSpinner() {
        val models = listOf(LOCAL_ML_KIT_PROMPT_API, LOCAL_EDGE_AI_SDK, REMOTE_GEMINI)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modelSpinner.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.createSampleButton.setOnClickListener {
            Toast.makeText(this, "Where do you want me to create the sample file?", Toast.LENGTH_SHORT).show()
            sampleFileCreatorLauncher.launch("sample_prompts.txt")
        }

        binding.selectFileButton.setOnClickListener {
            filePickerLauncher.launch("text/plain")
        }

        binding.runBatchButton.setOnClickListener {
            handleRunBatch()
        }

        binding.stopButton.setOnClickListener {
            isProcessing = false
        }

        binding.resultsLink.setOnClickListener {
            openResultsFile()
        }
    }

    private fun createSamplePromptsFile(uri: Uri) {
        val samplePrompts = """
            Why is the sky blue?
            How to implement edge-to-edge on Android?
        """.trimIndent()
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(samplePrompts.toByteArray())
            }
            Toast.makeText(this, "Sample file created successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error creating sample file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleRunBatch() {
        if (promptsFileUri == null) {
            Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Where do you want me to generate the results file?", Toast.LENGTH_SHORT).show()
        val outputFileName = "results_" + getFileName(promptsFileUri!!)
        fileCreatorLauncher.launch(outputFileName)
    }

    private fun showApiKeyDialog(outputUri: Uri) {
        val input = EditText(this).apply {
            hint = getString(R.string.api_key)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.gemini_api_key_title))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val apiKey = input.text.toString().trim()
                if (apiKey.isNotBlank()) {
                    lifecycleScope.launch {
                        setUiState(isLoading = true)
                        processPrompts(REMOTE_GEMINI, apiKey, outputUri)
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAiCoreSetupDialog() {
        val messageView = TextView(this).apply {
            text = Html.fromHtml(getString(R.string.aicore_setup_instructions), Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(48, 16, 48, 16)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.aicore_setup_title))
            .setView(messageView)
            .setPositiveButton(getString(R.string.retry)) { _, _ ->
                handleRunBatch() // Retry the whole process
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private suspend fun processPrompts(modelName: String, apiKey: String? = null, outputUri: Uri) {
        setUiState(isLoading = true)

        try {
            val prompts = readPromptsFromFile(promptsFileUri!!)
            val totalPrompts = prompts.size

            // Prefix Caching: Find the common prefix
            val commonPrefixLength = findCommonPrefixLength(prompts)
            val commonPrefix = if (commonPrefixLength > 0) prompts[0].substring(0, commonPrefixLength) else ""

            contentResolver.openOutputStream(outputUri)?.use { fileOutputStream ->

                // Create Gemini API model if using remote
                val geminiModel = if (modelName == REMOTE_GEMINI && apiKey != null) {
                    GenerativeModel(
                        modelName = "gemini-2.5-flash-lite",
                        apiKey = apiKey,
                        generationConfig = cloudGenerationConfig {
                            temperature = 0.2f
                            topK = 16
                            maxOutputTokens = 1024
                        }
                    )
                } else null

                for ((index, prompt) in prompts.withIndex()) {
                    if (!isProcessing) {
                        break
                    }
                    binding.progressText.text =
                        getString(R.string.processing_progress, index + 1, totalPrompts)

                    // Prefix Caching: Separate prefix and suffix
                    val dynamicSuffix = if (commonPrefixLength > 0) prompt.substring(commonPrefixLength) else prompt

                    val (result, timeTaken) = when (modelName) {
                        LOCAL_EDGE_AI_SDK -> realEdgeLlmCall(prompt)
                        REMOTE_GEMINI -> realGeminiApiCall(geminiModel!!, prompt)
                        LOCAL_ML_KIT_PROMPT_API -> realMlkitLlmCall(commonPrefix, dynamicSuffix)
                        else -> Pair("Error: Unknown model", 0L)
                    }

                    val csvRecord = listOf(
                        escapeCsvField(prompt),
                        escapeCsvField(result),
                        "\"$timeTaken milliseconds\""
                    ).joinToString(separator = ",") + "\n"
                    Log.d("LLM_BATCH_CSV", csvRecord.trim())
                    fileOutputStream.write(csvRecord.toByteArray())
                }
            }
            setUiState(isLoading = false, resultsReady = true)
        } catch (e: Exception) {
            e.printStackTrace()
            setUiState(isLoading = false)
            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            if (modelName == LOCAL_ML_KIT_PROMPT_API) {
                mlkitModel?.clearCaches()
                // Using withContext to show Toast on the main thread from a coroutine
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Prefix cache cleared.", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun escapeCsvField(data: String): String {
        val withEscapedQuotes = data.replace("\"", "\"\"")
        val withEscapedNewlines = withEscapedQuotes.replace("\n", "\\n")
        return "\"$withEscapedNewlines\""
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

    private suspend fun realEdgeLlmCall(prompt: String): Pair<String, Long> {
        var waitTime = INITIAL_WAIT_TIME
        while (true) {
            try {
                var response: com.google.ai.edge.aicore.GenerateContentResponse?
                val timeTaken = kotlin.system.measureTimeMillis {
                    response = edgeModel?.generateContent(prompt)
                }
                waitTime = INITIAL_WAIT_TIME // Reset wait time on success
                return Pair(response?.text ?: "Error: Empty response from model.", timeTaken)
            } catch (e: GenerativeAIException) {
                if (e.errorCode == GenerativeAIException.ErrorCode.BUSY) {
                    delay(waitTime)
                    waitTime *= 2
                } else {
                    e.printStackTrace()
                    return Pair("Error: ${e.message}", 0L)
                }
            }
        }
    }

    private suspend fun realGeminiApiCall(model: GenerativeModel, prompt: String): Pair<String, Long> = withContext(Dispatchers.IO) {
        return@withContext try {
            var response: com.google.ai.client.generativeai.type.GenerateContentResponse?
            val timeTaken = kotlin.system.measureTimeMillis {
                response = model.generateContent(prompt)
            }
            Pair(response?.text ?: "Error: Empty response from Gemini API.", timeTaken)
        } catch (e: com.google.ai.client.generativeai.type.GoogleGenerativeAIException) {
            e.printStackTrace()
            Pair("Error: Gemini API - ${e.message}", 0L)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair("Error: ${e.javaClass.simpleName} - ${e.message}", 0L)
        }
    }

    private suspend fun realMlkitLlmCall(prefix: String, suffix: String): Pair<String, Long> {
        var waitTime = INITIAL_WAIT_TIME
        while (true) {
            try {
                val request = com.google.mlkit.genai.prompt.generateContent {
                    promptPrefix = prefix
                    prompt(suffix)
                }
                var response: com.google.mlkit.genai.prompt.GenerateContentResponse?
                val timeTaken = kotlin.system.measureTimeMillis {
                    response = mlkitModel?.generateContent(request)
                }
                waitTime = INITIAL_WAIT_TIME // Reset wait time on success
                return Pair(
                    response?.candidates?.firstOrNull()?.text
                        ?: "Error: Empty response from ML Kit API.", timeTaken
                )
            } catch (e: GenAiException) {
                if (e.errorCode == 9) {
                    delay(waitTime)
                    waitTime *= 2
                } else {
                    e.printStackTrace()
                    return Pair("Error: ML Kit API - ${e.message}", 0L)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return Pair("Error: ML Kit API - ${e.message}", 0L)
            }
        }
    }

    private fun setUiState(isLoading: Boolean, resultsReady: Boolean = false) {
        isProcessing = isLoading
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.progressText.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.runBatchButton.visibility = if (isLoading) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.selectFileButton.isEnabled = !isLoading
        binding.resultsLink.visibility = if (resultsReady) View.VISIBLE else View.GONE
        if (resultsReady) {
            binding.progressText.visibility = View.GONE
        }
    }

    private fun openResultsFile() {
        resultsFileUri?.let { uri ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No app found to open text files.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex != -1) {
                        result = it.getString(columnIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }
        return result ?: "prompts.txt"
    }

    /**
     * Finds the length of the longest common prefix among a list of strings
     * using the efficient vertical scanning method.
     *
     * @param strings The list of strings to check.
     * @return The length of the longest common prefix.
     */
    private fun findCommonPrefixLength(strings: List<String>): Int {
        // If the list is empty, there is no prefix.
        if (strings.isEmpty()) {
            return 0
        }

        // Use the first string as the reference.
        val firstString = strings[0]

        // Iterate through each character index of the first string.
        for (i in firstString.indices) {
            val charToCompare = firstString[i]

            // Check this character against all other strings in the list.
            // We start from j=1 since strings[0] is our reference.
            for (j in 1 until strings.size) {
                val currentString = strings[j]

                // If the other string is shorter OR the character doesn't match,
                // we've found the end of the common prefix.
                if (i >= currentString.length || currentString[i] != charToCompare) {
                    // The length is the current index 'i'.
                    return i
                }
            }
        }

        // If the loop completes, the entire first string is the common prefix.
        return firstString.length
    }
}