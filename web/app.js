// ScamShield Live — browser client. Same protocol as the Android app: binary 16 kHz s16le frames to /ws/audio.
const $ = (id) => document.getElementById(id);
const RANK = { low: 0, medium: 1, high: 2 };
const COLORS = { low: "var(--emerald)", medium: "var(--amber)", high: "var(--red)" };

let ws = null, ctx = null, node = null, stream = null, demoSrc = null;
let started = 0, timer = null, sessionId = null;
let level = "low", rulesLevel = "low", llmLevel = "low";
let peaks = new Array(120).fill(0);
let vibratedFor = new Set();
let partialEl = null;
const metrics = { firstPartial: [], finalAfterSpeechEnd: [], llm: [], ruleAlertAt: [], reconnects: 0 };

function esc(s) { return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function wlang() { return $("wlang").value; }

// ---------------- start / stop ----------------
async function start() {
  const source = $("source").value;
  resetUi();
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const qs = new URLSearchParams({ source, lang: wlang(), stt_lang: $("sttlang").value });
  ws = new WebSocket(`${proto}://${location.host}/ws/audio?${qs}`);
  ws.binaryType = "arraybuffer";
  ws.onmessage = (e) => onEvent(JSON.parse(e.data));
  ws.onclose = () => { if (started) stop(true); };
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error("backend not reachable")); });

  ctx = new AudioContext();
  await ctx.audioWorklet.addModule("pcm-worklet.js");
  node = new AudioWorkletNode(ctx, "pcm-capture");
  node.port.onmessage = (e) => {
    if (e.data instanceof ArrayBuffer) { if (ws && ws.readyState === 1) ws.send(e.data); }
    else peaks.push(e.data.peak), peaks.shift();
  };

  if (source === "microphone") {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: false, noiseSuppression: true, autoGainControl: true } });
    ctx.createMediaStreamSource(stream).connect(node);
    $("srcv").textContent = "Microphone";
  } else {
    const id = source.split(":")[1];
    const buf = await (await fetch(`/demo/audio/${id}.wav`)).arrayBuffer();
    const audio = await ctx.decodeAudioData(buf);
    demoSrc = ctx.createBufferSource();
    demoSrc.buffer = audio;
    demoSrc.connect(node);
    demoSrc.connect(ctx.destination); // you hear what is being streamed
    demoSrc.onended = () => {
      // 1.2 s of silence so Sarvam's VAD closes the turn
      const silence = new Int16Array(1600).buffer;
      for (let i = 0; i < 12; i++) setTimeout(() => ws && ws.readyState === 1 && ws.send(silence), i * 100);
    };
    demoSrc.start();
    $("srcv").textContent = "SIMULATED demo audio (synthetic voice)";
  }
  // The worklet's input stays silent if nothing is connected; keep it pulled.
  const sink = ctx.createGain(); sink.gain.value = 0; node.connect(sink).connect(ctx.destination);

  started = Date.now();
  timer = setInterval(tick, 250);
  $("toggle").textContent = "Stop Protection"; $("toggle").classList.add("stop");
  $("st").textContent = "Active"; $("dot").classList.add("on"); $("manual").disabled = false;
  $("overlay").style.display = "flex"; setBubble("low");
}

function stop(remoteClosed = false) {
  if (!started) return;
  started = 0; clearInterval(timer);
  if (ws && ws.readyState === 1 && !remoteClosed) ws.send(JSON.stringify({ type: "stop" }));
  if (stream) stream.getTracks().forEach((t) => t.stop());
  if (demoSrc) try { demoSrc.stop(); } catch (_) {}
  if (ctx) ctx.close();
  stream = demoSrc = ctx = node = null;
  $("toggle").textContent = "Start Protection"; $("toggle").classList.remove("stop");
  $("st").textContent = "Inactive"; $("dot").classList.remove("on"); $("manual").disabled = true;
  $("overlay").style.display = "none"; $("ocard").style.display = "none";
}

function resetUi() {
  $("feed").innerHTML = ""; $("signs").innerHTML = '<div class="muted" id="signs-empty">None yet.</div>'; $("llmbox").innerHTML = "";
  $("summary").innerHTML = '<span class="muted">Appears when protection stops.</span>';
  level = rulesLevel = llmLevel = "low"; vibratedFor = new Set(); partialEl = null;
  Object.assign(metrics, { firstPartial: [], finalAfterSpeechEnd: [], llm: [], ruleAlertAt: [], reconnects: 0 });
  setRisk("low", 0, "");
}

function tick() {
  const s = Math.floor((Date.now() - started) / 1000);
  $("dur").textContent = `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

// ---------------- server events ----------------
function onEvent(ev) {
  switch (ev.type) {
    case "session":
      sessionId = ev.session_id;
      $("statusline").textContent = `Session ${ev.session_id} · STT ${ev.stt_model} (${ev.stt_language}) · LLM ${ev.llm_model}`;
      break;
    case "status":
      if (ev.stt === "error") $("statusline").textContent = `STT error: ${ev.code} ${ev.message || ""}${ev.fatal ? " (fatal)" : ""}`;
      if (ev.stt === "disconnected") { metrics.reconnects++; $("statusline").textContent = `STT disconnected (code ${ev.close_code}) — ${ev.will_reconnect ? "reconnecting on next audio" : "not retrying"}`; }
      break;
    case "transcript": onTranscript(ev); break;
    case "alert": onRuleAlert(ev); break;
    case "analysis_pending":
      $("llmbox").innerHTML = `<span class="prov llm">Sarvam-105B</span><span class="muted">Analyzing recent conversation (${esc(ev.trigger)})…</span>`;
      break;
    case "analysis": onAnalysis(ev); break;
    case "summary": onSummary(ev); break;
  }
  renderMetrics();
}

function onTranscript(ev) {
  if (ev.language) $("langv").textContent = ev.language;
  const feed = $("feed");
  if (ev.kind === "partial") {
    if (!partialEl) { partialEl = document.createElement("div"); partialEl.className = "line partial"; feed.appendChild(partialEl); }
    partialEl.innerHTML = `${esc(ev.text)}<span class="tag">partial</span>`;
  } else {
    if (partialEl) partialEl.remove(); partialEl = null;
    const el = document.createElement("div"); el.className = "line";
    const lat = ev.final_after_speech_end_ms != null ? ` · final ${ev.final_after_speech_end_ms} ms after speech end` : "";
    el.innerHTML = `${esc(ev.text)}<span class="tag">final · ${esc(ev.language || "")}${lat}</span>`;
    feed.appendChild(el);
    if (ev.final_after_speech_end_ms != null) metrics.finalAfterSpeechEnd.push(ev.final_after_speech_end_ms);
  }
  feed.scrollTop = feed.scrollHeight;
}

function warningText(ev) { return wlang() === "hi" ? ev.warning_hi : ev.warning_en; }

function onRuleAlert(ev) {
  metrics.ruleAlertAt.push(started ? Date.now() - started : 0);
  rulesLevel = maxLevel(rulesLevel, ev.level);
  addSign(ev.level, "rules", warningText(ev), ev.evidence, `${ev.category} · on ${ev.trigger} transcript`);
  updateOverall(ev.score, "Local rules engine (instant)");
  showOverlay(ev.level, ev.evidence[0], warningText(ev), `rules:${ev.alert_id}`);
}

function onAnalysis(ev) {
  if (!ev.ok) {
    $("llmbox").innerHTML = `<span class="prov llm">Sarvam-105B</span><span class="muted">Analysis unavailable (${esc(ev.error)}). Rule-based warnings still active.</span>`;
    return;
  }
  metrics.llm.push(ev.latency_ms);
  llmLevel = ev.risk_level;
  const ev0 = ev.evidence.map((e) => `<div class="chip" title="${esc(e.reason)}">“${esc(e.phrase)}” — ${esc(e.reason)}</div>`).join("");
  $("llmbox").innerHTML = `
    <div><span class="prov llm">Sarvam-105B · ${ev.latency_ms} ms · ${esc(ev.trigger)}</span>
      <b class="lvl ${ev.risk_level}" style="font-size:14px">${ev.risk_level}</b> · ${esc(ev.category)} · score ${ev.risk_score}</div>
    <div style="margin-top:6px">${esc(warningText(ev))}</div>
    <div>${ev0 || '<span class="muted">No evidence cited.</span>'}</div>
    <div class="muted" style="margin-top:6px"><b>Recommended:</b> ${esc(ev.recommended_action)}</div>
    ${ev.uncertainty_note ? `<div class="muted" style="color:#fcd34d">Uncertainty: ${esc(ev.uncertainty_note)}</div>` : ""}
    ${ev.unverified_evidence_dropped ? `<div class="muted">${ev.unverified_evidence_dropped} quote(s) dropped: not found verbatim in transcript.</div>` : ""}`;
  if (ev.should_alert && ev.is_new) {
    addSign(ev.risk_level, "llm", warningText(ev), ev.evidence.map((e) => e.phrase), `${ev.category} · contextual`);
    showOverlay(ev.risk_level, ev.evidence[0] && ev.evidence[0].phrase, warningText(ev), `llm:${ev.category}`);
  }
  updateOverall(ev.risk_score, "Sarvam-105B contextual analysis");
}

function onSummary(s) {
  $("summary").innerHTML = `
    <div><b class="lvl ${s.overall_risk}" style="font-size:16px">${s.overall_risk}</b> overall · ${s.alert_count} alert(s) · ${s.duration_s}s · language ${esc(s.detected_language || "—")}</div>
    <div class="muted">Categories: ${esc(s.categories.join(", ") || "none")}</div>
    <div>${s.evidence.map((p) => `<span class="chip">${esc(p)}</span>`).join("")}</div>
    ${s.llm_latest ? `<div class="muted" style="margin-top:6px">${esc(s.llm_latest.recommended_action || "")}</div>` : ""}
    <div class="muted" style="margin-top:6px">${esc(s.note)} Session transcript cleared on the server.</div>`;
  $("metrics").textContent = JSON.stringify(s.metrics, null, 2);
}

// ---------------- UI helpers ----------------
function maxLevel(a, b) { return RANK[b] > RANK[a] ? b : a; }

function updateOverall(score, src) {
  const newLevel = maxLevel(rulesLevel, llmLevel);
  level = newLevel;
  setRisk(level, score, src);
  setBubble(level);
}

function setRisk(lv, score, src) {
  const g = $("gauge");
  g.style.setProperty("--p", score); g.style.setProperty("--c", COLORS[lv]);
  $("score").textContent = score;
  $("lvl").className = `lvl ${lv}`;
  $("lvl").textContent = { low: "No strong indicators", medium: "Caution", high: "Strong scam indicators" }[lv];
  $("lvlsrc").textContent = src ? `Latest: ${src}. Score is a heuristic, not a probability.` : "Score is a heuristic indicator, not a probability.";
}

function addSign(lv, src, text, evidence, meta) {
  const box = $("signs");
  const empty = $("signs-empty"); if (empty) empty.remove();
  const el = document.createElement("div"); el.className = `warn ${lv}`;
  el.innerHTML = `<span class="prov ${src}">${src === "rules" ? "Rules engine" : "Sarvam-105B"}</span><b>${esc(text)}</b>
    <div class="muted">${esc(meta)}</div><div>${(evidence || []).map((p) => `<span class="chip">${esc(p)}</span>`).join("")}</div>`;
  box.prepend(el);
}

function setBubble(lv) { $("bubble").className = lv === "low" ? "" : lv; }

function showOverlay(lv, phrase, text, key) {
  if (lv === "low") return;
  const card = $("ocard");
  card.className = lv; card.style.display = "block";
  $("otitle").textContent = lv === "high" ? "⚠ Strong scam indicators" : "Caution: suspicious language";
  $("ophrase").textContent = phrase ? `“${phrase}”` : "";
  $("owarn").textContent = text;
  if (lv === "high" && !vibratedFor.has(key)) { vibratedFor.add(key); if (navigator.vibrate) navigator.vibrate(400); }
}

function renderMetrics() {
  const avg = (a) => a.length ? Math.round(a.reduce((x, y) => x + y, 0) / a.length) : null;
  $("metrics").textContent = JSON.stringify({
    stt_final_after_speech_end_ms: metrics.finalAfterSpeechEnd,
    llm_latency_ms: metrics.llm, llm_avg_ms: avg(metrics.llm),
    rule_alerts_at_ms_since_start: metrics.ruleAlertAt,
    stt_reconnects: metrics.reconnects,
  }, null, 2);
}

function drawWave() {
  const c = $("wave"), g = c.getContext("2d");
  g.clearRect(0, 0, c.width, c.height);
  const w = c.width / peaks.length;
  const grad = g.createLinearGradient(0, 0, c.width, 0);
  grad.addColorStop(0, "#22d3ee"); grad.addColorStop(1, "#10b981");
  g.fillStyle = grad;
  peaks.forEach((p, i) => { const h = Math.max(2, Math.min(1, p * 2.5) * c.height); g.fillRect(i * w + 1, (c.height - h) / 2, w - 2, h); });
  if (!started) peaks = peaks.map((p) => p * 0.9);
  requestAnimationFrame(drawWave);
}

// ---------------- typed demo (no STT) ----------------
async function loadTyped() {
  const { scenarios } = await (await fetch("/demo/scenarios")).json();
  for (const sc of scenarios) {
    const b = document.createElement("button"); b.className = "btn"; b.textContent = sc.title;
    b.onclick = async () => {
      $("typedout").innerHTML = `<div class="muted">Sending typed script to Sarvam-105B…</div>`;
      const r = await (await fetch("/analyze", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text: sc.script }) })).json();
      const a = r.analysis;
      $("typedout").innerHTML = `
        <div class="line"><span class="prov typed">Typed input · no speech recognition</span>${esc(sc.script)}</div>
        <div style="margin-top:8px"><span class="prov rules">Rules</span><b class="lvl ${r.rules.level}" style="font-size:14px">${r.rules.level}</b> · ${esc(r.rules.category)} ${r.rules.evidence.map((p) => `<span class="chip">${esc(p)}</span>`).join("")}</div>
        <div style="margin-top:6px"><span class="prov llm">Sarvam-105B${a.ok ? ` · ${a.latency_ms} ms` : ""}</span>${a.ok
          ? `<b class="lvl ${a.risk_level}" style="font-size:14px">${a.risk_level}</b> · ${esc(a.category)} · score ${a.risk_score} — ${esc(warningText(a))}
             <div>${a.evidence.map((e) => `<span class="chip">“${esc(e.phrase)}” — ${esc(e.reason)}</span>`).join("")}</div>
             <div class="muted">${esc(a.recommended_action)}</div>`
          : `<span class="muted">unavailable (${esc(a.error)})</span>`}</div>`;
    };
    $("typed").appendChild(b);
  }
}

// ---------------- wiring ----------------
$("toggle").onclick = async () => {
  if (started) return stop();
  try { await start(); } catch (e) { $("statusline").textContent = `Could not start: ${e.message}`; stop(); }
};
$("manual").onclick = () => ws && ws.readyState === 1 && ws.send(JSON.stringify({ type: "analyze" }));
$("odismiss").onclick = () => { $("ocard").style.display = "none"; };
$("oexplain").onclick = () => { $("assessment").scrollIntoView({ behavior: "smooth" }); };
$("bubble").onclick = () => { const c = $("ocard"); c.style.display = c.style.display === "block" ? "none" : (c.textContent.trim() && level !== "low" ? "block" : "none"); };
loadTyped();
drawWave();
