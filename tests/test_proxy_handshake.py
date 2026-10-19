# -*- coding: utf-8 -*-
"""上游握手字节流测试：SOCKS4/SOCKS4a、SOCKS5、HTTP CONNECT。

使用 FakeSock 脚本化回复，不启动真实服务、不发真实网络包。
"""
import socket

from core.local_proxy import (
    build_socks4_request, parse_socks4_reply, _socks4_connect,
    _socks5_connect, build_http_connect_request,
)


class FakeSock:
    """按队列回复字节、记录发送内容的假 socket。"""

    def __init__(self, replies):
        self.replies = list(replies)
        self.sent = b""

    def sendall(self, data):
        self.sent += data

    def recv(self, n):
        if not self.replies:
            return b""
        chunk = self.replies.pop(0)
        return chunk[:n]


# ---------------- SOCKS4 ----------------

def test_socks4_request_ipv4_exact_bytes():
    req = build_socks4_request("1.2.3.4", 443, userid="u")
    assert req == b"\x04\x01" + (443).to_bytes(2, "big") \
        + socket.inet_aton("1.2.3.4") + b"u\x00"


def test_socks4_request_domain_uses_socks4a():
    req = build_socks4_request("www.google.com", 443)
    assert req[:8] == b"\x04\x01" + (443).to_bytes(2, "big") + b"\x00\x00\x00\x01"
    # USERID 空 → 直接两个 NUL 之间夹域名
    assert req.endswith(b"\x00www.google.com\x00")


def test_socks4_reply_parse():
    assert parse_socks4_reply(b"\x00\x5A" + b"\x00" * 6)
    assert not parse_socks4_reply(b"\x00\x5B" + b"\x00" * 6)  # 拒绝
    assert not parse_socks4_reply(b"\x00")                    # 过短


def test_socks4_connect_success_and_failure():
    good = FakeSock([b"\x00\x5A" + b"\x00" * 6])
    assert _socks4_connect(good, "www.google.com", 443)
    assert good.sent[:2] == b"\x04\x01"

    bad = FakeSock([b"\x00\x5B" + b"\x00" * 6])
    assert not _socks4_connect(bad, "www.google.com", 443)


def test_socks4_connect_exception_safe():
    class Boom(FakeSock):
        def sendall(self, data):
            raise OSError("reset")

    assert not _socks4_connect(Boom([]), "www.google.com", 443)


# ---------------- SOCKS5 ----------------

def test_socks5_connect_ipv4_success():
    bound = b"\x05\x00\x00\x01" + socket.inet_aton("1.2.3.4") + (443).to_bytes(2, "big")
    sock = FakeSock([b"\x05\x00", bound])
    assert _socks5_connect(sock, "1.2.3.4", 443)
    # 无需认证方法协商
    assert sock.sent[:3] == b"\x05\x01\x00"
    # CONNECT 请求：atyp=IPv4
    assert b"\x05\x01\x00\x01" + socket.inet_aton("1.2.3.4") in sock.sent


def test_socks5_connect_domain_success():
    host = "www.google.com"
    bound = b"\x05\x00\x00\x03" + bytes([len(host)]) + host.encode() \
        + (443).to_bytes(2, "big")
    sock = FakeSock([b"\x05\x00", bound])
    assert _socks5_connect(sock, host, 443)
    # 域名请求：atyp=0x03 + 长度 + 域名
    assert b"\x05\x01\x00\x03" + bytes([len(host)]) + host.encode() in sock.sent


def test_socks5_connect_method_rejected():
    sock = FakeSock([b"\x05\x02"])  # 无可用认证方式
    assert not _socks5_connect(sock, "1.2.3.4", 443)


def test_socks5_connect_reply_rejected():
    sock = FakeSock([b"\x05\x00", b"\x05\x05\x00\x01" + b"\x00" * 6])
    assert not _socks5_connect(sock, "1.2.3.4", 443)


# ---------------- HTTP CONNECT ----------------

def test_http_connect_request_bytes():
    req = build_http_connect_request("example.com", 443)
    assert req == (b"CONNECT example.com:443 HTTP/1.1\r\n"
                   b"Host: example.com:443\r\n\r\n")
