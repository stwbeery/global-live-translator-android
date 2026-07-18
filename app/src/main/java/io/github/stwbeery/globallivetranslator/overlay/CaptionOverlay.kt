package io.github.stwbeery.globallivetranslator.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

class CaptionOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captionView: TextView? = null

    fun show(text: String) {
        if (!Settings.canDrawOverlays(context)) return
        mainHandler.post {
            if (!Settings.canDrawOverlays(context)) return@post
            val view = runCatching {
                captionView ?: createView().also { captionView = it }
            }.getOrNull() ?: return@post
            view.text = text.trim()
            view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    fun remove() {
        mainHandler.post {
            val view = captionView ?: return@post
            captionView = null
            runCatching { windowManager.removeView(view) }
        }
    }

    private fun createView(): TextView {
        val density = context.resources.displayMetrics.density
        val width = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (windowManager.currentWindowMetrics.bounds.width() * 0.92f).roundToInt()
        } else {
            @Suppress("DEPRECATION")
            (context.resources.displayMetrics.widthPixels * 0.92f).roundToInt()
        }
        val view = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 21f
            gravity = Gravity.CENTER
            maxLines = 4
            setPadding(
                (18 * density).roundToInt(),
                (12 * density).roundToInt(),
                (18 * density).roundToInt(),
                (12 * density).roundToInt(),
            )
            background = GradientDrawable().apply {
                setColor(Color.argb(218, 18, 20, 24))
                cornerRadius = 8 * density
                setStroke((1 * density).roundToInt(), Color.argb(100, 255, 255, 255))
            }
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (88 * density).roundToInt()
        }
        windowManager.addView(view, params)
        return view
    }
}
