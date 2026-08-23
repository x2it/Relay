# -*- coding: utf-8 -*-
"""VMess 协议客户端（AEAD 标准版，兼容 v2ray-core / Xray）。

实现要点（对照 v2ray-core v5 源码）：
- cmdKey = MD5(UUID + 固定盐 "c48619fe-8f02-49e0-b9e9-edf763e17e21")
- 请求头版本 = 1（AEAD），AuthID 采用 AES-128-ECB 加密的 16 字节随机挑战
- 请求/响应体统一使用 AES-128-GCM，块长度明文(2字节大端)，nonce 为
  bodyIV 前 12 字节（前 2 字节按大端计数器递增），无需 sha3
- 响应头为 AEAD 双层结构（长度 + 载荷），先读响应头再读响应体
- 流结束标记：读到长度 == 16 的块（空载荷 + 认证标签）

返回的 VMessStream 实现 fileno/recv/sendall 等接口，可被管道复用。
"""
import os
import socket
import ssl
import struct
import hashlib
import hmac
import time
import uuid as uuid_mod
import zlib

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .base import TunnelStream
from .addr import encode_v2ray_addr

# ---------------- KDF 常量（与 v2ray-core 一致） ----------------

_KDF_SALT = b"VMess AEAD KDF"
_AUTH_ID_KEY_SALT = "AES Auth ID Encryption"
_RESP_LEN_KEY_SALT = "AEAD Resp Header Len Key"
_RESP_LEN_IV_SALT = "AEAD Resp Header Len IV"
_RESP_PAYLOAD_KEY_SALT = "AEAD Resp Header Key"
_RESP_PAYLOAD_IV_SALT = "AEAD Resp Header IV"
_HDR_LEN_KEY_SALT = "VMess Header AEAD Key_Length"
_HDR_LEN_IV_SALT = "VMess Header AEAD Nonce_Length"
_HDR_KEY_SALT = "VMess Header AEAD Key"
_HDR_IV_SALT = "VMess Header AEAD Nonce"

_UUID_CMD_SALT = b"c48619fe-8f02-49e0-b9e9-edf763e17e21"

# ---------------- 密钥派生 ----------------

def _kdf(key, *path):
    """v2ray 的 vmessaead.KDF：嵌套 HMAC-SHA256。"""
    keys = [p.encode() if isinstance(p, str) else bytes(p) for p in path]

    def make(ks):
        if not ks:
            def root():
                return hmac.new(_KDF_SALT, digestmod=hashlib.sha256)
            return root
        inner = make(ks[:-1])
        k = ks[-1]

        def level():
            return hmac.new(k, digestmod=inner)
        return level

    h = make(keys)()
    h.update(key)
    return h.digest()


def _kdf16(key, *path):
    return _kdf(key, *path)[:16]


def _cmd_key(user_id: str) -> bytes:
    uid = uuid_mod.UUID(user_id).bytes
    return hashlib.md5(uid + _UUID_CMD_SALT).digest()


def _fnv1a32(data: bytes) -> bytes:
    h = 0x811C9DC5
    for b in data:
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return struct.pack(">I", h)


# ---------------- AuthID / 请求头加密 ----------------

def _create_auth_id(cmd_key: bytes, now: int = None) -> bytes:
    t = now if now is not None else int(time.time())
    buf = struct.pack(">q", t) + os.urandom(4)
    crc = zlib.crc32(buf) & 0xFFFFFFFF
    buf += struct.pack(">I", crc)
    aes = Cipher(algorithms.AES(_kdf16(cmd_key, _AUTH_ID_KEY_SALT)),
                 modes.ECB()).encryptor()
    return aes.update(buf) + aes.finalize()


def _seal_header(cmd_key: bytes, header_data: bytes) -> bytes:
    auth_id = _create_auth_id(cmd_key)
    conn_nonce = os.urandom(8)

    len_key = _kdf16(cmd_key, _HDR_LEN_KEY_SALT, auth_id, conn_nonce)
    len_iv = _kdf(cmd_key, _HDR_LEN_IV_SALT, auth_id, conn_nonce)[:12]
    len_ct = AESGCM(len_key).encrypt(len_iv, struct.pack(">H", len(header_data)), auth_id)

    h_key = _kdf16(cmd_key, _HDR_KEY_SALT, auth_id, conn_nonce)
    h_iv = _kdf(cmd_key, _HDR_IV_SALT, auth_id, conn_nonce)[:12]
    h_ct = AESGCM(h_key).encrypt(h_iv, header_data, auth_id)

    return auth_id + len_ct + conn_nonce + h_ct


def _build_request_header(body_key, body_iv, response_header,
                          target_host: str, target_port: int) -> bytes:
    """构造 VMess v2 请求头。"""
    option = 0x01      # RequestOptionChunkStream
    security = 0x03    # SecurityType_AES128_GCM
    command = 0x01     # RequestCommandTCP

    h = bytes([1])                 # version = 1 (AEAD)
    h += body_iv                   # 16
    h += body_key                  # 16
    h += bytes([response_header])  # 1
    h += bytes([option])           # 1
    h += bytes([security])         # 1 (padding=0 << 4 | security)
    h += bytes([0])                # reserved
    h += bytes([command])          # 1
    h += encode_v2ray_addr(target_host, target_port)
    h += _fnv1a32(h)               # 4
    return h


# ---------------- 分块 nonce ----------------

def _chunk_nonce(iv: bytes, count: int) -> bytes:
    """GenerateChunkNonce：前 2 字节大端计数器，取前 12 字节。"""
    c = bytearray(iv[:16])
    c[0:2] = struct.pack(">H", count & 0xFFFF)
    return bytes(c[:12])


# ---------------- VMess 流 ----------------

class VMessStream(TunnelStream):
    CHUNK = 0x4000
    EOF_SIZE = 16  # 空载荷 + 认证标签

    def __init__(self, sock, cmd_key: bytes, body_key: bytes, body_iv: bytes,
                 response_header: int, target_host: str, target_port: int):
        super().__init__(sock)
        req_header = _build_request_header(body_key, body_iv, response_header,
                                           target_host, target_port)
        self._sock.sendall(_seal_header(cmd_key, req_header))

        self._enc_key = body_key
        self._enc_iv = body_iv
        self._dec_key = hashlib.sha256(body_key).digest()[:16]
        self._dec_iv = hashlib.sha256(body_iv).digest()[:16]
        self._enc_nonce = 0
        self._dec_nonce = 0
        self._dec_buf = b""
        self._read_response_header(response_header)

    def _read_response_header(self, expected: int):
        len_key = _kdf16(self._dec_key, _RESP_LEN_KEY_SALT)
        len_iv = _kdf(self._dec_iv, _RESP_LEN_IV_SALT)[:12]
        len_ct = self._recv_exact(18)
        if len(len_ct) < 18:
            raise ConnectionError("VMess 响应头长度读取失败")
        try:
            ln = struct.unpack(">H", AESGCM(len_key).decrypt(len_iv, len_ct, None))[0]
        except Exception:
            raise ConnectionError("VMess 响应头长度校验失败")
        pay_key = _kdf16(self._dec_key, _RESP_PAYLOAD_KEY_SALT)
        pay_iv = _kdf(self._dec_iv, _RESP_PAYLOAD_IV_SALT)[:12]
        pay_ct = self._recv_exact(ln + 16)
        if len(pay_ct) < ln + 16:
            raise ConnectionError("VMess 响应头读取失败")
        try:
            header = AESGCM(pay_key).decrypt(pay_iv, pay_ct, None)
        except Exception:
            raise ConnectionError("VMess 响应头校验失败")
        if header and header[0] != expected:
            raise ConnectionError("VMess 身份校验失败")

    # ----- 发送 -----
    def sendall(self, data):
        while data:
            chunk, data = data[:self.CHUNK], data[self.CHUNK:]
            ln = struct.pack(">H", len(chunk))
            nonce = _chunk_nonce(self._enc_iv, self._enc_nonce)
            self._enc_nonce += 1
            ct = AESGCM(self._enc_key).encrypt(nonce, chunk, None)
            self._sock.sendall(ln + ct)

    # ----- 接收 -----
    def recv(self, n):
        while len(self._dec_buf) < n and not self._eof:
            try:
                lnb = self._recv_exact(2)
                if len(lnb) < 2:
                    self._eof = True
                    break
                ln = struct.unpack(">H", lnb)[0]
                if ln == self.EOF_SIZE:
                    self._eof = True
                    break
                ct = self._recv_exact(ln)
                if len(ct) < ln:
                    self._eof = True
                    break
                nonce = _chunk_nonce(self._dec_iv, self._dec_nonce)
                self._dec_nonce += 1
                try:
                    plain = AESGCM(self._dec_key).decrypt(nonce, ct, None)
                except Exception:
                    self._eof = True
                    break
                self._dec_buf += plain
            except socket.timeout:
                break
        if self._dec_buf:
            out, self._dec_buf = self._dec_buf[:n], self._dec_buf[n:]
            return out
        if self._eof:
            return b""
        raise socket.timeout


def connect(server_host: str, server_port: int, user_id: str,
            target_host: str, target_port: int,
            tls: bool = False, sni: str = "", verify_cert: bool = False,
            timeout: int = 15):
    """连到 VMess 服务器，建立到目标站点的加密通道。"""
    if not user_id:
        raise ValueError("缺少 VMess UUID")
    cmd_key = _cmd_key(user_id)
    body_key = os.urandom(16)
    body_iv = os.urandom(16)
    response_header = os.urandom(1)[0]

    s = socket.create_connection((server_host, server_port), timeout=timeout)
    s.settimeout(timeout)
    if tls:
        ctx = ssl.create_default_context()
        if not verify_cert:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        try:
            s = ctx.wrap_socket(s, server_hostname=sni or server_host)
        except Exception:
            try:
                s.close()
            except Exception:
                pass
            raise
    return VMessStream(s, cmd_key, body_key, body_iv, response_header,
                       target_host, target_port)
