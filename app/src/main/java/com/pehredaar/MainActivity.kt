package com.pehredaar

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Default.Videocam),
    RULES("Rules", Icons.Default.Rule),
    TIMELINE("Timeline", Icons.Default.List),
    ASK("Ask", Icons.Default.Chat),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    val stats by vm.stats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pehredaar", fontWeight = FontWeight.Bold) },
                actions = {
                    Button(onClick = { if (stats.running) vm.stop() else vm.start() }) {
                        Text(if (stats.running) "Stop" else "Start")
                    }
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.LIVE -> LiveScreen(vm, stats)
                Tab.RULES -> RulesScreen(vm)
                Tab.TIMELINE -> TimelineScreen(vm)
                Tab.ASK -> AskScreen(vm)
            }
        }
    }
}

// ------------------------------------------------------------------ shared bits

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(150.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SeverityChip(severity: String) {
    val color = when (severity) {
        "alert" -> Color(0xFFE53935)
        "warn" -> Color(0xFFFFA726)
        else -> Color(0xFF66BB6A)
    }
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(severity, fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

/** Marks a screen as running on canned data, so a demo never reads as a real detection. */
@Composable
fun MockBanner(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
