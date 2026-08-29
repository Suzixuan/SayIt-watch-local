"""X-Forwarded-For / X-Real-IP 信任边界测试（PM 审查要求 4）。

核心断言：只有在「直连地址命中显式受信代理名单」时才采信代理头；
私网地址本身绝不等于受信代理；未配置名单时永远用直连地址。
"""

from __future__ import annotations

from backend.app.net import client_ip_from_headers, trusted_client_ip


def test_direct_public_ip_ignores_spoofed_xff():
    # 直连是公网 IP，即使带伪造 XFF 也不采信
    assert trusted_client_ip("203.0.113.7", "1.2.3.4") == "203.0.113.7"
    assert trusted_client_ip("203.0.113.7", "6.6.6.6, 1.2.3.4") == "203.0.113.7"


def test_private_direct_without_trusted_proxy_ignores_xff():
    # 直连是私网/回环，但未配置受信代理名单：私网 ≠ 受信代理，仍用直连地址
    assert trusted_client_ip("192.168.1.10", "6.6.6.6") == "192.168.1.10"
    assert trusted_client_ip("127.0.0.1", "6.6.6.6") == "127.0.0.1"
    # 配置了受信代理但直连不命中：依然用直连地址
    assert trusted_client_ip("192.168.1.10", "6.6.6.6", ("10.0.0.1",)) == "192.168.1.10"


def test_trusted_proxy_uses_rightmost_nonprivate_xff():
    # 直连命中受信代理：从右往左取第一个非私网条目
    assert trusted_client_ip("10.0.0.1", "1.2.3.4, 10.0.0.1", ("10.0.0.1",)) == "1.2.3.4"
    # 攻击者把伪造 IP 塞在最左也不影响结果
    assert trusted_client_ip("10.0.0.1", "6.6.6.6, 1.2.3.4, 10.0.0.1", ("10.0.0.1",)) == "1.2.3.4"


def test_trusted_proxy_all_private_returns_rightmost():
    # 全为私网条目（纯内网环境）：取最右（最近一跳的受信代理），
    # 让所有内网客户端共享同一配额，避免伪装成其他内网主机绕过限流。
    assert trusted_client_ip("10.0.0.1", "192.168.1.5, 10.0.0.1", ("10.0.0.1",)) == "10.0.0.1"


def test_trusted_proxy_cidr_match():
    assert trusted_client_ip("10.10.0.3", "8.8.8.8", ("10.10.0.0/16",)) == "8.8.8.8"
    # 不在 CIDR 内 → 不采信
    assert trusted_client_ip("10.11.0.3", "8.8.8.8", ("10.10.0.0/16",)) == "10.11.0.3"


def test_x_real_ip_used_when_direct_is_trusted_proxy():
    assert client_ip_from_headers("10.0.0.1", {"x-real-ip": "9.9.9.9"}, ("10.0.0.1",)) == "9.9.9.9"
    # 未配置受信代理时 X-Real-IP 也不采信
    assert client_ip_from_headers("10.0.0.1", {"x-real-ip": "9.9.9.9"}) == "10.0.0.1"