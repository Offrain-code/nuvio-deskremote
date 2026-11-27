package com.nuvio.remote.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Optional phone-call observer that pauses Nuvio playback when a call rings/starts
 * and optionally resumes when the call ends (DESIGN-SPEC §5.15.2).
 * Only registers when explicitly enabled in Settings and READ_PHONE_STATE is granted.
 */
class CallPauseObserver(
    private val context: Context,
    private val isPlayingProvider: () -> Boolean,
    private val resumeAfterCallProvider: () -> Boolean,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private val telephony = context.applicationContext.getSystemService(TelephonyManager::class.java)
    private var pausedByCall = false
    private var registered = false
    private var callback31: Any? = null

    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null

    fun start() {
        if (registered || telephony == null) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerApi31(telephony)
            } else {
                registerLegacy(telephony)
            }
            registered = true
        }
    }

    fun stop() {
        if (!registered || telephony == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (callback31 as? TelephonyCallback)?.let { telephony.unregisterTelephonyCallback(it) }
                callback31 = null
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { telephony.listen(it, PhoneStateListener.LISTEN_NONE) }
                legacyListener = null
            }
        }
        registered = false
        pausedByCall = false
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK,
            -> {
                if (!pausedByCall && isPlayingProvider()) {
                    pausedByCall = true
                    onPause()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (pausedByCall) {
                    pausedByCall = false
                    if (resumeAfterCallProvider()) {
                        onResume()
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31(tm: TelephonyManager) {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallState(state)
            }
        }
        tm.registerTelephonyCallback(context.mainExecutor, cb)
        callback31 = cb
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(tm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallState(state)
            }
        }
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacyListener = listener
    }
}
