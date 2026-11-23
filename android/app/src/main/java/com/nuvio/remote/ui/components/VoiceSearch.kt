package com.nuvio.remote.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.remote.ui.i18n.l10n

/**
 * Voice search using the native Android speech recognizer (no microphone permission required).
 */
@Composable
fun rememberVoiceSearchLauncher(prompt: String? = null, onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val resolvedPrompt = prompt ?: l10n("Speak what to search in Nuvio", "Fale o que deseja buscar no Nuvio")
    val unavailableMsg = l10n(
        "Voice input is unavailable on this device.",
        "Entrada por voz indisponível neste aparelho.",
    )
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (!spoken.isNullOrEmpty()) {
            callback(spoken)
        }
    }

    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, resolvedPrompt)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                unavailableMsg,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
