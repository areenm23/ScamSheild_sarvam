"""One protection session: Sarvam STT stream -> Stage A rules -> Stage B LLM, with dedup and metrics.

Nothing is persisted. Audio exists only as the sub-100 ms re-chunking remainder; transcripts are a short
rolling window that is cleared when the session stops.
"""
import asyncio
import logging
import time
import uuid
from collections import deque
from typing import Awaitable, Callable, Optional

from . import rules
from .analysis import Analyzer, redact
from .sarvam_stt import SarvamRealtimeSTT

log = logging.getLogger("scamshield.session")

FRAME_BYTES = 3200  # 100 ms of 16 kHz s16le mono; Sarvam 'fast' caps frames at 16000 bytes
FINALS_WINDOW = 6  # finalized utterances kept in memory
LLM_WINDOW = 4  # finalized utterances sent to the LLM (overlapping windows)
MIN_WORDS_FOR_TURN_ANALYSIS = 6
LEVEL_RANK = rules.LEVEL_RANK

Sender = Callable[[dict], Awaitable[None]]

SUMMARIES: dict[str, dict] = {}  # session_id -> summary (no transcript, redacted evidence only)


class ProtectionSession:
    def __init__(self, send: Sender, analyzer: Analyzer, *, source: str, language_pref: str, stt_language: str):
        self.id = uuid.uuid4().hex[:12]
        self.source = source
        self.language_pref = language_pref
        self._send = send
        self._analyzer = analyzer
        self.stt_language = stt_language
        self.stt = SarvamRealtimeSTT(self._on_stt_event, language_code=stt_language)
        self.started = time.time()
        self._pending = b""
        self.finals: deque[dict] = deque(maxlen=FINALS_WINDOW)
        self.partial = ""
        self.partial_idx = -1
        self.detected_language: Optional[str] = None
        self.alert_keys: set[str] = set()
        self.alerts: list[dict] = []
        self.rules_level = "low"
        self.llm_latest: Optional[dict] = None
        self._llm_task: Optional[asyncio.Task] = None
        self._llm_rerun: Optional[str] = None
        self._llm_triggered_utt: set[int] = set()
        self._last_llm_text = ""
        self.metrics = {"audio_seconds": 0.0, "partials": 0, "finals": 0, "llm_calls": 0,
                        "stt_final_after_speech_end_ms": [], "stt_first_partial_ms": [], "llm_latency_ms": [],
                        "stt_reconnects": 0}
        self._speech_start_ts: dict[int, float] = {}
        self._speech_end_ts: dict[int, float] = {}
        self._first_partial_seen: set[int] = set()

    # ---------------- audio in ----------------
    async def feed_audio(self, pcm: bytes) -> None:
        buf = self._pending + pcm
        n = len(buf) - len(buf) % FRAME_BYTES
        self._pending = buf[n:]
        for i in range(0, n, FRAME_BYTES):
            if not await self.stt.send_pcm(buf[i:i + FRAME_BYTES]):
                break
            self.metrics["audio_seconds"] += FRAME_BYTES / 32000

    # ---------------- STT events ----------------
    async def _on_stt_event(self, ev: dict) -> None:
        kind = ev.get("event")
        now = ev.get("_recv_ts", time.monotonic())
        idx = ev.get("utterance_idx", -1)
        if kind == "transcript.partial":
            text = (ev.get("text") or "").strip()
            if not text:
                return
            self.metrics["partials"] += 1
            if idx not in self._first_partial_seen and idx in self._speech_start_ts:
                self._first_partial_seen.add(idx)
                self.metrics["stt_first_partial_ms"].append(round((now - self._speech_start_ts[idx]) * 1000))
            self.partial, self.partial_idx = text, idx
            self.detected_language = ev.get("language") or self.detected_language
            await self._send({"type": "transcript", "kind": "partial", "text": text, "utterance_idx": idx,
                              "language": ev.get("language"), "source": "sarvam-stt"})
            await self._run_rules(trigger="partial", utterance_idx=idx)
        elif kind == "transcript.final":
            text = (ev.get("text") or "").strip()
            latency = None
            if idx in self._speech_end_ts:
                latency = round((now - self._speech_end_ts[idx]) * 1000)
                self.metrics["stt_final_after_speech_end_ms"].append(latency)
            self.partial, self.partial_idx = "", -1
            if not text:
                return
            self.metrics["finals"] += 1
            self.detected_language = ev.get("language") or self.detected_language
            self.finals.append({"idx": idx, "text": text, "language": ev.get("language")})
            await self._send({"type": "transcript", "kind": "final", "text": text, "utterance_idx": idx,
                              "language": ev.get("language"), "language_confidence": ev.get("language_confidence"),
                              "final_after_speech_end_ms": latency, "source": "sarvam-stt"})
            found = await self._run_rules(trigger="final", utterance_idx=idx)
            if rules.is_suspicious_signal(found) or len(self._llm_text().split()) >= MIN_WORDS_FOR_TURN_ANALYSIS:
                self.request_llm("turn_end" if not rules.is_suspicious_signal(found) else "suspicious_final")
        elif kind == "vad.speech_start":
            self._speech_start_ts[idx] = now
        elif kind == "vad.speech_end":
            self._speech_end_ts[idx] = now
        elif kind == "error":
            await self._send({"type": "status", "stt": "error", "code": ev.get("code"), "message": ev.get("message"),
                              "fatal": ev.get("is_fatal", False)})
        elif kind in ("stt.connected", "stt.disconnected", "stt.error"):
            if kind == "stt.disconnected":
                self.metrics["stt_reconnects"] += 1
            await self._send({"type": "status", "stt": kind.split(".")[1],
                              **{k: v for k, v in ev.items() if k not in ("event", "_recv_ts")}})
        elif kind == "session.begin":
            await self._send({"type": "status", "stt": "session_begin", "config": ev.get("config")})

    # ---------------- Stage A ----------------
    def _rules_text(self) -> str:
        parts = [f["text"] for f in list(self.finals)[-2:]]
        if self.partial:
            parts.append(self.partial)
        return " ".join(parts)

    async def _run_rules(self, *, trigger: str, utterance_idx: int) -> dict:
        res = rules.evaluate(self._rules_text())
        if res.level == "low":
            return res.indicators
        key = f"rules:{res.rule_id or res.category}"
        is_new = key not in self.alert_keys
        if LEVEL_RANK[res.level] > LEVEL_RANK[self.rules_level]:
            self.rules_level = res.level
        if is_new:
            self.alert_keys.add(key)
            alert = {"type": "alert", "alert_id": key, "is_new": True, "trigger": trigger,
                     "utterance_idx": utterance_idx, "ts": time.time(), **res.to_dict()}
            self.alerts.append(alert)
            await self._send(alert)
            # Any new rule hit on a partial gets contextual analysis once per utterance.
            if trigger == "partial" and utterance_idx not in self._llm_triggered_utt:
                self._llm_triggered_utt.add(utterance_idx)
                self.request_llm("suspicious_partial")
        return res.indicators

    # ---------------- Stage B ----------------
    def _llm_text(self, include_partial: bool = False) -> str:
        lines = [f["text"] for f in list(self.finals)[-LLM_WINDOW:]]
        if include_partial and self.partial:
            lines.append(self.partial + " (speech in progress)")
        return "\n".join(lines)

    def request_llm(self, trigger: str) -> None:
        if self._llm_task and not self._llm_task.done():
            self._llm_rerun = trigger  # coalesce: run once more after the in-flight call
            return
        self._llm_task = asyncio.create_task(self._llm(trigger))

    async def _llm(self, trigger: str) -> None:
        while trigger:
            text = self._llm_text(include_partial=(trigger == "suspicious_partial"))
            if text.strip() and (text != self._last_llm_text or trigger == "manual"):
                self._last_llm_text = text
                self.metrics["llm_calls"] += 1
                await self._send({"type": "analysis_pending", "trigger": trigger})
                result = await self._analyzer.analyze(text, trigger=trigger)
                self.metrics["llm_latency_ms"].append(result.get("latency_ms"))
                if result.get("ok"):
                    self.llm_latest = result
                    key = f"llm:{result['category']}:{result['risk_level']}"
                    result["is_new"] = result["should_alert"] and key not in self.alert_keys
                    if result["is_new"]:
                        self.alert_keys.add(key)
                        self.alerts.append({"alert_id": key, **result})
                await self._send({"type": "analysis", **result})
            trigger, self._llm_rerun = self._llm_rerun, None

    # ---------------- lifecycle ----------------
    def overall_level(self) -> str:
        llm = self.llm_latest["risk_level"] if self.llm_latest else "low"
        return max(self.rules_level, llm, key=LEVEL_RANK.get)

    def summary(self) -> dict:
        m = self.metrics
        cats = sorted({a.get("category") for a in self.alerts if a.get("category")})
        evidence = []
        for a in self.alerts:
            for e in a.get("evidence", []):
                phrase = redact(e["phrase"] if isinstance(e, dict) else e)
                if phrase not in evidence:
                    evidence.append(phrase)
        return {
            "session_id": self.id, "source": self.source, "stt_language": self.stt_language,
            "duration_s": round(time.time() - self.started, 1),
            "overall_risk": self.overall_level(),
            "rules_max_level": self.rules_level,
            "llm_latest": {k: self.llm_latest.get(k) for k in ("risk_level", "risk_score", "category",
                                                                "recommended_action", "uncertainty_note")}
            if self.llm_latest else None,
            "alert_count": len(self.alerts), "categories": cats, "evidence": evidence[:10],
            "detected_language": self.detected_language,
            "metrics": {
                "audio_seconds": round(m["audio_seconds"], 1), "partials": m["partials"], "finals": m["finals"],
                "llm_calls": m["llm_calls"], "stt_reconnects": m["stt_reconnects"],
                "stt_first_partial_ms": m["stt_first_partial_ms"],
                "stt_final_after_speech_end_ms": m["stt_final_after_speech_end_ms"],
                "llm_latency_ms": m["llm_latency_ms"],
            },
            "note": "Risk levels are heuristic indicators, not proof that a caller is fraudulent.",
        }

    async def stop(self) -> dict:
        if self._pending:
            await self.stt.send_pcm(self._pending)
            self._pending = b""
        await self.stt.finish()
        if self._llm_task and not self._llm_task.done():
            try:
                await asyncio.wait_for(self._llm_task, 15)
            except asyncio.TimeoutError:
                self._llm_task.cancel()
        summary = self.summary()
        await self.close()
        SUMMARIES[self.id] = summary
        while len(SUMMARIES) > 20:
            SUMMARIES.pop(next(iter(SUMMARIES)))
        return summary

    async def close(self) -> None:
        await self.stt.close()
        if self._llm_task and not self._llm_task.done():
            self._llm_task.cancel()
        # Clear transcript/session data.
        self.finals.clear()
        self.partial = ""
        self._pending = b""
        self._last_llm_text = ""
        if self.llm_latest:
            self.llm_latest.pop("analyzed_text", None)
