/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ac.plz.super_copy.data.local.entity.ClipboardEntry
import ac.plz.super_copy.data.repository.ClipboardRepository
import ac.plz.super_copy.ocr.TextRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the main screen.
 * Manages UI state and business logic for screen capture and OCR.
 */
class MainViewModel(
    private val clipboardRepository: ClipboardRepository
) : ViewModel() {

    private val textRecognitionManager = TextRecognitionManager()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val clipboardHistory: StateFlow<List<ClipboardEntry>> = clipboardRepository
        .getRecentEntries(HISTORY_LIMIT)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    /**
     * Processes a captured screenshot for OCR.
     *
     * @param bitmap The screenshot bitmap to process.
     */
    fun processScreenshot(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                lastError = null
            )

            try {
                val recognizedText = textRecognitionManager.recognizeText(bitmap)

                if (recognizedText.isNotBlank()) {
                    clipboardRepository.saveEntry(recognizedText)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        lastRecognizedText = recognizedText,
                        showCopySuccess = true
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        lastError = "No text found in the image"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastError = e.message ?: "OCR processing failed"
                )
            }
        }
    }

    /**
     * Clears the success message state.
     */
    fun clearCopySuccessMessage() {
        _uiState.value = _uiState.value.copy(showCopySuccess = false)
    }

    /**
     * Clears the error message state.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(lastError = null)
    }

    /**
     * Deletes a clipboard entry.
     *
     * @param entry The entry to delete.
     */
    fun deleteEntry(entry: ClipboardEntry) {
        viewModelScope.launch {
            clipboardRepository.deleteEntry(entry)
        }
    }

    /**
     * Clears all clipboard history.
     */
    fun clearHistory() {
        viewModelScope.launch {
            clipboardRepository.clearAll()
        }
    }

    /**
     * Updates the capture permission state.
     */
    fun setCapturePermissionGranted(granted: Boolean) {
        _uiState.value = _uiState.value.copy(hasCapturePermission = granted)
    }

    /**
     * Updates the floating button enabled state.
     */
    fun setFloatingButtonEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isFloatingButtonEnabled = enabled)
    }

    override fun onCleared() {
        super.onCleared()
        textRecognitionManager.close()
    }

    companion object {
        private const val HISTORY_LIMIT = 50
        private const val STOP_TIMEOUT_MILLIS = 5000L
    }
}

/**
 * UI state for the main screen.
 */
data class MainUiState(
    val isProcessing: Boolean = false,
    val hasCapturePermission: Boolean = false,
    val isFloatingButtonEnabled: Boolean = false,
    val lastRecognizedText: String? = null,
    val lastError: String? = null,
    val showCopySuccess: Boolean = false
)

/**
 * Factory for creating MainViewModel instances.
 */
class MainViewModelFactory(
    private val clipboardRepository: ClipboardRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(clipboardRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
