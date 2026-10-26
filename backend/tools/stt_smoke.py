"""Stream a 16 kHz WAV through Sarvam realtime STT at real-time pace and print every event.

    python backend/tools/stt_smoke.py backend/demo_audio/bank_otp.wav
"""
import asyncio
import json
import sys
import time
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.stdout.reconfigure(encoding="utf-8")

from app.sarvam_stt import SarvamRealtimeSTT  # noqa: E402


async def main(path: str) -> None:
    t0 = time.monotonic()

    async def on_event(ev: dict) -> None:
        ev = {k: v for k, v in ev.items() if k != "_recv_ts"}
        print(f"[{time.monotonic() - t0:5.2f}s] {json.dumps(ev, ensure_ascii=False)}")

    stt = SarvamRealtimeSTT(on_event)
    with wave.open(path) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
        pcm = w.readframes(w.getnframes())
    chunk = 3200  # 100 ms
    for i in range(0, len(pcm), chunk):
        await stt.send_pcm(pcm[i:i + chunk])
        await asyncio.sleep(0.1)
    await stt.send_pcm(b"\x00" * 32000)  # 1 s of silence so VAD closes the turn
    await stt.finish()
    await stt.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))
