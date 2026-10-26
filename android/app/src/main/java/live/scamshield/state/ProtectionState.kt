package live.scamshield.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

enum class Risk { LOW, MEDIUM, HIGH;
    companion object { fun of(s: String?) = when (s) { "high" -> HIGH; "medium" -> MEDIUM; else -> LOW } }
}

data class TranscriptLine(val text: String, val meta: String)
data class Evidence(val phrase: String, val reason: String)
data class Sign(val level: Risk, val fromLlm: Boolean, val text: String, val evidence: List<String>, val meta: String)
data class LlmView(
    val level: Risk, val score: Int, val category: String, val warning: String, val evidence: List<Evidence>,
    val action: String, val uncertainty: String?, val latencyMs: Long, val trigger: String, val dropped: Int,
)
data class Alert(val key: String, val level: Risk, val phrase: String?, val warning: String, val fromLlm: Boolean)

data class UiState(
    val active: Boolean = false,
    val sourceLabel: String = "—",
    val simulated: Boolean = false,
    val startedAtMs: Long = 0,
    val detectedLanguage: String? = null,
    val warningLang: String = "en",
    val status: String = "",
    val finals: List<TranscriptLine> = emptyList(),
    val partial: String? = null,
    val risk: Risk = Risk.LOW,
    val rulesRisk: Risk = Risk.LOW,
    val score: Int = 0,
    val riskSource: String = "",
    val llm: LlmView? = null,
    val llmPending: String? = null,
    val signs: List<Sign> = emptyList(),
    val summary: JSONObject? = null,
    val sttFinalLatencies: List<Long> = emptyList(),
    val llmLatencies: List<Long> = emptyList(),
    val waveform: List<Float> = List(64) { 0f },
    val lastAlert: Alert? = null,
)

/** Single source of truth shared by the foreground service, the overlay and the Compose UI. */
object ProtectionRepository {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun startSession(label: String, simulated: Boolean, warningLang: String) = _state.update {
        UiState(active = true, sourceLabel = label, simulated = simulated, startedAtMs = System.currentTimeMillis(),
            warningLang = warningLang, summary = null)
    }

    fun stopped() = _state.update { it.copy(active = false, partial = null) }
    fun status(s: String) = _state.update { it.copy(status = s) }
    fun peak(p: Float) = _state.update { it.copy(waveform = (it.waveform.drop(1) + p)) }

    /** Applies one backend event. Returns a new alert (for overlay/vibration) if this event created one. */
    fun apply(ev: JSONObject): Alert? {
        var alert: Alert? = null
        _state.update { s ->
            val lang = s.warningLang
            fun warning(o: JSONObject) = if (lang == "hi") o.optString("warning_hi") else o.optString("warning_en")
            when (ev.optString("type")) {
                "session" -> s.copy(status = "Session ${ev.optString("session_id")} · STT ${ev.optString("stt_model")} " +
                    "(${ev.optString("stt_language")}) · LLM ${ev.optString("llm_model")}")
                "status" -> when (ev.optString("stt")) {
                    "error" -> s.copy(status = "STT error: ${ev.optString("code")} ${ev.optString("message")}")
                    "disconnected" -> s.copy(status = "STT disconnected (${ev.optInt("close_code")}), reconnecting on next audio")
                    else -> s
                }
                "transcript" -> {
                    val language = ev.optString("language").ifEmpty { null } ?: s.detectedLanguage
                    if (ev.optString("kind") == "partial") s.copy(partial = ev.optString("text"), detectedLanguage = language)
                    else {
                        val lat = if (ev.isNull("final_after_speech_end_ms")) null else ev.optLong("final_after_speech_end_ms")
                        val meta = "final · ${ev.optString("language")}" + (lat?.let { " · $it ms after speech end" } ?: "")
                        s.copy(partial = null, detectedLanguage = language,
                            finals = (s.finals + TranscriptLine(ev.optString("text"), meta)).takeLast(30),
                            sttFinalLatencies = lat?.let { s.sttFinalLatencies + it } ?: s.sttFinalLatencies)
                    }
                }
                "alert" -> {
                    val lvl = Risk.of(ev.optString("level"))
                    val ev0 = ev.optJSONArray("evidence").strings()
                    val text = warning(ev)
                    alert = Alert("rules:" + ev.optString("alert_id"), lvl, ev0.firstOrNull(), text, fromLlm = false)
                    val rules = maxOf(s.rulesRisk, lvl)
                    s.copy(rulesRisk = rules, risk = maxOf(rules, s.llm?.level ?: Risk.LOW), score = ev.optInt("score"),
                        riskSource = "Local rules engine (instant)", lastAlert = alert,
                        signs = listOf(Sign(lvl, false, text, ev0, "${ev.optString("category")} · on ${ev.optString("trigger")} transcript")) + s.signs)
                }
                "analysis_pending" -> s.copy(llmPending = ev.optString("trigger"))
                "analysis" -> {
                    if (!ev.optBoolean("ok")) return@update s.copy(llmPending = null,
                        status = "Contextual analysis unavailable (${ev.optString("error")}); rule warnings still active")
                    val evidence = ev.optJSONArray("evidence").objects().map { Evidence(it.optString("phrase"), it.optString("reason")) }
                    val view = LlmView(Risk.of(ev.optString("risk_level")), ev.optInt("risk_score"), ev.optString("category"),
                        warning(ev), evidence, ev.optString("recommended_action"),
                        ev.optString("uncertainty_note").takeIf { !ev.isNull("uncertainty_note") && it.isNotBlank() },
                        ev.optLong("latency_ms"), ev.optString("trigger"), ev.optInt("unverified_evidence_dropped"))
                    var signs = s.signs
                    if (ev.optBoolean("should_alert") && ev.optBoolean("is_new")) {
                        alert = Alert("llm:${view.category}:${view.level}", view.level, evidence.firstOrNull()?.phrase, view.warning, true)
                        signs = listOf(Sign(view.level, true, view.warning, evidence.map { it.phrase }, "${view.category} · contextual")) + signs
                    }
                    s.copy(llm = view, llmPending = null, risk = maxOf(s.rulesRisk, view.level), score = view.score,
                        riskSource = "Sarvam-105B contextual analysis", signs = signs, lastAlert = alert ?: s.lastAlert,
                        llmLatencies = s.llmLatencies + view.latencyMs)
                }
                "summary" -> s.copy(summary = ev)
                else -> s
            }
        }
        return alert
    }
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
