package com.pehredaar

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream

const val TAG = "Pehredaar"
private const val VLM_COOLDOWN_MS = 2500L

// ------------------------------------------------------------------ settings

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("pehredaar", Context.MODE_PRIVATE)

    var motionThreshold: Float
        get() = sp.getFloat("motion_threshold", MotionGate.DEFAULT_THRESHOLD)
        set(v) = sp.edit { putFloat("motion_threshold", v) }

    var sourceKind: SourceKind
        get() = runCatching { SourceKind.valueOf(sp.getString("source", null)!!) }
            .getOrDefault(SourceKind.LOCAL_VIDEO)     // runs before any camera exists
        set(v) = sp.edit { putString("source", v.name) }

    var rtspUrl: String
        get() = sp.getString("rtsp_url", "rtsp://192.168.1.100:554/stream1")!!
        set(v) = sp.edit { putString("rtsp_url", v) }

    var ttsEnabled: Boolean
        get() = sp.getBoolean("tts", true)
        set(v) = sp.edit { putBoolean("tts", v) }

    /** A video the user picked from the device; null means the bundled clip. */
    var localVideoUri: String?
        get() = sp.getString("local_video_uri", null)
        set(v) = sp.edit { putString("local_video_uri", v) }
}

// --------------------------------------------------------------- live stats

data class PipelineStats(
    val running: Boolean = false,
    val source: String = "-",
    val detector: String = "-",
    val analyzer: String = "-",
    val framesSeen: Long = 0,
    val framesDropped: Long = 0,
    val detections: Long = 0,
    val vlmCalls: Long = 0,
    val lastMotionScore: Float = 0f,
    val lastLatencyMs: Long = 0,
)

object PipelineState {
    private val _stats = MutableStateFlow(PipelineStats())
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()
    internal fun update(block: (PipelineStats) -> PipelineStats) = _stats.update(block)
}

// ---------------------------------------------------------------- pipeline

/**
 * frames -> motion gate -> detector -> (cooldown) -> VLM -> event log.
 *
 * Every AI stage is constructed through a `createOrNull` that falls back to the mock, so a missing
 * model or a broken delegate downgrades the pipeline instead of taking the service down.
 */
class Pipeline(private val context: Context) {

    private val db = Db.get(context)
    private val prefs = Prefs(context)
    private val gate = MotionGate(prefs.motionThreshold)
    private val alerter = Alerter(context)
    private val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }

    fun setThreshold(value: Float) {
        gate.threshold = value
        prefs.motionThreshold = value
    }

    suspend fun run() {
        seedRulesIfEmpty()

        val source = when (prefs.sourceKind) {
            SourceKind.STATIC_IMAGE -> StaticImageSource(context)
            SourceKind.LOCAL_VIDEO -> LocalVideoSource(context, prefs.localVideoUri?.let(Uri::parse))
            SourceKind.RTSP -> RtspSource(context, prefs.rtspUrl)
        }
        val detector = TfliteDetector.createOrNull(context) ?: StubDetector()
        val analyzer = NexaVisionAnalyzer.createOrNull(context) ?: MockVisionAnalyzer()

        Log.i(TAG, "pipeline start: source=${source.name} detector=${detector.name} analyzer=${analyzer.name}")
        gate.reset()
        PipelineState.update {
            PipelineStats(running = true, source = source.name, detector = detector.name, analyzer = analyzer.name)
        }

        var lastVlmAt = 0L
        var seen = 0L
        var detections = 0L
        var vlmCalls = 0L

        try {
            source.frames().collect { frame ->
                val frameStart = SystemClock.elapsedRealtime()
                seen++

                if (!gate.accept(frame)) {
                    PipelineState.update {
                        it.copy(framesSeen = seen, framesDropped = gate.dropped, lastMotionScore = gate.lastScore)
                    }
                    if (gate.dropped % 25L == 0L) {
                        Log.d(TAG, "motion gate: dropped=${gate.dropped} passed=${gate.passed} score=%.2f".format(gate.lastScore))
                    }
                    return@collect
                }

                val detectStart = SystemClock.elapsedRealtime()
                val boxes = try {
                    detector.detect(frame)
                } catch (e: Throwable) {
                    Log.e(TAG, "detector failed, skipping frame", e); emptyList()
                }
                val detectMs = SystemClock.elapsedRealtime() - detectStart
                if (boxes.isNotEmpty()) detections++

                val now = SystemClock.elapsedRealtime()
                // Gate the expensive stage twice: something must be there, and the cooldown must be up.
                val shouldAnalyze = boxes.isNotEmpty() && now - lastVlmAt >= VLM_COOLDOWN_MS
                var vlmMs = 0L
                if (shouldAnalyze) {
                    lastVlmAt = now
                    vlmCalls++
                    val rules = db.rules().enabled()
                    val vlmStart = SystemClock.elapsedRealtime()
                    val result = try {
                        analyzer.analyze(frame, rules)
                    } catch (e: Throwable) {
                        Log.e(TAG, "analyzer ${analyzer.name} failed, falling back to mock", e)
                        runCatching { MockVisionAnalyzer().analyze(frame, rules) }.getOrDefault(VisionResult.EMPTY)
                    }
                    vlmMs = SystemClock.elapsedRealtime() - vlmStart
                    if (result.description.isNotBlank()) log(result, frame)
                }

                val totalMs = SystemClock.elapsedRealtime() - frameStart
                Log.d(TAG, "frame#$seen motion=%.2f boxes=${boxes.size} detect=${detectMs}ms vlm=${vlmMs}ms total=${totalMs}ms"
                    .format(gate.lastScore))
                PipelineState.update {
                    it.copy(
                        framesSeen = seen, framesDropped = gate.dropped, detections = detections,
                        vlmCalls = vlmCalls, lastMotionScore = gate.lastScore, lastLatencyMs = totalMs,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "pipeline stopped on error", e)
        } finally {
            runCatching { detector.close() }
            runCatching { analyzer.close() }
            runCatching { alerter.close() }
            PipelineState.update { it.copy(running = false) }
            Log.i(TAG, "pipeline stop: seen=$seen dropped=${gate.dropped} detections=$detections vlmCalls=$vlmCalls")
        }
    }

    private suspend fun log(result: VisionResult, frame: Bitmap) {
        val timestamp = System.currentTimeMillis()
        val thumb = runCatching { writeThumb(frame, timestamp) }.getOrNull()
        val event = Event(
            timestamp = timestamp,
            description = result.description,
            ruleId = result.triggeredRules.firstOrNull(),
            severity = result.severity,
            thumbnailPath = thumb,
        )
        db.events().insert(event)
        Log.i(TAG, "event [${result.severity}] ${result.description} rules=${result.triggeredRules}")
        // Never let a notification or a dead TTS engine take the pipeline down with it.
        runCatching { alerter.fire(event, speak = prefs.ttsEnabled) }
            .onFailure { Log.e(TAG, "alerting failed", it) }
    }

    private fun writeThumb(frame: Bitmap, timestamp: Long): String {
        val file = File(thumbDir, "$timestamp.jpg")
        val scaled = Bitmap.createScaledBitmap(frame, 240, 135, true)
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (scaled !== frame) scaled.recycle()
        return file.absolutePath
    }

    private suspend fun seedRulesIfEmpty() {
        if (db.rules().count() > 0) return
        // Seed data so a fresh install has something to demo; the Rules screen edits these freely.
        listOf(
            "Alert me if someone climbs the boundary wall",
            "गेट के पास कोई पाँच मिनट से ज़्यादा रुके तो बताओ",
            "Tell me if a vehicle is parked blocking the gate",
        ).forEach { db.rules().insert(Rule(text = it)) }
    }
}
