/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * OCR recognizer using ML Kit for offline text recognition.
 * Supports both Latin and Chinese text recognition.
 */
class TextRecognitionManager {

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * Recognizes text from a bitmap image.
     *
     * @param bitmap The bitmap image to process.
     * @param preferChinese Whether to prefer Chinese text recognition.
     * @return The recognized text, or empty string if no text found.
     */
    suspend fun recognizeText(bitmap: Bitmap, preferChinese: Boolean = true): String {
        val inputImage = InputImage.fromBitmap(bitmap, ROTATION_DEGREES)
        val recognizer = if (preferChinese) chineseRecognizer else latinRecognizer

        return suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val result = visionText.text
                    continuation.resume(result)
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }

            continuation.invokeOnCancellation {
                // ML Kit tasks are not directly cancellable, but we handle cleanup
            }
        }
    }

    /**
     * Recognizes text with detailed block information.
     *
     * @param bitmap The bitmap image to process.
     * @param preferChinese Whether to prefer Chinese text recognition.
     * @return A list of recognized text blocks with their content.
     */
    suspend fun recognizeTextBlocks(
        bitmap: Bitmap,
        preferChinese: Boolean = true
    ): List<RecognizedBlock> {
        val inputImage = InputImage.fromBitmap(bitmap, ROTATION_DEGREES)
        val recognizer = if (preferChinese) chineseRecognizer else latinRecognizer

        return suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val blocks = visionText.textBlocks.map { block ->
                        RecognizedBlock(
                            text = block.text,
                            boundingBox = block.boundingBox,
                            confidence = block.lines.firstOrNull()?.confidence
                        )
                    }
                    continuation.resume(blocks)
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        }
    }

    /**
     * Closes the recognizers and releases resources.
     * Call this when the manager is no longer needed.
     */
    fun close() {
        latinRecognizer.close()
        chineseRecognizer.close()
    }

    companion object {
        private const val ROTATION_DEGREES = 0
    }
}

/**
 * Represents a recognized text block from OCR.
 *
 * @property text The recognized text content.
 * @property boundingBox The bounding box of the text in the image.
 * @property confidence The confidence score of the recognition (0.0 to 1.0).
 */
data class RecognizedBlock(
    val text: String,
    val boundingBox: android.graphics.Rect?,
    val confidence: Float?
)
