"""SayIt backend: FastAPI app with WebSocket audio streaming + HTTP API."""
from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from collections import defaultdict
from contextlib import asynccontextmanager
from pathlib import Path
from uuid import uuid4

import numpy as np
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .asr import ASREngine
from .asr_corrections import router as asr_corrections_router
from .config import load_config
from .db import Database
from .diagnostics import create_upload_token, router as diagnostics_router
from .llm import LLMEngine
from .logging_setup import attach_database_log_handler, bind_log_context, configure_logging, reset_log_context
from .net import trusted_client_ip
from .ratelimit import RateLimitMiddleware
from .notice import read_notice
from .releases import read_public_download, read_release_manifest, resolve_release_file
from .telemetry import TelemetryService

cfg = load_config()
configure_logging(cfg.logging.level, cfg.logging.file, cfg.logging.retention_days, cfg.telemetry.node_id)
logger = logging.getLogger("sayit")

asr_engine: ASREngine | None = None
llm_engine: LLMEngine | None = None
web_demo_llm_engine: LLMEngine | None = None
database: Database | None = None
telemetry_service: TelemetryService | None = None
_web_demo_active_by_ip: dict[str, int] = defaultdict(int)
_active_ws_count: int = 0
_active_ws_ids: set[str] = set()
# 每个真实客户端 IP -> 该 IP 当前存活连接的 cid 集合（用集合而非整数，计数由存活连接派生，永不漂移）
_ws_by_ip: dict[str, set[str]] = defaultdict(set)
_MAX_WS_TOTAL = 200
_MAX_WS_PER_IP = 10
# 接收空闲超时：客户端每 30s 发一次 ping；超过此时长无任何消息即视为死连接并回收，
# 避免半开/异常掉线的连接长期占用 per-IP 配额（不再依赖 OS TCP 超时，可能长达几分钟）。
_WS_IDLE_TIMEOUT_SEC = 300  # 5 分钟；客户端 30s 心跳理论够用，放宽是为短暂网络抖动/系统休眠留容错空间
# WebSocket 关闭码：不同拒绝/关闭原因用不同 code，客户端与日志据此区分排查
_WS_CLOSE_SERVER_FULL = 1013     # 服务器整体到达容量上限（标准“稍后再试”）
_WS_CLOSE_PER_IP_LIMIT = 4029    # 该客户端 IP 并发连接过多（对应 HTTP 429 语义）
_WS_CLOSE_IDLE = 4000            # 服务端回收空闲/疑似死连接
_MAX_PCM_BYTES = 10 * 1024 * 1024  # 10MB ≈ 5 min of 16kHz 16-bit mono


def _get_telemetry() -> TelemetryService | None:
    return telemetry_service


def _llm_model_name(profile) -> str:
    p = profile.provider.lower()
    return {"openai": profile.openai_model, "azure": profile.azure_deployment, "groq": profile.groq_model, "ollama": profile.ollama_model}.get(p, "-")


@asynccontextmanager
async def _lifespan(app: FastAPI):
    global asr_engine, llm_engine, web_demo_llm_engine, database, telemetry_service

    database = Database(cfg.telemetry.db_path, backend=cfg.telemetry.db_backend)
    database.initialize()
    attach_database_log_handler(database)
    telemetry_service = TelemetryService(cfg, database)
    app.state.telemetry = telemetry_service
    logger.info("Telemetry ready db=%s", cfg.telemetry.db_path)

    try:
        asr_engine = ASREngine(cfg)
        t = np.arange(int(1.2 * 16000), dtype=np.float32) / 16000.0
        dummy = (0.08 * np.sin(2 * np.pi * 220 * t)).astype(np.float32)
        loop = asyncio.get_running_loop()
        await loop.run_in_executor(None, asr_engine._transcribe_sync, dummy, None)
        # Extra warmup for TRT: bypass VAD, directly invoke TRT engine
        if cfg.asr.engine == "firered" and asr_engine._firered_backend is not None:
            fb = asr_engine._firered_backend
            import torch as _torch
            for dur in (5, 15):
                wav = _torch.randn(int(dur * 16000))
                feats, lengths, _ = fb._feat([wav])
                fb._asr.transcribe(feats, lengths, beam_size=1)
            fb._punc.process(["测试预热"])
        logger.info("ASR warmup done")
    except Exception:
        logger.exception("ASR engine failed to load")
        asr_engine = None

    if cfg.llm.enabled:
        try:
            llm_engine = LLMEngine(cfg.llm)
        except Exception:
            logger.exception("LLM engine failed to load")
            llm_engine = None

    if cfg.web_demo.enabled and cfg.web_demo.llm.enabled:
        try:
            web_demo_llm_engine = LLMEngine(cfg.web_demo.llm)
        except Exception:
            logger.exception("Web demo LLM engine failed to load")
            web_demo_llm_engine = None

    # ── Startup summary ──
    llm_status = f"{cfg.llm.provider} / {_llm_model_name(cfg.llm)}" if cfg.llm.enabled and llm_engine else "disabled"
    web_llm_status = f"{cfg.web_demo.llm.provider} / {_llm_model_name(cfg.web_demo.llm)}" if cfg.web_demo.llm.enabled and web_demo_llm_engine else "disabled"
    demo_status = f"enabled (LLM: {web_llm_status})" if cfg.web_demo.enabled else "disabled"

    logger.info(
        "\n"
        "  ┌──────────────────────────────────────────┐\n"
        "  │           SayIt Backend Ready             │\n"
        "  ├──────────────────────────────────────────┤\n"
        "  │ ASR:   %-34s │\n"
        "  │ LLM:   %-34s │\n"
        "  │ Demo:  %-34s │\n"
        "  │ HTTP:  %-34s │\n"
        "  └──────────────────────────────────────────┘",
        f"{cfg.asr.engine} ({cfg.asr.model})",
        llm_status,
        demo_status,
        f":{cfg.server.port}",
    )
    if cfg.llm.enabled and not llm_engine:
        logger.warning("LLM is configured but failed to initialize. Check API keys and provider settings.")

    yield

    if asr_engine:
        await asr_engine.close()
    if llm_engine:
        await llm_engine.close()
    if web_demo_llm_engine and web_demo_llm_engine is not llm_engine:
        await web_demo_llm_engine.close()
    if database:
        database.close()


app = FastAPI(title="SayIt", lifespan=_lifespan)
app.state.config = cfg
app.include_router(diagnostics_router)
app.include_router(asr_corrections_router)

app.add_middleware(
    CORSMiddleware,
    allow_origins=cfg.server.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.add_middleware(
    RateLimitMiddleware,
    requests_per_minute=120,
    burst=30,
    exclude_paths=("/healthz", "/ws/"),
    trusted_proxies=tuple(cfg.server.trusted_proxies),
)


# Public /api/ endpoints that stay reachable without a token.
_PUBLIC_API_PREFIXES = ("/api/public/", "/api/feedback", "/api/notice", "/api/desktop-updates")


@app.middleware("http")
async def enforce_api_token(request: Request, call_next):
    """服务端访问令牌。受保护的 /api/* 需携带 Bearer 令牌。

    安全（fail-closed）：若 SAYIT_API_TOKEN 未配置，受保护的 /api/* 一律拒绝，
    避免"空令牌 + 0.0.0.0"导致同网段可直接调用 ASR/API。公开端点
    （/api/public/*、feedback、notice、desktop-updates）、/healthz 与静态页不校验；
    WebSocket 在 ws_transcribe 内单独校验 query 参数。
    """
    path = request.url.path
    if path.startswith("/api/") and not any(
        path.startswith(p) for p in _PUBLIC_API_PREFIXES
    ):
        token = cfg.server.api_token
        if not token:
            # No token configured => refuse protected endpoints (fail closed).
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        auth = request.headers.get("authorization", "")
        supplied = auth.removeprefix("Bearer ").strip() if auth.lower().startswith("bearer ") else ""
        if supplied != token:
            return JSONResponse({"error": "unauthorized"}, status_code=401)
    return await call_next(request)


_NOISE_RE = re.compile(r"^[\s銆傦紝銆侊紵锛?,?!\u3000]*$")

# ── Voice-score: lightweight frequency-domain speech detector ────────
# Human speech (even whispered) concentrates energy in 85-3400 Hz.
# Environmental noise / silence has a flat or high-frequency spectrum.
_VS_FFT_SIZE = 512
_VS_HOP = 256
_VS_MAX_FRAMES = 200          # cap for long audio — keeps cost ≤ 2 ms
_VS_WINDOW = np.hanning(_VS_FFT_SIZE)
_VS_FREQS = np.fft.rfftfreq(_VS_FFT_SIZE, 1.0 / 16000)
_VS_VOICE_MASK = (_VS_FREQS >= 85) & (_VS_FREQS <= 3400)
_VS_THRESHOLD = 0.50          # noise/clicks < 0.47, whisper > 0.51


def _voice_score(audio: np.ndarray) -> float:
    """Return fraction of spectral energy in the voice band (0.0–1.0).

    Weighted by active-frame ratio so that a short click buried in
    digital silence (typical of web-demo quick-tap) scores low.
    """
    n_frames = max(0, (len(audio) - _VS_FFT_SIZE) // _VS_HOP)
    if n_frames == 0:
        return 0.0
    if n_frames > _VS_MAX_FRAMES:
        step = n_frames // _VS_MAX_FRAMES
        starts = np.arange(0, n_frames, step)[:_VS_MAX_FRAMES] * _VS_HOP
    else:
        starts = np.arange(n_frames) * _VS_HOP
    indices = starts[:, None] + np.arange(_VS_FFT_SIZE)[None, :]
    power = np.abs(np.fft.rfft(audio[indices] * _VS_WINDOW, axis=1)) ** 2
    frame_total = power.sum(axis=1)
    active = frame_total > 1e-10
    n_active = active.sum()
    if n_active == 0:
        return 0.0
    voice_ratio = float(power[active][:, _VS_VOICE_MASK].sum() / frame_total[active].sum())
    active_ratio = float(n_active / len(frame_total))
    return voice_ratio * active_ratio


def _is_noise(text: str, duration_sec: float, voice_score: float = 1.0) -> bool:
    if not text or _NOISE_RE.match(text):
        return True
    if len(text) <= 2 and duration_sec < 1.0:
        return True
    # Low voice-score means no real speech detected; short ASR output is phantom.
    if voice_score < _VS_THRESHOLD and len(text) <= 4:
        return True
    return False


def _is_hotword_hallucination(text: str, context: str, audio_peak: float = 1.0,
                              audio_dur: float = 0.0, voice_score: float = 1.0) -> bool:
    if not context or not text:
        return False
    words = set(w.lower() for w in context.split())
    cleaned = re.sub(r"[\s銆傦紝銆侊紵锛?,?!\u3000。]+", " ", text).strip()
    tokens = cleaned.split()
    if not tokens:
        return False
    matched = sum(1 for token in tokens if token.lower() in words)
    consec = max_consec = 0
    for token in tokens:
        if token.lower() in words:
            consec += 1
            max_consec = max(max_consec, consec)
        else:
            consec = 0
    if max_consec >= 5:
        return True
    if matched / len(tokens) >= 0.8 and matched >= 3:
        return True
    # Short result composed entirely of hotwords → almost certainly hallucination.
    # Real users don't speak just a single hotword and stop.
    if len(cleaned) <= 6 and all(t.lower() in words for t in tokens):
        return True
    return False


def _clean_text(text: str) -> str:
    return re.sub(r"<\|[^|]*\|>", "", text).strip()


def _pick(payload: dict, *keys: str) -> object | None:
    for key in keys:
        if key in payload:
            return payload.get(key)
    return None


def _normalize_client_meta(payload: object) -> dict[str, str]:
    data = payload if isinstance(payload, dict) else {}
    return {
        "user_id": str(_pick(data, "user_id", "userId") or "").strip(),
        "user_name": str(_pick(data, "user_name", "userName") or "").strip(),
        "device_id": str(_pick(data, "device_id", "deviceId") or "").strip(),
        "hostname": str(_pick(data, "hostname") or "").strip(),
        "client_version": str(_pick(data, "client_version", "clientVersion") or "").strip(),
        "platform": str(_pick(data, "platform") or "").strip(),
        "local_ip": str(_pick(data, "local_ip", "localIp") or "").strip(),
        "os_version": str(_pick(data, "os_version", "osVersion") or "").strip(),
        "system_locale": str(_pick(data, "system_locale", "systemLocale") or "").strip(),
        "cpu_cores": str(_pick(data, "cpu_cores", "cpuCores") or "").strip(),
        "memory_mb": str(_pick(data, "memory_mb", "memoryMb") or "").strip(),
    }


def _normalize_app_context(payload: object) -> dict[str, str]:
    data = payload if isinstance(payload, dict) else {}
    return {
        "process_name": str(_pick(data, "process_name", "processName") or "").strip(),
        "exe_path": str(_pick(data, "exe_path", "exePath") or "").strip(),
        "window_title": str(_pick(data, "window_title", "windowTitle") or "").strip(),
        "window_class": str(_pick(data, "window_class", "windowClass") or "").strip(),
        "focus_class": str(_pick(data, "focus_class", "focusClass") or "").strip(),
        "control_type": str(_pick(data, "control_type", "controlType") or "").strip(),
    }


def _normalize_text_context(payload: object) -> dict[str, str] | None:
    """Validate and cap ephemeral editor text at the server boundary.

    A truncated selection is rejected completely: AI output replaces the whole selection, so
    processing only its prefix could destroy unseen text.
    """
    data = payload if isinstance(payload, dict) else {}
    if bool(_pick(data, "selection_truncated", "selectionTruncated")):
        return None

    def _text(key: str, alternate: str, limit: int, *, tail: bool = False) -> str:
        value = str(_pick(data, key, alternate) or "").replace("\x00", "")
        return value[-limit:] if tail else value[:limit]

    normalized = {
        "source": _text("source", "source", 64),
        "text_before": _text("text_before", "textBefore", 500, tail=True),
        "selected_text": _text("selected_text", "selectedText", 6000),
        "text_after": _text("text_after", "textAfter", 300),
    }
    if not any(normalized[key] for key in ("text_before", "selected_text", "text_after")):
        return None
    return normalized


def _normalize_usage_meta(payload: object) -> dict[str, int | None]:
    data = payload if isinstance(payload, dict) else {}
    raw = _pick(data, "ptt_hold_ms", "pttHoldMs")
    try:
        value = int(raw) if raw is not None else None
    except (TypeError, ValueError):
        value = None
    return {"ptt_hold_ms": value}


def _normalize_audio_stats(payload: object) -> dict[str, float | int | None]:
    data = payload if isinstance(payload, dict) else {}
    def _float(k, *alts):
        raw = _pick(data, k, *alts)
        try:
            return float(raw) if raw is not None else None
        except (TypeError, ValueError):
            return None
    def _int(k, *alts):
        raw = _pick(data, k, *alts)
        try:
            return int(raw) if raw is not None else None
        except (TypeError, ValueError):
            return None
    return {
        "avg_rms": _float("avg_rms", "avgRms"),
        "peak_rms": _float("peak_rms", "peakRms"),
        "peak_amplitude": _float("peak_amplitude", "peakAmplitude"),
        "silence_ratio": _float("silence_ratio", "silenceRatio"),
        "total_frames": _int("total_frames", "totalFrames"),
    }


def _client_ip(ws: WebSocket) -> tuple[str | None, str | None]:
    direct = ws.client.host if ws.client else None
    forwarded_for = ws.headers.get("x-forwarded-for") or ws.headers.get("x-real-ip")
    return direct, forwarded_for


def _client_ip_key(direct: str | None, forwarded: str | None, proxies) -> str:
    """用于「按 IP 限流/计数」的真实客户端 IP。

    仅当直连地址命中显式受信代理名单（cfg.server.trusted_proxies）时才采信代理头，
    且从右往左取第一个非私网条目；否则一律使用直连地址。
    """
    return trusted_client_ip(direct, forwarded, proxies)


def _is_web_demo(ws: WebSocket) -> bool:
    return (ws.query_params.get("client") or "").strip().lower() == "web_demo"


def _selected_llm_engine(is_web_demo: bool) -> LLMEngine | None:
    return web_demo_llm_engine if is_web_demo else llm_engine


def _selected_llm_provider(is_web_demo: bool) -> str:
    profile = cfg.web_demo.llm if is_web_demo else cfg.llm
    return profile.provider


def _try_acquire_web_demo_slot(ip: str | None) -> bool:
    key = (ip or "unknown").strip() or "unknown"
    current = _web_demo_active_by_ip[key]
    if current >= cfg.web_demo.max_concurrency_per_ip:
        return False
    _web_demo_active_by_ip[key] = current + 1
    return True


def _release_web_demo_slot(ip: str | None) -> None:
    key = (ip or "unknown").strip() or "unknown"
    current = _web_demo_active_by_ip.get(key, 0)
    if current <= 1:
        _web_demo_active_by_ip.pop(key, None)
        return
    _web_demo_active_by_ip[key] = current - 1


async def _run_asr_llm(
    audio: np.ndarray,
    ws: WebSocket | None = None,
    context: str | None = None,
    language: str | None = None,
    system_prompt: str | None = None,
    text_context: dict[str, str] | None = None,
    disable_ai: bool = False,
    use_web_demo_profile: bool = False,
) -> dict:
    duration = len(audio) / 16000.0
    result = {"duration_sec": round(duration, 2)}

    if not asr_engine:
        result["error"] = "ASR not loaded"
        return result

    # Strip hotword context when audio has no detectable speech energy,
    # preventing the ASR model from hallucinating hotwords on noise.
    # Three-layer detection: voice_score, energy level, and VAD.
    audio_peak = float(np.max(np.abs(audio)))
    audio_rms = float(np.sqrt(np.mean(audio**2)))
    vs = _voice_score(audio)
    strip_context = False
    if vs < _VS_THRESHOLD or (audio_peak < 0.02 and audio_rms < 0.002):
        strip_context = True
    elif context and duration < 10.0 and asr_engine and asr_engine._vad is not None:
        # For short audio with hotwords: use VAD to detect if there's actual speech.
        # This catches non-speech noise (keyboard, clicks) that has high energy.
        import torch
        try:
            vad_res = asr_engine._vad.generate(
                input=torch.tensor(audio, dtype=torch.float32), cache={}, is_final=True)
            vad_segs = vad_res[0].get("value", []) if vad_res else []
            logger.debug("VAD check: segs=%d, duration=%.1f", len(vad_segs), duration)
            if not vad_segs:
                strip_context = True
        except Exception as e:
            logger.warning("VAD check failed: %s", e)
    if strip_context and (context is None or context):
        logger.info("Stripping hotword context: voice_score=%.3f peak=%.4f rms=%.5f", vs, audio_peak, audio_rms)
        context = ""

    raw_text, asr_ms, asr_debug = await asr_engine.transcribe(audio, context=context, language=language)
    asr_text = _clean_text(raw_text)
    asr_debug["voice_score"] = round(vs, 3)
    if _is_noise(asr_text, duration, voice_score=vs) or _is_hotword_hallucination(asr_text, asr_debug.get("context", ""), audio_peak=audio_peak, audio_dur=duration, voice_score=vs):
        asr_text = ""
    # Low energy audio producing short text is almost certainly noise/hallucination
    if asr_text and audio_peak < 0.02 and audio_rms < 0.002 and len(asr_text) <= 10:
        asr_text = ""
    # VAD detected no speech but ASR produced short text → noise hallucination
    if asr_text and strip_context and len(asr_text) <= 10:
        asr_text = ""

    if cfg.logging.slow_asr_ms and asr_ms >= cfg.logging.slow_asr_ms:
        logger.warning("Slow ASR detected duration_sec=%.2f asr_ms=%d", duration, asr_ms)

    result.update({"asr_text": asr_text, "asr_ms": asr_ms, "asr_debug": asr_debug})

    if ws:
        await ws.send_json(
            {
                "type": "asr",
                "text": asr_text,
                "asr_ms": asr_ms,
                "duration_sec": round(duration, 2),
                "asr_debug": asr_debug,
            }
        )

    selected_llm = _selected_llm_engine(use_web_demo_profile)
    selected_profile = cfg.web_demo.llm if use_web_demo_profile else cfg.llm

    llm_text, llm_ms, llm_debug = asr_text, 0, {}
    if asr_text and selected_llm and selected_profile.enabled and not disable_ai:
        llm_text, llm_ms, llm_debug = await selected_llm.polish(
            asr_text, system_prompt, text_context=text_context,
            is_web_demo=use_web_demo_profile,
        )
        if cfg.logging.slow_llm_ms and llm_ms >= cfg.logging.slow_llm_ms:
            logger.warning("Slow LLM detected duration_sec=%.2f llm_ms=%d", duration, llm_ms)

    # Do not overwrite selected text with a spoken command when the server has no usable LLM or
    # polishing failed. Re-inserting the original selection is a safe no-op in the editor.
    if text_context and text_context.get("selected_text") and not llm_debug.get("provider"):
        llm_text = text_context["selected_text"]
        llm_debug = {**llm_debug, "selected_edit_skipped": True}

    context_applied = bool(text_context and llm_debug.get("provider"))
    result.update({
        "llm_text": llm_text,
        "llm_ms": llm_ms,
        "llm_debug": llm_debug,
        "context_applied": context_applied if text_context else None,
    })
    return result


@app.get("/healthz")
async def healthz():
    return {"status": "ok", "asr": asr_engine is not None, "llm": cfg.llm.enabled and llm_engine is not None}


# ── Feedback ──────────────────────────────────────────────────────────────────
_feedback_timestamps: dict[str, list[float]] = defaultdict(list)
_FEEDBACK_WINDOW = 600  # 10 minutes
_FEEDBACK_LIMIT = 3


@app.post("/api/feedback")
async def post_feedback(request: Request):
    request_body = await request.json()
    # --- validate machine_id ---
    machine_id = (request_body.get("machine_id") or "").strip()
    if not machine_id:
        return JSONResponse({"error": "machine_id required"}, 400)

    # --- validate feedback_text ---
    feedback_text = (request_body.get("feedback_text") or "").strip()
    if len(feedback_text) < 2 or len(feedback_text) > 1000:
        return JSONResponse({"error": "feedback_text must be 2-1000 chars"}, 400)

    # --- truncate long fields ---
    transcript = request_body.get("transcript")
    if isinstance(transcript, dict):
        for k in ("asr_text", "ai_text"):
            if isinstance(transcript.get(k), str) and len(transcript[k]) > 5000:
                transcript[k] = transcript[k][:5000]
    else:
        transcript = None

    context = request_body.get("context")
    if isinstance(context, dict):
        for k in ("ai_system_prompt", "ai_prompt_append"):
            if isinstance(context.get(k), str) and len(context[k]) > 5000:
                context[k] = context[k][:5000]
    else:
        context = None

    # --- rate limit by machine_id: 3 per 10 min ---
    now = time.time()
    ts_list = _feedback_timestamps[machine_id]
    _feedback_timestamps[machine_id] = [t for t in ts_list if now - t < _FEEDBACK_WINDOW]
    if len(_feedback_timestamps[machine_id]) >= _FEEDBACK_LIMIT:
        return JSONResponse({"error": "rate limited"}, 429)
    _feedback_timestamps[machine_id].append(now)

    # --- store ---
    db = database
    if db:
        client_ip = request.headers.get("x-forwarded-for", "").split(",")[0].strip() or (request.client.host if request.client else "")
        db.execute(
            "INSERT INTO feedback (machine_id, app_version, client_ip, feedback_text, transcript_json, context_json, created_at)"
            f" VALUES ({','.join([db.dialect.placeholder] * 7)})",
            (
                machine_id,
                (request_body.get("app_version") or "")[:64],
                client_ip[:64],
                feedback_text,
                json.dumps(transcript, ensure_ascii=False) if transcript else None,
                json.dumps(context, ensure_ascii=False) if context else None,
                int(now * 1000),
            ),
        )
    logger.info("Feedback from %s: %s", machine_id, feedback_text[:100])
    return JSONResponse({"ok": True}, 200)


@app.get("/api/notice")
async def get_notice():
    """运营公告：客户端据此在 App 内展示通知。无公告时返回空对象。"""
    return JSONResponse(read_notice(cfg), headers={"Cache-Control": "no-store"})


@app.get("/api/desktop-updates/{platform}/{arch}/manifest")
async def get_desktop_update_manifest(platform: str, arch: str):
    return read_release_manifest(cfg, platform, arch)


@app.get("/api/desktop-updates/{platform}/{arch}/{filename:path}")
async def get_desktop_update_file(platform: str, arch: str, filename: str):
    file_path = resolve_release_file(cfg, platform, arch, filename)
    headers = {
        "Cache-Control": "no-store" if file_path.suffix in {".yml", ".yaml"} else "public, max-age=31536000, immutable"
    }
    return FileResponse(file_path, headers=headers)


@app.get("/api/public/config")
async def get_public_config():
    download = read_public_download(
        cfg,
        cfg.public_site.download_platform,
        cfg.public_site.download_arch,
    )
    return {
        "app_name": cfg.public_site.app_name,
        "headline": cfg.public_site.headline,
        "subheadline": cfg.public_site.subheadline,
        "download_label": cfg.public_site.download_label,
        "download_url": "/api/public/downloads/windows/latest" if download else None,
        "download_version": download["version"] if download else None,
        "web_demo": {
            "enabled": cfg.web_demo.enabled,
            "max_duration_sec": cfg.web_demo.max_duration_sec,
            "max_concurrency_per_ip": cfg.web_demo.max_concurrency_per_ip,
            "llm_enabled": cfg.web_demo.llm.enabled and web_demo_llm_engine is not None,
            "ws_url": "/ws/transcribe?client=web_demo",
        },
    }


@app.get("/api/public/downloads/windows/latest")
async def get_public_windows_download():
    download = read_public_download(
        cfg,
        cfg.public_site.download_platform,
        cfg.public_site.download_arch,
    )
    if not download or not isinstance(download.get("filename"), str):
        return JSONResponse({"error": "download unavailable"}, 404)
    file_path = resolve_release_file(
        cfg,
        str(download["platform"]),
        str(download["arch"]),
        str(download["filename"]),
    )
    headers = {"Cache-Control": "no-cache"}
    return FileResponse(
        file_path,
        filename=str(download["filename"]),
        media_type="application/octet-stream",
        headers=headers,
    )


@app.websocket("/ws/transcribe")
async def ws_transcribe(ws: WebSocket):
    global _active_ws_count
    cid = uuid4().hex[:8]
    connection_tokens = bind_log_context(connection_id=cid)
    telemetry = _get_telemetry()
    is_web_demo = _is_web_demo(ws)
    client_ip_direct, client_ip_forwarded = _client_ip(ws)
    ws_ip_key = _client_ip_key(client_ip_direct, client_ip_forwarded, cfg.server.trusted_proxies)

    # 先 accept，再用带专属 close code 的关闭来拒绝：
    # 若在 accept 之前 close，框架会以 HTTP 403 拒绝握手，客户端只能看到通用错误(1006)，无法区分原因。
    await ws.accept()

    # 鉴权（fail-closed）：握手必须带 ?token=<token>；未配置 SAYIT_API_TOKEN 则一律拒绝，
    # 避免"空令牌 + 0.0.0.0"让同网段直接调用转写。
    if cfg.server.api_token:
        if ws.query_params.get("token") != cfg.server.api_token:
            logger.warning("ws rejected (bad/absent token): cid=%s ip=%s", cid, ws_ip_key)
            await ws.close(4401, "unauthorized")
            reset_log_context(connection_tokens)
            return
    else:
        logger.warning("ws rejected (no token configured): cid=%s ip=%s", cid, ws_ip_key)
        await ws.close(4401, "unauthorized")
        reset_log_context(connection_tokens)
        return

    # Connection limits（拒绝时不计数、也不进入下方的 try/finally）
    if _active_ws_count >= _MAX_WS_TOTAL:
        logger.warning("ws rejected (server full): active=%d cid=%s", _active_ws_count, cid)
        await ws.close(_WS_CLOSE_SERVER_FULL, "server at capacity")
        reset_log_context(connection_tokens)
        return
    if len(_ws_by_ip.get(ws_ip_key, ())) >= _MAX_WS_PER_IP:
        logger.warning(
            "ws rejected (per-ip limit): ip=%s count=%d cid=%s",
            ws_ip_key, len(_ws_by_ip.get(ws_ip_key, ())), cid,
        )
        await ws.close(_WS_CLOSE_PER_IP_LIMIT, "too many connections from this IP")
        reset_log_context(connection_tokens)
        return

    if telemetry:
        telemetry.record_connection_event(cid, "ws_connected", None)

    # 计数注册与 ready 发送移入下方 try，确保任何异常都会经 finally 释放（避免计数泄漏）。

    pcm_buffers: list[bytes] = []
    pcm_total_bytes: int = 0
    active = False
    compare = False
    disable_ai = False
    ctx_override: str | None = None
    sys_prompt: str | None = None
    lang_override: str | None = None
    session_id: str | None = None
    session_tokens: dict = {}
    last_usage_meta: dict[str, int | None] = {"ptt_hold_ms": None}
    last_audio_stats: dict[str, float | int | None] = {}
    client_meta: dict[str, str] = {}
    app_context: dict[str, str] = {}
    text_context: dict[str, str] | None = None
    web_demo_slot_acquired = False
    logger.info("ws connected")

    def _clear_session_context() -> None:
        nonlocal session_tokens
        if session_tokens:
            reset_log_context(session_tokens)
            session_tokens = {}

    async def _process_stop():
        nonlocal session_id, web_demo_slot_acquired, pcm_total_bytes
        current_session_id = session_id
        ptt_hold_ms = last_usage_meta.get("ptt_hold_ms")
        all_pcm = b"".join(pcm_buffers)
        pcm_buffers.clear()
        pcm_total_bytes = 0
        audio_duration_ms = int((len(all_pcm) / 2 / 16000) * 1000)

        if telemetry:
            telemetry.update_stop(
                current_session_id,
                cid,
                ptt_hold_ms=ptt_hold_ms,
                audio_duration_ms=audio_duration_ms,
                is_empty_audio=not bool(all_pcm),
            )

        if not all_pcm:
            if telemetry:
                telemetry.mark_terminal_status(current_session_id, cid, "empty_audio", "No PCM received")
            await ws.send_json({"type": "done"})
            if web_demo_slot_acquired:
                _release_web_demo_slot(client_ip_direct)
                web_demo_slot_acquired = False
            _clear_session_context()
            session_id = None
            return

        if is_web_demo and audio_duration_ms > cfg.web_demo.max_duration_sec * 1000:
            if telemetry:
                telemetry.mark_terminal_status(current_session_id, cid, "demo_too_long", "Web demo exceeded max duration")
            await ws.send_json(
                {
                    "type": "error",
                    "message": f"单次体验最长支持 {cfg.web_demo.max_duration_sec // 60} 分钟录音",
                }
            )
            await ws.send_json({"type": "done"})
            if web_demo_slot_acquired:
                _release_web_demo_slot(client_ip_direct)
                web_demo_slot_acquired = False
            _clear_session_context()
            session_id = None
            return

        audio = np.frombuffer(all_pcm, dtype=np.int16).astype(np.float32) / 32768.0
        if len(audio) / 16000.0 < 0.3:
            if telemetry:
                telemetry.mark_terminal_status(current_session_id, cid, "short_audio", "Audio shorter than 0.3s")
            await ws.send_json({"type": "done"})
            if web_demo_slot_acquired:
                _release_web_demo_slot(client_ip_direct)
                web_demo_slot_acquired = False
            _clear_session_context()
            session_id = None
            return

        # Reject near-silent audio to prevent hotword hallucination.
        # Only reject very short + very quiet audio (accidental button press).
        # For longer quiet audio, strip hotword context to prevent hallucination
        # while still allowing whispered speech to be recognized.
        audio_peak = float(np.max(np.abs(audio)))
        audio_dur = len(audio) / 16000.0
        if audio_peak < 0.01:
            if telemetry:
                telemetry.mark_terminal_status(current_session_id, cid, "silent_audio", f"peak={audio_peak:.4f}")
            await ws.send_json({"type": "asr", "text": "", "asr_ms": 0, "duration_sec": round(len(audio) / 16000.0, 2), "asr_debug": {"silent": True}})
            await ws.send_json({"type": "final", "asr_text": "", "llm_text": "", "asr_ms": 0, "llm_ms": 0, "duration_sec": round(len(audio) / 16000.0, 2), "asr_debug": {"silent": True}, "llm_debug": {}})
            await ws.send_json({"type": "done"})
            if web_demo_slot_acquired:
                _release_web_demo_slot(client_ip_direct)
                web_demo_slot_acquired = False
            _clear_session_context()
            session_id = None
            return

        try:
            result = await _run_asr_llm(
                audio,
                ws,
                context=ctx_override,
                language=lang_override,
                system_prompt=sys_prompt,
                text_context=text_context,
                disable_ai=disable_ai,
                use_web_demo_profile=is_web_demo,
            )
        except Exception as exc:
            logger.exception("Pipeline failed")
            if telemetry:
                telemetry.mark_failure(current_session_id, cid, "pipeline_exception", str(exc))
            await ws.send_json({"type": "error", "message": str(exc)})
            await ws.send_json({"type": "done"})
            if web_demo_slot_acquired:
                _release_web_demo_slot(client_ip_direct)
                web_demo_slot_acquired = False
            _clear_session_context()
            session_id = None
            return

        if error := result.get("error"):
            if telemetry:
                telemetry.mark_failure(current_session_id, cid, "pipeline_error", str(error))
            await ws.send_json({"type": "error", "message": str(error)})
            await ws.send_json({"type": "done"})
            if web_demo_slot_acquired:
                _release_web_demo_slot(client_ip_direct)
                web_demo_slot_acquired = False
            _clear_session_context()
            session_id = None
            return

        llm_debug = result.get("llm_debug", {}) if isinstance(result.get("llm_debug"), dict) else {}

        if telemetry:
            telemetry.record_pipeline_result(
                current_session_id,
                cid,
                asr_provider="qwen3-asr",
                asr_model=cfg.asr.model,
                asr_lang=cfg.asr.language,
                asr_ms=int(result.get("asr_ms", 0) or 0),
                asr_debug=result.get("asr_debug", {}) if isinstance(result.get("asr_debug"), dict) else {},
                llm_enabled=bool(result.get("asr_text"))
                and bool(_selected_llm_engine(is_web_demo))
                and (cfg.web_demo.llm.enabled if is_web_demo else cfg.llm.enabled)
                and not disable_ai,
                llm_provider=str(llm_debug.get("provider") or _selected_llm_provider(is_web_demo) or ""),
                llm_model=str(llm_debug.get("model") or ""),
                llm_ms=int(result.get("llm_ms", 0) or 0),
                has_result=bool(result.get("llm_text") or result.get("asr_text")),
            )

        cmp = None
        if compare and asr_engine:
            text_no, ms_no, _ = await asr_engine.transcribe(audio, context="", language=lang_override)
            cmp = {"asr_text": _clean_text(text_no), "asr_ms": ms_no}

        msg = {
            "type": "final",
            "asr_text": result.get("asr_text", ""),
            "llm_text": result.get("llm_text", ""),
            "asr_ms": result.get("asr_ms", 0),
            "llm_ms": result.get("llm_ms", 0),
            "duration_sec": result.get("duration_sec", round(len(audio) / 16000.0, 2)),
            "asr_debug": result.get("asr_debug", {}),
            "llm_debug": result.get("llm_debug", {}),
            "asr_engine": cfg.asr.engine,
            "asr_model": "FireRedASR2-AED-TensorRT" if cfg.asr.engine == "firered" else cfg.asr.model,
            "context_applied": result.get("context_applied"),
        }
        # Audit log: metadata only (no transcript text persisted)
        logger.info(
            "audit ip=%s user=%s process=%s dur=%.1fs asr_ms=%d llm_ms=%d asr_len=%d llm_len=%d",
            client_ip_forwarded or client_ip_direct or "-",
            client_meta.get("user_id") or client_meta.get("user_name") or "-",
            app_context.get("process_name") or "-",
            result.get("duration_sec", 0),
            result.get("asr_ms", 0),
            result.get("llm_ms", 0),
            len(result.get("asr_text", "")),
            len(result.get("llm_text", "")),
        )
        if cmp:
            msg["compare_no_hotwords"] = cmp
        await ws.send_json(msg)
        await ws.send_json({"type": "done"})
        if web_demo_slot_acquired:
            _release_web_demo_slot(client_ip_direct)
            web_demo_slot_acquired = False
        _clear_session_context()
        session_id = None

    try:
        # 在 try 内注册连接计数，保证任何异常（含 accept 后立即断开）都会经 finally 释放
        _active_ws_ids.add(cid)
        _active_ws_count = len(_active_ws_ids)
        _ws_by_ip[ws_ip_key].add(cid)
        await ws.send_json(
            {
                "type": "ready",
                "connection_id": cid,
                "asr": asr_engine is not None,
                "llm": _selected_llm_engine(is_web_demo) is not None,
                "client": "web_demo" if is_web_demo else "desktop",
                "capabilities": {"text_context": True},
            }
        )
        while True:
            try:
                msg = await asyncio.wait_for(ws.receive(), timeout=_WS_IDLE_TIMEOUT_SEC)
            except asyncio.TimeoutError:
                logger.info("ws idle %ds, reaping cid=%s ip=%s", _WS_IDLE_TIMEOUT_SEC, cid, ws_ip_key)
                try:
                    await ws.close(_WS_CLOSE_IDLE, "idle timeout")
                except RuntimeError:
                    pass
                break
            if msg["type"] == "websocket.disconnect":
                break
            raw = msg.get("bytes")
            if raw:
                if active:
                    if pcm_total_bytes + len(raw) > _MAX_PCM_BYTES:
                        logger.warning("PCM buffer limit exceeded (%d bytes), dropping session", pcm_total_bytes)
                        active = False
                        await ws.send_json({"type": "error", "message": "Audio too long"})
                        await ws.send_json({"type": "done"})
                    else:
                        pcm_buffers.append(raw)
                        pcm_total_bytes += len(raw)
                continue
            text = msg.get("text")
            if not text:
                continue
            try:
                payload = json.loads(text)
            except json.JSONDecodeError:
                logger.warning("Invalid JSON payload received")
                continue

            cmd = payload.get("cmd") or payload.get("event")
            if cmd == "start":
                if is_web_demo and not cfg.web_demo.enabled:
                    await ws.send_json({"type": "error", "message": "当前部署未启用网页体验"})
                    await ws.send_json({"type": "done"})
                    continue
                if is_web_demo and not web_demo_slot_acquired:
                    if not _try_acquire_web_demo_slot(ws_ip_key):
                        await ws.send_json(
                            {
                                "type": "error",
                                "message": f"同一 IP 最多同时发起 {cfg.web_demo.max_concurrency_per_ip} 个体验请求",
                            }
                        )
                        await ws.send_json({"type": "done"})
                        continue
                    web_demo_slot_acquired = True
                _clear_session_context()
                pcm_buffers.clear()
                pcm_total_bytes = 0
                active = True
                compare = bool(payload.get("compare"))
                disable_ai = bool(payload.get("disable_ai")) or (
                    is_web_demo and (not cfg.web_demo.llm.enabled or _selected_llm_engine(True) is None)
                )
                hw = payload.get("hotwords")
                ctx_override = " ".join(hw) if isinstance(hw, list) else None
                sys_prompt = None if is_web_demo else payload.get("system_prompt")
                lang_override = payload.get("language")  # per-session language override
                source = str(payload.get("source") or "live").strip()
                client_meta = _normalize_client_meta(payload.get("client_meta"))
                app_context = _normalize_app_context(payload.get("app_context"))
                # Web demo never reads another application's editor, and must not accept a caller-
                # supplied imitation of that privileged desktop context.
                text_context = None if is_web_demo else _normalize_text_context(payload.get("text_context"))
                last_usage_meta = {"ptt_hold_ms": None}
                last_audio_stats = {}
                session_id = telemetry.create_session(
                    connection_id=cid,
                    client_ip=client_ip_direct,
                    forwarded_for=client_ip_forwarded,
                    client_meta=client_meta,
                    app_context=app_context,
                    source=source,
                ) if telemetry else None
                session_tokens = bind_log_context(session_id=session_id or "-")
                logger.info(
                    "start mode=%s source=%s compare=%s hotwords=%s disable_ai=%s user=%s process=%s",
                    "web_demo" if is_web_demo else "desktop",
                    source,
                    compare,
                    len(hw) if isinstance(hw, list) else "default",
                    disable_ai,
                    client_meta.get("user_id") or client_meta.get("user_name") or "-",
                    app_context.get("process_name") or "-",
                )
            elif cmd == "stop":
                active = False
                # 客户端在松键时才知道这次录了多久（短语音门槛），所以允许 stop 追加
                # 「别做 AI」。只能追加、不能反过来打开 —— start 已经关掉的不该被重开。
                if payload.get("disable_ai"):
                    if not disable_ai:
                        logger.info("stop requested disable_ai (client-side rule)")
                    disable_ai = True
                last_usage_meta = _normalize_usage_meta(payload.get("usage_meta"))
                last_audio_stats = _normalize_audio_stats(payload.get("audio_stats"))
                await _process_stop()
            elif cmd == "pause":
                active = False
            elif cmd == "resume":
                active = True
            elif cmd == "ping":
                await ws.send_json({"type": "pong", "ts": int(time.time() * 1000)})
            elif cmd == "request_upload_token":
                token = create_upload_token()
                await ws.send_json({"type": "upload_token", "token": token})
    except WebSocketDisconnect:
        logger.info("ws disconnected by client")
    except Exception:
        logger.exception("Unexpected websocket failure")
        if telemetry:
            telemetry.mark_failure(session_id, cid, "ws_exception", "Unexpected websocket failure")
        try:
            await ws.send_json({"type": "error", "message": "Unexpected websocket failure"})
        except RuntimeError:
            pass
    finally:
        _active_ws_ids.discard(cid)
        _active_ws_count = len(_active_ws_ids)
        conns = _ws_by_ip.get(ws_ip_key)
        if conns is not None:
            conns.discard(cid)
            if not conns:
                _ws_by_ip.pop(ws_ip_key, None)
        if telemetry:
            telemetry.mark_disconnected(session_id, cid)
        if web_demo_slot_acquired:
            _release_web_demo_slot(ws_ip_key)
        _clear_session_context()
        logger.info("ws disconnected")
        reset_log_context(connection_tokens)
        try:
            await ws.close(1000)
        except RuntimeError:
            pass


# ---------------------------------------------------------------------------
# Static files — serve landing page, browser demo, and assets
# ---------------------------------------------------------------------------
_WEB_DIR = Path(__file__).resolve().parents[2] / "web"


@app.get("/")
async def index():
    return FileResponse(_WEB_DIR / "index.html")


# Mount static assets (js, css, images) — must be after all API routes
if _WEB_DIR.is_dir():
    app.mount("/", StaticFiles(directory=str(_WEB_DIR)), name="static")
