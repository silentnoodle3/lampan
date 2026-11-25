package com.egron.lampan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal fun isVolumeKeyAccessEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name == VolumeKeyAccessibilityService::class.java.name }
}

/**
 * Intercepts the hardware volume keys system-wide so they can be redirected
 * to the AirPlay receiver's volume instead of the phone's own stream.
 *
 * A MediaSession-based remote volume provider (see RemoteVolumeSession)
 * only wins hardware-key routing if the system treats it as the top
 * priority session, which in practice loses to whatever app is actually
 * playing and holding audio focus (the normal case here, since Lampan
 * captures another app's audio rather than being the one playing it).
 * Filtering the raw key event bypasses that contest entirely.
 *
 * Only consumes the keys while Lampan is actively streaming
 * ([VolumeKeyState.isCapturing]); otherwise returns false so Android
 * handles them as usual.
 *
 * Consuming the key event also suppresses Android's own volume overlay
 * (it's tied to the normal stream-volume path we're bypassing), so this
 * draws its own transient indicator via [VolumeKeyOverlay].
 */
internal class VolumeKeyAccessibilityService : AccessibilityService() {

    private val overlay by lazy { VolumeKeyOverlay(this) }

    override fun onServiceConnected() {
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!VolumeKeyState.isCapturing) return false
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_STEP
            KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_STEP
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val next = (VolumeKeyState.groupPercent + step).coerceIn(0, 100)
            VolumeKeyState.groupPercent = next
            startService(
                Intent(this, AudioCaptureService::class.java).apply {
                    action = "SET_VOLUME"
                    putExtra("VOLUME", next / 100f)
                },
            )
            overlay.show(next)
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        overlay.hideImmediately()
        super.onDestroy()
    }

    private companion object {
        const val VOLUME_STEP = 5
    }
}

/** A small transient volume bar, shown in place of the system volume overlay we suppress. */
private class VolumeKeyOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { root.visibility = View.GONE }

    private val density = service.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private val fill = View(service).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#1DB7A0"))
        }
    }
    private val spacer = View(service)
    private val track = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#CC1A1A1A"))
        }
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(140))
        setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(spacer, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, 50f))
        addView(fill, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, 50f))
    }
    private val label = TextView(service).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, 0)
    }
    private val root = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        addView(track)
        addView(label)
    }

    private var attached = false

    fun show(percent: Int) {
        handler.post {
            if (!attached) attach()
            val clamped = percent.coerceIn(0, 100)
            (spacer.layoutParams as LinearLayout.LayoutParams).weight = (100 - clamped).toFloat()
            (fill.layoutParams as LinearLayout.LayoutParams).weight = clamped.toFloat()
            track.requestLayout()
            label.text = "$clamped%"
            root.visibility = View.VISIBLE
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, 1500)
        }
    }

    fun hideImmediately() {
        handler.post {
            handler.removeCallbacks(hideRunnable)
            if (attached) {
                runCatching { windowManager.removeView(root) }
                attached = false
            }
        }
    }

    private fun attach() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = dp(16)
        }
        windowManager.addView(root, params)
        attached = true
    }
}
