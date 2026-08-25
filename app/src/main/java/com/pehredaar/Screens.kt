package com.pehredaar

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault())
private val dvrFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

/**
 * Real shop CCTV, played as a live-looking feed. Deliberately NOT wired to the pipeline:
 * the footage is a continuously busy shop with no single counter line, so the stub detector
 * and mock analyzer would be inventing detections over real people. The pipeline keeps
 * running on whichever source is selected below.
 */
@Composable
private fun SampleFeed() {
    val context = LocalContext.current
    val uri = remember(context) {
        Uri.parse("android.resource://${context.packageName}/${R.raw.cctv_sample}")
    }
    var clock by remember { mutableStateOf(dvrFormat.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) { clock = dvrFormat.format(Date()); delay(1000) }
    }

    Card {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(uri)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                mp.setVolume(0f, 0f)   // muted: this is a picture, not a recording
                                start()
                            }
                            setOnErrorListener { _, _, _ -> true }  // never pop a system dialog
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "CH04 · SHOP FLOOR", color = Color.White, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(Color(0xFFC4442E)))
                        Spacer(Modifier.width(4.dp))
                        Text(clock, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Text(
                "Sample footage · playback only. Nothing here is scanned — the pipeline below " +
                    "runs on its own source.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

// -------------------------------------------------------------------- live

@Composable
fun LiveScreen(vm: MainViewModel, stats: PipelineStats) {
    val threshold by vm.threshold.collectAsState()
    val source by vm.source.collectAsState()
    val rtspUrl by vm.rtspUrl.collectAsState()
    val tts by vm.tts.collectAsState()
    val videoUri by vm.videoUri.collectAsState()

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        vm.setVideoUri(it)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SampleFeed() }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (stats.running) Color(0xFF4CAF50) else Color(0xFF757575))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (stats.running) "Watching · निगरानी चालू" else "Stopped", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    StatRow("source", stats.source)
                    StatRow("detector", stats.detector)
                    StatRow("analyzer", stats.analyzer)
                    val kept = stats.framesSeen - stats.framesDropped
                    StatRow("frames seen/pass/drop", "${stats.framesSeen} / $kept / ${stats.framesDropped}")
                    StatRow("motion score", "%.2f".format(stats.lastMotionScore))
                    StatRow("detections / vlm calls", "${stats.detections} / ${stats.vlmCalls}")
                    StatRow("last frame latency", "${stats.lastLatencyMs} ms")
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Motion threshold: %.2f".format(threshold), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = threshold,
                        onValueChange = { vm.setThreshold(it) },
                        valueRange = 0f..10f,
                        steps = 39,
                    )
                    Text(
                        "Mean absolute difference on a 64x64 grayscale frame. Higher = more frames dropped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Frame source" + if (stats.running) " (applies on next start)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceKind.entries.forEach { kind ->
                            FilterChip(
                                selected = source == kind,
                                onClick = { vm.setSource(kind) },
                                label = { Text(kind.name.lowercase().replace('_', ' '), fontSize = 12.sp) },
                            )
                        }
                    }

                    if (source == SourceKind.LOCAL_VIDEO) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            videoUri?.let { "Using picked file" } ?: "Using the bundled clip (res/raw/sample_scene.mp4)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { pickVideo.launch(arrayOf("video/*")) }) {
                                Text("Pick CCTV footage…")
                            }
                            if (videoUri != null) {
                                TextButton(onClick = { vm.setVideoUri(null) }) { Text("Use bundled clip") }
                            }
                        }
                    }

                    if (source == SourceKind.RTSP) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = rtspUrl,
                            onValueChange = { vm.setRtspUrl(it) },
                            label = { Text("RTSP URL (LAN)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = tts, onCheckedChange = { vm.setTts(it) })
                        Spacer(Modifier.width(8.dp))
                        Text("Read alerts aloud (hi-IN)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- rules

@Composable
fun RulesScreen(vm: MainViewModel) {
    val rules by vm.rules.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    val (voice, startListening) = rememberVoiceInput { spoken -> draft = spoken }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Rules are plain sentences — they go into the model prompt as written, in Hindi or English.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = if (voice.listening && voice.partial.isNotEmpty()) voice.partial else draft,
            onValueChange = { draft = it },
            label = { Text("New rule / नया नियम") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = startListening, enabled = voice.available) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Dictate in Hindi",
                        tint = if (voice.listening) Color(0xFFE53935) else LocalContentColor.current,
                    )
                }
            },
        )
        if (voice.status.isNotEmpty()) {
            Text(
                voice.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.addRule(draft); draft = "" },
            enabled = draft.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add rule") }

        Spacer(Modifier.height(16.dp))
        Text("${rules.count { it.enabled }} of ${rules.size} active", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rules, key = { it.id }) { rule ->
                Card {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rule.enabled, onCheckedChange = { vm.toggleRule(rule) })
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(rule.text, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "#${rule.id}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.deleteRule(rule) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete rule")
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- timeline

@Composable
fun TimelineScreen(vm: MainViewModel) {
    val events by vm.visibleEvents.collectAsState()
    val query by vm.query.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setQuery(it) },
            label = { Text("Search descriptions / खोजें") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (query.isBlank()) "${events.size} events" else "${events.size} matching \"$query\"",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))

        if (events.isEmpty()) {
            Text(
                if (query.isBlank())
                    "No events yet. Press Start on the Live tab — the bundled clip is still for its first " +
                        "4 seconds, so the motion gate drops those frames before anything fires."
                else "Nothing matches that search.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(events, key = { it.id }) { EventRow(it) }
        }
    }
}

@Composable
private fun EventRow(event: Event) {
    Card {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumb = remember(event.thumbnailPath) {
                event.thumbnailPath?.let { path ->
                    File(path).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(path) }
                }
            }
            Box(
                Modifier.size(72.dp, 46.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                thumb?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    timeFormat.format(Date(event.timestamp)) + (event.ruleId?.let { "  ·  rule #$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            SeverityChip(event.severity)
        }
    }
}

// --------------------------------------------------------------------- ask

@Composable
fun AskScreen(vm: MainViewModel) {
    val answer by vm.answer.collectAsState()
    val asking by vm.asking.collectAsState()
    val events by vm.events.collectAsState()
    var question by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MockBanner("Answers come from the last 50 event descriptions, on-device. Engine: ${vm.qaName}.")

        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("Ask about the log / लॉग के बारे में पूछें") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { vm.ask(question); keyboard?.hide() }),
            trailingIcon = {
                IconButton(onClick = { vm.ask(question); keyboard?.hide() }, enabled = question.isNotBlank() && !asking) {
                    Icon(Icons.Default.Send, contentDescription = "Ask")
                }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("कितने अलर्ट?", "wall", "loitering").forEach { suggestion ->
                AssistChip(
                    onClick = { question = suggestion; vm.ask(suggestion) },
                    label = { Text(suggestion, fontSize = 12.sp) },
                )
            }
        }

        when {
            asking -> LinearProgressIndicator(Modifier.fillMaxWidth())
            answer != null -> Card {
                Column(Modifier.padding(16.dp)) {
                    Text(answer!!, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.clearAnswer() }) { Text("Clear") }
                }
            }
            else -> Text(
                "${events.size} events in the log. Ask in Hindi or English.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
