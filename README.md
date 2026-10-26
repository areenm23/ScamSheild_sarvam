# ScamShield Live

**A real-time, multilingual AI warning system for phone scams, built on Sarvam AI.**

## What is it?

Phone scams in India follow a script: fake bank calls demanding an OTP, fake police threatening "digital arrest", fake KYC updates, job offers that need a "registration fee". They work because the victim is rushed, scared and alone on the call.

ScamShield Live is a second pair of ears. Once the user presses **Start Protection**, it:

1. **listens** to audio the user has authorized (for example a call on speakerphone),
2. **transcribes** it live in Hindi, English or Hinglish with Sarvam's speech recognition,
3. **detects** scam patterns: an instant rules engine first, then Sarvam's LLM for context,
4. **warns** with a floating red/amber shield, one vibration, the exact phrase that triggered it, and a short explanation in English or Hindi.

What it does **not** do:
- It doesn't claim a caller *is* a fraudster. Risk scores are heuristic indicators.
- It doesn't tap cellular call audio. Standard Android doesn't allow that (see [Android telephony audio](#android-telephony-audio-what-is-and-isnt-possible)).
- It doesn't store recordings.

The repo contains:
- a **FastAPI backend**,
- a **browser client** (fully tested),
- a **Kotlin / Jetpack Compose Android app** (written, not yet compiled; see [What was tested](#what-was-tested)).

---

## Architecture

```
┌──────────────────────────┐        16 kHz PCM audio         ┌───────────────────────────────────────────┐
│  Client                  │ ───────────(WebSocket)────────▶ │  FastAPI backend  (holds the Sarvam key)  │
│  • Android app           │                                 │                                           │
│    (Compose UI,          │ ◀──── JSON events: transcript,  │  session.py  ── coordinates one session   │
│    foreground service,   │       alerts, analysis,         │     │                                     │
│    overlay, vibration)   │       summary                   │     ├─▶ sarvam_stt.py ──WebSocket──▶ Sarvam STT (saaras:v4)
│  • Browser client        │                                 │     │        ◀── partial / final transcripts
│    (same protocol)       │                                 │     ├─▶ rules.py      Stage A: instant rules
└──────────────────────────┘                                 │     └─▶ analysis.py   Stage B ──HTTPS──▶ Sarvam LLM (sarvam-105b)
                                                             └───────────────────────────────────────────┘
```

### Components

| Part | Files | Role |
|---|---|---|
| **Android app** | `android/app/src/main/java/live/scamshield/` | `ui/` Compose screen; `service/` foreground service that runs only between Start and Stop; `audio/` pluggable audio sources (`MicrophoneAudioSource` via AudioRecord, simulated `DemoWavAudioSource`, and a stub for future authorized telephony); `net/` OkHttp WebSocket; `overlay/` floating shield (WindowManager); `state/` StateFlow store |
| **Browser client** | `web/` | Same protocol as the app. The AudioWorklet resamples the mic to 16 kHz. It also shows a simulated overlay. Served by the backend at `/` |
| **Backend API** | `backend/app/main.py` | `GET /health`, `WS /ws/audio`, `POST /analyze`, `GET /session/summary`, plus demo endpoints |
| **Session coordinator** | `backend/app/session.py` | Re-chunks audio into 100 ms frames, keeps a short rolling transcript window, triggers both stages, de-duplicates alerts, measures latency, clears data on Stop |
| **Sarvam STT client** | `backend/app/sarvam_stt.py` | Authenticated realtime WebSocket to Sarvam with reconnects and error/close-code handling |
| **Stage A: rules** | `backend/app/rules.py` | Deterministic English/Hindi/Hinglish patterns scored as **combinations** |
| **Stage B: LLM** | `backend/app/analysis.py` | Redaction, sarvam-105b call, Pydantic validation, verbatim-evidence check |

### What happens during a call

1. **Start.** The user presses **Start Protection**. The Android app starts a foreground service with a persistent notification, and the client opens `ws://…/ws/audio`.
2. **Audio in.** The client streams 16-bit mono PCM at 16 kHz. The backend forwards it in 100 ms frames to Sarvam realtime STT.
3. **Transcripts.** Sarvam returns **partial** transcripts while the person is speaking and a **final** transcript when its voice detection says the sentence ended.
4. **Stage A (instant).** Every partial and final runs through the rules engine, using the last two sentences plus the one in progress.
   - About 150 patterns are grouped into 12 signal types: credential, share-request, urgency, account-threat, bank or authority impersonation, digital arrest, payment demand, remote access, KYC, job fee and investment promise.
   - **A single keyword never makes it red.** Combinations do, such as *credential + share request* ("OTP share kijiye") or *police + payment* ("case hai… settlement bhejna padega").
5. **Stage B (contextual).** sarvam-105b runs only when it's worth it:
   - when a rule fires,
   - when a sentence ends with a suspicious signal or with enough context, or
   - when the user taps **Analyze now**.

   It reads an overlapping window of the last four sentences, with credentials redacted, and returns structured JSON. Evidence quotes not found verbatim in the transcript are dropped, and the alert is downgraded and marked uncertain.
6. **Warning.** The overall risk is the higher of the two stages. New high-risk alerts show the red overlay with the matched phrase and vibrate once. "View Explanation" opens the full analysis.
7. **Stop.** The user presses Stop. Sarvam finalizes the last sentence, a session summary is sent, and the server clears the transcript.

---

## Sarvam AI services used

| # | Sarvam service | Model | How it's called | Used for | When |
|---|---|---|---|---|---|
| 1 | **Realtime speech-to-text** | `saaras:v4` | WebSocket `wss://api.sarvam.ai/speech-to-text-realtime/ws`, `Api-Subscription-Key` header | Live transcription of the conversation, with language detection | Continuously during protection |
| 2 | **Chat completions (LLM)** | `sarvam-105b` | Python SDK `sarvamai` (`AsyncSarvamAI.chat.completions`) | Stage B contextual scam analysis: risk, category, evidence, EN/HI warnings, recommended action | Only on triggers (see step 5) |
| 3 | **Text-to-speech** | `bulbul:v3` | Python SDK (`text_to_speech.convert`) | Creating the three **synthetic, fictional** demo clips | Once, offline (`backend/tools/make_demo_audio.py`). Not used at runtime |

`sarvam-105b-conversations` is also called once by `hello_sarvam.py`, the original "does my key work" check.

### Configuration details

**Speech-to-text (`saaras:v4`)**
- `stream_type=fast`: low-latency partial transcripts, which allow mid-sentence warnings.
- `mode=codemix`: final transcripts keep English words in Latin script and Hindi in Devanagari, matching how people actually talk ("आपका account block होने वाला है").
- `endpointing=vad`: Sarvam's voice detection decides when a sentence has ended.
- `encoding=linear16`, `sample_rate=16000`.
- `language_code=auto` by default. The user can choose Hindi/Hinglish or English. In testing, `hi-IN` gave cleaner Hindi partials than `auto`.
- **Events used:**
  - `transcript.partial` → instant rules check
  - `transcript.final` (with `language` and `language_confidence`) → rules check and the LLM window
  - `vad.speech_start` / `vad.speech_end` → latency measurement
  - `error` / `session.end` → status
- Close codes `1003` (bad key or quota) and `4000` (bad config) stop retries. Others, such as `1008` inactivity, reconnect automatically on the next audio frame.

**LLM (`sarvam-105b`)**
- `response_format={"type": "json_object"}`, `temperature=0.1`, `max_tokens=700`, `reasoning_effort=None`.
- The prompt is short, treats the transcript as untrusted (prompt-injection resistant), requires exact quotes, and asks for an uncertainty note when evidence is weak.
- The output is validated with Pydantic, with one retry on invalid output.
- Measured latency: about **0.7–3.3 s** per call.

### Sarvam features not used yet
- Translation
- Transliteration
- Batch speech-to-text
- Streaming text-to-speech (for example, reading warnings aloud)

Translation would be the natural next step for languages beyond Hindi and English; see [Languages](#languages).

---

## What was tested

Tested on 2026-10-26:

| Component | Status |
|---|---|
| Sarvam realtime STT with the settings above | ✅ Real API, all 3 demo clips |
| Rules engine | ✅ 9 unit tests (`pytest backend/tests`) |
| sarvam-105b analysis, Pydantic validation, verbatim evidence | ✅ Real API; resisted a prompt-injection test; OTP digits redacted before sending |
| Backend endpoints | ✅ End-to-end via `backend/tools/ws_e2e.py` |
| Browser client, demo-audio mode | ✅ Driven in headless Chrome: transcript, red bubble, overlay, EN/HI warnings, summary |
| Browser client, microphone path | ⚠️ Partly tested. Chrome's fake mic audio was captured, resampled, streamed and transcribed by Sarvam. A full alert from a live mic was **not** observed in that run. |
| **Android app** | ❌ **Not compiled and no APK.** The build machine had no Android SDK or Gradle. The code is complete but unverified. |

Measured on a laptop with the demo clips:
- **STT final transcript:** 64–196 ms after speech ended.
- **sarvam-105b:** 0.7–3.3 s.
- **Rule alerts:** fire at the final transcript, or earlier when a partial is clear enough.

These are observations from a few runs, not guarantees.

## Languages

- **Speech recognition:** Sarvam supports 22 Indian languages plus `auto`. The app currently exposes Auto, Hindi/Hinglish and English.
- **Rules engine:** covers English, Devanagari Hindi and romanized Hinglish.
- **Other languages:** a Tamil or Telugu call would likely still get the sarvam-105b warning. Instant rule alerts would be partial: they only catch English loanwords such as "OTP" or "account block". This is **untested**.

---

## Run it

```bash
pip install -r backend/requirements.txt
cp .env.example .env            # then put your key after SARVAM_API_KEY=
python backend/tools/make_demo_audio.py           # optional: regenerates the 3 synthetic demo clips
uvicorn backend.app.main:app --host 0.0.0.0 --port 8000
```

- **Browser app:** open http://localhost:8000. Microphone access needs `localhost` or HTTPS.
- **Tests:** `pytest backend/tests`
- **Backend end-to-end check:** `python backend/tools/ws_e2e.py backend/demo_audio/bank_otp.wav`
- **Android:** open `android/` in Android Studio (JDK 17, SDK 34) and press Run. Set the backend URL in the app:
  - emulator: `http://10.0.2.2:8000`
  - phone: `http://<laptop-LAN-IP>:8000`, or your HTTPS deployment

  Grant microphone, notifications and "display over other apps".

### Live demo
1. Run the backend. Open the app (or browser client), choose **Microphone**, and press **Start Protection**.
2. On a **second device**, play a fictional scam on speaker: for example, open `http://<laptop-ip>:8000/demo/audio/bank_otp.wav`, or read a script aloud.
3. Watch the live transcript, the red shield with the matched phrase, and the sarvam-105b explanation.

### Demo mode (clearly labelled)
- **SIMULATED demo audio:** three fictional scripts voiced by Sarvam bulbul:v3. The voices are synthetic, so no real person was recorded. They stream through the **real** STT and analysis pipeline. Nothing is hardcoded.
- **Typed demo scripts:** these send text straight to the rules engine and sarvam-105b. The UI shows "Speech recognition not used".

The scenarios are a banking OTP scam, fake police intimidation and a normal conversation (`backend/app/scenarios.py`).

## Android telephony audio: what is and isn't possible
- Standard Android **does not let third-party apps capture cellular call audio** (uplink or downlink). That needs `CAPTURE_AUDIO_OUTPUT`, which only system apps can hold. Android 10+ playback capture also excludes call audio.
- ScamShield does **not** use root, hidden APIs, privileged audio sources or AccessibilityService workarounds.
- The MVP uses the **microphone** with consent, for example a call on speakerphone from another device.
- Capture is **pluggable** through `PcmAudioSource`. An OEM, carrier or dialer integration that legitimately receives call audio can provide `AuthorizedTelephonyAudioSource` without other changes.
- Recording conversations may require everyone's consent, depending on local law.

## Privacy and safety
- **API key:** the Sarvam key lives only in the backend `.env` or the host's environment variables. It is never in the APK or the browser.
- **No storage:** raw audio is never stored. Only a sub-100 ms buffer and the last six transcript sentences are held in memory, and they're cleared on Stop. No analytics or telemetry.
- **Redaction:** OTP-like codes, card or account numbers and UPI IDs/emails are replaced before text reaches the LLM.
- **Deduplication:** alerts are deduplicated. Vibration fires once per high-risk episode.
- **Overlay:** the overlay is small, not focusable and draggable, and stays clear of call and emergency controls. If overlay permission is denied, warnings fall back to a notification.
- **No authentication yet.** Anyone with a deployed URL can use it and spend the key's credits.

## Known limitations
- **The LLM can't clear a rules false alarm.** Because the higher level wins, sarvam-105b saying "low" won't turn the bubble green.
- **Rules depend on wording.** A scammer who avoids the listed phrases is only caught by Stage B.
- **Transcription errors affect both stages.**

## Notes from building against the real APIs
- **Encoding:** the streaming encoding is `linear16`. In `fast` mode the frame size is capped at 16,000 bytes, so the backend re-chunks into 100 ms frames.
- **Structured output:** sarvam-105b's strict `json_schema` mode degenerated into whitespace, so the app uses `json_object` mode plus Pydantic.
- **Reasoning:** with reasoning on, even `low`, the model used the entire token budget. Reasoning is turned off for this short classification task.

## Judges' pitch (30 seconds)
India loses thousands of crores a year to phone scams: fake bank KYC calls, "digital arrest" police impersonation, OTP theft. They work because victims are rushed and alone on the call.

**ScamShield Live** is a second pair of ears. It transcribes Hindi, English and Hinglish live with Sarvam saaras:v4. An instant rules engine flags dangerous combinations like *"OTP" + "share now"*, and sarvam-105b then explains why, quoting the exact words from the call. The phone buzzes once, and a red shield says *"Don't share your OTP"* in the user's language.

We don't claim to catch criminals or to tap call audio Android doesn't allow. We warn honestly, keep no recordings, and keep the key on the server.

## Repository layout
```
backend/app/      main.py (API), session.py (coordinator), sarvam_stt.py, rules.py, analysis.py, scenarios.py, config.py
backend/tools/    make_demo_audio.py, stt_smoke.py, ws_e2e.py
backend/tests/    test_rules.py
backend/demo_audio/  3 synthetic fictional clips
web/              browser client (same /ws/audio protocol as Android)
android/          Kotlin + Compose app: audio/, net/, service/, overlay/, state/, ui/
hello_sarvam.py   original one-call setup check
```
