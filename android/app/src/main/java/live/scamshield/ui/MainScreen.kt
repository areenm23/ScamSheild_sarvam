package live.scamshield.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import live.scamshield.BuildConfig
import live.scamshield.net.analyzeTyped
import live.scamshield.service.ProtectionService
import live.scamshield.state.ProtectionRepository
import live.scamshield.state.Risk
import live.scamshield.state.UiState
import org.json.JSONObject

private val SOURCES = listOf(
    "microphone" to "Microphone",
    "demo:bank_otp" to "SIMULATED: Bank OTP",
    "demo:fake_police" to "SIMULATED: Fake police",
    "demo:normal" to "SIMULATED: Normal call",
)
private val TYPED = listOf(
    "Banking OTP scam" to "Sir, main bank se baat kar raha hoon. Aapka account block hone wala hai. Abhi OTP share kijiye.",
    "Fake police" to "Main cyber crime department se bol raha hoon. Aapke naam par case hai. Abhi settlement amount bhejna padega.",
    "Normal conversation" to "Hello, kal office meeting hai. Please 10 baje aa jana.",
)
private const val ANALYSIS_ITEM_INDEX = 4

fun riskColor(r: Risk) = when (r) { Risk.HIGH -> Red; Risk.MEDIUM -> Amber; Risk.LOW -> Emerald }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(scrollToAnalysisSignal: Int) {
    val ctx = LocalContext.current
    val state by ProtectionRepository.state.collectAsStateWithLifecycle()
    val prefs = remember { ctx.getSharedPreferences("scamshield", Context.MODE_PRIVATE) }
    var backendUrl by remember { mutableStateOf(prefs.getString("url", BuildConfig.DEFAULT_BACKEND_URL)!!) }
    var source by remember { mutableStateOf("microphone") }
    var sttLang by remember { mutableStateOf("auto") }
    var warnLang by remember { mutableStateOf("en") }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var permissionNote by remember { mutableStateOf<String?>(null) }
    var typedResult by remember { mutableStateOf<Pair<String, JSONObject?>?>(null) }
    var typedError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { overlayGranted = Settings.canDrawOverlays(ctx) }
    LaunchedEffect(scrollToAnalysisSignal) { if (scrollToAnalysisSignal > 0) listState.animateScrollToItem(ANALYSIS_ITEM_INDEX) }

    fun startService() {
        prefs.edit().putString("url", backendUrl.trimEnd('/')).apply()
        val intent = Intent(ctx, ProtectionService::class.java).setAction(ProtectionService.ACTION_START)
            .putExtra(ProtectionService.EXTRA_URL, backendUrl.trimEnd('/'))
            .putExtra(ProtectionService.EXTRA_SOURCE, source)
            .putExtra(ProtectionService.EXTRA_LANG, warnLang)
            .putExtra(ProtectionService.EXTRA_STT_LANG, sttLang)
        ContextCompat.startForegroundService(ctx, intent)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (source == "microphone" && res[Manifest.permission.RECORD_AUDIO] != true) {
            permissionNote = "Microphone permission is required for live protection. Demo audio still works."
        } else {
            permissionNote = null
            startService()
        }
    }

    fun onToggle() {
        if (state.active) {
            ctx.startService(Intent(ctx, ProtectionService::class.java).setAction(ProtectionService.ACTION_STOP))
            return
        }
        val needed = buildList {
            if (source == "microphone" && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startService() else permLauncher.launch(needed.toTypedArray())
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1E27), Bg, Bg))).statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 0: header
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Cyan, Emerald))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Shield, null, tint = Color(0xFF032018))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ScamShield Live", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    Text("Your AI shield against suspicious calls.", color = Muted, fontSize = 14.sp)
                }
            }
        }
        // 1: A. protection status + B. start/stop
        item {
            GlassCard("Protection status") {
                StatusGrid(state)
                Spacer(Modifier.height(12.dp))
                Label("Audio source")
                ChipRow(SOURCES, source, enabled = !state.active) { source = it }
                Label("Call language (speech recognition)")
                ChipRow(listOf("auto" to "Auto-detect", "hi-IN" to "Hindi/Hinglish", "en-IN" to "English"), sttLang, !state.active) { sttLang = it }
                Label("Warning language")
                ChipRow(listOf("en" to "English", "hi" to "हिन्दी"), warnLang, !state.active) { warnLang = it }
                OutlinedTextField(
                    value = backendUrl, onValueChange = { backendUrl = it }, enabled = !state.active, singleLine = true,
                    label = { Text("Backend URL (holds the Sarvam key)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                if (!overlayGranted) {
                    Text("Floating shield is off — warnings will appear as notifications and in-app instead.", color = Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Enable floating shield") }
                }
                permissionNote?.let { Text(it, color = Amber, fontSize = 13.sp) }
                Spacer(Modifier.height(12.dp))
                BigToggle(state.active) { onToggle() }
                Waveform(state.waveform, state.active)
                if (state.status.isNotEmpty()) Text(state.status, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                if (state.active) OutlinedButton(onClick = {
                    ctx.startService(Intent(ctx, ProtectionService::class.java).setAction(ProtectionService.ACTION_ANALYZE))
                }, modifier = Modifier.padding(top = 6.dp)) { Text("Analyze now") }
            }
        }
        // 2: C. live transcript
        item { TranscriptCard(state) }
        // 3: E. warning signs
        item { SignsCard(state) }
        // 4: D. risk assessment (scroll target for "View Explanation")
        item { RiskCard(state) }
        // 5: F. recommendations
        item { RecommendationsCard(state) }
        // 6: typed demo
        item {
            GlassCard("Typed demo scripts", badge = "Speech recognition not used" to Amber) {
                Text("Sends fictional script text straight to the rules engine and Sarvam-105B. No audio, no STT.", color = Muted, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TYPED.forEach { (title, script) ->
                        OutlinedButton(onClick = {
                            typedError = null
                            typedResult = script to null
                            scope.launch {
                                runCatching { analyzeTyped(backendUrl.trimEnd('/'), script) }
                                    .onSuccess { typedResult = script to it }
                                    .onFailure { typedError = "Backend error: ${it.message}" }
                            }
                        }) { Text(title) }
                    }
                }
                typedError?.let { Text(it, color = Red, fontSize = 13.sp) }
                typedResult?.let { (script, res) -> TypedResult(script, res, warnLang) }
            }
        }
        // 7: G. session summary
        item { SummaryCard(state) }
        // 8: dev metrics
        item {
            GlassCard("Development metrics (measured)") {
                Text("STT final after speech end (ms): ${state.sttFinalLatencies}", color = Muted, fontSize = 12.sp)
                Text("Sarvam-105B latency (ms): ${state.llmLatencies}", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatusGrid(state: UiState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.active) { while (state.active) { now = System.currentTimeMillis(); delay(1000) } }
    val secs = if (state.active) ((now - state.startedAtMs) / 1000).coerceAtLeast(0) else 0
    Row(Modifier.fillMaxWidth()) {
        Stat("Status", if (state.active) "● Active" else "Inactive", if (state.active) Emerald else Muted, Modifier.weight(1f))
        Stat("Duration", "%02d:%02d".format(secs / 60, secs % 60), TextMain, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Stat("Audio source", state.sourceLabel, if (state.simulated) Amber else TextMain, Modifier.weight(1f))
        Stat("Language", state.detectedLanguage ?: "—", TextMain, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(k: String, v: String, color: Color, modifier: Modifier) = Column(modifier) {
    Text(k, color = Muted, fontSize = 12.sp)
    Text(v, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
}

@Composable
private fun Label(s: String) = Text(s, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected, enabled = enabled, onClick = { onSelect(value) }, label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(alpha = .18f), selectedLabelColor = Cyan),
            )
        }
    }
}

@Composable
private fun BigToggle(active: Boolean, onClick: () -> Unit) {
    val c1 by animateColorAsState(if (active) Color(0xFFF43F5E) else Cyan, tween(400), label = "c1")
    val c2 by animateColorAsState(if (active) Color(0xFFB91C1C) else Emerald, tween(400), label = "c2")
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(c1, c2))).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (active) "Stop Protection" else "Start Protection", fontSize = 19.sp, fontWeight = FontWeight.Bold,
            color = if (active) Color.White else Color(0xFF032018))
    }
}

@Composable
private fun Waveform(peaks: List<Float>, active: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(56.dp).padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x40000000))) {
        val w = size.width / peaks.size
        val brush = Brush.horizontalGradient(listOf(Cyan, Emerald))
        peaks.forEachIndexed { i, p ->
            val h = (if (active) (p * 2.5f).coerceIn(0.04f, 1f) else 0.04f) * size.height
            drawRoundRect(brush, Offset(i * w + 1f, (size.height - h) / 2), Size(w - 2f, h), CornerRadius(2f, 2f))
        }
    }
}

@Composable
private fun TranscriptCard(state: UiState) = GlassCard("Live transcript", badge = "Sarvam saaras:v4" to Muted) {
    if (state.finals.isEmpty() && state.partial == null) Text("Start protection to see the live transcript.", color = Muted, fontSize = 14.sp)
    state.finals.takeLast(8).forEach { line ->
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x08FFFFFF)).padding(8.dp)) {
            Text(line.text, color = TextMain, fontSize = 15.sp)
            Text(line.meta, color = Muted, fontSize = 11.sp)
        }
    }
    state.partial?.let { Text("$it  · partial", color = Muted, fontStyle = FontStyle.Italic, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
}

@Composable
private fun RiskCard(state: UiState) = GlassCard("Current risk assessment") {
    val sweep by animateFloatAsState(state.score / 100f, tween(600), label = "sweep")
    val color by animateColorAsState(riskColor(state.risk), tween(500), label = "risk")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Color(0x10FFFFFF), 0f, 360f, false, style = Stroke(10.dp.toPx()))
                drawArc(color, -90f, 360f * sweep, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
            }
            Text("${state.score}", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = TextMain)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(when (state.risk) { Risk.HIGH -> "STRONG SCAM INDICATORS"; Risk.MEDIUM -> "CAUTION"; Risk.LOW -> "NO STRONG INDICATORS" },
                color = color, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text((if (state.riskSource.isNotEmpty()) "Latest: ${state.riskSource}. " else "") + "Score is a heuristic, not a probability.", color = Muted, fontSize = 12.sp)
        }
    }
    state.llmPending?.let { Text("Sarvam-105B analyzing recent conversation ($it)…", color = Violet, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    state.llm?.let { llm ->
        Column(Modifier.padding(top = 10.dp)) {
            Badge("Sarvam-105B · ${llm.latencyMs} ms · ${llm.trigger}", Violet)
            Text("${llm.level.name.lowercase()} · ${llm.category} · score ${llm.score}", color = riskColor(llm.level), fontWeight = FontWeight.SemiBold)
            Text(llm.warning, color = TextMain, fontSize = 14.sp)
            llm.evidence.forEach { Text("“${it.phrase}” — ${it.reason}", color = Color(0xFFFDE68A), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            Text("Recommended: ${llm.action}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            llm.uncertainty?.let { Text("Uncertainty: $it", color = Amber, fontSize = 13.sp) }
            if (llm.dropped > 0) Text("${llm.dropped} quote(s) dropped: not found verbatim in transcript.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SignsCard(state: UiState) = GlassCard("Detected warning signs") {
    if (state.signs.isEmpty()) Text("None yet.", color = Muted, fontSize = 14.sp)
    state.signs.take(6).forEach { sign ->
        AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 3 }) {
            val c = riskColor(sign.level)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = .09f))
                .border(1.dp, c.copy(alpha = .45f), RoundedCornerShape(12.dp)).padding(10.dp)) {
                Badge(if (sign.fromLlm) "Sarvam-105B" else "Rules engine", if (sign.fromLlm) Violet else Cyan)
                Text(sign.text, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(sign.meta, color = Muted, fontSize = 12.sp)
                if (sign.evidence.isNotEmpty()) Text(sign.evidence.joinToString("  ·  ") { "“$it”" }, color = Color(0xFFFDE68A), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RecommendationsCard(state: UiState) = GlassCard("Safety recommendations") {
    listOf(
        "Never share an OTP, UPI PIN, CVV or password — banks and police never ask for them.",
        "No real agency performs a \"digital arrest\" or asks for a \"settlement\" over the phone.",
        "Don't install AnyDesk/TeamViewer or screen-share at a caller's request.",
        "Hang up and call back on the official number on your card or the bank's website.",
        "Report fraud at cybercrime.gov.in or call 1930.",
    ).forEach { Text("•  $it", color = TextMain, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    state.llm?.takeIf { it.level != Risk.LOW }?.let { Text("For this call: ${it.action}", color = Amber, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun SummaryCard(state: UiState) = GlassCard("Session summary") {
    val s = state.summary
    if (s == null) { Text("Appears when protection stops.", color = Muted, fontSize = 14.sp); return@GlassCard }
    val overall = Risk.of(s.optString("overall_risk"))
    Text("${overall.name} overall · ${s.optInt("alert_count")} alert(s) · ${s.optDouble("duration_s")} s", color = riskColor(overall), fontWeight = FontWeight.Bold)
    val cats = s.optJSONArray("categories")
    Text("Categories: " + (if (cats == null || cats.length() == 0) "none" else (0 until cats.length()).joinToString { cats.optString(it) }), color = Muted, fontSize = 13.sp)
    s.optJSONObject("llm_latest")?.optString("recommended_action")?.takeIf { it.isNotBlank() }?.let { Text(it, color = TextMain, fontSize = 13.sp) }
    Text(s.optString("note") + " Session transcript cleared on the server.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun TypedResult(script: String, res: JSONObject?, lang: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Badge("Typed input · no speech recognition", Amber)
        Text(script, color = TextMain, fontSize = 14.sp)
        if (res == null) { Text("Sending to Sarvam-105B…", color = Muted, fontSize = 13.sp); return@Column }
        val rules = res.getJSONObject("rules")
        Text("Rules: ${rules.optString("level")} · ${rules.optString("category")}", color = riskColor(Risk.of(rules.optString("level"))), fontSize = 13.sp)
        val a = res.getJSONObject("analysis")
        if (!a.optBoolean("ok")) { Text("Sarvam-105B unavailable (${a.optString("error")})", color = Muted, fontSize = 13.sp); return@Column }
        Text("Sarvam-105B (${a.optLong("latency_ms")} ms): ${a.optString("risk_level")} · ${a.optString("category")} · score ${a.optInt("risk_score")}",
            color = riskColor(Risk.of(a.optString("risk_level"))), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(a.optString(if (lang == "hi") "warning_hi" else "warning_en"), color = TextMain, fontSize = 13.sp)
        val ev = a.optJSONArray("evidence")
        if (ev != null) for (i in 0 until ev.length()) {
            val e = ev.getJSONObject(i)
            Text("“${e.optString("phrase")}” — ${e.optString("reason")}", color = Color(0xFFFDE68A), fontSize = 12.sp)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(text.uppercase(), color = color, fontSize = 10.sp, letterSpacing = .6.sp,
        modifier = Modifier.padding(bottom = 4.dp).border(1.dp, color.copy(alpha = .45f), CircleShape).padding(horizontal = 8.dp, vertical = 1.dp))
}

@Composable
private fun GlassCard(title: String, badge: Pair<String, Color>? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg)
            .border(1.dp, CardLine, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            badge?.let { Spacer(Modifier.width(8.dp)); Badge(it.first, it.second) }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}
