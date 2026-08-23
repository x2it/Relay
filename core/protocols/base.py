# -*- coding: utf-8 -*-
"""协议无关的流式基类与公共工具。

所有加密协议（SS / Trojan / VLess / VMess）返回的“流”都实现同一套接口，
供本地代理的线程管道复用：

- fileno()    : 底层 socket 的 fd
- recv(n)     : 返回最多 n 字节已解密数据（阻塞至有数据或超时）
- sendall(d)  : 发送加密数据
- send(d)     : 同上，返回字节数
- close() / shutdown(how) / settimeout(t)

recv 语义约定（供管道区分“超时”与“连接结束”）：
- 超时且无缓冲数据：抛出 socket.timeout（管道据此继续等待）
- 真正 EOF：返回 b""
"""
import socket


class TunnelStream:
    """加密隧道流的基类。"""

    def __init__(self, sock):
        self._sock = sock
        self._eof = False

    # ----- 委托给底层 socket -----

    def fileno(self):
        return self._sock.fileno()

    def settimeout(self, t):
        self._sock.settimeout(t)

    def gettimeout(self):
        return self._sock.gettimeout()

    def close(self):
        try:
            self._sock.close()
        except Exception:
            pass

    def shutdown(self, how):
        try:
            self._sock.shutdown(how)
        except Exception:
            pass

    # ----- 读辅助 -----

    def _recv_exact(self, n):
        """精确读取 n 字节。遇 socket.timeout 原样抛出；EOF 则返回已读部分。"""
        data = b""
        while len(data) < n:
            chunk = self._sock.recv(n - len(data))
            if not chunk:  # EOF
                break
            data += chunk
        return data

    # ----- 由子类实现 -----

    def recv(self, n):
        raise NotImplementedError

    def sendall(self, data):
        raise NotImplementedError

    def send(self, data):
        self.sendall(data)
        return len(data)
