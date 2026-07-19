package io.github.stwbeery.globallivetranslator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.stwbeery.globallivetranslator.data.AppSettings
import io.github.stwbeery.globallivetranslator.data.OverlayPosition
import kotlin.math.roundToInt

class CaptionOverlay(
    private val context: Context,
    private val onPositionChanged: (OverlayPosition) -> Unit = {},
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderLock = Any()
    private var rootView: LinearLayout? = null
    private var captionView: TextView? = null
    private var dragHandle: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var appearance = AppSettings()
    private var position: OverlayPosition? = null
    private var attachRequested = false
    private var attachRetryIndex = 0
    private var attachRetry: Runnable? = null
    private var latestText = ""
    private var renderScheduled = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0

    private val renderLatest = object : Runnable {
        override fun run() {
            val text = synchronized(renderLock) {
                renderScheduled = false
                latestText
            }
            if (ensureAttached()) {
                captionView?.text = text
                rootView?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            val changed = synchronized(renderLock) {
                if (latestText != text && !renderScheduled) {
                    renderScheduled = true
                    true
                } else {
                    false
                }
            }
            if (changed) mainHandler.post(this)
        }
    }

    fun attach(settings: AppSettings, savedPosition: OverlayPosition?, initialText: String) {
        synchronized(renderLock) { latestText = initialText.trim() }
        mainHandler.post {
            appearance = settings
            position = savedPosition
            attachRequested = true
            attachRetryIndex = 0
            if (ensureAttached()) {
                applyAppearance()
                rootView?.post { applyPosition(position) }
            }
            scheduleRender()
        }
    }

    fun show(text: String) {
        synchronized(renderLock) { latestText = text.trim() }
        scheduleRender()
    }

    fun remove() {
        mainHandler.post {
            attachRequested = false
            attachRetry?.let(mainHandler::removeCallbacks)
            attachRetry = null
            mainHandler.removeCallbacks(renderLatest)
            synchronized(renderLock) {
                latestText = ""
                renderScheduled = false
            }
            val view = rootView
            rootView = null
            captionView = null
            dragHandle = null
            layoutParams = null
            if (view != null) runCatching { windowManager.removeView(view) }
        }
    }

    private fun scheduleRender() {
        val post = synchronized(renderLock) {
            if (renderScheduled) false else {
                renderScheduled = true
                true
            }
        }
        if (post) mainHandler.post(renderLatest)
    }

    private fun ensureAttached(): Boolean {
        rootView?.let { existing ->
            if (existing.isAttachedToWindow) return true
            clearViewReferences(existing, removeFromWindow = false)
        }
        if (!attachRequested || !Settings.canDrawOverlays(context)) {
            scheduleAttachRetry()
            return false
        }
        return try {
            createView()
            attachRetry?.let(mainHandler::removeCallbacks)
            attachRetry = null
            attachRetryIndex = 0
            true
        } catch (_: SecurityException) {
            scheduleAttachRetry()
            false
        } catch (_: WindowManager.BadTokenException) {
            scheduleAttachRetry()
            false
        } catch (_: IllegalArgumentException) {
            scheduleAttachRetry()
            false
        }
    }

    private fun scheduleAttachRetry() {
        if (!attachRequested || attachRetry != null || attachRetryIndex >= ATTACH_RETRY_MS.size) return
        val runnable = Runnable {
            attachRetry = null
            scheduleRender()
        }
        attachRetry = runnable
        mainHandler.postDelayed(runnable, ATTACH_RETRY_MS[attachRetryIndex++])
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createView() {
        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val handle = TextView(context).apply {
            text = "拖动字幕"
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, (5 * density).roundToInt(), 0, (3 * density).roundToInt())
            setOnTouchListener { view, event -> handleDrag(view, event) }
        }
        val caption = TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = MAX_LINES
            ellipsize = TextUtils.TruncateAt.END
            setPadding(
                (18 * density).roundToInt(),
                (10 * density).roundToInt(),
                (18 * density).roundToInt(),
                (12 * density).roundToInt(),
            )
            setOnTouchListener { view, event -> handleDrag(view, event) }
        }
        val initialText = synchronized(renderLock) { latestText }
        caption.text = initialText
        root.visibility = if (initialText.isBlank()) View.GONE else View.VISIBLE
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
            if (sizeChanged) applyPosition(position)
        }
        root.addView(
            handle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            caption,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            overlayWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(appearance.overlayPositionLocked),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(root, params)
        rootView = root
        captionView = caption
        dragHandle = handle
        layoutParams = params
        applyAppearance()
        root.post { applyPosition(position) }
    }

    private fun applyAppearance() {
        val root = rootView ?: return
        val density = context.resources.displayMetrics.density
        val windowAlpha = overlayWindowAlpha(appearance.overlayPositionLocked)
        val drawableAlpha = (appearance.overlayBackgroundOpacity / windowAlpha).coerceIn(0f, 1f)
        captionView?.textSize = appearance.overlayFontSizeSp
        dragHandle?.visibility = if (appearance.overlayPositionLocked) View.GONE else View.VISIBLE
        root.background = GradientDrawable().apply {
            setColor(
                Color.argb(
                    (drawableAlpha * 255).roundToInt(),
                    18,
                    20,
                    24,
                ),
            )
            cornerRadius = 8 * density
            setStroke((1 * density).roundToInt(), Color.argb(100, 255, 255, 255))
        }
        layoutParams?.let { params ->
            params.width = overlayWidth()
            params.flags = baseFlags(appearance.overlayPositionLocked)
            params.alpha = windowAlpha
            updateViewLayoutSafely(root, params)
        }
    }

    private fun baseFlags(locked: Boolean): Int {
        val touchFlags = if (locked) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            touchFlags
    }

    private fun overlayWindowAlpha(locked: Boolean): Float {
        if (!locked || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f
        val maximum = context.getSystemService(InputManager::class.java)
            ?.maximumObscuringOpacityForTouch
            ?: FALLBACK_MAX_OBSCURING_ALPHA
        // Android 12+ drops touches behind non-touchable overlays above this threshold.
        return (maximum - TOUCH_ALPHA_MARGIN).coerceIn(MIN_TOUCH_THROUGH_ALPHA, 1f)
    }

    private fun handleDrag(view: View, event: MotionEvent): Boolean {
        if (appearance.overlayPositionLocked) return false
        val params = layoutParams ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = dragStartX + (event.rawX - dragStartRawX).roundToInt()
                params.y = dragStartY + (event.rawY - dragStartRawY).roundToInt()
                clampToSafeBounds(params)
                rootView?.let { updateViewLayoutSafely(it, params) }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                clampToSafeBounds(params)
                persistCurrentPosition(params)
                view.performClick()
                true
            }
            else -> false
        }
    }

    private fun applyPosition(savedPosition: OverlayPosition?) {
        val view = rootView ?: return
        val params = layoutParams ?: return
        val safe = safeBounds()
        val availableX = (safe.width() - view.width).coerceAtLeast(0)
        val availableY = (safe.height() - view.height).coerceAtLeast(0)
        if (savedPosition != null) {
            params.x = safe.left + (availableX * savedPosition.xFraction.coerceIn(0f, 1f)).roundToInt()
            params.y = safe.top + (availableY * savedPosition.yFraction.coerceIn(0f, 1f)).roundToInt()
        } else {
            val bottomMargin = (88 * context.resources.displayMetrics.density).roundToInt()
            params.x = safe.left + availableX / 2
            params.y = (safe.top + availableY - bottomMargin).coerceAtLeast(safe.top)
        }
        clampToSafeBounds(params)
        updateViewLayoutSafely(view, params)
    }

    private fun updateViewLayoutSafely(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (_: SecurityException) {
            clearViewReferences(view, removeFromWindow = true)
            false
        } catch (_: IllegalArgumentException) {
            clearViewReferences(view, removeFromWindow = true)
            false
        }
    }

    private fun clearViewReferences(view: View, removeFromWindow: Boolean) {
        if (rootView !== view) return
        if (removeFromWindow) runCatching { windowManager.removeView(view) }
        rootView = null
        captionView = null
        dragHandle = null
        layoutParams = null
        attachRetryIndex = 0
        scheduleAttachRetry()
    }

    private fun clampToSafeBounds(params: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val safe = safeBounds()
        val maxX = (safe.right - view.width).coerceAtLeast(safe.left)
        val maxY = (safe.bottom - view.height).coerceAtLeast(safe.top)
        params.x = params.x.coerceIn(safe.left, maxX)
        params.y = params.y.coerceIn(safe.top, maxY)
    }

    private fun persistCurrentPosition(params: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val safe = safeBounds()
        val availableX = (safe.width() - view.width).coerceAtLeast(0)
        val availableY = (safe.height() - view.height).coerceAtLeast(0)
        val updated = OverlayPosition(
            xFraction = if (availableX == 0) {
                0.5f
            } else {
                ((params.x - safe.left).toFloat() / availableX).coerceIn(0f, 1f)
            },
            yFraction = if (availableY == 0) {
                0.5f
            } else {
                ((params.y - safe.top).toFloat() / availableY).coerceIn(0f, 1f)
            },
        )
        position = updated
        onPositionChanged(updated)
    }

    private fun overlayWidth(): Int = (safeBounds().width() * 0.92f).roundToInt()

    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
        }
        @Suppress("DEPRECATION")
        return Rect(
            0,
            0,
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
        )
    }

    private companion object {
        const val MAX_LINES = 4
        const val FALLBACK_MAX_OBSCURING_ALPHA = 0.8f
        const val TOUCH_ALPHA_MARGIN = 0.01f
        const val MIN_TOUCH_THROUGH_ALPHA = 0.1f
        val ATTACH_RETRY_MS = longArrayOf(250, 750, 1_500)
    }
}
