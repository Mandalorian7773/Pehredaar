package com.pehredaar

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How many recent descriptions the Ask screen hands the model. */
private const val ASK_CONTEXT_EVENTS = 50

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val db = Db.get(app)
    private val qa: LogQa = NexaLogQa.createOrNull(app) ?: MockLogQa()

    val stats = PipelineState.stats

    val events = db.events().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rules = db.rules().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- timeline search: plain substring match over descriptions, Hindi included.
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    val visibleEvents = combine(events, _query) { list, q ->
        if (q.isBlank()) list else list.filter { it.description.contains(q.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) { _query.value = value }

    // ---- settings
    private val _threshold = MutableStateFlow(prefs.motionThreshold)
    val threshold = _threshold.asStateFlow()

    private val _source = MutableStateFlow(prefs.sourceKind)
    val source = _source.asStateFlow()

    private val _rtspUrl = MutableStateFlow(prefs.rtspUrl)
    val rtspUrl = _rtspUrl.asStateFlow()

    private val _tts = MutableStateFlow(prefs.ttsEnabled)
    val tts = _tts.asStateFlow()

    private val _videoUri = MutableStateFlow(prefs.localVideoUri)
    val videoUri = _videoUri.asStateFlow()

    fun setThreshold(value: Float) { _threshold.value = value; prefs.motionThreshold = value }

    /** Source, URL and picked file only take effect on the next start — the running flow is bound to one source. */
    fun setSource(kind: SourceKind) { _source.value = kind; prefs.sourceKind = kind }
    fun setRtspUrl(url: String) { _rtspUrl.value = url; prefs.rtspUrl = url }
    fun setTts(enabled: Boolean) { _tts.value = enabled; prefs.ttsEnabled = enabled }

    fun setVideoUri(uri: Uri?) {
        uri?.let {
            // Without a persisted grant the Uri stops resolving after a reboot.
            runCatching {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        _videoUri.value = uri?.toString()
        prefs.localVideoUri = uri?.toString()
    }

    // ---- rules
    fun addRule(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { db.rules().insert(Rule(text = trimmed)) }
    }

    fun toggleRule(rule: Rule) = viewModelScope.launch { db.rules().update(rule.copy(enabled = !rule.enabled)) }
    fun deleteRule(rule: Rule) = viewModelScope.launch { db.rules().delete(rule) }

    // ---- ask
    private val _answer = MutableStateFlow<String?>(null)
    val answer = _answer.asStateFlow()

    private val _asking = MutableStateFlow(false)
    val asking = _asking.asStateFlow()

    val qaName: String get() = qa.name

    fun ask(question: String) {
        if (question.isBlank()) return
        viewModelScope.launch {
            _asking.value = true
            val context = events.value.take(ASK_CONTEXT_EVENTS)
            _answer.value = try {
                qa.ask(question, context)
            } catch (e: Throwable) {
                // Same contract as the vision stage: fall back to the mock, never crash the screen.
                android.util.Log.e(TAG, "LogQa ${qa.name} failed, using mock", e)
                runCatching { MockLogQa().ask(question, context) }
                    .getOrElse { "Could not answer that." }
            }
            _asking.value = false
        }
    }

    fun clearAnswer() { _answer.value = null }

    fun start() = PipelineService.start(getApplication())
    fun stop() = PipelineService.stop(getApplication())
}
