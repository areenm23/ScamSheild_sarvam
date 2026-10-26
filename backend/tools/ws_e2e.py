"""End-to-end check of the running backend: stream a WAV to /ws/audio like the phone would.

    python backend/tools/ws_e2e.py backend/demo_audio/bank_otp.wav [ws://127.0.0.1:8000]
"""
import asyncio
import json
import sys
import time
import wave

import websockets

sys.stdout.reconfigure(encoding="utf-8")


async def main(path: str, base: str) -> None:
    with wave.open(path) as w:
        pcm = w.readframes(w.getnframes())
    t0 = time.monotonic()
    async with websockets.connect(f"{base}/ws/audio?source=e2e-test&lang=en") as ws:
        async def reader():
            async for raw in ws:
                ev = json.loads(raw)
                t = f"[{time.monotonic() - t0:5.2f}s]"
                if ev["type"] == "transcript":
                    print(t, ev["kind"].upper(), ev["text"], f"(final_after_speech_end_ms={ev.get('final_after_speech_end_ms')})" if ev["kind"] == "final" else "")
                elif ev["type"] == "alert":
                    print(t, "RULE ALERT", ev["level"], ev["category"], ev["evidence"])
                elif ev["type"] == "analysis":
                    print(t, "LLM", {k: ev.get(k) for k in ("ok", "trigger", "risk_level", "risk_score", "category", "latency_ms")},
                          [e["phrase"] for e in ev.get("evidence", [])])
                elif ev["type"] == "summary":
                    print(t, "SUMMARY", json.dumps(ev, ensure_ascii=False))
                else:
                    print(t, ev)

        rt = asyncio.create_task(reader())
        for i in range(0, len(pcm), 2048):  # odd-sized frames on purpose: server must re-chunk
            await ws.send(pcm[i:i + 2048])
            await asyncio.sleep(2048 / 32000)
        await ws.send(b"\x00" * 32000)  # 1 s of silence
        await asyncio.sleep(1.5)
        await ws.send(json.dumps({"type": "stop"}))
        await asyncio.wait_for(rt, 30)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "ws://127.0.0.1:8000"))
