/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * OCR recognizer using ML Kit for offline text recognition.
 * Supports both Latin and Chinese text recognition with image preprocessing.
 */
class TextRecognitionManager {

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * Recognizes text from a bitmap image with automatic language detection.
     * Uses both recognizers and returns the best result.
     *
     * @param bitmap The bitmap image to process.
     * @param preferChinese Whether to prefer Chinese text recognition as primary.
     * @return The recognized text, or empty string if no text found.
     */
    suspend fun recognizeText(bitmap: Bitmap, preferChinese: Boolean = true): String {
        // Preprocess image for better recognition
        val processedBitmap = preprocessImage(bitmap)
        val inputImage = InputImage.fromBitmap(processedBitmap, ROTATION_DEGREES)

        // Run both recognizers in parallel for best results
        return coroutineScope {
            val chineseDeferred = async { recognizeWithRecognizer(chineseRecognizer, inputImage) }
            val latinDeferred = async { recognizeWithRecognizer(latinRecognizer, inputImage) }

            val chineseResult = chineseDeferred.await()
            val latinResult = latinDeferred.await()

            // Choose best result based on content analysis
            selectBestResult(chineseResult, latinResult, preferChinese)
        }
    }

    /**
     * Preprocesses the image to improve OCR accuracy.
     * Applies contrast enhancement and optional grayscale conversion.
     */
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Create output bitmap
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        // Apply contrast enhancement
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(createContrastMatrix(CONTRAST_VALUE))
        }

        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return outputBitmap
    }

    /**
     * Creates a color matrix for contrast adjustment.
     */
    private fun createContrastMatrix(contrast: Float): ColorMatrix {
        val scale = contrast + 1f
        val translate = (-.5f * scale + .5f) * 255f

        return ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /**
     * Recognizes text using a specific recognizer.
     */
    private suspend fun recognizeWithRecognizer(
        recognizer: TextRecognizer,
        inputImage: InputImage
    ): RecognitionResult {
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    continuation.resume(
                        RecognitionResult(
                            text = visionText.text,
                            blocks = visionText.textBlocks,
                            confidence = calculateAverageConfidence(visionText)
                        )
                    )
                }
                .addOnFailureListener { exception ->
                    // Return empty result on failure instead of throwing
                    continuation.resume(RecognitionResult("", emptyList(), 0f))
                }
        }
    }

    /**
     * Calculates average confidence score from recognition result.
     */
    private fun calculateAverageConfidence(visionText: Text): Float {
        val confidences = visionText.textBlocks
            .flatMap { it.lines }
            .mapNotNull { it.confidence }

        return if (confidences.isNotEmpty()) {
            confidences.average().toFloat()
        } else {
            0f
        }
    }

    /**
     * Selects the best result from Chinese and Latin recognizers.
     */
    private fun selectBestResult(
        chineseResult: RecognitionResult,
        latinResult: RecognitionResult,
        preferChinese: Boolean
    ): String {
        // If one is empty, return the other
        if (chineseResult.text.isBlank()) return latinResult.text
        if (latinResult.text.isBlank()) return chineseResult.text

        // Analyze content to determine best result
        val chineseCharCount = chineseResult.text.count { it.code > 0x4E00 && it.code < 0x9FFF }
        val latinCharCount = latinResult.text.count { it.isLetter() && it.code < 128 }

        val totalChars = max(chineseResult.text.length, 1)
        val chineseRatio = chineseCharCount.toFloat() / totalChars

        // If more than 20% Chinese characters, prefer Chinese recognizer
        // Otherwise use the one with higher confidence
        return when {
            chineseRatio > 0.2f -> chineseResult.text
            latinResult.confidence > chineseResult.confidence + 0.1f -> latinResult.text
            preferChinese -> chineseResult.text
            else -> latinResult.text
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
        val processedBitmap = preprocessImage(bitmap)
        val inputImage = InputImage.fromBitmap(processedBitmap, ROTATION_DEGREES)
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
        // Contrast enhancement value (0.0 = no change, 0.3 = moderate boost)
        private const val CONTRAST_VALUE = 0.25f
    }
}

/**
 * Internal result holder for recognition comparison.
 */
private data class RecognitionResult(
    val text: String,
    val blocks: List<Text.TextBlock>,
    val confidence: Float
)

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
