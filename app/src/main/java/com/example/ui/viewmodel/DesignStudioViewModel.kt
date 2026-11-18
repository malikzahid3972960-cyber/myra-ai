package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ai.JarvisAiService
import com.example.data.ai.resolveGeminiApiKey
import com.example.data.local.AppDatabase
import com.example.data.model.DesignConcept
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

sealed interface DesignStudioState {
    data object Idle : DesignStudioState
    data object GeneratingConcept : DesignStudioState
    data object GeneratingImage : DesignStudioState
    data class Ready(
        val conceptDescription: String,
        val tagline: String,
        val paletteSummary: String,
        val imagePrompt: String,
        val svgPreview: String,
        val bitmap: Bitmap?,
        val imageError: String? = null
    ) : DesignStudioState
    data class Error(val message: String) : DesignStudioState
}

class DesignStudioViewModel(
    private val repository: JarvisRepository,
    private val aiService: JarvisAiService,
    private val dataStore: PreferencesDataStore,
    private val apiKeyProvider: () -> String = { resolveGeminiApiKey() }
) : ViewModel() {

    private val _brandName = MutableStateFlow("")
    val brandName: StateFlow<String> = _brandName.asStateFlow()

    private val _selectedStyle = MutableStateFlow("Futuristic Tech")
    val selectedStyle: StateFlow<String> = _selectedStyle.asStateFlow()

    private val _selectedColorScheme = MutableStateFlow("Electric Cyan & Cosmic Purple")
    val selectedColorScheme: StateFlow<String> = _selectedColorScheme.asStateFlow()

    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes.asStateFlow()

    private val _studioState = MutableStateFlow<DesignStudioState>(DesignStudioState.Idle)
    val studioState: StateFlow<DesignStudioState> = _studioState.asStateFlow()

    val savedDesigns: StateFlow<DataUiState<List<DesignConcept>>> = repository.observeDesigns()
        .map<List<DesignConcept>, DataUiState<List<DesignConcept>>> { DataUiState.Success(it) }
        .catch { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Error observing local designs", error)
            emit(DataUiState.Error(error.message ?: "محفوظ ڈیزائنز لوڈ نہیں ہو سکے۔"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    fun updateBrandName(value: String) { _brandName.value = value }
    fun updateStyle(value: String) { _selectedStyle.value = value }
    fun updateColorScheme(value: String) { _selectedColorScheme.value = value }
    fun updateNotes(value: String) { _notes.value = value }

    fun prefillFromChatPrompt(prompt: String) {
        if (prompt.isNotBlank()) {
            _notes.value = prompt
        }
    }

    fun loadSavedConcept(concept: DesignConcept) {
        _brandName.value = concept.brandName
        _selectedStyle.value = concept.style
        _selectedColorScheme.value = concept.colorScheme
        _studioState.value = DesignStudioState.Ready(
            conceptDescription = concept.conceptDescription,
            tagline = "",
            paletteSummary = concept.colorScheme,
            imagePrompt = concept.imagePrompt,
            svgPreview = concept.svgPreview.orEmpty(),
            bitmap = null
        )
    }

    fun generateLogoAndConcept(renderAiImage: Boolean = true) {
        val name = _brandName.value.trim()
        if (name.isBlank()) {
            _studioState.value = DesignStudioState.Error("براہ کرم برانڈ یا پروجیکٹ کا نام درج کریں۔")
            return
        }

        viewModelScope.launch {
            _studioState.value = DesignStudioState.GeneratingConcept
            try {
                val apiKey = resolveGeminiApiKey(apiKeyProvider())
                if (apiKey.isBlank()) {
                    _studioState.value = DesignStudioState.Error("Gemini API key خالی ہے، براہِ کرم AI Studio کے Secrets پینل یا کوڈ میں GEMINI_API_KEY سیٹ کریں۔")
                    return@launch
                }
                val bundle = aiService.generateDesignConcept(
                    apiKey = apiKey,
                    brandName = name,
                    style = _selectedStyle.value,
                    colorScheme = _selectedColorScheme.value,
                    notes = _notes.value.trim()
                )

                var generatedBitmap: Bitmap? = null
                var imageError: String? = null

                if (renderAiImage) {
                    _studioState.value = DesignStudioState.GeneratingImage
                    try {
                        generatedBitmap = aiService.generateLogoBitmap(apiKey, bundle.imagePrompt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "AI image rendering failed; surfacing real error", e)
                        imageError = e.localizedMessage ?: "اے آئی لوگو تصویر نہیں بن سکی۔"
                    }
                }

                repository.saveDesignConcept(
                    DesignConcept(
                        brandName = name,
                        style = _selectedStyle.value,
                        colorScheme = _selectedColorScheme.value,
                        conceptDescription = buildString {
                            if (bundle.tagline.isNotBlank()) append("Tagline: ${bundle.tagline}\n\n")
                            append(bundle.conceptDescription)
                            append("\n\nPalette: ${bundle.paletteSummary}")
                        },
                        imagePrompt = bundle.imagePrompt,
                        svgPreview = bundle.svgPreview.take(48000),
                        hasGeneratedImage = generatedBitmap != null
                    )
                )

                _studioState.value = DesignStudioState.Ready(
                    conceptDescription = bundle.conceptDescription,
                    tagline = bundle.tagline,
                    paletteSummary = bundle.paletteSummary,
                    imagePrompt = bundle.imagePrompt,
                    svgPreview = bundle.svgPreview,
                    bitmap = generatedBitmap,
                    imageError = imageError
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Design generation failed", e)
                _studioState.value = DesignStudioState.Error(e.localizedMessage ?: "ڈیزائن تیار کرنے میں خرابی پیش آئی۔")
            }
        }
    }

    fun renderBitmapForCurrentPrompt() {
        val current = _studioState.value as? DesignStudioState.Ready ?: return
        viewModelScope.launch {
            _studioState.value = DesignStudioState.GeneratingImage
            try {
                val apiKey = resolveGeminiApiKey(apiKeyProvider())
                if (apiKey.isBlank()) {
                    _studioState.value = current.copy(imageError = "Gemini API key خالی ہے، براہِ کرم AI Studio کے Secrets پینل یا کوڈ میں GEMINI_API_KEY سیٹ کریں۔")
                    return@launch
                }
                val bmp = aiService.generateLogoBitmap(apiKey, current.imagePrompt)
                _studioState.value = current.copy(bitmap = bmp, imageError = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Logo bitmap render error", e)
                _studioState.value = current.copy(imageError = e.localizedMessage ?: "اے آئی تصویر نہیں بن سکی۔")
            }
        }
    }

    fun deleteDesign(designId: String) {
        viewModelScope.launch {
            try {
                repository.deleteDesign(designId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _studioState.value = DesignStudioState.Error(e.localizedMessage ?: "ڈیزائن حذف نہیں ہو سکا۔")
            }
        }
    }

    companion object {
        private const val TAG = "DesignStudioVM"

        fun provideFactory(
            context: Context,
            dataStore: PreferencesDataStore
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val dao = AppDatabase.getInstance(context).jarvisDao()
                DesignStudioViewModel(
                    repository = JarvisRepository(dao),
                    aiService = JarvisAiService(),
                    dataStore = dataStore
                )
            }
        }
    }
}
