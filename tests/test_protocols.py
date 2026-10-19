# -*- coding: utf-8 -*-
"""加密协议关键逻辑测试：SS 加密方式/密钥派生、地址编码、VMess KDF、VLess/Trojan 控制头。

不发起真实网络连接：VLess/Trojan 构造函数只向 FakeSock 写控制头，直接校验字节。
"""
import hashlib
import socket
import struct
import uuid as uuid_mod

from core.protocols import ss, vmess, vless, trojan
from core.protocols.addr import (
    encode_socks_addr, decode_socks_addr,
    encode_v2ray_addr, decode_v2ray_addr,
)

UUID = "b831381d-6324-4d53-ab92-7510788b939e"


class FakeSock:
    """只记录发送字节的假 socket。"""

    def __init__(self):
        self.sent = b""

    def sendall(self, data):
        self.sent += data

    def recv(self, n):
        return b""


# ---------------- SS ----------------

def test_ss_supported_methods():
    for m in ("aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
              "chacha20-ietf-poly1305", "rc4-md5",
              "aes-128-cfb", "aes-256-cfb", "chacha20-ietf"):
        assert ss.is_supported(m), m
    for m in ("bf-cfb", "aes-256-ctr", "", None):
        assert not ss.is_supported(m)


def test_ss_evp_bytes_to_key_deterministic():
    k1 = ss._evp_bytes_to_key(b"password", 32)
    k2 = ss._evp_bytes_to_key(b"password", 32)
    assert k1 == k2 and len(k1) == 32
    assert ss._evp_bytes_to_key(b"other", 32) != k1


# ---------------- 地址编码 ----------------

def test_socks_addr_ipv4_roundtrip():
    raw = encode_socks_addr("1.2.3.4", 443)
    assert raw[0] == 0x01
    assert raw[1:5] == socket.inet_aton("1.2.3.4")
    assert raw[5:7] == (443).to_bytes(2, "big")
    host, port, consumed = decode_socks_addr(raw)
    assert host == "1.2.3.4" and port == 443 and consumed == len(raw)


def test_socks_addr_domain_roundtrip():
    raw = encode_socks_addr("example.com", 8080)
    assert raw[0] == 0x03
    host, port, _ = decode_socks_addr(raw)
    assert host == "example.com" and port == 8080


def test_socks_addr_ipv6_roundtrip():
    raw = encode_socks_addr("::1", 443)
    assert raw[0] == 0x04
    host, port, _ = decode_socks_addr(raw)
    assert host == "::1" and port == 443


def test_v2ray_addr_ipv4_and_domain_roundtrip():
    raw = encode_v2ray_addr("9.9.9.9", 443)
    assert raw[:2] == (443).to_bytes(2, "big") and raw[2] == 0x01
    host, port, _ = decode_v2ray_addr(raw)
    assert host == "9.9.9.9" and port == 443

    raw2 = encode_v2ray_addr("example.org", 80)
    assert raw2[2] == 0x02
    host, port, _ = decode_v2ray_addr(raw2)
    assert host == "example.org" and port == 80


# ---------------- VMess KDF ----------------

def test_vmess_fnv1a_empty():
    # 空输入返回 FNV 初始 basis
    assert vmess._fnv1a32(b"") == struct.pack(">I", 0x811C9DC5)


def test_vmess_cmd_key_deterministic():
    k1 = vmess._cmd_key(UUID)
    k2 = vmess._cmd_key(UUID)
    assert k1 == k2 and len(k1) == 16
    assert vmess._cmd_key("00000000-0000-0000-0000-000000000000") != k1


def test_vmess_kdf16_length():
    assert len(vmess._kdf16(b"master", "path-a", "path-b")) == 16


# ---------------- VLess 控制头 ----------------

def test_vless_header_bytes():
    sock = FakeSock()
    vless.VLessStream(sock, UUID, "example.com", 443)
    h = sock.sent
    assert h[0] == 0x00                      # version = 0
    assert h[1:17] == uuid_mod.UUID(UUID).bytes
    assert h[17] == 0x00                      # addons 长度 = 0
    assert h[18] == 0x01                      # command = TCP
    # 其后为 v2ray 风格目标地址
    host, port, consumed = decode_v2ray_addr(h, 19)
    assert host == "example.com" and port == 443
    assert consumed == len(h)


# ---------------- Trojan 控制头 ----------------

def test_trojan_header_bytes():
    sock = FakeSock()
    trojan.TrojanStream(sock, "s3cret", "example.com", 443)
    h = sock.sent
    expected_pwd = hashlib.sha224(b"s3cret").hexdigest().encode()
    assert h.startswith(expected_pwd + b"\r\n")
    rest = h[len(expected_pwd) + 2:]
    host, port, consumed = decode_socks_addr(rest)
    assert host == "example.com" and port == 443
    assert rest[consumed:] == b"\r\n"
