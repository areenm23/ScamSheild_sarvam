"""ScamShield Live backend: one service that coordinates Sarvam streaming STT and fraud analysis.

Run from the project root:
    uvicorn backend.app.main:app --host 0.0.0.0 --port 8000
"""
import asyncio
import json
import logging
from typing import Literal, Optional

from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import config, rules
from .analysis import Analyzer
from .scenarios import SCENARIOS
from .session import SUMMARIES, ProtectionSession

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
log = logging.getLogger("scamshield")

app = FastAPI(title="ScamShield Live backend")
analyzer = Analyzer()
ACTIVE: dict[str, ProtectionSession] = {}


@app.on_event("startup")
async def _warm_llm_client() -> None:
    # Creating the HTTP client lazily cost ~1.6 s on the first alert in testing; build it up front.
    if config.SARVAM_API_KEY:
        analyzer._get_client()


@app.get("/health")
async def health() -> dict:
    return {
        "ok": True,
        "sarvam_key_configured": bool(config.SARVAM_API_KEY),
        "stt_model": config.STT_MODEL,
        "llm_model": config.LLM_MODEL,
        "dev_mode": config.DEV_MODE,
        "active_sessions": len(ACTIVE),
    }


class AnalyzeRequest(BaseModel):
    text: str = Field(min_length=1, max_length=4000)
    input_kind: Literal["typed", "transcript"] = "typed"


@app.post("/analyze")
async def analyze(req: AnalyzeRequest) -> dict:
    """Typed text (e.g. demo scripts) or a transcript excerpt. Speech recognition is NOT involved."""
    rule_res = rules.evaluate(req.text).to_dict()
    llm = await analyzer.analyze(req.text, trigger="manual_text")
    return {
        "input_kind": req.input_kind,
        "speech_recognition_used": False,
        "rules": rule_res,
        "analysis": llm,
    }


@app.get("/session/summary")
async def session_summary(session_id: Optional[str] = None) -> dict:
    if session_id:
        if session_id in ACTIVE:
            return {"active": True, **ACTIVE[session_id].summary()}
        if session_id in SUMMARIES:
            return {"active": False, **SUMMARIES[session_id]}
        raise HTTPException(404, "unknown session")
    if not SUMMARIES:
        raise HTTPException(404, "no completed sessions")
    return {"active": False, **SUMMARIES[next(reversed(SUMMARIES))]}


@app.get("/demo/scenarios")
async def demo_scenarios() -> dict:
    return {
        "note": "Fictional scripts. Audio is synthetic (Sarvam bulbul:v3 TTS); no real person was recorded.",
        "scenarios": [
            {"id": sid, "title": sc["title"], "script": sc["script"],
             "audio_url": f"/demo/audio/{sid}.wav" if (config.DEMO_AUDIO_DIR / f"{sid}.wav").exists() else None}
            for sid, sc in SCENARIOS.items()
        ],
    }


@app.get("/demo/audio/{sid}.wav")
async def demo_audio(sid: str) -> FileResponse:
    if sid not in SCENARIOS or not (config.DEMO_AUDIO_DIR / f"{sid}.wav").exists():
        raise HTTPException(404, "no demo audio; run backend/tools/make_demo_audio.py")
    return FileResponse(config.DEMO_AUDIO_DIR / f"{sid}.wav", media_type="audio/wav")


@app.websocket("/ws/audio")
async def ws_audio(
    ws: WebSocket,
    source: str = Query("microphone", max_length=64),
    lang: Literal["en", "hi"] = Query("en"),
    stt_lang: Literal["auto", "hi-IN", "en-IN"] = Query(config.STT_LANGUAGE),
) -> None:
    """Binary frames: 16-bit mono PCM @ 16 kHz. Text frames: {"type": "analyze"} | {"type": "stop"}."""
    await ws.accept()
    if not config.SARVAM_API_KEY:
        await ws.send_json({"type": "status", "stt": "error", "code": "server_missing_api_key", "fatal": True})
        await ws.close(code=1011)
        return

    send_lock = asyncio.Lock()

    async def send(msg: dict) -> None:
        async with send_lock:
            try:
                await ws.send_json(msg)
            except (RuntimeError, WebSocketDisconnect):
                pass

    session = ProtectionSession(send, analyzer, source=source, language_pref=lang, stt_language=stt_lang)
    ACTIVE[session.id] = session
    await send({"type": "session", "session_id": session.id, "source": source,
                "stt_model": config.STT_MODEL, "stt_language": stt_lang, "llm_model": config.LLM_MODEL,
                "dev_mode": config.DEV_MODE})
    log.info("session %s started (source=%s)", session.id, source)
    try:
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                break
            if msg.get("bytes"):
                await session.feed_audio(msg["bytes"])
            elif msg.get("text"):
                try:
                    ctl = json.loads(msg["text"])
                except ValueError:
                    continue
                if ctl.get("type") == "analyze":
                    session.request_llm("manual")
                elif ctl.get("type") == "stop":
                    summary = await session.stop()
                    await send({"type": "summary", **summary})
                    await ws.close()
                    break
    except WebSocketDisconnect:
        pass
    finally:
        ACTIVE.pop(session.id, None)
        if session.id not in SUMMARIES:
            await session.close()
        log.info("session %s ended", session.id)


if config.WEB_DIR.exists():
    app.mount("/", StaticFiles(directory=config.WEB_DIR, html=True), name="web")
