# -*- coding: utf-8 -*-
"""在任意 TunnelStream 之上叠加 TLS（MemoryBIO 双缓冲）。

用途：验证加密代理（SS / VMess / VLess / Trojan）时，通过隧道与目标站点
（如 www.google.com）完成 TLS 握手，确认整条链路可正常访问 HTTPS。

实现要点：
- 使用 ssl.MemoryBIO 与 SSLObject，手动在"密文流"与"TLS 内部缓冲"之间搬运数据，
  从而避免 ssl.wrap_socket 直接基于底层 fd 重建裸 socket（那会绕过加密）。
- 返回的 TLSStream 同样实现 fileno/recv/sendall 等接口，可被管道复用。
"""
import socket
import ssl

from .base import TunnelStream


class TLSStream(TunnelStream):
    """在底层隧道流之上封装的 TLS 流。"""

    def __init__(self, stream, obj, in_bio, out_bio):
        super().__init__(stream._sock)
        self._stream = stream
        self._obj = obj
        self._in_bio = in_bio
        self._out_bio = out_bio
        self._dec_buf = b""

    def _flush_out(self):
        try:
            enc = self._out_bio.read()
            if enc:
                self._stream.sendall(enc)
        except Exception:
            pass

    def sendall(self, data):
        self._obj.write(data)
        self._flush_out()

    def recv(self, n):
        while len(self._dec_buf) < n and not self._eof:
            try:
                data = self._obj.read(n)
                if data:
                    self._dec_buf += data
                    continue
                if data == b"":
                    # close_notify / EOF
                    self._eof = True
                    break
                # data is None → 无明文可读，继续读密文
            except ssl.SSLWantReadError:
                pass
            except ssl.SSLWantWriteError:
                self._flush_out()
                continue
            # 需要更多密文
            try:
                raw = self._stream.recv(8192)
            except socket.timeout:
                break
            if not raw:
                self._eof = True
                break
            self._in_bio.write(raw)
            self._flush_out()
        if self._dec_buf:
            out, self._dec_buf = self._dec_buf[:n], self._dec_buf[n:]
            return out
        if self._eof:
            return b""
        raise socket.timeout


def tls_over_stream(stream, host, timeout=10, verify_cert=False):
    """在 stream 上完成 TLS 握手，返回 TLSStream。失败抛 ConnectionError。"""
    ctx = ssl.create_default_context()
    if not verify_cert:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    in_bio = ssl.MemoryBIO()
    out_bio = ssl.MemoryBIO()
    obj = ctx.wrap_bio(in_bio, out_bio, server_side=False, server_hostname=host)
    stream.settimeout(timeout)

    done = False
    while not done:
        try:
            obj.do_handshake()
            done = True
        except ssl.SSLWantReadError:
            data = out_bio.read()
            if data:
                stream.sendall(data)
            try:
                chunk = stream.recv(8192)
            except socket.timeout:
                raise ConnectionError("TLS 握手超时")
            if not chunk:
                raise ConnectionError("TLS 握手期间隧道断开")
            in_bio.write(chunk)
        except ssl.SSLWantWriteError:
            data = out_bio.read()
            if data:
                stream.sendall(data)

    tls = TLSStream(stream, obj, in_bio, out_bio)
    tls._flush_out()
    return tls
