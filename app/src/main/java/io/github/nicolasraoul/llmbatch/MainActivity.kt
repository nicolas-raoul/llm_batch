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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel as EdgeGenerativeModel
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig as cloudGenerationConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.nicolasraoul.llmbatch.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    companion object {
        private const val INITIAL_WAIT_TIME = 100L
    }

    private lateinit var binding: ActivityMainBinding
    private var promptsFileUri: Uri? = null
    private var resultFile: File? = null
    private var edgeModel: EdgeGenerativeModel? = null

    // Activity result launcher for the file picker
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            promptsFileUri = it
            val fileName = getFileName(it)
            binding.selectFileButton.text = fileName
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
    }

    override fun onDestroy() {
        super.onDestroy()
        edgeModel?.close()
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
        val models = listOf("Local Edge AI SDK", "Remote Gemini API")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modelSpinner.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.selectFileButton.setOnClickListener {
            filePickerLauncher.launch("text/plain")
        }

        binding.runBatchButton.setOnClickListener {
            handleRunBatch()
        }

        binding.resultsLink.setOnClickListener {
            openResultsFile()
        }
    }

    private fun handleRunBatch() {
        if (promptsFileUri == null) {
            Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
            return
        }

        val selectedModel = binding.modelSpinner.selectedItem.toString()

        lifecycleScope.launch {
            setUiState(isLoading = true)
            if (selectedModel == "Remote Gemini API") {
                setUiState(isLoading = false) // Hide progress bar while dialog is shown
                showApiKeyDialog()
            } else {
                if (edgeModel != null) {
                    processPrompts(selectedModel)
                } else {
                    setUiState(isLoading = false)
                    showAiCoreSetupDialog()
                }
            }
        }
    }

    private fun showApiKeyDialog() {
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
                        processPrompts("Remote Gemini API", apiKey)
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

    private suspend fun processPrompts(modelName: String, apiKey: String? = null) {
        setUiState(isLoading = true)

        try {
            val prompts = readPromptsFromFile(promptsFileUri!!)
            val totalPrompts = prompts.size
            val outputFileName = getFileName(promptsFileUri!!).replace(".txt", "") + "_results.txt"
            resultFile = File(getExternalFilesDir(null), outputFileName)
            val fileOutputStream = FileOutputStream(resultFile)

            // Create Gemini API model if using remote
            val geminiModel = if (modelName == "Remote Gemini API" && apiKey != null) {
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

            prompts.forEachIndexed { index, prompt ->
                binding.progressText.text =
                    getString(R.string.processing_progress, index + 1, totalPrompts)

                val (result, timeTaken) = if (modelName == "Local Edge AI SDK") {
                    realEdgeLlmCall(prompt)
                } else {
                    realGeminiApiCall(geminiModel!!, prompt)
                }

                val csvRecord = listOf(
                    escapeCsvField(prompt),
                    escapeCsvField(result),
                    "\"$timeTaken milliseconds\""
                ).joinToString(separator = ",") + "\n"
                Log.d("LLM_BATCH_CSV", csvRecord.trim())
                fileOutputStream.write(csvRecord.toByteArray())
            }
            fileOutputStream.close()
            setUiState(isLoading = false, resultsReady = true)
        } catch (e: Exception) {
            e.printStackTrace()
            setUiState(isLoading = false)
            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun setUiState(isLoading: Boolean, resultsReady: Boolean = false) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.progressText.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.runBatchButton.isEnabled = !isLoading
        binding.selectFileButton.isEnabled = !isLoading
        binding.resultsLink.visibility = if (resultsReady) View.VISIBLE else View.GONE
        if (resultsReady) {
            binding.progressText.visibility = View.GONE
        }
    }

    private fun openResultsFile() {
        resultFile?.let { file ->
            val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", file)
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
}