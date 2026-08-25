package com.pehredaar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fires a heads-up notification for warn/alert events, and optionally reads them out in Hindi.
 *
 * TTS init is asynchronous, so an event arriving before the engine is ready is held in [pending]
 * and spoken on init — otherwise the very first alert, the one you most want to hear, is silent.
 */
class Alerter(private val context: Context) : Closeable {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val ids = AtomicInteger(100)

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var pending: String? = null

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pehredaar alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Rule matches seen by the camera" }
        )
    }

    fun fire(event: Event, speak: Boolean) {
        if (event.severity == "info") return          // the timeline is enough for those
        notify(event)
        if (speak) speak(event.description)
    }

    private fun notify(event: Event) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(if (event.severity == "alert") "Alert · अलर्ट" else "Warning · चेतावनी")
            .setContentText(event.description)
            .setStyle(Notification.BigTextStyle().bigText(event.description))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Rolling ids so consecutive alerts stack instead of overwriting one another.
        manager.notify(ids.getAndIncrement(), notification)
    }

    private fun speak(text: String) {
        pending = text
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TTS init failed ($status), alerts will be silent")
                    return@TextToSpeech
                }
                val result = tts?.setLanguage(HINDI)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "hi-IN voice data missing, falling back to the device default")
                    tts?.language = Locale.getDefault()
                } else {
                    Log.i(TAG, "TTS ready: hi-IN")
                }
                ttsReady = true
                pending?.let { say(it) }
            }
            return
        }
        if (ttsReady) say(text) else Log.d(TAG, "TTS not ready yet, queued: $text")
    }

    private fun say(text: String) {
        // FLUSH, not ADD: with a 2.5s VLM cooldown a queue would fall behind the camera and read stale events.
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pehredaar")
        pending = null
    }

    override fun close() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private companion object {
        const val CHANNEL_ID = "pehredaar_alerts"
        val HINDI: Locale = Locale("hi", "IN")
    }
}
