# -*- coding: utf-8 -*-
"""checker 验证逻辑测试：全部网络（requests / socket / TLS）以 mock 替换。"""
from unittest.mock import patch

from core import checker


class FakeResp:
    """requests.get 的假响应（支持上下文管理器与 iter_content）。"""
    status_code = 200

    def json(self):
        return {"origin": "203.0.113.8"}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def iter_content(self, chunk_size):
        yield b"x" * 1024


class FakeSock:
    def __init__(self, reply):
        self.reply = reply
        self.sent = b""

    def sendall(self, data):
        self.sent += data

    def settimeout(self, t):
        pass

    def recv(self, n):
        out, self.reply = self.reply[:n], self.reply[n:]
        return out

    def close(self):
        pass


def _http_proxy():
    return {"ip": "1.1.1.1", "port": 8080, "protocol": "http"}


def test_check_one_http_ok_via_mock():
    p = _http_proxy()
    with patch.object(checker.requests, "get", return_value=FakeResp()) as g, \
         patch.object(checker, "_test_connect_tunnel", return_value=(True, 50)):
        out = checker.check_one(p, timeout=2)
    assert out["alive"] is True
    assert out["https_ok"] is True
    assert out["latency_ms"] == 50
    assert g.call_count >= 1


def test_check_one_http_tunnel_fail_marks_https_false():
    p = _http_proxy()
    with patch.object(checker.requests, "get", return_value=FakeResp()), \
         patch.object(checker, "_test_connect_tunnel", return_value=(False, 0)):
        out = checker.check_one(p, timeout=2)
    assert out["alive"] is True          # HTTP 通
    assert out["https_ok"] is False     # 隧道不通


def test_check_one_http_unreachable_marked_dead():
    p = _http_proxy()
    with patch.object(checker.requests, "get",
                      side_effect=Exception("unreachable")):
        out = checker.check_one(p, timeout=2)
    assert out["alive"] is False
    assert out["https_ok"] is False


def test_check_one_socks4_skips_requests_and_uses_tunnel():
    p = {"ip": "9.9.9.9", "port": 1080, "protocol": "socks4"}
    with patch.object(checker.requests, "get") as g, \
         patch.object(checker, "_test_connect_tunnel",
                      return_value=(True, 80)) as tunnel:
        out = checker.check_one(p, timeout=2)
    tunnel.assert_called_once()
    g.assert_not_called()  # socks4 不走 requests
    assert out["alive"] is True
    assert out["https_ok"] is True
    assert out["latency_ms"] == 80


def test_check_one_socks4_tunnel_fail():
    p = {"ip": "9.9.9.9", "port": 1080, "protocol": "socks4"}
    with patch.object(checker, "_test_connect_tunnel",
                      return_value=(False, 0)):
        out = checker.check_one(p, timeout=2)
    assert out["alive"] is False
    assert out["https_ok"] is False


def test_check_one_socks4_handshake_rejected_no_tls():
    p = {"ip": "9.9.9.9", "port": 1080, "protocol": "socks4"}
    sock = FakeSock(b"\x00\x5B" + b"\x00" * 6)  # 代理拒绝
    with patch.object(checker.socket, "create_connection",
                      return_value=sock):
        ok, latency = checker._test_connect_tunnel(p, "www.google.com", 443,
                                                   timeout=2)
    assert ok is False
    assert isinstance(latency, int)


def test_check_one_encrypted_delegates():
    p = {"ip": "1.1.1.1", "port": 8388, "protocol": "ss", "config": {}}
    sentinel = dict(p, alive=True)
    with patch.object(checker, "_check_encrypted", return_value=sentinel) as e:
        out = checker.check_one(p, timeout=2)
    e.assert_called_once()
    assert out is sentinel
