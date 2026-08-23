# -*- coding: utf-8 -*-
"""Trojan 协议客户端。

Trojan 通过标准 TLS 与服务器握手，随后发送明文控制头：
- 56 位十六进制 SHA224(password) + CRLF
- 目标地址（SOCKS5 格式 atyp+addr+port）+ CRLF
- 之后为原始数据流（TLS 已加密）。

返回的 TrojanStream 实现 fileno/recv/sendall 等接口，可被管道复用。
"""
import socket
import ssl
import hashlib

from .base import TunnelStream
from .addr import encode_socks_addr


class TrojanStream(TunnelStream):
    def __init__(self, sock, password: str, target_host: str, target_port: int):
        super().__init__(sock)
        head = hashlib.sha224(password.encode("utf-8")).hexdigest().encode() + b"\r\n"
        head += encode_socks_addr(target_host, target_port) + b"\r\n"
        self._sock.sendall(head)
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


def connect(server_host: str, server_port: int, password: str,
            target_host: str, target_port: int,
            sni: str = "", verify_cert: bool = False, timeout: int = 15):
    """连到 Trojan 服务器，建立到目标站点的 TLS 加密通道。"""
    s = socket.create_connection((server_host, server_port), timeout=timeout)
    s.settimeout(timeout)
    ctx = ssl.create_default_context()
    if not verify_cert:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    try:
        ssock = ctx.wrap_socket(s, server_hostname=sni or server_host)
    except Exception:
        try:
            s.close()
        except Exception:
            pass
        raise
    return TrojanStream(ssock, password, target_host, target_port)
