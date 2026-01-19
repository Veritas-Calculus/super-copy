/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import ac.plz.super_copy.OcrResultActivity
import ac.plz.super_copy.R
import java.nio.ByteBuffer

private const val TAG = "ScreenCaptureService"

/**
 * Foreground service for capturing screenshots using MediaProjection API.
 * This implementation does not rely on accessibility services.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var isFromFloating: Boolean = false
    private var isOneShotMode: Boolean = false
    private var hasShownOcrResult: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        setupScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        // If service is restarted by system with null intent, just stop
        if (intent == null) {
            Log.d(TAG, "Null intent, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        
        when (intent.action) {
            ACTION_START_CAPTURE -> {
                Log.d(TAG, "ACTION_START_CAPTURE")
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = getParcelableExtraCompat<Intent>(intent, EXTRA_DATA)
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForegroundService()
                    startMediaProjection(resultCode, data)
                } else {
                    Log.e(TAG, "Invalid resultCode or data")
                    stopSelf()
                }
            }
            ACTION_ONE_SHOT_CAPTURE -> {
                Log.d(TAG, "ACTION_ONE_SHOT_CAPTURE")
                // One-shot mode: capture once then stop service
                isOneShotMode = true
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = getParcelableExtraCompat<Intent>(intent, EXTRA_DATA)
                Log.d(TAG, "resultCode=$resultCode, data=${data != null}")
                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        Log.d(TAG, "Starting foreground and media projection")
                        startForegroundService()
                        startMediaProjection(resultCode, data)
                        // Capture after virtual display is ready
                        Log.d(TAG, "Scheduling capture in ${CAPTURE_DELAY_MS}ms")
                        handler.postDelayed({
                            captureScreenOneShot()
                        }, CAPTURE_DELAY_MS)
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception in one-shot capture", e)
                        restoreFloatingButton()
                        stopSelf()
                    }
                } else {
                    Log.e(TAG, "Invalid resultCode or data for one-shot")
                    restoreFloatingButton()
                    stopSelf()
                }
            }
            ACTION_CAPTURE_SCREEN -> {
                Log.d(TAG, "ACTION_CAPTURE_SCREEN")
                isFromFloating = intent.getBooleanExtra(EXTRA_FROM_FLOATING, false)
                captureScreen()
            }
            ACTION_STOP_CAPTURE -> {
                Log.d(TAG, "ACTION_STOP_CAPTURE")
                stopCapture()
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // If we're in one-shot mode and haven't shown OCR result, restore floating button
        if (isOneShotMode && !hasShownOcrResult) {
            restoreFloatingButton()
        }
        stopCapture()
        super.onDestroy()
    }

    private fun setupScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val displayMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }
        screenDensity = resources.displayMetrics.densityDpi
    }

    private inline fun <reified T : android.os.Parcelable> getParcelableExtraCompat(
        intent: Intent,
        name: String
    ): T? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(name)
        }
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Screen capture service notification"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_launcher_foreground,
                getString(R.string.stop_capture),
                stopPendingIntent
            )
            .build()
    }

    private fun startMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, handler)

        setupImageReader()
        createVirtualDisplay()
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            IMAGE_BUFFER_SIZE
        )
    }

    private fun createVirtualDisplay() {
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    private fun captureScreen() {
        handler.postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                if (bitmap != null) {
                    sendBitmapBroadcast(bitmap)
                }
            }
        }, CAPTURE_DELAY_MS)
    }

    private fun captureScreenOneShot() {
        Log.d(TAG, "captureScreenOneShot called")
        captureScreenOneShotWithRetry(0)
    }

    private fun captureScreenOneShotWithRetry(attempt: Int) {
        Log.d(TAG, "captureScreenOneShotWithRetry attempt=$attempt")
        try {
            val image = imageReader?.acquireLatestImage()
            Log.d(TAG, "acquireLatestImage: ${if (image != null) "success" else "null"}")
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                Log.d(TAG, "imageToBitmap: ${if (bitmap != null) "success" else "null"}")
                if (bitmap != null) {
                    ScreenshotHolder.bitmap = bitmap
                    showOcrResultActivity()
                } else {
                    Log.e(TAG, "Bitmap is null, restoring floating button")
                    restoreFloatingButton()
                }
                // Stop capture and service after one-shot
                stopCapture()
                stopSelf()
            } else if (attempt < MAX_CAPTURE_RETRIES) {
                // Retry after delay if image not ready
                Log.d(TAG, "Image null, scheduling retry ${attempt + 1}")
                handler.postDelayed({
                    captureScreenOneShotWithRetry(attempt + 1)
                }, RETRY_DELAY_MS)
            } else {
                // Max retries reached, restore floating button
                Log.e(TAG, "Max retries reached, restoring floating button")
                restoreFloatingButton()
                stopCapture()
                stopSelf()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            restoreFloatingButton()
            stopCapture()
            stopSelf()
        }
    }

    private fun showOcrResultActivity() {
        Log.d(TAG, "showOcrResultActivity")
        hasShownOcrResult = true
        try {
            // Use FloatingButtonService to show OCR result overlay
            // This works from background because it uses system overlay, not Activity
            val intent = FloatingButtonService.createShowOcrResultIntent(this)
            startForegroundService(intent)
            Log.d(TAG, "OCR result overlay intent sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show OCR result overlay", e)
            // If we can't show overlay, restore floating button
            restoreFloatingButton()
        }
    }

    private fun restoreFloatingButton() {
        Log.d(TAG, "restoreFloatingButton")
        try {
            val intent = FloatingButtonService.createRestoreIntent(this)
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore floating button", e)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = android.graphics.Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to actual screen size
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) {
            null
        }
    }

    private fun sendBitmapBroadcast(bitmap: Bitmap) {
        val intent = Intent(ACTION_SCREENSHOT_CAPTURED).apply {
            setPackage(packageName)
            putExtra(EXTRA_FROM_FLOATING, isFromFloating)
        }
        ScreenshotHolder.bitmap = bitmap
        sendBroadcast(intent)
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
            stopSelf()
        }
    }

    companion object {
        const val ACTION_START_CAPTURE = "ac.plz.super_copy.ACTION_START_CAPTURE"
        const val ACTION_ONE_SHOT_CAPTURE = "ac.plz.super_copy.ACTION_ONE_SHOT_CAPTURE"
        const val ACTION_CAPTURE_SCREEN = "ac.plz.super_copy.ACTION_CAPTURE_SCREEN"
        const val ACTION_STOP_CAPTURE = "ac.plz.super_copy.ACTION_STOP_CAPTURE"
        const val ACTION_SCREENSHOT_CAPTURED = "ac.plz.super_copy.ACTION_SCREENSHOT_CAPTURED"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_FROM_FLOATING = "from_floating"

        private const val NOTIFICATION_ID = 1001
        private const val OCR_RESULT_NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val OCR_RESULT_CHANNEL_ID = "ocr_result_channel"
        private const val CHANNEL_NAME = "Screen Capture"
        private const val OCR_RESULT_CHANNEL_NAME = "OCR Results"
        private const val VIRTUAL_DISPLAY_NAME = "SuperCopyScreenCapture"
        private const val IMAGE_BUFFER_SIZE = 2
        private const val CAPTURE_DELAY_MS = 300L
        private const val RETRY_DELAY_MS = 100L
        private const val MAX_CAPTURE_RETRIES = 10

        /**
         * Creates an intent for one-shot capture from floating button.
         * This captures once and stops the service immediately.
         */
        fun createOneShotCaptureIntent(
            context: Context,
            resultCode: Int,
            data: Intent
        ): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_ONE_SHOT_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
        }

        /**
         * Creates an intent to start screen capture.
         */
        fun createStartCaptureIntent(
            context: Context,
            resultCode: Int,
            data: Intent
        ): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
        }

        /**
         * Creates an intent to trigger a screenshot.
         */
        fun createCaptureIntent(context: Context, fromFloating: Boolean = false): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE_SCREEN
                putExtra(EXTRA_FROM_FLOATING, fromFloating)
            }
        }

        /**
         * Creates an intent to stop the capture service.
         */
        fun createStopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
        }
    }
}

/**
 * Singleton holder for passing bitmap between service and activity.
 * This avoids putting large bitmaps in Intent extras.
 */
object ScreenshotHolder {
    var bitmap: Bitmap? = null
        set(value) {
            // Clear previous bitmap to avoid memory leaks
            field?.recycle()
            field = value
        }

    fun clear() {
        bitmap?.recycle()
        bitmap = null
    }
}
