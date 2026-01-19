/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ac.plz.super_copy.service.FloatingButtonService
import ac.plz.super_copy.ui.MainViewModel
import ac.plz.super_copy.ui.MainViewModelFactory
import ac.plz.super_copy.ui.screens.MainScreen
import ac.plz.super_copy.ui.theme.SuperCopyTheme

/**
 * Main activity for the SuperCopy application.
 * Manages the floating button toggle and clipboard history display.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            (application as SuperCopyApplication).clipboardRepository
        )
    }

    private lateinit var clipboardManager: ClipboardManager

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingButton()
        } else {
            Toast.makeText(
                this,
                getString(R.string.overlay_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        clipboardManager = getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        // Restore floating button state from saved preferences
        restoreFloatingButtonState()

        setContent {
            SuperCopyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onFloatingButtonClick = ::onFloatingButtonClick,
                        onCopyToClipboard = ::copyToClipboard
                    )
                }
            }
        }
    }

    private fun restoreFloatingButtonState() {
        val isEnabled = FloatingButtonService.isFloatingButtonEnabled(this)
        viewModel.setFloatingButtonEnabled(isEnabled)
        
        // If it was enabled, restart the service
        if (isEnabled && Settings.canDrawOverlays(this)) {
            val intent = FloatingButtonService.createShowIntent(this)
            startForegroundService(intent)
        }
    }

    private fun onFloatingButtonClick() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (viewModel.uiState.value.isFloatingButtonEnabled) {
            stopFloatingButton()
        } else {
            startFloatingButton()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun startFloatingButton() {
        val intent = FloatingButtonService.createShowIntent(this)
        startForegroundService(intent)
        viewModel.setFloatingButtonEnabled(true)

        // Minimize app so user can use floating button on other apps
        moveTaskToBack(true)
    }

    private fun stopFloatingButton() {
        val intent = FloatingButtonService.createHideIntent(this)
        startService(intent)
        viewModel.setFloatingButtonEnabled(false)
    }

    private fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText(CLIPBOARD_LABEL, text)
        clipboardManager.setPrimaryClip(clip)
    }

    companion object {
        private const val CLIPBOARD_LABEL = "SuperCopy OCR"
    }
}
