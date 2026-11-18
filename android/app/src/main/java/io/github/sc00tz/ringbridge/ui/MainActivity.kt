package io.github.sc00tz.ringbridge.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sc00tz.ringbridge.R

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra("tab") ?: "today"
        setContent { RingBridgeTheme { App(vm, start) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

private val TABS = listOf(
    Triple("today", "Today", R.drawable.ic_today), Triple("sleep", "Sleep", R.drawable.ic_sleep),
    Triple("trends", "Trends", R.drawable.ic_trends), Triple("record", "Record", R.drawable.ic_record),
    Triple("ring", "Ring", R.drawable.ic_ring),
)

@Composable
fun App(vm: AppViewModel, startTab: String) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    val job by vm.job.collectAsState()
    val ring by vm.ring.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                for ((id, label, icon) in TABS) NavigationBarItem(selected = tab == id, onClick = { tab = id },
                    icon = { Icon(painterResource(icon), null) }, label = { Text(label) })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(TABS.first { it.first == tab }.second, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (ring?.address != null) FilledTonalButton(onClick = vm::sync, enabled = job?.running != true) { Text("Sync") }
            }
            SyncLine(job, ring)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    "today" -> TodayScreen(vm) { tab = it }
                    "sleep" -> SleepScreen(vm)
                    "trends" -> TrendsScreen(vm)
                    "record" -> RecordScreen(vm)
                    else -> RingScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun SyncLine(job: AppViewModel.Job?, ring: AppViewModel.Ring?) {
    val text = when {
        job?.running == true -> "${job.label}: ${job.message}…"
        job?.error != null -> job.error
        job != null && job.label == "Sync" -> job.message
        ring?.address == null -> "No ring yet. Open Ring to find yours."
        ring.lastSync != null -> "Synced ${AppViewModel.ago(ring.lastSync.first)}"
        else -> "Not synced yet"
    }
    Text(text, color = if (job?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

// ----- shared pieces, matching the web dashboard's -----
@Composable
fun Screen(content: LazyListScope.() -> Unit) = LazyColumn(
    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize(), content = content)

@Composable
fun Panel(title: String? = null, sub: String? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.semantics { heading() })
            if (sub != null) Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            content()
        }
    }
}

@Composable
fun Kv(key: String, value: String, divider: Boolean = true) {
    if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Notice(bold: String?, text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).background(LocalPalette.current.surface2, RoundedCornerShape(12.dp)).padding(10.dp, 8.dp)) {
        Text(buildString { if (bold != null) append("$bold ") ; append(text) }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Note(text: String) = Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

@Composable
fun Stepper(label: String, prev: String?, next: String?, go: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { prev?.let(go) }, enabled = prev != null) { Text("‹", fontSize = 24.sp) }
        Text(label, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { next?.let(go) }, enabled = next != null) { Text("›", fontSize = 24.sp) }
    }
}

/** A status always carries its word as well as its color. */
@Composable
fun StatusLabel(band: String) {
    val p = LocalPalette.current
    val color = when (band) { "Optimal" -> p.best; "Good" -> p.good; "Average" -> p.warning; else -> p.critical }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(band, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun Tile(label: String, value: String?, unit: String = "", whenText: String? = null, estimate: Boolean = false, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (estimate) Text(" Estimate", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value ?: "–", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                if (value != null && unit.isNotEmpty()) Text(" $unit", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(if (value == null) "Not measured" else (whenText ?: " "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Expandable(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text((if (open) "▾ " else "▸ ") + title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp).clickable(role = Role.Button) { open = !open }.padding(vertical = 4.dp))
    if (open) Column(content = content)
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

val Transparent = Color.Transparent
