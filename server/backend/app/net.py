"""客户端真实 IP 的解析。

安全约束（PM 审查要求）：
- **X-Forwarded-For / X-Real-IP 只在「直连地址命中明确配置的受信代理名单」时才采信**，
  不能因为对端是私网/回环地址就当成可信代理（私网地址也可能是攻击者直连）。
- 采信后从**右往左**取第一个非私网条目（受信代理会在右侧追加），
  防止攻击者把伪造 IP 塞在最左以绕过按 IP 的限流/配额。
- 默认 `trusted_proxies=()`（本地回环部署）：任何情况下都直接使用直连地址。
"""

from __future__ import annotations

import ipaddress
from typing import Iterable, Sequence


def _is_private(ip: str) -> bool:
    try:
        addr = ipaddress.ip_address(ip.strip().strip("[]"))
    except ValueError:
        # 无法解析的条目（如 Unix socket 名）一律视为"非外部 IP"，跳过
        return True
    if addr.is_loopback or addr.is_link_local or addr.is_multicast:
        return True
    return addr.is_private or addr.is_reserved


def _in_trusted_proxies(ip: str, trusted_proxies: Sequence[str]) -> bool:
    try:
        addr = ipaddress.ip_address(ip.strip().strip("[]"))
    except ValueError:
        return False
    for entry in trusted_proxies:
        entry = entry.strip()
        if not entry:
            continue
        try:
            if "/" in entry:
                if addr in ipaddress.ip_network(entry, strict=False):
                    return True
            elif addr == ipaddress.ip_address(entry.strip("[]")):
                return True
        except ValueError:
            continue
    return False


def _split_forwarded(forwarded: str | None) -> list[str]:
    if not forwarded:
        return []
    return [part.strip() for part in forwarded.split(",") if part.strip()]


def trusted_client_ip(
    direct: str | None,
    forwarded: str | None,
    trusted_proxies: Sequence[str] = (),
) -> str:
    """解析真实客户端 IP。

    - 未配置受信代理：一律返回直连地址（不信任任何代理头）。
    - 直连地址命中受信代理名单：从右往左取第一个非私网 XFF 条目；全为私网则取最右。
    - 直连地址未命中名单：忽略代理头，返回直连地址。
    """
    direct = (direct or "").strip() or "unknown"
    if not trusted_proxies:
        return direct
    if direct == "unknown" or not _in_trusted_proxies(direct, trusted_proxies):
        return direct
    chain = _split_forwarded(forwarded)
    if not chain:
        return direct
    for part in reversed(chain):
        if not _is_private(part):
            return part
    return chain[-1]


def client_ip_from_headers(
    direct: str | None,
    headers: Iterable[tuple[str, str]] | dict | None,
    trusted_proxies: Sequence[str] = (),
) -> str:
    """便捷入口：直接从请求头里取 X-Forwarded-For / X-Real-IP。"""
    forwarded = None
    if isinstance(headers, dict):
        forwarded = headers.get("x-forwarded-for") or headers.get("x-real-ip")
    else:
        for key, value in headers or []:
            if key.lower() in ("x-forwarded-for", "x-real-ip"):
                forwarded = value
                break
    return trusted_client_ip(direct, forwarded, trusted_proxies)