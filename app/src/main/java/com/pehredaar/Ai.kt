package com.pehredaar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.io.Closeable
import kotlin.math.abs

// ---------------------------------------------------------------- motion gate

/**
 * Cheapest stage in the pipeline: 64x64 grayscale mean-absolute-difference against the previous frame.
 * Everything downstream only runs on frames that clear [threshold].
 */
fun meanAbsDiff(a: IntArray, b: IntArray): Float {
    require(a.size == b.size) { "frame size changed: ${a.size} vs ${b.size}" }
    var sum = 0L
    for (i in a.indices) sum += abs(a[i] - b[i])
    return sum.toFloat() / a.size
}

class MotionGate(@Volatile var threshold: Float = DEFAULT_THRESHOLD) {

    private var previous: IntArray? = null
    var passed = 0L; private set
    var dropped = 0L; private set
    var lastScore = 0f; private set

    fun accept(frame: Bitmap): Boolean {
        val current = frame.toGray64()
        val prev = previous
        previous = current
        if (prev == null) {                       // first frame always passes
            passed++
            return true
        }
        lastScore = meanAbsDiff(current, prev)
        return (lastScore >= threshold).also { if (it) passed++ else dropped++ }
    }

    fun reset() {
        previous = null; passed = 0; dropped = 0; lastScore = 0f
    }

    private val scratch = IntArray(SIZE * SIZE)

    private fun Bitmap.toGray64(): IntArray {
        val small = Bitmap.createScaledBitmap(this, SIZE, SIZE, true)
        small.getPixels(scratch, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== this) small.recycle()
        val out = IntArray(scratch.size)
        for (i in scratch.indices) {
            val p = scratch[i]
            // ITU-R BT.601 luma, integer math.
            out[i] = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return out
    }

    companion object {
        // Measured on the bundled clip: still frames score 0.00, a walking figure ~2.1.
        // ponytail: a real camera has sensor noise and will idle nearer 0.5-2 — retune on the slider.
        const val DEFAULT_THRESHOLD = 1f
        const val SIZE = 64
    }
}

// ------------------------------------------------------------------ detector

data class Box(val label: String, val confidence: Float, val rect: RectF)

/** [rect] is normalised 0..1 so callers do not care about the source resolution. */
interface Detector : Closeable {
    val name: String
    fun detect(frame: Bitmap): List<Box>
    override fun close() {}
}

/** Hardcoded box so the pipeline runs end to end with no model file present. */
class StubDetector : Detector {
    override val name = "stub"
    override fun detect(frame: Bitmap) = listOf(
        Box("person", 0.87f, RectF(0.34f, 0.40f, 0.58f, 0.88f))
    )
}

/**
 * LiteRT object detector. Delegate preference: QNN (Qualcomm NPU) -> NNAPI -> CPU.
 *
 * QNN is loaded reflectively: its artifact is not on a public Maven repo, so hard-linking it would
 * break the build for everyone without it. When the .aar is dropped in, this picks it up with no
 * code change. [createOrNull] returns null when the model asset is missing, and the pipeline falls
 * back to [StubDetector] rather than failing to start.
 */
class TfliteDetector private constructor(
    private val interpreter: org.tensorflow.lite.Interpreter,
    private val delegate: AutoCloseable?,
    override val name: String,
    private val labels: List<String>,
    private val inputSize: Int,
) : Detector {

    private val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
    private val locations = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
    private val classes = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val scores = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val count = FloatArray(1)

    override fun detect(frame: Bitmap): List<Box> = try {
        val scaled = Bitmap.createScaledBitmap(frame, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (scaled !== frame) scaled.recycle()
        for (y in 0 until inputSize) for (x in 0 until inputSize) {
            val p = pixels[y * inputSize + x]
            input[0][y][x][0] = (p shr 16 and 0xFF) / 255f
            input[0][y][x][1] = (p shr 8 and 0xFF) / 255f
            input[0][y][x][2] = (p and 0xFF) / 255f
        }
        interpreter.runForMultipleInputsOutputs(
            arrayOf<Any>(input),
            mapOf<Int, Any>(0 to locations, 1 to classes, 2 to scores, 3 to count),
        )
        // Standard SSD head: [ymin, xmin, ymax, xmax] normalised.
        (0 until count[0].toInt().coerceAtMost(MAX_DETECTIONS))
            .filter { scores[0][it] >= MIN_CONFIDENCE }
            .map { i ->
                val b = locations[0][i]
                Box(
                    labels.getOrElse(classes[0][i].toInt()) { "class_${classes[0][i].toInt()}" },
                    scores[0][i],
                    RectF(b[1], b[0], b[3], b[2]),
                )
            }
    } catch (e: Throwable) {
        Log.e(TAG, "TfliteDetector.detect failed", e)
        emptyList()
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { delegate?.close() }
    }

    companion object {
        private const val MAX_DETECTIONS = 10
        private const val MIN_CONFIDENCE = 0.45f

        fun createOrNull(
            context: Context,
            modelAsset: String = "detector.tflite",
            labelAsset: String = "labels.txt",
            inputSize: Int = 300,
        ): TfliteDetector? {
            val model = try {
                context.assets.openFd(modelAsset).use { fd ->
                    fd.createInputStream().use { stream ->
                        stream.channel.map(
                            java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "no $modelAsset in assets — staying on StubDetector")
                return null
            }
            val labels = runCatching {
                context.assets.open(labelAsset).bufferedReader().readLines()
            }.getOrDefault(emptyList())

            for ((delegateName, make) in delegateChain()) {
                var delegate: AutoCloseable? = null
                try {
                    delegate = make()
                    val options = org.tensorflow.lite.Interpreter.Options()
                    (delegate as? org.tensorflow.lite.Delegate)?.let { options.addDelegate(it) }
                    val interpreter = org.tensorflow.lite.Interpreter(model, options)
                    Log.i(TAG, "detector delegate initialised: $delegateName")
                    return TfliteDetector(interpreter, delegate, "tflite/$delegateName", labels, inputSize)
                } catch (e: Throwable) {
                    Log.w(TAG, "delegate $delegateName unavailable: ${e.message}")
                    runCatching { delegate?.close() }
                }
            }
            Log.e(TAG, "no delegate could be initialised, not even CPU")
            return null
        }

        /** Ordered candidates. Each entry builds a delegate or throws; CPU is the null-delegate case. */
        private fun delegateChain(): List<Pair<String, () -> AutoCloseable?>> = listOf(
            "qnn" to {
                // ponytail: reflection keeps the QNN .aar optional; swap to a direct call once it is vendored.
                val cls = Class.forName("com.qualcomm.qti.QnnDelegate")
                val optionsCls = Class.forName("com.qualcomm.qti.QnnDelegate\$Options")
                cls.getConstructor(optionsCls).newInstance(optionsCls.getConstructor().newInstance()) as AutoCloseable
            },
            "nnapi" to { org.tensorflow.lite.nnapi.NnApiDelegate() },
            "cpu" to { null },
        )
    }
}

// ------------------------------------------------------------------- vlm

data class VisionResult(
    val description: String,
    val triggeredRules: List<Long>,
    val severity: String,   // info | warn | alert
) {
    companion object {
        val EMPTY = VisionResult("", emptyList(), "info")

        /** Tolerant of the model wrapping JSON in prose or a ``` fence. */
        fun fromJson(raw: String): VisionResult {
            val body = raw.substringAfter('{', "").substringBeforeLast('}', "")
            require(body.isNotBlank()) { "no JSON object in model output" }
            val json = org.json.JSONObject("{$body}")
            val ids = json.optJSONArray("triggeredRules")
            return VisionResult(
                description = json.optString("description").ifBlank { "(no description)" },
                triggeredRules = (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optLong(it) },
                severity = json.optString("severity", "info")
                    .lowercase().takeIf { it in setOf("info", "warn", "alert") } ?: "info",
            )
        }
    }
}

interface VisionAnalyzer : Closeable {
    val name: String
    suspend fun analyze(frame: Bitmap, rules: List<Rule>): VisionResult
    override fun close() {}
}

/** Rules stay natural language — they are pasted into the prompt, never parsed into conditions. */
fun buildPrompt(rules: List<Rule>): String = buildString {
    append("You are a CCTV analyst. Look at the image and answer only with JSON.\n")
    append("Active rules (id: rule):\n")
    if (rules.isEmpty()) append("  (none)\n")
    rules.forEach { append("  ${it.id}: ${it.text}\n") }
    append("Reply exactly: {\"description\":\"<one short sentence>\",")
    append("\"triggeredRules\":[<ids of rules the image violates>],")
    append("\"severity\":\"info|warn|alert\"}")
}

/** Canned results so the timeline and alerts are testable before any model loads. */
class MockVisionAnalyzer : VisionAnalyzer {
    override val name = "mock"
    private var i = 0

    override suspend fun analyze(frame: Bitmap, rules: List<Rule>): VisionResult {
        val (description, severity) = CANNED[i++ % CANNED.size]
        // Attribute to a rule roughly a third of the time so both branches of the UI get exercised.
        val rule = if (rules.isNotEmpty() && severity != "info") rules[i % rules.size] else null
        return VisionResult(description, listOfNotNull(rule?.id), severity)
    }

    private companion object {
        val CANNED = listOf(
            "A person is walking across the yard." to "info",
            "गेट के पास एक आदमी खड़ा है।" to "warn",
            "Someone is loitering near the parked bike." to "warn",
            "A person climbed over the boundary wall." to "alert",
            "The yard is quiet, one person passing through." to "info",
            "दो लोग दरवाज़े के पास रुके हुए हैं।" to "alert",
        )
    }
}

/**
 * On-device Nexa VLM. Not wired: the Nexa Android SDK is not published to a public Maven repo, so
 * pulling it in would mean adding a fetch step I was told to flag instead of doing.
 * [createOrNull] returns null and the pipeline stays on [MockVisionAnalyzer].
 * The prompt and the JSON contract above are the real ones, so wiring it is a one-function change.
 */
class NexaVisionAnalyzer private constructor() : VisionAnalyzer {
    override val name = "nexa"
    override suspend fun analyze(frame: Bitmap, rules: List<Rule>): VisionResult =
        throw UnsupportedOperationException("Nexa runtime not wired yet")

    companion object {
        fun createOrNull(@Suppress("UNUSED_PARAMETER") context: Context): NexaVisionAnalyzer? {
            Log.i(TAG, "Nexa SDK not present — staying on MockVisionAnalyzer")
            return null
        }
    }
}

// ------------------------------------------------------------- ask (text-only)

/** Question answering over the event log. Text in, text out — no frames involved. */
interface LogQa : Closeable {
    val name: String
    suspend fun ask(question: String, events: List<Event>): String
    override fun close() {}
}

fun buildAskPrompt(question: String, events: List<Event>): String = buildString {
    append("These are the recent events from a CCTV log, newest first:\n")
    if (events.isEmpty()) append("  (the log is empty)\n")
    events.forEach { append("  [${it.severity}] ${it.description}\n") }
    append("\nAnswer the question in the same language it was asked, in one or two sentences. ")
    append("Use only the events above; if they do not contain the answer, say so.\n")
    append("Question: ").append(question)
}

private val DEVANAGARI = '\u0900'..'\u097F'

fun String.isHindi(): Boolean = any { it in DEVANAGARI }

/**
 * Keyword matching over the log, so the Ask screen answers something true before a model loads.
 * ponytail: bag-of-words, no synonyms or time reasoning — that is exactly the part the VLM replaces.
 */
class MockLogQa : LogQa {
    override val name = "mock"

    override suspend fun ask(question: String, events: List<Event>): String {
        val hindi = question.isHindi()
        if (events.isEmpty()) {
            return if (hindi) "लॉग अभी खाली है।" else "The log is empty — nothing has been recorded yet."
        }
        val asked = question.lowercase()
        // "how many alerts" / "कितने अलर्ट" is a question about severity, not about words in the
        // descriptions — match it against the severity column instead of the text.
        val severity = SEVERITY_WORDS.entries.firstOrNull { (word, _) -> asked.contains(word) }?.value
        val terms = asked
            // \p{M} is load-bearing: Devanagari matras are combining marks, not letters, so leaving
            // them out of the token class splits "कितने" into "क" + "तन" and Hindi never matches.
            .split(Regex("[^\\p{L}\\p{N}\\p{M}]+"))
            // `contains`, not `!in`: "alerts" must drop out too, or the plural survives as a search
            // term and filters away the very events the severity match just found.
            .filter { t -> t.length >= 2 && t !in STOPWORDS && SEVERITY_WORDS.keys.none { t.contains(it) } }

        var hits = events
        if (severity != null) hits = hits.filter { it.severity == severity }
        if (terms.isNotEmpty()) hits = hits.filter { e -> terms.any { e.description.lowercase().contains(it) } }

        val narrowed = severity != null || terms.isNotEmpty()
        if (narrowed && hits.isEmpty()) {
            return if (hindi) "इससे मेल खाती कोई घटना नहीं मिली।"
            else "Nothing in the last ${events.size} events matches that."
        }
        val list = if (narrowed) hits else events
        val lead = when {
            narrowed -> if (hindi) "${list.size} ${severity?.let { SEVERITY_HINDI[it] + " " } ?: ""}घटनाएँ मिलीं।"
                        else "Found ${list.size} matching ${severity?.let { "$it " } ?: ""}event(s)."
            else -> if (hindi) "पूरे लॉग में ${list.size} घटनाएँ हैं।" else "Summarising all ${list.size} events."
        }
        val alerts = list.count { it.severity == "alert" }
        val warns = list.count { it.severity == "warn" }
        val newest = list.first()
        val time = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(newest.timestamp))
        return if (hindi) {
            "$lead $alerts अलर्ट, $warns चेतावनी। नवीनतम ($time): ${newest.description}"
        } else {
            "$lead $alerts alert, $warns warn. Latest ($time): ${newest.description}"
        }
    }

    private companion object {
        val SEVERITY_WORDS = mapOf(
            "alert" to "alert", "अलर्ट" to "alert",
            "warn" to "warn", "warning" to "warn", "चेतावनी" to "warn",
            "info" to "info", "सूचना" to "info",
        )
        val SEVERITY_HINDI = mapOf("alert" to "अलर्ट", "warn" to "चेतावनी", "info" to "सूचना")
        val STOPWORDS = setOf(
            "the", "was", "were", "did", "any", "and", "for", "you", "how", "many", "when", "what",
            "where", "who", "there", "has", "have", "show", "tell", "give", "about", "que",
            "क्या", "कब", "कहाँ", "कहां", "कितने", "कितनी", "कौन", "है", "हैं", "था", "थे", "थी",
            "में", "के", "का", "की", "को", "से", "पर", "मुझे", "बताओ", "कोई",
        )
    }
}

/** Same story as [NexaVisionAnalyzer]: the SDK is not on a public repo, so the mock stands in. */
class NexaLogQa private constructor() : LogQa {
    override val name = "nexa"
    override suspend fun ask(question: String, events: List<Event>): String =
        throw UnsupportedOperationException("Nexa runtime not wired yet")

    companion object {
        fun createOrNull(@Suppress("UNUSED_PARAMETER") context: Context): NexaLogQa? = null
    }
}
