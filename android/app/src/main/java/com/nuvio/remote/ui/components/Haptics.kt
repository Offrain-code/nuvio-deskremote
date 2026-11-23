package com.nuvio.remote.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Hardware tactile feedback module (`android.os.Vibrator`) calibrated for
 * crisp response across both LRA and ERM vibration motors.
 * Uses decisive amplitudes (215-255) and short durations (>= 22ms) to overcome motor inertia
 * without prematurely cancelling single taps.
 */
object Haptics {

    @Volatile
    private var lastScrollPulseMs = 0L

    private val audioAttrs: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun vibrateEffect(v: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
            v.vibrate(effect, attrs)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, audioAttrs)
        }
    }

    private fun pulse(context: Context, durationMs: Long, amplitude: Int) {
        val v = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = if (v.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                val effect = VibrationEffect.createOneShot(durationMs, amp)
                vibrateEffect(v, effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    /** Crisp directional pulse for D-Pad navigation (34ms @ 245). */
    fun dpad(context: Context) = pulse(context, 34L, 245)

    /** Rising double-pulse confirmation for OK / Select. */
    fun ok(context: Context) {
        val v = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0L, 24L, 28L, 38L)
                val effect = if (v.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(timings, intArrayOf(0, 220, 0, 255), -1)
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }
                vibrateEffect(v, effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0L, 24L, 28L, 38L), -1)
            }
        } catch (_: Exception) {}
    }

    /** Strong tactile pulse for Play / Pause (42ms @ 255). */
    fun playPause(context: Context) = pulse(context, 42L, 255)

    /** Firm tactile pulse for Volume (+/-) and Channel step buttons (32ms @ 240). */
    fun volume(context: Context) = pulse(context, 32L, 240)

    /** Crisp click for dock shortcuts and primary controls (35ms @ 245). */
    fun action(context: Context) = pulse(context, 35L, 245)

    /** Subtle tap for bottom-dock tab switching and mode pills (30ms @ 230). */
    fun tab(context: Context) = pulse(context, 30L, 230)

    /** Quick double pulse for switches and TV color buttons. */
    fun toggle(context: Context) {
        val v = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0L, 22L, 24L, 28L)
                val effect = if (v.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(timings, intArrayOf(0, 210, 0, 250), -1)
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }
                vibrateEffect(v, effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(32L)
            }
        } catch (_: Exception) {}
    }

    /** Throttled scroll tick (>= 24ms spacing) for smooth mouse scrolling. */
    fun scroll(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScrollPulseMs < 24L) return
        lastScrollPulseMs = now
        pulse(context, 22L, 215)
    }
}
