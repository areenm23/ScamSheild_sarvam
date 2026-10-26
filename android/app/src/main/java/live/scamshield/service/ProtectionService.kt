package live.scamshield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import live.scamshield.MainActivity
import live.scamshield.R
import live.scamshield.audio.DemoWavAudioSource
import live.scamshield.audio.MicrophoneAudioSource
import live.scamshield.audio.PcmAudioSource
import live.scamshield.net.BackendSocket
import live.scamshield.net.http
import live.scamshield.overlay.OverlayController
import live.scamshield.state.Alert
import live.scamshield.state.ProtectionRepository
import live.scamshield.state.Risk

/**
 * Foreground service that runs only between the user's explicit Start and Stop. It streams authorized
 * audio to the backend, applies events to [ProtectionRepository], and drives the overlay + haptics.
 */
class ProtectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var socket: BackendSocket? = null
    private var audioJob: Job? = null
    private var overlay: OverlayController? = null
    private var lastVibrationMs = 0L
    private var stopping = false
    private var reconnects = 0
    private lateinit var params: StartParams

    data class StartParams(val baseUrl: String, val source: String, val warningLang: String, val sttLang: String)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                StartParams(
                    intent.getStringExtra(EXTRA_URL)!!, intent.getStringExtra(EXTRA_SOURCE)!!,
                    intent.getStringExtra(EXTRA_LANG) ?: "en", intent.getStringExtra(EXTRA_STT_LANG) ?: "auto",
                ),
            )
            ACTION_STOP -> stopProtection()
            ACTION_ANALYZE -> socket?.requestAnalysis()
        }
        return START_NOT_STICKY
    }

    private fun start(p: StartParams) {
        params = p
        val isMic = p.source == "microphone"
        createChannels()
        ServiceCompat.startForeground(
            this, NOTIF_ID, monitoringNotification(),
            if (Build.VERSION.SDK_INT >= 30) {
                if (isMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else 0,
        )
        val source: PcmAudioSource =
            if (isMic) MicrophoneAudioSource() else DemoWavAudioSource(http, p.baseUrl, p.source.removePrefix("demo:"))
        ProtectionRepository.startSession(source.label, source.isSimulated, p.warningLang)
        if (Settings.canDrawOverlays(this)) {
            overlay = OverlayController(this).also { it.show() }
        }
        connectSocket()
        audioJob = scope.launch(Dispatchers.Default) {
            try {
                source.stream { pcm, peak ->
                    socket?.sendAudio(pcm)
                    ProtectionRepository.peak(peak)
                }
                if (source.isSimulated) {
                    delay(2500) // let the final transcript and analysis arrive
                    scope.launch { stopProtection() }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    ProtectionRepository.status("Audio source error: ${e.message}")
                    scope.launch { stopProtection() }
                }
            }
        }
    }

    private fun connectSocket() {
        socket = BackendSocket(
            params.baseUrl,
            onEventCb = { ev -> scope.launch { onBackendEvent(ev) } },
            onClosedCb = { reason, failed ->
                scope.launch {
                    if (stopping) return@launch finish()
                    if (failed && reconnects < 5) {
                        reconnects++
                        ProtectionRepository.status("Backend connection lost ($reason). Reconnecting ($reconnects/5)…")
                        delay(1000L * reconnects)
                        if (!stopping) connectSocket()
                    } else if (failed) {
                        ProtectionRepository.status("Backend unreachable: $reason")
                        finish()
                    }
                }
            },
        ).also { it.connect(params.source, params.warningLang, params.sttLang) }
    }

    private fun onBackendEvent(ev: org.json.JSONObject) {
        if (ev.optString("type") == "session") reconnects = 0
        val alert = ProtectionRepository.apply(ev)
        val state = ProtectionRepository.state.value
        overlay?.render(state)
        if (alert != null) handleAlert(alert)
        if (ev.optString("type") == "summary") finish()
    }

    private fun handleAlert(alert: Alert) {
        if (alert.level == Risk.LOW) return
        overlay?.showAlert(alert) ?: postAlertNotification(alert) // overlay permission denied → notification fallback
        // One vibration per high-risk episode: the rules alert and the LLM confirmation of the same scam
        // must not buzz twice, and repeated phrases must not keep buzzing.
        val now = System.currentTimeMillis()
        if (alert.level == Risk.HIGH && now - lastVibrationMs > VIBRATION_COOLDOWN_MS) {
            lastVibrationMs = now
            vibrateOnce()
        }
    }

    private fun stopProtection() {
        if (stopping) return
        stopping = true
        audioJob?.cancel()
        if (socket?.stop() != true) finish()
        else scope.launch { delay(20_000); finish() } // safety net if the summary never arrives
    }

    private fun finish() {
        socket?.close()
        socket = null
        overlay?.hide()
        overlay = null
        ProtectionRepository.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        audioJob?.cancel()
        socket?.close()
        overlay?.hide()
        ProtectionRepository.stopped()
        scope.cancel()
        super.onDestroy()
    }

    private fun vibrateOnce() {
        val effect = VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, getString(R.string.channel_monitoring), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun monitoringNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProtectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("ScamShield protection is active")
            .setContentText("Listening to authorized audio for scam warning signs.")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop Protection", stop)
            .build()
    }

    private fun postAlertNotification(alert: Alert) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(if (alert.level == Risk.HIGH) "Strong scam indicators" else "Caution: suspicious language")
            .setContentText(listOfNotNull(alert.phrase?.let { "“$it”" }, alert.warning).joinToString(" — "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(alert.phrase?.let { "“$it”" }, alert.warning, "This is a warning, not proof.").joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(alert.key.hashCode(), n) }
    }

    companion object {
        const val ACTION_START = "live.scamshield.START"
        const val ACTION_STOP = "live.scamshield.STOP"
        const val ACTION_ANALYZE = "live.scamshield.ANALYZE"
        const val EXTRA_URL = "url"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_LANG = "lang"
        const val EXTRA_STT_LANG = "stt_lang"
        private const val CH_MONITOR = "monitoring"
        private const val CH_ALERT = "alerts"
        private const val NOTIF_ID = 42
        private const val VIBRATION_COOLDOWN_MS = 60_000L
    }
}
