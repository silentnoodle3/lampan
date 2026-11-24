package com.egron.lampan

/**
 * Process-local bridge between [AudioCaptureService] (which owns the real
 * volume state) and [VolumeKeyAccessibilityService] (which runs as a
 * separate component but the same process, and has no other way to reach
 * the service without a slower, heavier IPC mechanism).
 */
internal object VolumeKeyState {
    @Volatile var isCapturing: Boolean = false
    @Volatile var groupPercent: Int = 50
}
