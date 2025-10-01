package io.github.nicolasraoul.llmbatch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
import android.text.method.LinkMovementMethod
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
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.nicolasraoul.llmbatch.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var promptsFileUri: Uri? = null
    private var resultFile: File? = null
    private var model: GenerativeModel? = null

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
        initGenerativeModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        model?.close()
    }

    private fun initGenerativeModel() {
        try {
            model = GenerativeModel(
                generationConfig {
                    context = applicationContext
                    temperature = 0.2f
                    topK = 16
                    maxOutputTokens = 10000
                }
            )
        } catch (e: Exception) {
            // Model initialization can fail if AI Core is not available.
            model = null
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
                if (model != null) {
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
                val apiKey = input.text.toString()
                if (apiKey.isNotBlank()) {
                    lifecycleScope.launch {
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
            val outputFileName = getFileName(promptsFileUri!!).replace(".txt", "") + "_results.txt"
            resultFile = File(getExternalFilesDir(null), outputFileName)
            val fileOutputStream = FileOutputStream(resultFile)

            prompts.forEach { prompt ->
                val result = if (modelName == "Local Edge AI SDK") {
                    realLlmCall(prompt)
                } else {
                    // Placeholder for remote call logic if it were real
                    "Gemini (key: ${apiKey?.take(4)}...) response for '$prompt'"
                }
                val formattedResult = if (result.startsWith("Error:")) {
                    "Prompt: $prompt -> $result\n"
                } else {
                    "Prompt: $prompt -> Result: $result\n"
                }
                fileOutputStream.write(formattedResult.toByteArray())
            }
            fileOutputStream.close()
            setUiState(isLoading = false, resultsReady = true)
        } catch (e: Exception) {
            e.printStackTrace()
            setUiState(isLoading = false)
            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    private suspend fun realLlmCall(prompt: String): String {
        return try {
            val response = model?.generateContent(prompt)
            response?.text ?: "Error: Empty response from model."
        } catch (e: GenerativeAIException) {
            e.printStackTrace()
            "Error: ${e.message}"
        }
    }

    private fun setUiState(isLoading: Boolean, resultsReady: Boolean = false) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.runBatchButton.isEnabled = !isLoading
        binding.selectFileButton.isEnabled = !isLoading
        binding.resultsLink.visibility = if (resultsReady) View.VISIBLE else View.GONE
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