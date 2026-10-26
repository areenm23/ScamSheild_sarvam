# ScamShield Live

ScamShield Live listens to audio the user has authorized, transcribes it in real time with Sarvam saaras:v4, and shows a floating warning when the conversation contains scam indicators. Examples include OTP or PIN requests, threats to block an account, fake police demanding a "settlement", remote-access apps and job fees. It supports English, Hindi and Hinglish.

It **warns; it does not prove fraud.** Risk scores are heuristics, not probabilities.

```
Phone / browser ──16 kHz PCM──▶ FastAPI backend ──▶ Sarvam /speech-to-text-realtime/ws (saaras:v4)
   ▲  overlay, vibration             │  Stage A: local rules (instant, deterministic)
   └──────── JSON events ◀───────────┘  Stage B: sarvam-105b on overlapping transcript windows
```

## What was actually tested (2026-10-26)

| Component | Status |
|---|---|
| Sarvam realtime STT (saaras:v4, `fast`, `codemix`, `vad`, `linear16`, 16 kHz) | ✅ Real API, all 3 demo clips |
| Rules engine (EN / Devanagari / Hinglish, combo-based) | ✅ 9 unit tests (`pytest backend/tests`) |
| sarvam-105b analysis, Pydantic validation, evidence must be verbatim | ✅ Real API; also resisted a prompt-injection test; OTP digits redacted before sending |
| Backend `/health`, `/ws/audio`, `/analyze`, `/session/summary` | ✅ End-to-end via `backend/tools/ws_e2e.py` |
| Browser client, demo-audio mode | ✅ Driven in headless Chrome: transcript, red bubble, overlay card, warnings in EN and HI, summary |
| Browser client, microphone path | ⚠️ Partly tested. Chrome's fake mic captured audio, resampled it and streamed it; Sarvam transcribed it. A full alert from a live mic was **not** observed in that run. |
| **Android app (Kotlin/Compose)** | ❌ **Not compiled and no APK.** This machine has no Android SDK or Gradle, and only JDK 8. The code is complete but unverified. |

Measured on this laptop (demo clips):
- **STT final transcript:** 64–196 ms after speech ended.
- **sarvam-105b:** 0.7–3.3 s per analysis.
- **Rule alerts:** fire at the final transcript, or earlier when a partial is clear enough.

These are measurements from a few runs, not guarantees.

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
  - phone: `http://<laptop-LAN-IP>:8000`

  Grant microphone, notifications and "display over other apps". Command-line build: `gradle wrapper && ./gradlew assembleDebug`.

### Live demo (the success scenario)
1. Run the backend. Open the app (or the browser client) and press **Start Protection** with **Microphone** selected.
2. On a **second device**, play a fictional scam on speaker: for example, open `http://<laptop-ip>:8000/demo/audio/bank_otp.wav`, or read a script aloud.
3. Watch the transcript, the red bubble with the matched phrase, and the evidence-backed Sarvam-105B explanation.
4. For Hindi or Hinglish callers, choose **Call language → Hindi/Hinglish**. In testing, `auto` sometimes produced English paraphrases in the partials, which delays early warnings until the final transcript.

## Demo mode (clearly labelled)
- **SIMULATED demo audio:** three fictional scripts synthesized with Sarvam bulbul:v3. The voices are synthetic, so no real person was recorded. They stream through the **real** STT and analysis pipeline. Nothing is hardcoded.
- **Typed demo scripts:** these send text straight to the rules engine and sarvam-105b. The UI shows "Speech recognition not used".

The three scenarios: Banking OTP scam, Fake police intimidation, Normal conversation (see `backend/app/scenarios.py`).

## Android telephony audio: what is and isn't possible
- Standard Android **does not let third-party apps capture cellular call audio** (uplink or downlink). That needs `CAPTURE_AUDIO_OUTPUT`, which only system apps can hold. Android 10+ playback capture also excludes call audio.
- ScamShield does **not** use root, hidden APIs, privileged audio sources or AccessibilityService workarounds.
- The MVP uses the **microphone** with the user's consent. Typical setups are a call on speakerphone from another device, or an in-person conversation.
- Capture is **pluggable** through `PcmAudioSource` (`android/.../audio/AudioSources.kt`). An OEM, carrier or dialer integration that legitimately receives call audio can provide `AuthorizedTelephonyAudioSource` without other changes.
- Recording calls or conversations may require the consent of everyone involved, depending on local law.

## Privacy and safety design
- **API key:** the Sarvam key lives only in the backend `.env`. It is never in the APK or the browser.
- **No storage:** raw audio is never stored. Only a sub-100 ms re-chunking buffer and the last six transcript utterances are held in memory. They are cleared on Stop. No analytics or telemetry.
- **Redaction:** OTP-like codes, long numbers (card or account) and UPI IDs/emails are replaced before transcript text reaches the LLM.
- **Prompt injection:** the LLM is told the transcript is untrusted. It must quote evidence verbatim. Quotes not found in the transcript are dropped, and an alert with no verified evidence is downgraded and marked uncertain.
- **Deduplication:** alerts are deduplicated. Vibration fires once per high-risk episode (60 s cooldown).
- **Overlay:** the overlay is small, not focusable and draggable, and sits at the top so it stays clear of call and emergency controls. If overlay permission is denied, warnings fall back to a notification and the in-app screen.

## Notes from building against the real APIs
- **Encoding:** the streaming encoding is `linear16` (the spec said `pcm_s16le`). In `fast` mode the frame size is capped at 16,000 bytes, so the backend re-chunks into 100 ms frames.
- **Structured output:** sarvam-105b's strict `json_schema` mode degenerated into whitespace. The app uses `json_object` mode plus Pydantic validation, with one retry.
- **Reasoning:** with reasoning on, even `reasoning_effort="low"`, the model used the entire token budget. Reasoning is turned off for this short classification task.

## Judges' pitch (30 seconds)
India loses thousands of crores a year to phone scams: fake bank KYC calls, "digital arrest" police impersonation, OTP theft. They work because victims are rushed and alone on the call.

**ScamShield Live** is a second pair of ears. It transcribes Hindi, English and Hinglish live with Sarvam saaras:v4. An instant on-device-style rules engine flags dangerous combinations like *"OTP" + "share now"*, and Sarvam-105B then explains why, citing exact phrases from the call. The phone buzzes once, and a red shield says *"Don't share your OTP"* in the user's language.

We don't claim to catch criminals or to tap call audio Android doesn't allow. We warn honestly, keep no recordings, and keep the key on the server.

## Layout
```
backend/app/      main.py (FastAPI), session.py (coordinator), sarvam_stt.py, rules.py, analysis.py, scenarios.py
backend/tools/    make_demo_audio.py, stt_smoke.py, ws_e2e.py
backend/tests/    test_rules.py
web/              browser client (same /ws/audio protocol as Android)
android/          Kotlin + Compose app: audio/, net/, service/, overlay/, state/, ui/
hello_sarvam.py   original one-call setup check
```
