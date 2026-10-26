package live.scamshield.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

const val SAMPLE_RATE = 16_000
const val FRAME_SAMPLES = 1_600 // 100 ms

/**
 * Pluggable source of 16 kHz, 16-bit mono PCM (little-endian).
 *
 * Standard Android does not let third-party apps read cellular call uplink/downlink audio
 * (that needs the system-only CAPTURE_AUDIO_OUTPUT permission). An OEM, carrier or dialer-role
 * integration that legitimately has call audio can implement this interface without touching
 * the rest of the app.
 */
interface PcmAudioSource {
    val label: String
    val isSimulated: Boolean

    /** Streams frames until the source ends or the coroutine is cancelled. [peak] is 0..1 for the waveform. */
    suspend fun stream(onFrame: suspend (pcm: ByteArray, peak: Float) -> Unit)
}

/** The user's microphone: e.g. a call on speakerphone from a second device, with everyone's consent. */
class MicrophoneAudioSource : PcmAudioSource {
    override val label = "Microphone (authorized)"
    override val isSimulated = false

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before the service starts
    override suspend fun stream(onFrame: suspend (ByteArray, Float) -> Unit) = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME_SAMPLES * 2 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        val shorts = ShortArray(FRAME_SAMPLES)
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val n = record.read(shorts, 0, shorts.size) // blocking read paces the loop
                if (n > 0) onFrame(toLittleEndian(shorts, n), peakOf(shorts, n))
                else if (n < 0) error("AudioRecord read error $n")
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}

/**
 * SIMULATED source: a fictional, synthetic-voice demo clip fetched from the backend, played aloud and
 * streamed through the same real Sarvam STT pipeline as the microphone.
 */
class DemoWavAudioSource(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val scenarioId: String,
) : PcmAudioSource {
    override val label = "SIMULATED demo audio: $scenarioId"
    override val isSimulated = true

    override suspend fun stream(onFrame: suspend (ByteArray, Float) -> Unit) = withContext(Dispatchers.IO) {
        val wav = http.newCall(Request.Builder().url("$baseUrl/demo/audio/$scenarioId.wav").build()).execute().use {
            check(it.isSuccessful) { "Demo audio HTTP ${it.code}" }
            it.body!!.bytes()
        }
        val pcm = pcmFromWav(wav)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(FRAME_SAMPLES * 2 * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val frameBytes = FRAME_SAMPLES * 2
            var off = 0
            while (off < pcm.size && currentCoroutineContext().isActive) {
                val chunk = pcm.copyOfRange(off, minOf(off + frameBytes, pcm.size))
                track.write(chunk, 0, chunk.size) // blocking write paces to real time
                val shorts = ShortArray(chunk.size / 2)
                ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                onFrame(chunk, peakOf(shorts, shorts.size))
                off += frameBytes
            }
            // 1.2 s of silence so Sarvam's VAD closes the turn.
            repeat(12) { onFrame(ByteArray(frameBytes), 0f); kotlinx.coroutines.delay(100) }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}

/** Placeholder for a future, properly authorized telephony integration. Never used by default. */
class AuthorizedTelephonyAudioSource : PcmAudioSource {
    override val label = "Telephony (requires OEM/carrier authorization)"
    override val isSimulated = false
    override suspend fun stream(onFrame: suspend (ByteArray, Float) -> Unit) {
        throw UnsupportedOperationException(
            "Call audio is not available to third-party apps on standard Android. " +
                "Provide a PcmAudioSource from an authorized OEM/carrier/dialer integration.",
        )
    }
}

private fun toLittleEndian(shorts: ShortArray, n: Int): ByteArray {
    val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until n) buf.putShort(shorts[i])
    return buf.array()
}

private fun peakOf(shorts: ShortArray, n: Int): Float {
    var p = 0
    for (i in 0 until n) p = max(p, abs(shorts[i].toInt()))
    return p / 32768f
}

/** Returns the PCM payload of a 16 kHz mono s16le WAV (walks RIFF chunks to find "data"). */
internal fun pcmFromWav(wav: ByteArray): ByteArray {
    val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    require(wav.size > 12 && String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE") { "Not a WAV file" }
    var pos = 12
    while (pos + 8 <= wav.size) {
        val id = String(wav, pos, 4)
        val size = bb.getInt(pos + 4)
        if (id == "fmt ") {
            val channels = bb.getShort(pos + 10).toInt()
            val rate = bb.getInt(pos + 12)
            val bits = bb.getShort(pos + 22).toInt()
            require(channels == 1 && rate == SAMPLE_RATE && bits == 16) { "Need 16 kHz mono 16-bit WAV" }
        }
        if (id == "data") return wav.copyOfRange(pos + 8, minOf(pos + 8 + size, wav.size))
        pos += 8 + size + (size and 1)
    }
    error("WAV has no data chunk")
}
