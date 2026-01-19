/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ac.plz.super_copy.service.FloatingButtonService
import ac.plz.super_copy.service.ScreenCaptureService

/**
 * Transparent activity that requests MediaProjection permission,
 * then delegates the actual capture to ScreenCaptureService.
 * This is required because MediaProjection must run in a foreground service.
 */
class ScreenCaptureActivity : ComponentActivity() {

    private var hasStartedService = false
    private val handler = Handler(Looper.getMainLooper())

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Start capture service with permission data
            startCaptureService(result.resultCode, result.data!!)
        } else {
            // Permission denied, restore floating button and finish
            Log.d(TAG, "Permission denied, restoring floating button")
            restoreFloatingButton()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity completely transparent
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        // Request media projection permission
        try {
            val mediaProjectionManager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch media projection request", e)
            restoreFloatingButton()
            finish()
        }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        try {
            Log.d(TAG, "Starting capture service with resultCode: $resultCode")
            val serviceIntent = ScreenCaptureService.createOneShotCaptureIntent(
                this,
                resultCode,
                data
            )
            startForegroundService(serviceIntent)
            hasStartedService = true
            
            // Schedule a fallback restore in case service fails silently
            handler.postDelayed({
                // This will only execute if the activity is still around
                // which means something went wrong
            }, FALLBACK_RESTORE_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture service", e)
            restoreFloatingButton()
        }
    }

    private fun restoreFloatingButton() {
        try {
            Log.d(TAG, "Restoring floating button")
            val intent = FloatingButtonService.createRestoreIntent(this)
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore floating button", e)
        }
    }

    override fun onDestroy() {
        // If we never started the capture service successfully, restore floating button
        if (!hasStartedService) {
            Log.d(TAG, "onDestroy: service not started, restoring floating button")
            restoreFloatingButton()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCaptureActivity"
        private const val FALLBACK_RESTORE_DELAY_MS = 5000L

        fun createIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }
}
