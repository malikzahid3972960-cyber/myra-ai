package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ai.JarvisAiService
import com.example.data.ai.resolveGeminiApiKey
import com.example.data.local.AppDatabase
import com.example.data.model.WebsiteProject
import com.example.data.repository.JarvisRepository
import com.example.data.repository.PreferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface WebsiteGenState {
    data object Idle : WebsiteGenState
    data object Generating : WebsiteGenState
    data class Ready(val summary: String) : WebsiteGenState
    data class Error(val message: String) : WebsiteGenState
}

class WebsiteCreatorViewModel(
    private val repository: JarvisRepository,
    private val aiService: JarvisAiService,
    private val dataStore: PreferencesDataStore,
    private val apiKeyProvider: () -> String = { resolveGeminiApiKey() }
) : ViewModel() {

    private val _businessName = MutableStateFlow("")
    val businessName: StateFlow<String> = _businessName.asStateFlow()

    private val _industry = MutableStateFlow("")
    val industry: StateFlow<String> = _industry.asStateFlow()

    private val _requirements = MutableStateFlow("")
    val requirements: StateFlow<String> = _requirements.asStateFlow()

    private val _colorPalette = MutableStateFlow("Midnight Cyan & Violet")
    val colorPalette: StateFlow<String> = _colorPalette.asStateFlow()

    private val _activeProjectId = MutableStateFlow("")
    val activeProjectId: StateFlow<String> = _activeProjectId.asStateFlow()

    private val _htmlCode = MutableStateFlow("")
    val htmlCode: StateFlow<String> = _htmlCode.asStateFlow()

    private val _cssCode = MutableStateFlow("")
    val cssCode: StateFlow<String> = _cssCode.asStateFlow()

    private val _jsCode = MutableStateFlow("")
    val jsCode: StateFlow<String> = _jsCode.asStateFlow()

    private val _genState = MutableStateFlow<WebsiteGenState>(WebsiteGenState.Idle)
    val genState: StateFlow<WebsiteGenState> = _genState.asStateFlow()

    val savedWebsites: StateFlow<DataUiState<List<WebsiteProject>>> = repository.observeWebsites()
        .map<List<WebsiteProject>, DataUiState<List<WebsiteProject>>> { DataUiState.Success(it) }
        .catch { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Error observing local websites", error)
            emit(DataUiState.Error(error.message ?: "محفوظ ویب سائٹس لوڈ نہیں ہو سکیں۔"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    fun updateBusinessName(value: String) { _businessName.value = value }
    fun updateIndustry(value: String) { _industry.value = value }
    fun updateRequirements(value: String) { _requirements.value = value }
    fun updateColorPalette(value: String) { _colorPalette.value = value }
    fun updateHtmlCode(value: String) { _htmlCode.value = value }
    fun updateCssCode(value: String) { _cssCode.value = value }
    fun updateJsCode(value: String) { _jsCode.value = value }

    fun prefillFromChatPrompt(prompt: String) {
        if (prompt.isNotBlank()) {
            _requirements.value = prompt
        }
    }

    fun loadProject(project: WebsiteProject) {
        _activeProjectId.value = project.id
        _businessName.value = project.businessName
        _industry.value = project.industry
        _requirements.value = project.requirements
        _colorPalette.value = project.colorPalette
        _htmlCode.value = project.htmlCode
        _cssCode.value = project.cssCode
        _jsCode.value = project.jsCode
        _genState.value = WebsiteGenState.Ready("'${project.businessName}' فون سے لوڈ کر لی گئی ہے۔")
    }

    fun generateWebsite() {
        val name = _businessName.value.trim().ifBlank { "My Business" }
        val reqs = _requirements.value.trim()
        if (reqs.isBlank()) {
            _genState.value = WebsiteGenState.Error("براہ کرم پہلے اپنی ویب سائٹ کی تفصیلات لکھیں۔")
            return
        }

        viewModelScope.launch {
            _genState.value = WebsiteGenState.Generating
            try {
                val apiKey = resolveGeminiApiKey(apiKeyProvider())
                if (apiKey.isBlank()) {
                    _genState.value = WebsiteGenState.Error("Gemini API key خالی ہے، براہِ کرم AI Studio کے Secrets پینل یا کوڈ میں GEMINI_API_KEY سیٹ کریں۔")
                    return@launch
                }
                val bundle = aiService.generateWebsite(
                    apiKey = apiKey,
                    businessName = name,
                    industry = _industry.value.trim(),
                    requirements = reqs,
                    colorPalette = _colorPalette.value
                )
                _businessName.value = name
                _htmlCode.value = bundle.htmlCode
                _cssCode.value = bundle.cssCode
                _jsCode.value = bundle.jsCode

                val savedId = repository.saveWebsite(
                    WebsiteProject(
                        id = _activeProjectId.value,
                        businessName = name,
                        industry = _industry.value.trim(),
                        requirements = reqs,
                        colorPalette = _colorPalette.value,
                        htmlCode = bundle.htmlCode,
                        cssCode = bundle.cssCode,
                        jsCode = bundle.jsCode
                    )
                )
                _activeProjectId.value = savedId
                _genState.value = WebsiteGenState.Ready(bundle.summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Website generation error", e)
                _genState.value = WebsiteGenState.Error(e.localizedMessage ?: "ویب سائٹ تیار نہیں ہو سکی۔")
            }
        }
    }

    fun saveEditsLocally() {
        if (_htmlCode.value.isBlank()) return
        viewModelScope.launch {
            try {
                val savedId = repository.saveWebsite(
                    WebsiteProject(
                        id = _activeProjectId.value,
                        businessName = _businessName.value.trim().ifBlank { "My Business" },
                        industry = _industry.value.trim(),
                        requirements = _requirements.value.trim().ifBlank { "Custom edited website" },
                        colorPalette = _colorPalette.value,
                        htmlCode = _htmlCode.value,
                        cssCode = _cssCode.value,
                        jsCode = _jsCode.value
                    )
                )
                _activeProjectId.value = savedId
                _genState.value = WebsiteGenState.Ready("تبدیلیاں فون میں محفوظ کر دی گئی ہیں۔")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _genState.value = WebsiteGenState.Error(e.localizedMessage ?: "تبدیلیاں محفوظ نہیں ہو سکیں۔")
            }
        }
    }

    fun deleteWebsite(websiteId: String) {
        viewModelScope.launch {
            try {
                repository.deleteWebsite(websiteId)
                if (_activeProjectId.value == websiteId) {
                    _activeProjectId.value = ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _genState.value = WebsiteGenState.Error(e.localizedMessage ?: "پروجیکٹ حذف نہیں ہو سکا۔")
            }
        }
    }

    fun buildCombinedHtmlDocument(): String {
        val rawHtml = _htmlCode.value
        val css = _cssCode.value
        val js = _jsCode.value

        return if (rawHtml.contains("<html", ignoreCase = true)) {
            val withCss = if (rawHtml.contains("</head>", ignoreCase = true)) {
                rawHtml.replace("</head>", "<style>\n$css\n</style>\n</head>", ignoreCase = true)
            } else {
                "<style>\n$css\n</style>\n$rawHtml"
            }
            if (withCss.contains("</body>", ignoreCase = true)) {
                withCss.replace("</body>", "<script>\n$js\n</script>\n</body>", ignoreCase = true)
            } else {
                "$withCss\n<script>\n$js\n</script>"
            }
        } else {
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>${_businessName.value.ifBlank { "Jarvis Website" }}</title>
              <style>
            $css
              </style>
            </head>
            <body>
            $rawHtml
              <script>
            $js
              </script>
            </body>
            </html>
            """.trimIndent()
        }
    }

    companion object {
        private const val TAG = "WebsiteCreatorVM"

        fun provideFactory(
            context: Context,
            dataStore: PreferencesDataStore
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val dao = AppDatabase.getInstance(context).jarvisDao()
                WebsiteCreatorViewModel(
                    repository = JarvisRepository(dao),
                    aiService = JarvisAiService(),
                    dataStore = dataStore
                )
            }
        }
    }
}
