"""运营公告（remote notice）。

与桌面更新机制完全解耦：客户端定期拉取 /api/notice，据此在 App 内展示一条公告
（例如引导旧版用户去官网/GitHub 手动下载新版）。只读、无副作用、无任何记录能力。

运维方式：编辑 <notices_dir>/notice.json 即可下发；删除或留空即撤下。
字段见 notice.example.json。
"""

from __future__ import annotations

import json
from pathlib import Path

from .config import Config


def read_notice(cfg: Config) -> dict:
    """读取当前公告；文件不存在或解析失败时返回空对象（客户端据此不展示）。"""
    notice_file = Path(cfg.paths.notices_dir) / "notice.json"
    try:
        if not notice_file.is_file():
            return {}
        data = json.loads(notice_file.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}
