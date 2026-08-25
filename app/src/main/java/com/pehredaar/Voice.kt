package com.pehredaar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

/** What the mic button is currently doing, so the UI can show it without owning the recogniser. */
data class VoiceState(
    val available: Boolean,
    val listening: Boolean = false,
    val partial: String = "",
    val status: String = "",
)

/**
 * Dictation for rule text, in Hindi. Partial results stream into the field as you speak so the
 * demo shows something happening before the final result lands.
 *
 * On a bare emulator there is usually no recognition service at all — [VoiceState.available] is
 * false and the UI says so rather than the button silently doing nothing.
 */
@Composable
fun rememberVoiceInput(onFinal: (String) -> Unit): Pair<VoiceState, () -> Unit> {
    val context = LocalContext.current
    val finalCallback by rememberUpdatedState(onFinal)
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            Log.w(TAG, "no speech recognition service on this device")
            null
        }
    }
    var state by remember {
        mutableStateOf(
            VoiceState(
                available = recognizer != null,
                status = if (recognizer == null) "No speech service on this device — type instead." else "",
            )
        )
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                state = state.copy(listening = true, partial = "", status = "बोलिए… (listening)")
            }

            override fun onPartialResults(partialResults: Bundle) {
                partialResults.text()?.let { state = state.copy(partial = it) }
            }

            override fun onResults(results: Bundle) {
                val text = results.text()
                state = state.copy(listening = false, partial = "", status = if (text == null) "Nothing heard." else "")
                text?.let(finalCallback)
            }

            override fun onError(error: Int) {
                state = state.copy(listening = false, partial = "", status = errorText(error))
                Log.w(TAG, "speech recogniser error $error")
            }

            override fun onEndOfSpeech() { state = state.copy(listening = false) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { recognizer?.destroy() }
    }

    fun start() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, HINDI)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, HINDI)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { state = state.copy(listening = false, status = "Could not start: ${it.message}") }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else state = state.copy(status = "Microphone permission denied.")
    }

    return state to {
        when {
            recognizer == null -> Unit
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> start()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

private const val HINDI = "hi-IN"

private fun Bundle.text(): String? =
    getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

private fun errorText(error: Int) = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        "The device recogniser wanted the network. Install offline hi-IN in Settings > Voice input."
    // 12/13 are what a device without the offline Hindi pack actually reports — the emulator hits
    // 13 every time, so say what to install rather than printing a bare code.
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "hi-IN speech pack not installed. Settings > System > Languages > Voice input, or type the rule."
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Could not check hi-IN support on this device."
    SpeechRecognizer.ERROR_AUDIO -> "Microphone unavailable."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy, try again."
    else -> "Speech error $error."
}
