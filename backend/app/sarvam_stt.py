"""Sarvam realtime speech-to-text client (/speech-to-text-realtime/ws) with reconnection.

Protocol (verified against sarvamai SDK 0.1.39):
  query params: language_code, model, stream_type, mode, endpointing, encoding, sample_rate
  auth header:  Api-Subscription-Key
  client -> server: {"event": "audio_input", "audio": <base64 PCM>}, {"event": "end"}, {"event": "ping"}
  server -> client: session.begin, transcript.partial, transcript.final, vad.speech_start,
                    vad.speech_end, error, session.end, pong
  close codes: 1003 bad key/quota, 1008 inactivity, 1011 server error, 4000 config rejected
"""
import asyncio
import base64
import json
import logging
import time
import urllib.parse
from typing import Awaitable, Callable, Optional

import websockets

from . import config

log = logging.getLogger("scamshield.stt")

EventHandler = Callable[[dict], Awaitable[None]]

# Close codes that reconnecting will not fix.
FATAL_CLOSE_CODES = {1003, 4000}


class SarvamRealtimeSTT:
    def __init__(self, on_event: EventHandler, *, language_code: str = config.STT_LANGUAGE, sample_rate: int = 16000):
        self._on_event = on_event
        self._params = {
            "language_code": language_code,
            "model": config.STT_MODEL,
            "stream_type": "fast",
            "mode": "codemix",
            "endpointing": "vad",
            "encoding": "linear16",  # the documented name for 16-bit little-endian PCM
            "sample_rate": str(sample_rate),
        }
        self._ws = None
        self._lock = asyncio.Lock()
        self._recv_task: Optional[asyncio.Task] = None
        self._closed = False
        self._fatal: Optional[str] = None
        self.reconnects = 0
        self.last_audio_sent = 0.0

    @property
    def url(self) -> str:
        return f"{config.SARVAM_WS_BASE}/speech-to-text-realtime/ws?{urllib.parse.urlencode(self._params)}"

    async def _connect(self) -> None:
        headers = {"Api-Subscription-Key": config.require_key()}
        try:
            self._ws = await websockets.connect(self.url, additional_headers=headers, max_size=2**22)
        except websockets.InvalidStatus as exc:
            status = exc.response.status_code
            self._fatal = "invalid_api_key" if status in (401, 403) else f"handshake_http_{status}"
            await self._on_event({"event": "stt.error", "fatal": True, "code": self._fatal})
            raise
        self._recv_task = asyncio.create_task(self._recv_loop(self._ws))
        await self._on_event({"event": "stt.connected", "reconnects": self.reconnects})

    async def _ensure_connected(self) -> bool:
        if self._fatal or self._closed:
            return False
        async with self._lock:
            if self._ws is not None:
                return True
            delay = 0.5
            for attempt in range(4):
                try:
                    await self._connect()
                    return True
                except websockets.InvalidStatus:
                    return False
                except (OSError, websockets.WebSocketException) as exc:
                    log.warning("STT connect attempt %d failed: %s", attempt + 1, exc)
                    await asyncio.sleep(delay)
                    delay *= 2
            await self._on_event({"event": "stt.error", "fatal": False, "code": "connect_failed"})
            return False

    async def _recv_loop(self, ws) -> None:
        try:
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                except (TypeError, ValueError):
                    continue
                if msg.get("event") == "error" and msg.get("is_fatal"):
                    if msg.get("status_code") in (401, 403):
                        self._fatal = "invalid_api_key"
                msg["_recv_ts"] = time.monotonic()
                await self._on_event(msg)
        except websockets.ConnectionClosed:
            pass
        finally:
            code = ws.close_code
            if self._ws is ws:
                self._ws = None
            if code in FATAL_CLOSE_CODES:
                self._fatal = self._fatal or f"closed_{code}"
            if not self._closed:
                # 1008 = inactivity; the next audio chunk transparently reconnects.
                self.reconnects += 1
                await self._on_event({
                    "event": "stt.disconnected",
                    "close_code": code,
                    "reason": ws.close_reason,
                    "will_reconnect": self._fatal is None,
                })

    async def send_pcm(self, pcm: bytes) -> bool:
        if not await self._ensure_connected():
            return False
        msg = json.dumps({"event": "audio_input", "audio": base64.b64encode(pcm).decode("ascii")})
        try:
            await self._ws.send(msg)
            self.last_audio_sent = time.monotonic()
            return True
        except (websockets.ConnectionClosed, AttributeError):
            self._ws = None
            return False

    async def finish(self, timeout: float = 6.0) -> None:
        """Ask Sarvam to finalize the pending utterance, then wait for the socket to close."""
        ws = self._ws
        if ws is None:
            return
        try:
            await ws.send(json.dumps({"event": "end"}))
            if self._recv_task:
                await asyncio.wait_for(asyncio.shield(self._recv_task), timeout)
        except (asyncio.TimeoutError, websockets.ConnectionClosed):
            pass

    async def close(self) -> None:
        self._closed = True
        ws, self._ws = self._ws, None
        if ws is not None:
            await ws.close()
        if self._recv_task:
            self._recv_task.cancel()
