# -*- coding: utf-8 -*-
"""本地代理转发服务器。

工作原理：
- 本地起一个 HTTP 代理端口（默认 127.0.0.1:8888）。
- 客户端（浏览器/系统代理）把请求发到本端口。
- 普通HTTP请求：本地代理解析后通过"上游代理"转发。
- HTTPS请求：客户端发 CONNECT，本地代理让上游代理建立 TCP 隧道，
  之后两端双向转发原始字节——流量由目标站点 TLS 端到端加密，
  上游代理和本地代理都看不到明文，即"加密访问"。
- 上游代理可选 http / socks5 协议；当前代理失败可自动切换。
- 可选 Token 鉴权：防止局域网他人蹭用本地端口。
"""
import socket
import select
import threading
import time
import secrets
import atexit
import sys
from typing import List, Dict, Optional, Callable

from config import (DEFAULT_LOCAL_HOST, DEFAULT_LOCAL_PORT,
                    PROXY_MODE_GLOBAL, PROXY_MODE_SMART, PROXY_MODE_DIRECT)


# ---------- Windows 系统代理管理（两阶段提交 + 必达恢复） ----------

class SystemProxyManager:
    """安全地开关 Windows 系统代理。

    核心原则：两阶段提交 + 完整备份 + 必达恢复。
    - 写入前完整备份所有相关注册表键值。
    - 只有确认本地代理端口在监听时才开启系统代理。
    - 进程退出/异常时必达恢复（atexit + try/finally）。
    - 提供紧急恢复入口：一键关闭系统代理并恢复原值。
    """

    KEY_PATH = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"

    def __init__(self):
        self._backup = None  # {ProxyEnable, ProxyServer, ProxyOverride, AutoConfigURL}
        self._applied = False  # 当前是否由本程序接管

    def backup(self):
        """备份当前系统代理设置。"""
        import winreg
        try:
            k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, self.KEY_PATH, 0, winreg.KEY_READ)
            data = {}
            for val in ("ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL"):
                try:
                    v, _ = winreg.QueryValueEx(k, val)
                    data[val] = v
                except FileNotFoundError:
                    data[val] = None
            winreg.CloseKey(k)
            self._backup = data
            return data
        except Exception:
            self._backup = {"ProxyEnable": None, "ProxyServer": None,
                            "ProxyOverride": None, "AutoConfigURL": None}
            return self._backup

    def _notify_changed(self):
        """通知 Windows 代理设置已变更。"""
        import ctypes
        w = ctypes.windll.wininet
        w.InternetSetOptionW(None, 39, None, 0)  # INTERNET_OPTION_SETTINGS_CHANGED
        w.InternetSetOptionW(None, 37, None, 0)  # INTERNET_OPTION_REFRESH

    def enable(self, host: str, port: int) -> bool:
        """开启系统代理指向本地代理。必须先确认本地代理在监听。"""
        import winreg
        # 两阶段：确认端口在监听
        try:
            s = socket.create_connection((host, port), timeout=3)
            s.close()
        except Exception:
            return False  # 本地代理没起来，绝不改系统代理

        self.backup()
        proxy_str = f"http={host}:{port};https={host}:{port}"
        try:
            k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, self.KEY_PATH, 0, winreg.KEY_ALL_ACCESS)
            winreg.SetValueEx(k, "ProxyEnable", 0, winreg.REG_DWORD, 1)
            winreg.SetValueEx(k, "ProxyServer", 0, winreg.REG_SZ, proxy_str)
            # 清空 ProxyOverride，确保所有流量都走代理
            winreg.SetValueEx(k, "ProxyOverride", 0, winreg.REG_SZ, "")
            winreg.CloseKey(k)
            self._applied = True
            self._notify_changed()
            # 注册必达恢复
            atexit.register(self.restore)
            return True
        except Exception:
            self.restore()  # 写失败立即恢复
            return False

    def restore(self):
        """恢复系统代理到备份状态（紧急恢复也走这里）。"""
        import winreg
        if not self._backup:
            # 没有备份也强制关闭代理（最安全）
            try:
                k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, self.KEY_PATH, 0, winreg.KEY_ALL_ACCESS)
                winreg.SetValueEx(k, "ProxyEnable", 0, winreg.REG_DWORD, 0)
                winreg.CloseKey(k)
                self._notify_changed()
            except Exception:
                pass
            return

        try:
            k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, self.KEY_PATH, 0, winreg.KEY_ALL_ACCESS)
            # 逐项恢复
            if self._backup.get("ProxyEnable") is not None:
                winreg.SetValueEx(k, "ProxyEnable", 0, winreg.REG_DWORD, self._backup["ProxyEnable"])
            else:
                try:
                    winreg.DeleteValue(k, "ProxyEnable")
                except Exception:
                    pass

            if self._backup.get("ProxyServer") is not None:
                winreg.SetValueEx(k, "ProxyServer", 0, winreg.REG_SZ, self._backup["ProxyServer"])
            else:
                try:
                    winreg.DeleteValue(k, "ProxyServer")
                except Exception:
                    pass

            if self._backup.get("ProxyOverride") is not None:
                winreg.SetValueEx(k, "ProxyOverride", 0, winreg.REG_SZ, self._backup["ProxyOverride"])
            else:
                try:
                    winreg.DeleteValue(k, "ProxyOverride")
                except Exception:
                    pass

            if self._backup.get("AutoConfigURL") is not None:
                winreg.SetValueEx(k, "AutoConfigURL", 0, winreg.REG_SZ, self._backup["AutoConfigURL"])
            else:
                try:
                    winreg.DeleteValue(k, "AutoConfigURL")
                except Exception:
                    pass

            winreg.CloseKey(k)
            self._applied = False
            self._notify_changed()
        except Exception:
            pass

    def emergency_restore(self):
        """紧急恢复网络：强制关闭系统代理 + 恢复原值。"""
        self.restore()
        self._applied = False

    @property
    def is_applied(self) -> bool:
        return self._applied


# ---------- 规则引擎：判断域名直连还是走代理 ----------

class RuleEngine:
    """根据域名清单决定 direct / proxy。"""

    def __init__(self, mode: str = PROXY_MODE_SMART,
                 direct_domains: Optional[List[str]] = None,
                 proxy_domains: Optional[List[str]] = None):
        self.mode = mode
        # 转成小写后缀集合，便于匹配
        self._direct = {d.lower().lstrip(".") for d in (direct_domains or [])}
        self._proxy = {d.lower().lstrip(".") for d in (proxy_domains or [])}

    def update(self, mode: str = None, direct_domains: List[str] = None,
               proxy_domains: List[str] = None):
        if mode is not None:
            self.mode = mode
        if direct_domains is not None:
            self._direct = {d.lower().lstrip(".") for d in direct_domains}
        if proxy_domains is not None:
            self._proxy = {d.lower().lstrip(".") for d in proxy_domains}

    @staticmethod
    def _host_base(host: str) -> str:
        h = (host or "").lower().strip()
        # 去端口
        if ":" in h:
            h = h.split(":", 1)[0]
        return h

    def _matches(self, host: str, domains: set) -> bool:
        h = self._host_base(host)
        if not h:
            return False
        # IP 地址直接跳过域名匹配
        if all(p.isdigit() for p in h.split(".")) and h.count(".") == 3:
            return False
        # 命中：host 等于该项，或以 .该项 结尾
        for d in domains:
            if h == d or h.endswith("." + d):
                return True
        return False

    def decide(self, host: str) -> str:
        """返回 'direct' 或 'proxy'。"""
        if self.mode == PROXY_MODE_GLOBAL:
            return "proxy"
        if self.mode == PROXY_MODE_DIRECT:
            return "direct"
        # smart：强制代理清单优先，其次直连清单，其余默认走代理
        if self._matches(host, self._proxy):
            return "proxy"
        if self._matches(host, self._direct):
            return "direct"
        # 未知域名默认走代理（智能模式的核心：海外走代理）
        return "proxy"


# ---------- SOCKS5 上游握手（无第三方依赖） ----------

def _socks5_connect(upstream_sock: socket.socket, host: str, port: int) -> bool:
    try:
        # 握手：无需认证
        upstream_sock.sendall(b"\x05\x01\x00")
        resp = upstream_sock.recv(2)
        if len(resp) < 2 or resp[0] != 0x05 or resp[1] != 0x00:
            return False
        # 请求连接
        try:
            ip = socket.inet_aton(host)
            req = b"\x05\x01\x00\x01" + ip + port.to_bytes(2, "big")
        except OSError:
            hb = host.encode("idna")
            req = b"\x05\x01\x00\x03" + bytes([len(hb)]) + hb + port.to_bytes(2, "big")
        upstream_sock.sendall(req)
        # 读取完整 SOCKS5 响应
        def _recv_exact(n):
            data = b""
            while len(data) < n:
                chunk = upstream_sock.recv(n - len(data))
                if not chunk:
                    break
                data += chunk
            return data
        rep = _recv_exact(4)
        if len(rep) < 2 or rep[1] != 0x00:
            return False
        atyp = rep[3] if len(rep) >= 4 else 0x01
        if atyp == 0x01:  # IPv4
            _recv_exact(6)
        elif atyp == 0x03:  # Domain
            dlen_data = _recv_exact(1)
            if dlen_data:
                dlen = dlen_data[0]
                _recv_exact(dlen + 2)
        elif atyp == 0x04:  # IPv6
            _recv_exact(18)
        return True
    except Exception:
        return False


# ---------- 上游代理选择 ----------

class ProxyRotator:
    """从存活代理列表中挑选并轮换上游代理。"""

    def __init__(self, get_proxies: Callable[[], List[Dict]],
                 auto_switch: bool = True,
                 max_latency_ms: int = 3000):
        self._get = get_proxies
        self._lock = threading.Lock()
        self._current: Optional[Dict] = None
        self._idx = 0
        self._idx_https = 0
        self.auto_switch = auto_switch
        self.max_latency_ms = max_latency_ms
        self._fail_count = 0
        self._locked: Optional[Dict] = None   # 锁定的代理（优先级最高，不自动切换）

    def lock(self, proxy: Optional[Dict]):
        """锁定指定代理；传 None 解除锁定。"""
        with self._lock:
            self._locked = proxy
            if proxy is not None:
                self._current = proxy

    def is_locked(self) -> bool:
        with self._lock:
            return self._locked is not None

    def _candidates(self) -> List[Dict]:
        alive = [p for p in self._get()
                 if p.get("alive") and p.get("latency_ms", 99999) <= self.max_latency_ms]
        # HTTPS-capable 优先，然后按低延迟
        alive.sort(key=lambda p: (not p.get("https_ok", False),
                                  p.get("latency_ms", 99999)))
        return alive

    def _https_candidates(self) -> List[Dict]:
        """仅返回 HTTPS 可用代理。"""
        alive = [p for p in self._get()
                 if p.get("alive") and p.get("https_ok")
                 and p.get("latency_ms", 99999) <= self.max_latency_ms]
        alive.sort(key=lambda p: p.get("latency_ms", 99999))
        return alive

    def current(self) -> Optional[Dict]:
        with self._lock:
            if self._current and self._current.get("alive"):
                return self._current
            cands = self._candidates()
            if not cands:
                return None
            self._idx %= len(cands)
            self._current = cands[self._idx]
            return self._current

    def pick(self) -> Optional[Dict]:
        """取一个可用上游。锁定优先。"""
        with self._lock:
            if self._locked is not None:
                return self._locked
            cands = self._candidates()
            if not cands:
                return self._current
            if self._current in cands:
                return self._current
            self._idx %= len(cands)
            self._current = cands[self._idx]
            return self._current

    def pick_https(self) -> Optional[Dict]:
        """取一个 HTTPS 可用上游（用于 CONNECT 隧道）。"""
        with self._lock:
            if self._locked is not None:
                return self._locked
            cands = self._https_candidates()
            if not cands:
                # 没有 HTTPS 可用代理，回退到全部可用
                all_cands = self._candidates()
                if not all_cands:
                    return self._current
                self._idx %= len(all_cands)
                self._current = all_cands[self._idx]
                return self._current
            self._idx_https %= len(cands)
            self._current = cands[self._idx_https]
            return self._current

    def rotate(self) -> Optional[Dict]:
        """主动切换到下一个。锁定时无效。"""
        with self._lock:
            if self._locked is not None:
                return self._locked
            cands = self._candidates()
            if not cands:
                return self._current
            self._idx = (self._idx + 1) % len(cands)
            self._current = cands[self._idx]
            return self._current

    def rotate_https(self) -> Optional[Dict]:
        """在 HTTPS 可用代理中切换。"""
        with self._lock:
            if self._locked is not None:
                return self._locked
            cands = self._https_candidates()
            if not cands:
                # 回退到全部
                all_cands = self._candidates()
                if not all_cands:
                    return self._current
                self._idx = (self._idx + 1) % len(all_cands)
                self._current = all_cands[self._idx]
                return self._current
            self._idx_https = (self._idx_https + 1) % len(cands)
            self._current = cands[self._idx_https]
            return self._current

    def report_fail(self, proxy: Dict):
        with self._lock:
            self._fail_count += 1
            # 标记为 HTTPS 不可用（如果 CONNECT 失败）
            if proxy and proxy.get("https_ok"):
                # 不直接修改 https_ok（验证结果），但可以降权
                pass
            # 锁定模式下不自动切换
            if self._locked is not None:
                return
            if self.auto_switch:
                self._idx += 1
                cands = self._candidates()
                if cands:
                    self._idx %= len(cands)
                    self._current = cands[self._idx]


# ---------- 隧道双向转发 ----------

def _pipe(a: socket.socket, b: socket.socket):
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 60)
            if not r:
                continue
            for s in r:
                data = s.recv(8192)
                if not data:
                    return
                if s is a:
                    b.sendall(data)
                else:
                    a.sendall(data)
    except Exception:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                s.close()
            except Exception:
                pass


# ---------- 本地代理服务器 ----------

class LocalProxyServer:
    def __init__(self, rotator: ProxyRotator,
                 host: str = DEFAULT_LOCAL_HOST,
                 port: int = DEFAULT_LOCAL_PORT,
                 auth_token: str = "",
                 rule_engine: Optional[RuleEngine] = None):
        self.rotator = rotator
        self.host = host
        self.port = port
        self.auth_token = auth_token
        self.rule_engine = rule_engine or RuleEngine(mode=PROXY_MODE_SMART)
        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self.stats = {"requests": 0, "success": 0, "fail": 0,
                      "direct": 0, "proxy": 0}

    # ----- 鉴权 -----
    def _check_auth(self, header_bytes: bytes) -> bool:
        if not self.auth_token:
            return True
        head = header_bytes.split(b"\r\n", 1)[0].decode("latin-1", "ignore")
        # 简单校验 Proxy-Authorization 或自定义 X-LiteProxy-Token
        for line in header_bytes.split(b"\r\n"):
            try:
                line_s = line.decode("latin-1")
            except Exception:
                continue
            if line_s.lower().startswith("x-liteproxy-token:"):
                if secrets.compare_digest(line_s.split(":", 1)[1].strip(),
                                           self.auth_token):
                    return True
            if line_s.lower().startswith("proxy-authorization:"):
                # Bearer <token>
                val = line_s.split(":", 1)[1].strip()
                if val.lower().startswith("bearer "):
                    val = val[7:].strip()
                if secrets.compare_digest(val, self.auth_token):
                    return True
        return False

    # ----- 处理一个客户端连接 -----
    def _handle(self, client: socket.socket, addr):
        try:
            client.settimeout(15)
            first = b""
            while b"\r\n\r\n" not in first and len(first) < 8192:
                chunk = client.recv(4096)
                if not chunk:
                    return
                first += chunk
            if not first:
                return

            # 鉴权
            if not self._check_auth(first):
                client.sendall(b"HTTP/1.1 407 Proxy Authentication Required\r\n"
                               b"Proxy-Authenticate: Bearer\r\n\r\n")
                return

            head_line = first.split(b"\r\n", 1)[0].decode("latin-1", "ignore")
            parts = head_line.split()
            if len(parts) < 3:
                return
            method, target, _ = parts[0], parts[1], parts[2]
            self.stats["requests"] += 1

            # ----- HTTPS CONNECT -----
            if method.upper() == "CONNECT":
                host, _, port_s = target.partition(":")
                port = int(port_s or "443")
                # 智能分流：直连 or 代理
                if self.rule_engine.decide(host) == "direct":
                    up_sock = self._connect_direct(host, port)
                    if not up_sock:
                        client.sendall(b"HTTP/1.1 502 Direct Connect Failed\r\n\r\n")
                        self.stats["fail"] += 1
                        return
                    client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                    self.stats["success"] += 1
                    self.stats["direct"] += 1
                    _pipe(client, up_sock)
                    return
                # CONNECT 隧道：优先使用 HTTPS 可用代理
                upstream = self.rotator.pick_https()
                if not upstream:
                    # 无任何代理可用
                    client.sendall(b"HTTP/1.1 502 No Available Upstream\r\n\r\n")
                    self.stats["fail"] += 1
                    return
                ok = None
                max_retries = 5  # 最多尝试 5 个代理
                tried_ips = set()
                for attempt in range(max_retries):
                    if not upstream:
                        break
                    # 跳过已尝试的代理
                    ip_key = f"{upstream.get('ip','')}:{upstream.get('port','')}"
                    if ip_key in tried_ips:
                        # 找下一个不同的代理
                        upstream = self.rotator.rotate_https()
                        continue
                    tried_ips.add(ip_key)
                    ok = self._open_upstream_tunnel(upstream, host, port)
                    if ok:
                        break
                    # 此代理失败，标记并尝试下一个
                    upstream["https_ok"] = False
                    self.rotator.report_fail(upstream)
                    upstream = self.rotator.rotate_https()
                if not ok:
                    # 所有代理都失败了，尝试直连（最后的手段）
                    direct_sock = self._connect_direct(host, port)
                    if direct_sock:
                        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                        self.stats["success"] += 1
                        self.stats["direct"] += 1
                        header_end = first.find(b"\r\n\r\n") + 4
                        leftover = first[header_end:]
                        if leftover:
                            try:
                                direct_sock.sendall(leftover)
                            except Exception:
                                pass
                        _pipe(client, direct_sock)
                        return
                    client.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                    self.stats["fail"] += 1
                    return
                client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                self.stats["success"] += 1
                self.stats["proxy"] += 1
                up_sock = ok
                # 关键: first 中可能包含了客户端提前发送的 TLS 数据
                # 需要将其转发到 upstream 而非丢弃
                # 计算 CONNECT 请求的长度 (包含 \r\n\r\n)
                header_end = first.find(b"\r\n\r\n") + 4
                leftover = first[header_end:]  # CONNECT 请求之后的残留数据
                if leftover:
                    # 有残留数据 (客户端提前发送的 TLS 握手)
                    # 直接将其转发到 upstream
                    try:
                        up_sock.sendall(leftover)
                    except Exception:
                        pass
                _pipe(client, up_sock)
                return

            # ----- 普通 HTTP 请求 -----
            # 解析目标 host:port
            host_port = target
            if "://" in target:
                # 绝对 URI: http://host/path
                from urllib.parse import urlsplit
                u = urlsplit(target)
                host = u.hostname or ""
                port = u.port or 80
            else:
                # 已经是 host:port 形式
                host, _, port_s = target.partition(":")
                port = int(port_s or "80")

            # 智能分流判断
            is_direct = (self.rule_engine.decide(host) == "direct")

            # 直连模式：需要把绝对URI改成相对路径再发送
            if is_direct and "://" in target:
                from urllib.parse import urlsplit
                u = urlsplit(target)
                rel = u.path or "/"
                if u.query:
                    rel += "?" + u.query
                first_line = head_line.split(" ", 2)
                first_line[1] = rel
                new_head_line = " ".join(first_line)
                first = (new_head_line + "\r\n").encode("latin-1") + first.split(b"\r\n", 1)[1]

            if is_direct:
                up_sock = self._connect_direct(host, port)
                if not up_sock:
                    client.sendall(b"HTTP/1.1 502 Direct Connect Failed\r\n\r\n")
                    self.stats["fail"] += 1
                    return
                up_sock.sendall(first)
                self.stats["success"] += 1
                self.stats["direct"] += 1
                _pipe(client, up_sock)
                return
            # 走代理模式
            upstream = self.rotator.pick()
            if not upstream:
                client.sendall(b"HTTP/1.1 502 No Available Upstream\r\n\r\n")
                self.stats["fail"] += 1
                return
            # SOCKS5 代理：隧道已连到目标，需转相对路径
            if upstream.get("protocol") == "socks5" and "://" in target:
                from urllib.parse import urlsplit
                u = urlsplit(target)
                rel = u.path or "/"
                if u.query:
                    rel += "?" + u.query
                first_line = head_line.split(" ", 2)
                first_line[1] = rel
                new_head_line = " ".join(first_line)
                first = (new_head_line + "\r\n").encode("latin-1") + first.split(b"\r\n", 1)[1]
            up_sock = self._connect_upstream(upstream, host, port)
            if not up_sock:
                self.rotator.report_fail(upstream)
                upstream = self.rotator.rotate()
                if upstream:
                    # 重试时如果换了 SOCKS5，需再次处理 URI
                    if upstream.get("protocol") == "socks5" and "://" in target:
                        from urllib.parse import urlsplit
                        u = urlsplit(target)
                        rel = u.path or "/"
                        if u.query:
                            rel += "?" + u.query
                        first_line = head_line.split(" ", 2)
                        first_line[1] = rel
                        new_head_line = " ".join(first_line)
                        first = (new_head_line + "\r\n").encode("latin-1") + first.split(b"\r\n", 1)[1]
                    up_sock = self._connect_upstream(upstream, host, port)
            if not up_sock:
                client.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                self.stats["fail"] += 1
                return
            up_sock.sendall(first)
            self.stats["success"] += 1
            self.stats["proxy"] += 1
            _pipe(client, up_sock)
        except Exception as e:
            self.stats["fail"] += 1
            try:
                client.sendall(b"HTTP/1.1 500 Internal Error\r\n\r\n")
            except Exception:
                pass
        finally:
            try:
                client.close()
            except Exception:
                pass

    # ----- 直连目标服务器 -----
    def _connect_direct(self, host: str, port: int):
        """智能模式下命中直连规则，直接 TCP 连到目标服务器，不走上游代理。"""
        try:
            s = socket.create_connection((host, port), timeout=10)
            s.settimeout(30)
            return s
        except Exception:
            return None

    def _connect_upstream(self, upstream: Dict, host: str, port: int):
        proto = upstream.get("protocol", "http")
        try:
            s = socket.create_connection((upstream["ip"], upstream["port"]),
                                         timeout=10)
            s.settimeout(30)
        except Exception:
            return None
        if proto in ("http", "https"):
            # HTTP 上游代理：直接连接到上游代理 socket 即可，
            # 请求行使用绝对 URI 或 CONNECT。
            return s
        if proto == "socks5":
            if _socks5_connect(s, host, port):
                return s
            try:
                s.close()
            except Exception:
                pass
            return None
        try:
            s.close()
        except Exception:
            pass
        return None

    def _open_upstream_tunnel(self, upstream: Dict, host: str, port: int):
        """HTTPS CONNECT：通过上游建立隧道。返回上游 socket 或 None。"""
        proto = upstream.get("protocol", "http")
        try:
            s = socket.create_connection((upstream["ip"], upstream["port"]),
                                         timeout=15)
            s.settimeout(30)
        except Exception:
            return None
        if proto in ("http", "https"):
            # 发 CONNECT 给上游代理
            req = (f"CONNECT {host}:{port} HTTP/1.1\r\n"
                   f"Host: {host}:{port}\r\n\r\n").encode("latin-1")
            try:
                s.sendall(req)
                resp = b""
                while b"\r\n\r\n" not in resp and len(resp) < 4096:
                    chunk = s.recv(1024)
                    if not chunk:
                        break
                    resp += chunk
                # 解析 HTTP 状态行，正确提取状态码
                first_line = resp.split(b"\r\n", 1)[0].strip()
                status_ok = False
                if first_line:
                    # 格式: HTTP/1.x 200 或 HTTP/1.x 200 OK
                    parts = first_line.split(b" ", 2)
                    if len(parts) >= 2:
                        try:
                            code = int(parts[1])
                            if code == 200:
                                status_ok = True
                        except ValueError:
                            pass
                # 备用检查：响应中是否包含 " 200"
                if not status_ok and b" 200" in resp[:200]:
                    status_ok = True
                if status_ok:
                    return s
            except Exception:
                pass
            try:
                s.close()
            except Exception:
                pass
            return None
        if proto == "socks5":
            if _socks5_connect(s, host, port):
                return s
            try:
                s.close()
            except Exception:
                pass
            return None
        try:
            s.close()
        except Exception:
            pass
        return None

    # ----- 生命周期 -----
    def start(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind((self.host, self.port))
        self._sock.listen(128)
        self._sock.settimeout(1)
        self._running = True

        def _serve():
            while self._running:
                try:
                    client, addr = self._sock.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                t = threading.Thread(target=self._handle,
                                     args=(client, addr), daemon=True)
                t.start()

        self._thread = threading.Thread(target=_serve, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        if self._sock:
            try:
                self._sock.close()
            except Exception:
                pass
        self._sock = None
