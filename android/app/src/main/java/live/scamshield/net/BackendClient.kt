package live.scamshield.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .pingInterval(15, TimeUnit.SECONDS)
    .build()

/**
 * WebSocket to the ScamShield backend (/ws/audio). The backend, not the app, holds the Sarvam key
 * and opens the authenticated Sarvam connections.
 */
class BackendSocket(
    private val baseUrl: String,
    private val onEventCb: (JSONObject) -> Unit,
    private val onClosedCb: (reason: String, failed: Boolean) -> Unit,
) {
    private var ws: WebSocket? = null

    fun connect(source: String, warningLang: String, sttLang: String) {
        val url = baseUrl.replaceFirst("http", "ws") +
            "/ws/audio?source=${enc(source)}&lang=$warningLang&stt_lang=${enc(sttLang)}"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.onSuccess(onEventCb)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosedCb("closed $code $reason", false)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onClosedCb(t.message ?: t.javaClass.simpleName, true)
        })
    }

    fun sendAudio(pcm: ByteArray): Boolean = ws?.send(pcm.toByteString()) ?: false
    fun requestAnalysis() = ws?.send("""{"type":"analyze"}""")
    fun stop() = ws?.send("""{"type":"stop"}""")
    fun close() { ws?.close(1000, "bye"); ws = null }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

/** Typed demo scripts: POST /analyze. Speech recognition is NOT involved. */
suspend fun analyzeTyped(baseUrl: String, text: String): JSONObject = withContext(Dispatchers.IO) {
    val body = JSONObject().put("text", text).put("input_kind", "typed").toString()
        .toRequestBody("application/json".toMediaType())
    http.newCall(Request.Builder().url("$baseUrl/analyze").post(body).build()).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }
        JSONObject(it.body!!.string())
    }
}

suspend fun fetchHealth(baseUrl: String): JSONObject = withContext(Dispatchers.IO) {
    http.newCall(Request.Builder().url("$baseUrl/health").build()).execute().use { JSONObject(it.body!!.string()) }
}
