"""ASR 纠错反馈接口测试。

刻意只挂 asr_corrections.router 到一个空 FastAPI 上：这样不碰 main.py，
不用起 ASR / LLM 引擎，也不需要 GPU，本地和服务器都能跑。
数据库和落盘目录都指向临时目录，跑完删干净。
"""
from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from backend.app import asr_corrections
from backend.app.db import Database

SAMPLE_RATE = 16000


def make_wav(
    pcm: bytes,
    *,
    sample_rate: int = SAMPLE_RATE,
    channels: int = 1,
    bits: int = 16,
    audio_format: int = 1,
    declared_data_size: int | None = None,
    extra_chunk: bool = False,
) -> bytes:
    """拼一个 WAV。参数留着是为了造各种坏文件。"""
    block_align = channels * bits // 8
    byte_rate = sample_rate * block_align
    fmt = (
        b"fmt "
        + (16).to_bytes(4, "little")
        + audio_format.to_bytes(2, "little")
        + channels.to_bytes(2, "little")
        + sample_rate.to_bytes(4, "little")
        + byte_rate.to_bytes(4, "little")
        + block_align.to_bytes(2, "little")
        + bits.to_bytes(2, "little")
    )
    # fmt 与 data 之间夹一个别的块：真实录音软件会写 LIST，解析必须按块走而不是死读偏移
    middle = b"LIST" + (4).to_bytes(4, "little") + b"INFO" if extra_chunk else b""
    data_size = len(pcm) if declared_data_size is None else declared_data_size
    data = b"data" + data_size.to_bytes(4, "little") + pcm
    body = b"WAVE" + fmt + middle + data
    return b"RIFF" + len(body).to_bytes(4, "little") + body


def silence(ms: int) -> bytes:
    return b"\x00" * int(SAMPLE_RATE * 2 * ms / 1000)


class AsrCorrectionsTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = Path(tempfile.mkdtemp(prefix="sayit-asr-corr-test-"))
        self.storage = self._tmpdir / "asr-corrections"
        self.db = Database(str(self._tmpdir / "sayit.sqlite3"))
        self.db.initialize()

        app = FastAPI()
        app.include_router(asr_corrections.router)
        app.state.database = self.db
        self.client = TestClient(app)

        self._storage_patch = patch.object(asr_corrections, "STORAGE_DIR", self.storage)
        self._storage_patch.start()

    def tearDown(self) -> None:
        self._storage_patch.stop()
        self.client.close()
        self.db.close()
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    # ── helpers ────────────────────────────────────────────────────────────
    def submit(self, **overrides):
        data = {
            "correction_id": "corr-00000001",
            "machine_id": "machine-a",
            "original_asr_text": "今天天气很好",
            "corrected_text": "今天天气很号",
            "work_mode": "server",
            "app_version": "0.1.6",
            "client_record_id": "rec-1",
            "asr_provider": "Qwen3-ASR-1.7B",
            "language": "zh",
            "hotwords": json.dumps(["SayIt", "Kiro"]),
            "consent_version": "1",
        }
        audio = overrides.pop("audio", make_wav(silence(500)))
        data.update({k: v for k, v in overrides.items()})
        files = {"audio": ("whatever.wav", audio, "audio/wav")}
        return self.client.post("/api/asr-corrections", data=data, files=files)

    def rows(self) -> list[dict]:
        return self.db.fetch_all("SELECT * FROM asr_corrections ORDER BY id")

    # ── happy path ─────────────────────────────────────────────────────────
    def test_accepts_correction_and_stores_audio_and_row(self) -> None:
        resp = self.submit()
        self.assertEqual(resp.status_code, 200, resp.text)
        body = resp.json()
        self.assertEqual(body["status"], "received")
        self.assertEqual(body["correction_id"], "corr-00000001")
        self.assertEqual(body["duration_ms"], 500)
        # 客户端拿它拼同意文案，改了值要连着 asrCorrection.ts 的常量一起改
        self.assertEqual(body["withdraw_days"], asr_corrections.WITHDRAW_DAYS)

        rows = self.rows()
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row["original_asr_text"], "今天天气很好")
        self.assertEqual(row["corrected_text"], "今天天气很号")
        self.assertEqual(row["status"], "pending")
        self.assertEqual(row["audio_duration_ms"], 500)
        self.assertEqual(row["asr_provider"], "Qwen3-ASR-1.7B")
        self.assertEqual(json.loads(row["hotwords_json"]), ["SayIt", "Kiro"])
        self.assertEqual(len(row["audio_sha256"]), 64)

        # 文件名由服务端生成，客户端给的 whatever.wav 不能出现在路径里
        stored = self.storage / row["audio_path"]
        self.assertTrue(stored.is_file())
        self.assertNotIn("whatever", row["audio_path"])
        self.assertEqual(stored.stat().st_size, row["audio_bytes"])

    def test_wav_with_extra_chunk_between_fmt_and_data(self) -> None:
        resp = self.submit(audio=make_wav(silence(1000), extra_chunk=True))
        self.assertEqual(resp.status_code, 200, resp.text)
        self.assertEqual(resp.json()["duration_ms"], 1000)

    # ── 幂等与查重 ─────────────────────────────────────────────────────────
    def test_same_correction_id_is_idempotent(self) -> None:
        self.assertEqual(self.submit().status_code, 200)
        again = self.submit()
        self.assertEqual(again.status_code, 200)
        self.assertEqual(again.json()["status"], "duplicate")
        self.assertEqual(len(self.rows()), 1)

    def test_same_audio_under_new_id_is_rejected(self) -> None:
        self.assertEqual(self.submit().status_code, 200)
        again = self.submit(correction_id="corr-00000002")
        self.assertEqual(again.status_code, 409)
        self.assertEqual(again.json()["error"], "already_submitted")
        self.assertEqual(again.json()["correction_id"], "corr-00000001")
        self.assertEqual(len(self.rows()), 1)

    # ── 文本校验 ───────────────────────────────────────────────────────────
    def test_rejects_identical_text(self) -> None:
        resp = self.submit(corrected_text="今天天气很好")
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "no_change")
        self.assertEqual(self.rows(), [])

    def test_rejects_implausible_length(self) -> None:
        resp = self.submit(corrected_text="改" * 400)
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "length_mismatch")

    def test_rejects_control_characters(self) -> None:
        resp = self.submit(corrected_text="今天天气很\x00号")
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "text_has_control_chars")

    def test_rejects_empty_text(self) -> None:
        resp = self.submit(corrected_text="   ")
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "empty_text")

    def test_rejects_non_server_work_mode(self) -> None:
        resp = self.submit(work_mode="cloud_api")
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "unsupported_work_mode")

    def test_rejects_bad_correction_id(self) -> None:
        resp = self.submit(correction_id="../../etc/passwd")
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "bad_correction_id")

    def test_crlf_is_normalized_before_comparison(self) -> None:
        # 只有行尾不同不算改动，否则客户端换个平台就能刷出"纠错"
        resp = self.submit(original_asr_text="第一行\n第二行", corrected_text="第一行\r\n第二行")
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "no_change")

    # ── 音频校验 ───────────────────────────────────────────────────────────
    def test_rejects_non_wav(self) -> None:
        resp = self.submit(audio=b"not a wav file at all, just some bytes" * 2)
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "audio_not_wav")
        self.assertFalse(self.storage.exists())

    def test_rejects_wrong_sample_rate(self) -> None:
        resp = self.submit(audio=make_wav(silence(500), sample_rate=44100))
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "audio_bad_params")

    def test_rejects_stereo(self) -> None:
        resp = self.submit(audio=make_wav(silence(500), channels=2))
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "audio_bad_params")

    def test_rejects_non_pcm(self) -> None:
        resp = self.submit(audio=make_wav(silence(500), audio_format=3))
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "audio_not_pcm")

    def test_rejects_truncated_data_chunk(self) -> None:
        # 头里声明的比实际字节多：时长会算错，不能收
        resp = self.submit(audio=make_wav(silence(100), declared_data_size=999_999))
        self.assertEqual(resp.status_code, 400)
        self.assertEqual(resp.json()["error"], "audio_truncated")

    def test_rejects_empty_audio(self) -> None:
        resp = self.submit(audio=make_wav(b""))
        self.assertEqual(resp.status_code, 400)
        self.assertIn(resp.json()["error"], {"audio_too_short", "audio_empty"})

    def test_rejects_oversized_audio(self) -> None:
        with patch.object(asr_corrections, "MAX_AUDIO_BYTES", 4096):
            resp = self.submit(audio=make_wav(silence(1000)))
        self.assertEqual(resp.status_code, 413)
        self.assertEqual(resp.json()["error"], "audio_too_large")
        self.assertEqual(self.rows(), [])

    def test_five_minute_recording_fits_under_the_cap(self) -> None:
        # 这条钉住"10 MiB 装得下五分钟"这个前提，改上限时会立刻炸
        five_min = 300 * SAMPLE_RATE * 2
        self.assertLess(five_min + 44, asr_corrections.MAX_AUDIO_BYTES)

    # ── 限流与配额 ─────────────────────────────────────────────────────────
    def test_rate_limit_per_machine(self) -> None:
        with patch.object(asr_corrections, "_RATE_RULES", (("machine_id", 3600_000, 2),)):
            self.assertEqual(self.submit(correction_id="corr-aaaaaaa1", audio=make_wav(silence(100))).status_code, 200)
            self.assertEqual(self.submit(correction_id="corr-aaaaaaa2", audio=make_wav(silence(200))).status_code, 200)
            blocked = self.submit(correction_id="corr-aaaaaaa3", audio=make_wav(silence(300)))
        self.assertEqual(blocked.status_code, 429)
        self.assertEqual(blocked.json()["error"], "rate_limited")
        self.assertEqual(len(self.rows()), 2)

    def test_rate_limit_counts_withdrawn_rows(self) -> None:
        """撤回不能把额度还回来，否则「提交完撤回」可以无限刷。"""
        with patch.object(asr_corrections, "_RATE_RULES", (("machine_id", 3600_000, 1),)):
            self.assertEqual(self.submit(correction_id="corr-bbbbbbb1").status_code, 200)
            withdraw = self.client.post(
                "/api/asr-corrections/corr-bbbbbbb1/withdraw", json={"machine_id": "machine-a"}
            )
            self.assertEqual(withdraw.status_code, 200)
            blocked = self.submit(correction_id="corr-bbbbbbb2", audio=make_wav(silence(700)))
        self.assertEqual(blocked.status_code, 429)

    def test_quota_full(self) -> None:
        self.assertEqual(self.submit().status_code, 200)
        with patch.object(asr_corrections, "MAX_TOTAL_AUDIO_BYTES", 1):
            resp = self.submit(correction_id="corr-ccccccc1", audio=make_wav(silence(300)))
        self.assertEqual(resp.status_code, 507)
        self.assertEqual(resp.json()["error"], "storage_full")

    # ── 撤回 ───────────────────────────────────────────────────────────────
    def test_withdraw_deletes_audio_and_clears_text(self) -> None:
        self.assertEqual(self.submit().status_code, 200)
        stored = self.storage / self.rows()[0]["audio_path"]
        self.assertTrue(stored.is_file())

        resp = self.client.post(
            "/api/asr-corrections/corr-00000001/withdraw", json={"machine_id": "machine-a"}
        )
        self.assertEqual(resp.status_code, 200, resp.text)
        self.assertFalse(stored.exists())

        row = self.rows()[0]
        self.assertEqual(row["status"], "withdrawn")
        self.assertEqual(row["original_asr_text"], "")
        self.assertEqual(row["corrected_text"], "")
        self.assertEqual(row["audio_bytes"], 0)
        self.assertEqual(row["audio_path"], "")

    def test_withdraw_rejects_other_machine(self) -> None:
        self.assertEqual(self.submit().status_code, 200)
        resp = self.client.post(
            "/api/asr-corrections/corr-00000001/withdraw", json={"machine_id": "machine-b"}
        )
        self.assertEqual(resp.status_code, 403)
        self.assertTrue((self.storage / self.rows()[0]["audio_path"]).is_file())

    def test_withdraw_is_idempotent(self) -> None:
        self.assertEqual(self.submit().status_code, 200)
        url = "/api/asr-corrections/corr-00000001/withdraw"
        self.assertEqual(self.client.post(url, json={"machine_id": "machine-a"}).status_code, 200)
        self.assertEqual(self.client.post(url, json={"machine_id": "machine-a"}).status_code, 200)

    def test_withdraw_unknown_id(self) -> None:
        resp = self.client.post(
            "/api/asr-corrections/corr-zzzzzzzz/withdraw", json={"machine_id": "machine-a"}
        )
        self.assertEqual(resp.status_code, 404)

    # ── 纯函数 ─────────────────────────────────────────────────────────────
    def test_hotwords_normalization(self) -> None:
        self.assertIsNone(asr_corrections._normalize_hotwords(""))
        self.assertIsNone(asr_corrections._normalize_hotwords("not json"))
        self.assertIsNone(asr_corrections._normalize_hotwords('{"a": 1}'))
        self.assertIsNone(asr_corrections._normalize_hotwords('[1, 2, 3]'))
        self.assertEqual(
            json.loads(asr_corrections._normalize_hotwords('["  a  ", "", "b", 5]')),
            ["a", "b"],
        )
        many = json.dumps([f"w{i}" for i in range(500)])
        self.assertEqual(len(json.loads(asr_corrections._normalize_hotwords(many))), asr_corrections.MAX_HOTWORDS)

    def test_parse_wav_duration(self) -> None:
        self.assertEqual(asr_corrections.parse_wav(make_wav(silence(2500)))[1], 2500)


if __name__ == "__main__":
    unittest.main()
