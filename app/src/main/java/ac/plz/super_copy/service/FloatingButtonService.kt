/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.content.edit
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import ac.plz.super_copy.MainActivity
import ac.plz.super_copy.R
import ac.plz.super_copy.ScreenCaptureActivity
import ac.plz.super_copy.data.local.database.AppDatabase
import ac.plz.super_copy.data.repository.ClipboardRepository
import ac.plz.super_copy.ocr.TextRecognitionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Foreground service that displays a floating action button overlay.
 * The floating button allows users to trigger screen capture from any app.
 */
class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var clipboardRepository: ClipboardRepository
    private val textRecognitionManager = TextRecognitionManager()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    
    private var floatingView: View? = null
    private var ocrResultView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isHiddenForCapture = false
    private var currentOcrText: String? = null

    // For dragging
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var lastAction: Int = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardRepository = ClipboardRepository(
            AppDatabase.getInstance(this).clipboardDao()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW -> {
                Log.d(TAG, "ACTION_SHOW")
                startForegroundWithNotification()
                showFloatingButton()
                saveFloatingButtonEnabled(true)
            }
            ACTION_HIDE -> {
                Log.d(TAG, "ACTION_HIDE")
                saveFloatingButtonEnabled(false)
                hideFloatingButton()
                stopSelf()
            }
            ACTION_TEMPORARILY_HIDE -> {
                Log.d(TAG, "ACTION_TEMPORARILY_HIDE")
                isHiddenForCapture = true
                floatingView?.visibility = View.GONE
            }
            ACTION_RESTORE -> {
                Log.d(TAG, "ACTION_RESTORE: floatingView=${floatingView != null}")
                isHiddenForCapture = false
                // Ensure foreground service is running (in case it was killed)
                startForegroundWithNotification()
                // Force recreate the floating button
                if (floatingView == null) {
                    Log.d(TAG, "Creating new floating button")
                    showFloatingButton()
                } else {
                    Log.d(TAG, "Making existing floating button visible")
                    floatingView?.visibility = View.VISIBLE
                }
            }
            ACTION_SHOW_OCR_RESULT -> {
                Log.d(TAG, "ACTION_SHOW_OCR_RESULT")
                isHiddenForCapture = false
                startForegroundWithNotification()
                // Show floating button first
                if (floatingView == null) {
                    showFloatingButton()
                } else {
                    floatingView?.visibility = View.VISIBLE
                }
                // Then show OCR result overlay
                showOcrResultOverlay()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideFloatingButton()
        hideOcrResultOverlay()
        textRecognitionManager.close()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
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
            description = "Floating button service notification"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingButtonService::class.java).apply {
            action = ACTION_HIDE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                getString(R.string.stop_floating),
                stopPendingIntent
            )
            .build()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showFloatingButton() {
        // If already showing, don't recreate
        if (floatingView != null) {
            // Make sure it's visible
            floatingView?.visibility = View.VISIBLE
            return
        }

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_button, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        val floatingButton = floatingView?.findViewById<ImageView>(R.id.floating_button)
        floatingButton?.setOnTouchListener { view, event ->
            handleTouch(view, event)
        }

        try {
            windowManager.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            // Handle exception if overlay permission is not granted
            floatingView = null
            stopSelf()
        }
    }

    private fun hideFloatingButton() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // View might already be removed
            }
            floatingView = null
        }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams?.x ?: 0
                initialY = layoutParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                lastAction = event.action
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                layoutParams?.let { params ->
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                }
                lastAction = event.action
                return true
            }
            MotionEvent.ACTION_UP -> {
                // Check if it was a click (not a drag)
                val deltaX = abs(event.rawX - initialTouchX)
                val deltaY = abs(event.rawY - initialTouchY)
                if (deltaX < CLICK_THRESHOLD && deltaY < CLICK_THRESHOLD) {
                    onFloatingButtonClick()
                }
                lastAction = event.action
                return true
            }
        }
        return false
    }

    private fun onFloatingButtonClick() {
        // Temporarily hide the floating button before capture (just hide visibility, don't remove)
        isHiddenForCapture = true
        floatingView?.visibility = View.GONE

        // Launch ScreenCaptureActivity to request permission and capture
        val captureIntent = ScreenCaptureActivity.createIntent(this)
        startActivity(captureIntent)
    }

    @SuppressLint("InflateParams")
    private fun showOcrResultOverlay() {
        Log.d(TAG, "showOcrResultOverlay")
        
        // Remove existing overlay if any
        hideOcrResultOverlay()
        
        val bitmap = ScreenshotHolder.bitmap
        if (bitmap == null) {
            Log.e(TAG, "No screenshot available")
            Toast.makeText(this, R.string.no_screenshot, Toast.LENGTH_SHORT).show()
            return
        }
        
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        ocrResultView = inflater.inflate(R.layout.floating_ocr_result, null)
        
        val ocrParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        
        try {
            windowManager.addView(ocrResultView, ocrParams)
            Log.d(TAG, "OCR result overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add OCR result overlay", e)
            return
        }
        
        // Set up close button
        val closeButton = ocrResultView?.findViewById<View>(R.id.close_button)
        closeButton?.setOnClickListener {
            hideOcrResultOverlay()
            ScreenshotHolder.clear()
        }
        
        // Start OCR processing
        performOcr(bitmap)
    }
    
    private fun performOcr(bitmap: android.graphics.Bitmap) {
        val loadingContainer = ocrResultView?.findViewById<LinearLayout>(R.id.loading_container)
        val resultText = ocrResultView?.findViewById<TextView>(R.id.result_text)
        val errorText = ocrResultView?.findViewById<TextView>(R.id.error_text)
        val buttonContainer = ocrResultView?.findViewById<LinearLayout>(R.id.button_container)
        val copyButton = ocrResultView?.findViewById<Button>(R.id.copy_button)
        val cancelButton = ocrResultView?.findViewById<Button>(R.id.cancel_button)
        
        // Set up button listeners
        copyButton?.setOnClickListener {
            currentOcrText?.let { text ->
                copyToClipboard(text)
            }
            hideOcrResultOverlay()
            ScreenshotHolder.clear()
        }
        
        cancelButton?.setOnClickListener {
            hideOcrResultOverlay()
            ScreenshotHolder.clear()
        }
        
        // Perform OCR in background
        serviceScope.launch {
            try {
                val text = textRecognitionManager.recognizeText(bitmap)
                
                handler.post {
                    loadingContainer?.visibility = View.GONE
                    
                    if (text.isBlank()) {
                        errorText?.text = getString(R.string.no_text_found)
                        errorText?.visibility = View.VISIBLE
                        buttonContainer?.visibility = View.VISIBLE
                        copyButton?.isEnabled = false
                    } else {
                        currentOcrText = text
                        resultText?.text = text
                        resultText?.visibility = View.VISIBLE
                        buttonContainer?.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                handler.post {
                    loadingContainer?.visibility = View.GONE
                    errorText?.text = getString(R.string.ocr_error)
                    errorText?.visibility = View.VISIBLE
                    buttonContainer?.visibility = View.VISIBLE
                    copyButton?.isEnabled = false
                }
            }
        }
    }
    
    private fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("SuperCopy OCR", text)
        clipboardManager.setPrimaryClip(clip)
        
        // Save to history
        serviceScope.launch {
            clipboardRepository.saveEntry(text)
        }
        
        Toast.makeText(this, R.string.copy_success, Toast.LENGTH_SHORT).show()
    }
    
    private fun hideOcrResultOverlay() {
        ocrResultView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // View might already be removed
            }
            ocrResultView = null
        }
        currentOcrText = null
    }

    private fun saveFloatingButtonEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FLOATING_ENABLED, enabled) }
    }

    companion object {
        private const val TAG = "FloatingButtonService"
        
        const val ACTION_SHOW = "ac.plz.super_copy.ACTION_SHOW_FLOATING"
        const val ACTION_HIDE = "ac.plz.super_copy.ACTION_HIDE_FLOATING"
        const val ACTION_TEMPORARILY_HIDE = "ac.plz.super_copy.ACTION_TEMP_HIDE_FLOATING"
        const val ACTION_RESTORE = "ac.plz.super_copy.ACTION_RESTORE_FLOATING"
        const val ACTION_SHOW_OCR_RESULT = "ac.plz.super_copy.ACTION_SHOW_OCR_RESULT"
        const val ACTION_CAPTURE_CLICKED = "ac.plz.super_copy.ACTION_CAPTURE_CLICKED"

        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "floating_button_channel"
        private const val CHANNEL_NAME = "Floating Button"
        private const val CLICK_THRESHOLD = 10
        private const val PREFS_NAME = "super_copy_prefs"
        private const val KEY_FLOATING_ENABLED = "floating_button_enabled"

        fun isFloatingButtonEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FLOATING_ENABLED, false)
        }

        fun createShowIntent(context: Context): Intent {
            return Intent(context, FloatingButtonService::class.java).apply {
                action = ACTION_SHOW
            }
        }

        fun createHideIntent(context: Context): Intent {
            return Intent(context, FloatingButtonService::class.java).apply {
                action = ACTION_HIDE
            }
        }

        fun createRestoreIntent(context: Context): Intent {
            return Intent(context, FloatingButtonService::class.java).apply {
                action = ACTION_RESTORE
            }
        }
        
        fun createShowOcrResultIntent(context: Context): Intent {
            return Intent(context, FloatingButtonService::class.java).apply {
                action = ACTION_SHOW_OCR_RESULT
            }
        }
    }
}
