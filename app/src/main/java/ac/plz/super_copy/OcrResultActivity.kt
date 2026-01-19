/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ac.plz.super_copy.data.local.database.AppDatabase
import ac.plz.super_copy.data.repository.ClipboardRepository
import ac.plz.super_copy.ocr.TextRecognitionManager
import ac.plz.super_copy.service.FloatingButtonService
import ac.plz.super_copy.service.ScreenshotHolder
import ac.plz.super_copy.ui.theme.SuperCopyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent activity that displays OCR results and asks user to confirm copying.
 * This activity is shown after a screenshot is captured from the floating button.
 */
class OcrResultActivity : ComponentActivity() {

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var clipboardRepository: ClipboardRepository
    private val textRecognitionManager = TextRecognitionManager()
    private var hasRestoredFloating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "OcrResultActivity onCreate")

        // Cancel the notification that launched this activity
        cancelOcrResultNotification()

        // Make the activity appear as a dialog overlay
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardRepository = ClipboardRepository(
            AppDatabase.getInstance(this).clipboardDao()
        )

        val bitmap = ScreenshotHolder.bitmap

        setContent {
            SuperCopyTheme {
                OcrResultDialog(
                    bitmap = bitmap,
                    onCopy = ::copyToClipboard,
                    onDismiss = ::finishAndRestoreFloating
                )
            }
        }
    }

    private fun cancelOcrResultNotification() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(OCR_RESULT_NOTIFICATION_ID)
            Log.d(TAG, "Cancelled OCR result notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel notification", e)
        }
    }

    private fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText(CLIPBOARD_LABEL, text)
        clipboardManager.setPrimaryClip(clip)

        // Save to history
        kotlinx.coroutines.MainScope().launch {
            clipboardRepository.saveEntry(text)
        }

        Toast.makeText(this, R.string.copy_success, Toast.LENGTH_SHORT).show()
        finishAndRestoreFloating()
    }

    private fun finishAndRestoreFloating() {
        Log.d(TAG, "finishAndRestoreFloating called")
        hasRestoredFloating = true
        try {
            // Restore floating button (use startForegroundService to ensure it survives)
            val restoreIntent = FloatingButtonService.createRestoreIntent(this)
            startForegroundService(restoreIntent)
            Log.d(TAG, "Restore intent sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send restore intent", e)
        }

        // Clear the screenshot
        ScreenshotHolder.clear()

        finish()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: hasRestoredFloating=$hasRestoredFloating")
        // Ensure floating button is restored even if activity is destroyed unexpectedly
        if (!hasRestoredFloating) {
            Log.d(TAG, "Restoring floating button in onDestroy")
            try {
                val restoreIntent = FloatingButtonService.createRestoreIntent(this)
                startForegroundService(restoreIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore floating button in onDestroy", e)
            }
            ScreenshotHolder.clear()
        }
        textRecognitionManager.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OcrResultActivity"
        private const val CLIPBOARD_LABEL = "SuperCopy OCR"
        private const val OCR_RESULT_NOTIFICATION_ID = 1003

        fun createIntent(context: Context): Intent {
            return Intent(context, OcrResultActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                // Required for launching from background on Android 10+
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        }
    }
}

@Composable
private fun OcrResultDialog(
    bitmap: Bitmap?,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var isProcessing by remember { mutableStateOf(true) }
    var recognizedText by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val textRecognitionManager = remember { TextRecognitionManager() }

    LaunchedEffect(bitmap) {
        if (bitmap == null) {
            errorMessage = "No screenshot available"
            isProcessing = false
            return@LaunchedEffect
        }

        scope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    textRecognitionManager.recognizeText(bitmap)
                }
                if (text.isNotBlank()) {
                    recognizedText = text
                } else {
                    errorMessage = "No text found in the image"
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "OCR processing failed"
            } finally {
                isProcessing = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.ocr_result_title),
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                when {
                    isProcessing -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.processing),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    errorMessage != null -> {
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.close))
                        }
                    }
                    recognizedText != null -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = recognizedText!!,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = stringResource(R.string.copy_to_clipboard_prompt),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(onClick = onDismiss) {
                                Text(stringResource(R.string.cancel))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            androidx.compose.material3.Button(
                                onClick = { onCopy(recognizedText!!) }
                            ) {
                                Text(stringResource(R.string.copy))
                            }
                        }
                    }
                }
            }
        }
    }
}
