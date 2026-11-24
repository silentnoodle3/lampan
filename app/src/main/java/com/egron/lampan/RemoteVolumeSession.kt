package com.egron.lampan

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.VolumeProviderCompat

/**
 * Lets the phone's hardware volume buttons (and the lock screen's volume UI)
 * control the group volume of whatever Lampan is currently streaming to,
 * instead of the phone's own local media stream. This is the same mechanism
 * Bluetooth speakers and real casting apps use (an absolute-volume remote
 * MediaSession), so it also works while the screen is off or the app isn't
 * in the foreground, as long as the service's session stays the active one.
 *
 * [onVolumeRequested] is called with a 0-100 percent whenever the OS asks
 * for a new volume (a button press, or a drag on the lock screen's slider).
 * Call [updateVolume] whenever the real volume changes for some other
 * reason (the in-app slider, a restored preference) so the system's own
 * volume UI stays in sync instead of silently going stale.
 */
internal class RemoteVolumeSession(
    context: Context,
    private val onVolumeRequested: (percent: Int) -> Unit,
) {
    private var currentPercent: Int = 50

    private val volumeProvider = object : VolumeProviderCompat(
        VOLUME_CONTROL_ABSOLUTE,
        MAX_VOLUME,
        currentPercent,
    ) {
        override fun onSetVolumeTo(volume: Int) {
            val clamped = volume.coerceIn(0, MAX_VOLUME)
            currentPercent = clamped
            this.currentVolume = clamped
            onVolumeRequested(clamped)
        }

        override fun onAdjustVolume(direction: Int) {
            val clamped = (currentPercent + direction * VOLUME_STEP).coerceIn(0, MAX_VOLUME)
            currentPercent = clamped
            this.currentVolume = clamped
            onVolumeRequested(clamped)
        }
    }

    private val session = MediaSessionCompat(context, "LampanRemoteVolume").apply {
        setPlaybackToRemote(volumeProvider)
        setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                .build(),
        )
    }

    val sessionToken: MediaSessionCompat.Token get() = session.sessionToken

    /** Call once capture actually starts; makes this the volume-key target. */
    fun start(initialPercent: Int) {
        currentPercent = initialPercent.coerceIn(0, MAX_VOLUME)
        volumeProvider.currentVolume = currentPercent
        session.isActive = true
    }

    /** Reflects a volume change that came from somewhere other than a button press. */
    fun updateVolume(percent: Int) {
        val clamped = percent.coerceIn(0, MAX_VOLUME)
        if (clamped == currentPercent) return
        currentPercent = clamped
        volumeProvider.currentVolume = clamped
    }

    /** Call when capture stops; releases this as the volume-key target. */
    fun stop() {
        session.isActive = false
        session.release()
    }

    private companion object {
        const val MAX_VOLUME = 100
        const val VOLUME_STEP = 5
    }
}
