package com.egron.lampan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

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
 */
internal class VolumeKeyAccessibilityService : AccessibilityService() {

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
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    private companion object {
        const val VOLUME_STEP = 5
    }
}
