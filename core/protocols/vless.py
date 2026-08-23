# -*- coding: utf-8 -*-
"""VLess 协议客户端（v0 明文版，兼容 Xray / V2Ray 的 'none' 加密）。

请求头结构：
- version(1) = 0
- UUID(16)
- addons 长度(1) = 0
- command(1) = 1 (TCP)
- 目标地址（v2ray 风格 port(2)+atyp(1)+addr）

加密方式为 "none"（明文），可选外层 TLS（security=tls）。

返回的 VLessStream 实现 fileno/recv/sendall 等接口，可被管道复用。
"""
import socket
import ssl
import uuid as uuid_mod

from .base import TunnelStream
from .addr import encode_v2ray_addr


class VLessStream(TunnelStream):
    def __init__(self, sock, user_id: str, target_host: str, target_port: int):
        super().__init__(sock)
        uid = uuid_mod.UUID(user_id).bytes  # 16 字节
        header = bytes([0])                 # version = 0
        header += uid
        header += b"\x00"                   # addons 长度 = 0
        header += bytes([1])                # command = 1 (TCP)
        header += encode_v2ray_addr(target_host, target_port)
        self._sock.sendall(header)
        self._dec_buf = b""

    def sendall(self, data):
        self._sock.sendall(data)

    def recv(self, n):
        while len(self._dec_buf) < n and not self._eof:
            try:
                chunk = self._sock.recv(8192)
            except socket.timeout:
                break
            if not chunk:
                self._eof = True
                break
            self._dec_buf += chunk
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
    """连到 VLess 服务器，建立到目标站点的通道（可加 TLS）。"""
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
    return VLessStream(s, user_id, target_host, target_port)
