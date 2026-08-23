# -*- coding: utf-8 -*-
"""Shadowsocks 协议客户端（兼容 shadowsocks-libev）。

支持两类加密方式：
- AEAD 加密：aes-128-gcm / aes-192-gcm / aes-256-gcm / chacha20-ietf-poly1305
- 经典 stream 加密：rc4-md5 / aes-128-cfb / aes-192-cfb / aes-256-cfb / chacha20-ietf

密钥派生：
- AEAD：master_key = EVP_BytesToKey(password, key_len)
         subkey     = HKDF-SHA1(master_key, salt, "ss-subkey", key_len)
         初始 nonce 为 12 字节零，每次 AEAD 操作后按大端计数器 +1
- stream：rc4-md5 的 key = MD5(password + iv)；其余为 EVP_BytesToKey
          响应 IV = SHA256(请求 IV)[:iv_len]

返回的 CipherStream 实现了 fileno/recv/sendall/send/close/shutdown/settimeout，
可直接用于线程管道（recv 区分超时与 EOF）。
"""
import os
import socket
import hashlib
import hmac
import struct

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM, ChaCha20Poly1305

from .base import TunnelStream
from .addr import encode_socks_addr

# ---------------- 加密方式表 ----------------

AEAD_CIPHERS = {
    "aes-128-gcm":              (16, 16, "gcm"),
    "aes-192-gcm":              (24, 24, "gcm"),
    "aes-256-gcm":              (32, 32, "gcm"),
    "chacha20-ietf-poly1305":   (32, 32, "chacha"),
    "xchacha20-ietf-poly1305":  (32, 32, "xchacha"),
}

STREAM_CIPHERS = {
    "rc4-md5":        (16, 16, "rc4"),
    "aes-128-cfb":    (16, 16, "cfb"),
    "aes-192-cfb":    (24, 16, "cfb"),
    "aes-256-cfb":    (32, 16, "cfb"),
    "chacha20-ietf":  (32, 12, "chacha20"),
}

SUPPORTED_METHODS = set(AEAD_CIPHERS) | set(STREAM_CIPHERS)

_MASTER_INFO = b"ss-subkey"


def is_supported(method: str) -> bool:
    return (method or "").lower() in SUPPORTED_METHODS


# ---------------- 密钥派生 ----------------

def _evp_bytes_to_key(password: bytes, key_len: int) -> bytes:
    d = b""
    prev = b""
    while len(d) < key_len:
        prev = hashlib.md5(prev + password).digest()
        d += prev
    return d[:key_len]


def _hkdf_sha1(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha1).digest()
    okm = b""
    t = b""
    i = 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha1).digest()
        okm += t
        i += 1
    return okm[:length]


# ---------------- 通用流式包装 ----------------

class CipherStream(TunnelStream):
    """select / 线程友好的加密流包装。"""

    def _recv_exact(self, n):
        data = b""
        while len(data) < n:
            chunk = self._sock.recv(n - len(data))  # socket.timeout 原样抛出
            if not chunk:  # EOF
                break
            data += chunk
        return data


# ---------------- AEAD 实现 ----------------

class AEADStream(CipherStream):
    CHUNK = 0x3FFF
    TAG = 16

    def __init__(self, sock, method: str, password: bytes,
                 target_host: str, target_port: int):
        super().__init__(sock)
        self._method = method
        self._key_len, self._salt_len, self._kind = AEAD_CIPHERS[method]

        master = _evp_bytes_to_key(password, self._key_len)
        # 发送方向：随机 salt + HKDF subkey
        self._enc_salt = os.urandom(self._salt_len)
        self._enc_key = _hkdf_sha1(master, self._enc_salt, _MASTER_INFO, self._key_len)
        self._enc_nonce = 0
        self._sock.sendall(self._enc_salt + self._aead_enc(
            self._enc_key, self._enc_nonce, encode_socks_addr(target_host, target_port)))
        self._enc_nonce += 1  # 地址块占用一次 nonce

        # 接收方向：先读到服务器的 salt 再派生 subkey
        self._master = master
        self._dec_buf = b""
        self._dec_key = None
        self._dec_nonce = 0
        self._dec_pending = 0  # 0 = 等待长度头, >0 = 等待 payload

    # --- 工具 ---
    @staticmethod
    def _nonce(i):
        return i.to_bytes(12, "big")

    def _aead_enc(self, key, nonce_i, plain):
        nonce = self._nonce(nonce_i)
        if self._kind == "chacha":
            return ChaCha20Poly1305(key).encrypt(nonce, plain, None)
        if self._kind == "xchacha":
            return ChaCha20Poly1305(key).encrypt(nonce + b"\x00" * 12, plain, None)
        return AESGCM(key).encrypt(nonce, plain, None)

    def _aead_dec(self, key, nonce_i, data):
        nonce = self._nonce(nonce_i)
        try:
            if self._kind == "chacha":
                return ChaCha20Poly1305(key).decrypt(nonce, data, None)
            if self._kind == "xchacha":
                return ChaCha20Poly1305(key).decrypt(nonce + b"\x00" * 12, data, None)
            return AESGCM(key).decrypt(nonce, data, None)
        except Exception:
            return None

    # --- 发送 ---
    def sendall(self, data):
        while data:
            chunk, data = data[:self.CHUNK], data[self.CHUNK:]
            ln = struct.pack("<H", len(chunk))
            c = self._aead_enc(self._enc_key, self._enc_nonce, ln)
            self._enc_nonce += 1
            c += self._aead_enc(self._enc_key, self._enc_nonce, chunk)
            self._enc_nonce += 1
            self._sock.sendall(c)

    # --- 接收 ---
    def recv(self, n):
        while len(self._dec_buf) < n and not self._eof:
            try:
                if self._dec_key is None:
                    salt = self._recv_exact(self._salt_len)
                    if len(salt) < self._salt_len:
                        self._eof = True
                        break
                    self._dec_key = _hkdf_sha1(self._master, salt, _MASTER_INFO, self._key_len)
                    self._dec_nonce = 0
                    self._dec_pending = 0
                if self._dec_pending == 0:
                    ln_ct = self._recv_exact(2 + self.TAG)
                    if len(ln_ct) < 2 + self.TAG:
                        self._eof = True
                        break
                    ln_plain = self._aead_dec(self._dec_key, self._dec_nonce, ln_ct)
                    self._dec_nonce += 1
                    if ln_plain is None or len(ln_plain) != 2:
                        self._eof = True
                        break
                    plen = struct.unpack("<H", ln_plain)[0]
                    if plen > self.CHUNK:
                        self._eof = True
                        break
                    self._dec_pending = plen
                pl_ct = self._recv_exact(self._dec_pending + self.TAG)
                if len(pl_ct) < self._dec_pending + self.TAG:
                    self._eof = True
                    break
                plain = self._aead_dec(self._dec_key, self._dec_nonce, pl_ct)
                self._dec_nonce += 1
                self._dec_pending = 0
                if plain is None:
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


# ---------------- 经典 stream 实现 ----------------

def _new_stream_cipher(method: str, key: bytes, iv: bytes):
    """返回 (encryptor, decryptor)。"""
    if method == "rc4-md5":
        enc = Cipher(algorithms.ARC4(key), None).encryptor()
        dec = Cipher(algorithms.ARC4(key), None).decryptor()
        return enc, dec
    if method == "chacha20-ietf":
        nonce = iv + b"\x00" * 4
        enc = Cipher(algorithms.ChaCha20(key, nonce), None).encryptor()
        return enc, enc  # chacha 为对称流
    # AES-CFB
    algo = algorithms.AES(key)
    enc = Cipher(algo, modes.CFB(iv)).encryptor()
    dec = Cipher(algo, modes.CFB(iv)).decryptor()
    return enc, dec


class StreamCipherStream(CipherStream):
    def __init__(self, sock, method: str, password: bytes,
                 target_host: str, target_port: int):
        super().__init__(sock)
        self._method = method
        self._key_len, self._iv_len, _ = STREAM_CIPHERS[method]
        self._iv = os.urandom(self._iv_len)

        if method == "rc4-md5":
            req_key = hashlib.md5(password + self._iv).digest()
        else:
            req_key = _evp_bytes_to_key(password, self._key_len)
        enc, _ = _new_stream_cipher(method, req_key, self._iv)

        # 响应 IV = SHA256(请求 IV)[:iv_len]
        resp_iv = hashlib.sha256(self._iv).digest()[:self._iv_len]
        if method == "rc4-md5":
            resp_key = hashlib.md5(password + resp_iv).digest()
        else:
            resp_key = req_key
        _, dec = _new_stream_cipher(method, resp_key, resp_iv)

        self._enc = enc
        self._dec = dec
        self._dec_buf = b""
        addr = encode_socks_addr(target_host, target_port)
        self._sock.sendall(self._iv + self._enc.update(addr) + self._enc.finalize())

    def sendall(self, data):
        self._sock.sendall(self._enc.update(data) + self._enc.finalize())

    def recv(self, n):
        while len(self._dec_buf) < n and not self._eof:
            try:
                chunk = self._sock.recv(8192)
            except socket.timeout:
                break
            if not chunk:
                self._eof = True
                break
            self._dec_buf += self._dec.update(chunk) + self._dec.finalize()
        if self._dec_buf:
            out, self._dec_buf = self._dec_buf[:n], self._dec_buf[n:]
            return out
        if self._eof:
            return b""
        raise socket.timeout


# ---------------- 对外入口 ----------------

def connect(server_host: str, server_port: int, method: str, password: str,
            target_host: str, target_port: int, timeout: int = 15):
    """连到 SS 服务器，建立到目标站点的加密通道。

    返回 CipherStream（实现了 fileno/recv/sendall 等，可被管道复用）。
    """
    method = (method or "aes-256-gcm").lower()
    if method not in SUPPORTED_METHODS:
        raise ValueError(f"不支持的加密方式: {method}")
    pwd = password.encode("utf-8") if isinstance(password, str) else password
    s = socket.create_connection((server_host, server_port), timeout=timeout)
    s.settimeout(timeout)
    try:
        if method in AEAD_CIPHERS:
            return AEADStream(s, method, pwd, target_host, target_port)
        return StreamCipherStream(s, method, pwd, target_host, target_port)
    except Exception:
        try:
            s.close()
        except Exception:
            pass
        raise
