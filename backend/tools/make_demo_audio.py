"""Synthesize the fictional demo scenarios to 16 kHz mono WAV with Sarvam TTS (bulbul:v3).

The voices are synthetic, so no real person's speech is recorded. Run from the project root:
    python backend/tools/make_demo_audio.py
"""
import base64
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sarvamai import SarvamAI  # noqa: E402

from app.config import DEMO_AUDIO_DIR, require_key  # noqa: E402
from app.scenarios import SCENARIOS  # noqa: E402


def main() -> None:
    client = SarvamAI(api_subscription_key=require_key())
    DEMO_AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    for sid, sc in SCENARIOS.items():
        resp = client.text_to_speech.convert(
            text=sc["tts_text"],
            language_code="hi-IN",
            speaker=sc["speaker"],
            model="bulbul:v3",
            speech_sample_rate=16000,
            output_audio_codec="wav",
        )
        out = DEMO_AUDIO_DIR / f"{sid}.wav"
        out.write_bytes(b"".join(base64.b64decode(a) for a in resp.audios))
        print(f"wrote {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
