"""ASR 纠错反馈：接收用户主动提交的 (音频, 原始 ASR 文本, 用户修正文本) 样本。

这条通道和「服务端静默录音」不是一回事：每一个字节都是用户在历史记录里点了
「提交纠正」、看过同意文案之后才发出来的，所以公开版允许存在（见 docs/decisions.md）。

几条约束都不是随手加的：

- **只收服务器模式的样本。** 只有服务器上的模型是我们能改的；云 API / 本地模型认错了，
  把用户音频收到这里既没用，又是一次用户没预期的隐私事件。
- **文本只入库，绝不进日志。** 日志一律只记字符数（与 pitfalls #15 同口径）。
- **请求体大小必须在这里自己数。** backend 和 gateway 两层都没有任何 HTTP body 上限，
  不数就是一个任意大小的公网文件上传口。
- **限流计数放数据库，不放内存。** 每次部署都 `systemctl restart`，内存计数归零等于没限。
- **配额用数据库里的 SUM(audio_bytes)，不去 rglob 目录。** 样本是长期累积的，
  每个请求都扫一遍目录会越来越慢。
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
import time
import weakref
from pathlib import Path

from fastapi import APIRouter, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse

from .db import Database
from .net import trusted_client_ip

logger = logging.getLogger("sayit.asr_corrections")

router = APIRouter()

# 落盘根目录。用 __file__ 定位而不是跟着 telemetry.db_path，这样切到 PostgreSQL
# （db_path 变成连接串）时不会把音频写到莫名其妙的地方。
# 部署树是扁的（/root/Fun-ASR/SayIt/backend/app/…），parents[2] 正好是项目根。
STORAGE_DIR = Path(__file__).resolve().parents[2] / "runtime" / "asr-corrections"

# 16kHz / 单声道 / 16bit 一秒 32KB，五分钟 = 9.6MB (9.16 MiB) + 44 字节头。
# 服务器模式的录音长度由 PTT 五分钟硬释放和 main.py 的 _MAX_PCM_BYTES(10MiB) 两道兜住，
# 所以 10 MiB 一定装得下最长的一次录音，还剩约 0.9MB 余量。
MAX_AUDIO_BYTES = 10 * 1024 * 1024
_READ_CHUNK = 256 * 1024

# 读进内存再落盘（上限就是 10MiB），不用临时文件：需要整份内容算 sha256 和校验 WAV 头，
# 而且落盘失败时不会留下半个文件。并发被限流压着，内存峰值可控。
MAX_TEXT_CHARS = 5000
MAX_HOTWORDS = 200
MAX_HOTWORD_CHARS = 64
MAX_HOTWORDS_TOTAL_CHARS = 4000

# 总量硬上限。单条约 300KB~10MB，5GiB 够放上千条；到顶返回 507 而不是把磁盘写满。
MAX_TOTAL_AUDIO_BYTES = 5 * 1024 * 1024 * 1024

# 限流：同一台机器 1 小时 10 条 / 24 小时 30 条，同一 IP 24 小时 60 条。
# 一次集中纠正十几条是正常使用，刷接口不是。
_RATE_RULES = (
    ("machine_id", 3600_000, 10),
    ("machine_id", 86_400_000, 30),
    ("client_ip", 86_400_000, 60),
)

WAV_SAMPLE_RATE = 16000
WAV_CHANNELS = 1
WAV_BITS = 16

# 承诺给用户的撤回窗口，回给客户端做文案。**不用它拦撤回**：承诺是「30 天内可以撤回」，
# 放宽到永远可撤回不会违背承诺，而加一道硬期限只会多出一个"点了没反应"的失败分支。
WITHDRAW_DAYS = 30

_CORRECTION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
# 允许换行，其余控制字符（含 \r、\t、零宽字符之外的 C0）一律拒绝
_CONTROL_CHARS_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def _err(status: int, code: str, message: str) -> JSONResponse:
    """统一错误形状：code 给客户端做判断，message 只给排查用。"""
    return JSONResponse(status_code=status, content={"error": code, "message": message})


def _client_ip(request: Request) -> str:
    cfg = getattr(request.app.state, "config", None)
    proxies = getattr(cfg.server, "trusted_proxies", ()) if cfg else ()
    return trusted_client_ip(
        request.client.host if request.client else None,
        request.headers.get("x-forwarded-for") or request.headers.get("x-real-ip"),
        proxies,
    )


def _get_db(request: Request) -> Database | None:
    """取数据库句柄。

    优先 app.state（测试里挂一个临时库进来就能跑，不用起 ASR 引擎）；
    生产走 main 的模块级 database —— 延迟导入是为了避开与 main 的循环导入，
    调用时 main 早就加载完了。
    """
    db = getattr(request.app.state, "database", None)
    if db is not None:
        return db
    try:
        from . import main as _main
    except Exception:  # pragma: no cover - 只在 main 自身导入失败时发生
        return None
    return getattr(_main, "database", None)


def _clean_text(raw: str) -> str:
    """统一行尾并去掉首尾空白。CRLF 不统一的话同一句话会算出两个 sha 和两种长度。"""
    return raw.replace("\r\n", "\n").replace("\r", "\n").strip()


def _length_is_plausible(original: str, corrected: str) -> bool:
    """挡掉「改完长度差一个数量级」的提交（贴错内容、粘了整篇文档）。

    只在极端情况下触发：短句允许 30 字符的绝对宽容，长句允许 3 倍。
    正常纠错改的是几个词，不会碰到这条。
    """
    lo, hi = sorted((len(original), len(corrected)))
    return hi <= max(30, lo * 3)


class WavError(ValueError):
    """WAV 校验失败，code 用于返回给客户端。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def parse_wav(data: bytes) -> tuple[int, int]:
    """校验是不是我们要的 WAV，返回 (data 块字节数, 时长毫秒)。

    只接受 16kHz / 单声道 / 16bit PCM —— 客户端录音和服务端 ASR 都是这个格式，
    别的格式进来要么是客户端出了 bug，要么是有人在拿这个接口当网盘。
    按 chunk 走而不是死读 44 字节偏移：真实 WAV 里 fmt 和 data 之间可能夹着 LIST 等块。
    """
    if len(data) < 44:
        raise WavError("audio_too_short", "WAV file is too short")
    if data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise WavError("audio_not_wav", "Not a RIFF/WAVE file")

    fmt_seen = False
    offset = 12
    while offset + 8 <= len(data):
        chunk_id = data[offset:offset + 4]
        chunk_size = int.from_bytes(data[offset + 4:offset + 8], "little")
        body = offset + 8

        if chunk_id == b"fmt ":
            if chunk_size < 16 or body + 16 > len(data):
                raise WavError("audio_bad_format", "Malformed fmt chunk")
            audio_format = int.from_bytes(data[body:body + 2], "little")
            channels = int.from_bytes(data[body + 2:body + 4], "little")
            sample_rate = int.from_bytes(data[body + 4:body + 8], "little")
            bits = int.from_bytes(data[body + 14:body + 16], "little")
            if audio_format != 1:
                raise WavError("audio_not_pcm", "Only uncompressed PCM is accepted")
            if channels != WAV_CHANNELS or sample_rate != WAV_SAMPLE_RATE or bits != WAV_BITS:
                raise WavError(
                    "audio_bad_params",
                    f"Expected {WAV_SAMPLE_RATE}Hz mono {WAV_BITS}-bit, got {sample_rate}Hz "
                    f"{channels}ch {bits}-bit",
                )
            fmt_seen = True
        elif chunk_id == b"data":
            if not fmt_seen:
                raise WavError("audio_bad_format", "data chunk before fmt chunk")
            available = len(data) - body
            # 头里声明的长度比实际字节还多 = 文件被截断，时长会算错，不能收
            if chunk_size > available:
                raise WavError("audio_truncated", "data chunk is larger than the file")
            size = chunk_size or available
            if size <= 0:
                raise WavError("audio_empty", "WAV contains no audio data")
            bytes_per_sec = WAV_SAMPLE_RATE * WAV_CHANNELS * (WAV_BITS // 8)
            return size, int(size / bytes_per_sec * 1000)

        # chunk 大小按偶数对齐；size 为 0 时必须自己往前走一格，否则死循环
        offset = body + chunk_size + (chunk_size % 2)
        if chunk_size == 0:
            offset = body + 1

    raise WavError("audio_no_data", "WAV has no data chunk")


def _normalize_hotwords(raw: str) -> str | None:
    """热词只留字符串数组，超量截断。存原样 JSON，导出时才用得上。"""
    if not raw:
        return None
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(parsed, list):
        return None
    words: list[str] = []
    total = 0
    for item in parsed:
        if not isinstance(item, str):
            continue
        word = item.strip()[:MAX_HOTWORD_CHARS]
        if not word:
            continue
        total += len(word)
        if total > MAX_HOTWORDS_TOTAL_CHARS or len(words) >= MAX_HOTWORDS:
            break
        words.append(word)
    if not words:
        return None
    return json.dumps(words, ensure_ascii=False)


# 已经建过表的库。用 WeakSet 而不是一个全局 bool：测试里每个用例换一个临时库，
# 全局 bool 会让第二个库被"已经建过了"跳过。
_schema_ready: "weakref.WeakSet[Database]" = weakref.WeakSet()


def _ensure_schema(db: Database) -> None:
    """建表。

    `db.initialize()` 的 ddl 列表里已经有这张表，这里是为了让「远端还是旧 db.py」
    也能自愈 —— 只上传一个新模块就能上线，不必去改服务器上的 db.py。

    每个库只跑一次：`CREATE TABLE IF NOT EXISTS` 在 SQLite 上同样要拿写锁并提交，
    每个请求来一遍就是白白跟遥测写入抢锁。
    """
    if db in _schema_ready:
        return
    db.execute(
        f"""
        CREATE TABLE IF NOT EXISTS asr_corrections (
            id {db.dialect.autoincrement_pk},
            correction_id TEXT NOT NULL UNIQUE,
            machine_id TEXT NOT NULL,
            client_ip TEXT,
            app_version TEXT,
            client_record_id TEXT,
            audio_path TEXT NOT NULL,
            audio_bytes INTEGER NOT NULL DEFAULT 0,
            audio_sha256 TEXT NOT NULL,
            audio_duration_ms INTEGER NOT NULL DEFAULT 0,
            original_asr_text TEXT NOT NULL,
            corrected_text TEXT NOT NULL,
            asr_provider TEXT,
            language TEXT,
            hotwords_json TEXT,
            consent_version TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            review_note TEXT,
            created_at BIGINT NOT NULL,
            updated_at BIGINT NOT NULL
        )
        """
    )
    _schema_ready.add(db)


def _count_recent(db: Database, column: str, value: str, window_ms: int, now_ms: int) -> int:
    p = db.dialect.placeholder
    row = db.fetch_one(
        f"SELECT COUNT(*) AS n FROM asr_corrections WHERE {column} = {p} AND created_at > {p}",
        (value, now_ms - window_ms),
    )
    return int((row or {}).get("n") or 0)


def _total_audio_bytes(db: Database) -> int:
    row = db.fetch_one("SELECT COALESCE(SUM(audio_bytes), 0) AS total FROM asr_corrections")
    return int((row or {}).get("total") or 0)


@router.post("/api/asr-corrections")
async def post_asr_correction(
    request: Request,
    audio: UploadFile = File(...),
    correction_id: str = Form(...),
    machine_id: str = Form(...),
    original_asr_text: str = Form(...),
    corrected_text: str = Form(...),
    work_mode: str = Form("server"),
    app_version: str = Form(""),
    client_record_id: str = Form(""),
    asr_provider: str = Form(""),
    language: str = Form(""),
    hotwords: str = Form(""),
    consent_version: str = Form(""),
):
    # 先做不花钱的检查，再读 body —— 顺序反了就等于让任何人白传 10MB。
    machine_id = machine_id.strip()
    if not machine_id:
        return _err(400, "machine_id_required", "machine_id is required")

    correction_id = correction_id.strip()
    if not _CORRECTION_ID_RE.fullmatch(correction_id):
        return _err(400, "bad_correction_id", "correction_id must be 8-64 chars of [A-Za-z0-9_-]")

    if work_mode.strip() != "server":
        return _err(400, "unsupported_work_mode", "Only server-mode recognition results are accepted")

    original = _clean_text(original_asr_text)
    corrected = _clean_text(corrected_text)
    if not original or not corrected:
        return _err(400, "empty_text", "original_asr_text and corrected_text must be non-empty")
    if len(original) > MAX_TEXT_CHARS or len(corrected) > MAX_TEXT_CHARS:
        return _err(400, "text_too_long", f"Texts must be at most {MAX_TEXT_CHARS} chars")
    if _CONTROL_CHARS_RE.search(original) or _CONTROL_CHARS_RE.search(corrected):
        return _err(400, "text_has_control_chars", "Texts must not contain control characters")
    if original == corrected:
        # 没有改动就没有标注可言，收下来只会给审核添噪声
        return _err(400, "no_change", "corrected_text is identical to original_asr_text")
    if not _length_is_plausible(original, corrected):
        return _err(400, "length_mismatch", "corrected_text length is implausible for this sample")

    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_AUDIO_BYTES * 2:
        return _err(413, "audio_too_large", f"Audio must be at most {MAX_AUDIO_BYTES} bytes")

    db = _get_db(request)
    if db is None:
        return _err(503, "storage_unavailable", "Database is not ready")
    _ensure_schema(db)

    p = db.dialect.placeholder
    now_ms = int(time.time() * 1000)
    ip = _client_ip(request)

    existing = db.fetch_one(
        f"SELECT correction_id, status FROM asr_corrections WHERE correction_id = {p}",
        (correction_id,),
    )
    if existing:
        # 客户端重试/双击。已经收下了就照原样回 200，别让用户看到失败。
        return JSONResponse(
            status_code=200,
            content={
                "correction_id": correction_id,
                "status": "duplicate",
                "withdraw_days": WITHDRAW_DAYS,
            },
        )

    for column, window_ms, limit in _RATE_RULES:
        value = machine_id if column == "machine_id" else ip
        if _count_recent(db, column, value, window_ms, now_ms) >= limit:
            return _err(429, "rate_limited", f"Too many corrections ({limit} per {window_ms // 1000}s)")

    if _total_audio_bytes(db) >= MAX_TOTAL_AUDIO_BYTES:
        return _err(507, "storage_full", "Correction storage is full")

    # 边读边数：超了立刻停，不把整份读完
    buffer = bytearray()
    while True:
        chunk = await audio.read(_READ_CHUNK)
        if not chunk:
            break
        buffer.extend(chunk)
        if len(buffer) > MAX_AUDIO_BYTES:
            return _err(413, "audio_too_large", f"Audio must be at most {MAX_AUDIO_BYTES} bytes")
    payload = bytes(buffer)

    try:
        data_bytes, duration_ms = parse_wav(payload)
    except WavError as exc:
        return _err(400, exc.code, str(exc))

    sha256 = hashlib.sha256(payload).hexdigest()
    dup = db.fetch_one(
        f"SELECT correction_id FROM asr_corrections WHERE machine_id = {p} AND audio_sha256 = {p}",
        (machine_id, sha256),
    )
    if dup:
        return JSONResponse(
            status_code=409,
            content={
                "error": "already_submitted",
                "message": "This recording has already been submitted",
                "correction_id": dup["correction_id"],
            },
        )

    date_dir = time.strftime("%Y-%m-%d")
    out_dir = STORAGE_DIR / date_dir
    relative_path = f"{date_dir}/{correction_id}.wav"
    # 文件名一律服务端生成（correction_id 已被正则限死），绝不用客户端给的文件名
    file_path = out_dir / f"{correction_id}.wav"
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
        file_path.write_bytes(payload)
    except OSError:
        logger.exception("Failed to store correction audio id=%s", correction_id)
        return _err(500, "store_failed", "Failed to store audio")

    try:
        db.execute(
            "INSERT INTO asr_corrections (correction_id, machine_id, client_ip, app_version,"
            " client_record_id, audio_path, audio_bytes, audio_sha256, audio_duration_ms,"
            " original_asr_text, corrected_text, asr_provider, language, hotwords_json,"
            " consent_version, status, created_at, updated_at)"
            f" VALUES ({','.join([p] * 18)})",
            (
                correction_id,
                machine_id[:128],
                ip[:64],
                app_version.strip()[:64],
                client_record_id.strip()[:64],
                relative_path,
                len(payload),
                sha256,
                duration_ms,
                original,
                corrected,
                asr_provider.strip()[:128],
                language.strip()[:32],
                _normalize_hotwords(hotwords),
                consent_version.strip()[:32],
                "pending",
                now_ms,
                now_ms,
            ),
        )
    except Exception:
        # 入库失败就把文件删掉，否则留下一个没人知道、也没人清理的孤儿 wav
        file_path.unlink(missing_ok=True)
        logger.exception("Failed to record correction id=%s", correction_id)
        return _err(500, "store_failed", "Failed to record correction")

    # 只记长度，不记文本
    logger.info(
        "asr correction received id=%s machine=%s bytes=%d data_bytes=%d dur_ms=%d"
        " orig_len=%d fixed_len=%d model=%s",
        correction_id,
        machine_id[:32],
        len(payload),
        data_bytes,
        duration_ms,
        len(original),
        len(corrected),
        asr_provider.strip()[:64] or "-",
    )
    return JSONResponse(
        status_code=200,
        content={
            "correction_id": correction_id,
            "status": "received",
            "duration_ms": duration_ms,
            "withdraw_days": WITHDRAW_DAYS,
        },
    )


@router.post("/api/asr-corrections/{correction_id}/withdraw")
async def withdraw_asr_correction(correction_id: str, request: Request):
    """用户撤回自己的提交：删音频、清文本，行留着。

    行不能删：限流是按 created_at 数行的，删了就等于「提交完撤回」可以无限刷。
    audio_bytes 归零，配额（SUM）会跟着释放。
    """
    if not _CORRECTION_ID_RE.fullmatch(correction_id):
        return _err(400, "bad_correction_id", "Invalid correction id")

    try:
        body = await request.json()
    except Exception:
        body = {}
    machine_id = str((body or {}).get("machine_id") or "").strip()
    if not machine_id:
        return _err(400, "machine_id_required", "machine_id is required")

    db = _get_db(request)
    if db is None:
        return _err(503, "storage_unavailable", "Database is not ready")
    _ensure_schema(db)

    p = db.dialect.placeholder
    row = db.fetch_one(
        f"SELECT machine_id, audio_path, status FROM asr_corrections WHERE correction_id = {p}",
        (correction_id,),
    )
    if not row:
        return _err(404, "not_found", "Correction not found")
    # 不比对就等于任何人凭编号删别人的样本
    if row["machine_id"] != machine_id:
        return _err(403, "forbidden", "This correction belongs to another machine")

    if row["status"] != "withdrawn":
        audio_path = str(row["audio_path"] or "")
        if audio_path:
            candidate = (STORAGE_DIR / audio_path).resolve()
            # 路径来自我们自己入库的相对路径，仍然兜一层，防止越界删除
            if candidate.is_relative_to(STORAGE_DIR.resolve()) and candidate.is_file():
                candidate.unlink(missing_ok=True)
        db.execute(
            "UPDATE asr_corrections SET status = 'withdrawn', audio_path = '', audio_bytes = 0,"
            f" original_asr_text = '', corrected_text = '', updated_at = {p}"
            f" WHERE correction_id = {p}",
            (int(time.time() * 1000), correction_id),
        )
        logger.info("asr correction withdrawn id=%s", correction_id)

    return JSONResponse(status_code=200, content={"correction_id": correction_id, "status": "withdrawn"})
