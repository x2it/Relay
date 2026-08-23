# -*- coding: utf-8 -*-
"""本地代理转发服务器。

工作原理：
- 本地起一个 HTTP 代理端口（默认 127.0.0.1:8888）。
- 客户端（浏览器/系统代理）把请求发到本端口。
- 普通HTTP请求：本地代理解析后通过"上游代理"转发。
- HTTPS请求：客户端发 CONNECT，本地代理让上游代理建立 TCP 隧道，
  之后两端双向转发原始字节——流量由目标站点 TLS 端到端加密，
  上游代理和本地代理都看不到明文，即"加密访问"。
- 上游代理可选 http / https / socks5 / ss / vmess / vless / trojan；
  其中加密协议（ss/vmess/vless/trojan）通过统一入口 connect_proxy 建立
  直达目标站点的加密隧道，当前代理失败可自动切换。
- 可选 Token 鉴权：防止局域网他人蹭用本地端口。
"""
import socket
import select
import threading
import time
import secrets
import atexit
import sys
import ipaddress
from collections import deque
from typing import List, Dict, Optional, Callable

from config import (DEFAULT_LOCAL_HOST, DEFAULT_LOCAL_PORT,
                    PROXY_MODE_GLOBAL, PROXY_MODE_SMART, PROXY_MODE_DIRECT,
                    APP_NAME, APP_VERSION, APP_COPYRIGHT)
from core.protocols import connect_proxy, TUNNEL_PROTOCOLS, ENCRYPTED_PROTOCOLS


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

    @staticmethod
    def _is_local_host(host: str) -> bool:
        """是否本机/局域网地址（loopback / 私网 / 链路本地 / localhost）。

        这类地址必须直连：上游海外代理无法访问用户本机的 127.0.0.1、
        局域网 IP 或 localhost 服务，否则"访问不了本地"。
        """
        h = (host or "").lower().strip()
        if not h:
            return False
        if h == "localhost" or h.endswith(".localhost"):
            return True
        if ":" in h:
            h = h.split(":", 1)[0]
        try:
            ip = ipaddress.ip_address(h)
        except ValueError:
            return False
        return (ip.is_loopback or ip.is_private or ip.is_link_local
                or ip.is_reserved or ip.is_unspecified)

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
        # 本机/局域网地址永远直连（任何模式下都不能走海外代理）
        if self._is_local_host(host):
            return "direct"
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
                 max_latency_ms: int = 3000,
                 min_speed_kbps: float = 0.0):
        self._get = get_proxies
        self._lock = threading.Lock()
        self._current: Optional[Dict] = None
        self._idx = 0
        self._idx_https = 0
        self.auto_switch = auto_switch
        self.max_latency_ms = max_latency_ms
        self.min_speed_kbps = min_speed_kbps
        self._fail_count = 0
        self._locked: Optional[Dict] = None   # 锁定的代理（优先级最高，不自动切换）

    def set_quality_gate(self, max_latency_ms: Optional[int] = None,
                         min_speed_kbps: Optional[float] = None):
        """运行时更新品质门槛（设置保存后调用）。"""
        with self._lock:
            if max_latency_ms is not None:
                self.max_latency_ms = max_latency_ms
            if min_speed_kbps is not None:
                self.min_speed_kbps = min_speed_kbps

    def _meets_gate(self, p: Dict) -> bool:
        """单个代理是否满足品质门槛（alive + 延迟≤上限 + 速度≥下限）。未测速的节点按 alive 放行。"""
        if not p.get("alive"):
            return False
        if p.get("latency_ms", 99999) > self.max_latency_ms:
            return False
        # 速度门槛：仅对已测过速度的节点生效（speed_kbps > 0 说明真测过）。
        # 新抓回来、还没验证的节点（speed_kbps=0）不应被过滤，否则用户无节点可用。
        if self.min_speed_kbps > 0:
            spd = p.get("speed_kbps") or 0.0
            if spd > 0 and spd < self.min_speed_kbps:
                return False
        return True

    def _candidates(self) -> List[Dict]:
        alive = [p for p in self._get() if self._meets_gate(p)]
        # HTTPS-capable 优先，然后按低延迟
        alive.sort(key=lambda p: (not p.get("https_ok", False),
                                  p.get("latency_ms", 99999)))
        return alive

    def _https_candidates(self) -> List[Dict]:
        """仅返回 HTTPS 可用代理。"""
        alive = [p for p in self._get()
                 if self._meets_gate(p) and p.get("https_ok")]
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
    """双向转发。a/b 可为裸 socket 或 TunnelStream（都实现 fileno/recv/sendall）。

    TunnelStream.recv 在"暂无数据但未结束"时抛 socket.timeout，
    此处按"继续等待"处理，不中断管道。
    """
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 60)
            if not r:
                continue
            for s in r:
                try:
                    data = s.recv(8192)
                except socket.timeout:
                    continue
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
        # 最近请求记录（看板展示用），保留最多 24 条
        self.recent_requests: deque = deque(maxlen=24)
        self._recent_lock = threading.Lock()
        # 状态看板挂载路径
        self._status_paths = {"/", "/index.html", "/status", "/status.html"}

    # ----- 状态看板（GET / 或 /status 直接返回 HTML） -----
    def _build_status_page(self) -> bytes:
        """终端风格状态页：用户直接把代理地址当网址打，不再看到 400。"""
        mode_label = {PROXY_MODE_SMART: "smart  国内直连 + 海外代理",
                      PROXY_MODE_GLOBAL: "global  全走代理（本机仍直连）",
                      PROXY_MODE_DIRECT: "direct  全直连（调试）"}.get(
            self.rule_engine.mode, self.rule_engine.mode)

        # 当前上游：严格取「下一次转发实际会被分配的上游」，与 do_CONNECT/do_GET 用同一 API
        # 优先级：HTTPS 代理(占绝大多请求) > 普通 HTTP 代理 > 无。保证看板标签不误导。
        up = None
        if self.rotator:
            up = self.rotator.pick_https()
            if up is None:
                up = self.rotator.pick()
        if up is None:
            up_str = "无 · 请先在应用内「抓取代理 → 验证全部」"
            up_tag_cls = "bad"
        else:
            proto = up.get("protocol", "http").upper()
            lat = up.get("latency_ms", 0) or 0
            spd = up.get("speed_kbps", 0.0) or 0.0
            locked_tag = " [★ 锁定]" if self.rotator and self.rotator.is_locked() else ""
            up_str = (f"{proto}  {up.get('ip','?')}:{up.get('port','?')}"
                      f"  延迟 {lat}ms  速度 {spd:.1f} KB/s{locked_tag}")
            # 上游质量标签：差 → 红 / 中 → 橙 / 优 → 绿
            if spd <= 0 or lat >= 1500:
                up_tag_cls = "bad"
            elif lat >= 600 or spd < 50:
                up_tag_cls = "warn"
            else:
                up_tag_cls = "green"

        alive_list = (self.rotator._candidates() if self.rotator else [])  # noqa: SLF001
        total_list = self.rotator._get() if self.rotator else []           # noqa: SLF001
        alive_count = len(alive_list)
        total_count = len(total_list) if total_list else 0

        # 验证进度：有延迟测量的节点 = 已通过校验（不管成功失败，有结果就算被验过）
        verified_count = 0
        if total_list:
            verified_count = sum(1 for p in total_list
                                 if isinstance(p.get("latency_ms"), int)
                                 and p["latency_ms"] > 0)

        # HTTPS 支持计数：任何代理协议（HTTP/HTTPS/SOCKS/SS/VMess/VLESS/Trojan）默认都支持
        # CONNECT 隧道，只有显式 https_ok=False 的才剔除；否则就算支持。
        def _supports_https(p) -> bool:
            if p.get("https_ok") is False:
                return False
            proto = (p.get("protocol") or "http").lower()
            return proto in {"http", "https", "socks4", "socks5",
                             "ss", "vmess", "vless", "trojan"}

        https_ok_count = sum(1 for p in alive_list if _supports_https(p))

        # 健康度标签：已验很少就标"待验证"
        if total_count == 0:
            pool_tag_cls, pool_tag = "bad", "空代理池"
        elif verified_count < max(10, total_count // 4):
            pool_tag_cls, pool_tag = "warn", f"待验证（仅 {verified_count}/{total_count} 已测）"
        elif alive_count == 0:
            pool_tag_cls, pool_tag = "bad", "0 存活"
        elif alive_count < 5:
            pool_tag_cls, pool_tag = "warn", f"存活不足（{alive_count} 个可用）"
        else:
            pool_tag_cls, pool_tag = "green", f"正常（{alive_count}/{verified_count} 合格）"

        # Top 5 候选：按延迟升序 + 速度降序排（给用户直观"代理质量到底好不好"参考）
        def _sort_key(p):
            lat = p.get("latency_ms") or 999999
            spd = p.get("speed_kbps") or 0.0
            # 先按延迟桶（优/中/差），再按速度倒序
            lat_bucket = 0 if lat < 500 else 1 if lat < 1500 else 2
            return (lat_bucket, lat, -spd)

        top_candidates = sorted(alive_list, key=_sort_key)[:5]
        top_rows_html = ""
        if not top_candidates:
            top_rows_html = ('<tr><td colspan="5" style="color:var(--fg-faint);padding:10px 2px">'
                             '无可用上游 · 请回到 Relay 应用「抓取代理 → 验证全部」</td></tr>')
        else:
            for i, p in enumerate(top_candidates, 1):
                proto = (p.get("protocol") or "http").upper()
                lat = p.get("latency_ms") or 0
                spd = p.get("speed_kbps") or 0.0
                addr = f"{p.get('ip','?')}:{p.get('port','?')}"
                cur_marker = "●" if up and p.get("ip") == up.get("ip") and str(p.get("port")) == str(up.get("port")) else "·"
                cur_color = "var(--pri)" if cur_marker == "●" else "var(--fg-faint)"
                # 延迟染色
                if lat >= 1500:
                    lat_c = "var(--bad)"
                elif lat >= 600:
                    lat_c = "var(--warn)"
                else:
                    lat_c = "var(--good)"
                # 速度染色
                if spd <= 0:
                    spd_c = "var(--fg-faint)"
                elif spd < 50:
                    spd_c = "var(--warn)"
                else:
                    spd_c = "var(--good)"
                https_m = ("✓" if _supports_https(p) else "✗")
                https_c = "var(--good)" if _supports_https(p) else "var(--bad)"
                top_rows_html += (
                    f'<tr style="border-bottom:1px dashed var(--bd)">'
                    f'<td style="padding:6px 8px 6px 0;color:{cur_color};width:18px">{cur_marker}</td>'
                    f'<td style="padding:6px 8px;color:var(--pri)">{proto}</td>'
                    f'<td style="padding:6px 8px;color:var(--fg)" class="mono">{addr}</td>'
                    f'<td style="padding:6px 8px;text-align:right;color:{lat_c}" class="mono">{lat}ms</td>'
                    f'<td style="padding:6px 8px;text-align:right;color:{spd_c}" class="mono">{spd:.1f} KB/s</td>'
                    f'<td style="padding:6px 0 6px 10px;text-align:right;color:{https_c}">HTTPS {https_m}</td>'
                    f'</tr>\n'
                )

        # 最近 10 条请求记录（从 stats 里拿；没有的话展示占位）
        recent = getattr(self, "recent_requests", None)
        if recent is None:
            recent_html = ('<div style="color:var(--fg-faint);padding:10px 0">'
                           '暂无请求记录 · 设置浏览器代理后刷新此页。</div>')
        else:
            recent_rows = []
            for rec in reversed(list(recent)):  # 新→旧
                ok = rec.get("ok", True)
                host = rec.get("host", "-")[:48]
                via = rec.get("via", "?")  # direct / proxy
                ms = rec.get("ms", 0)
                color_c = "var(--good)" if ok else "var(--bad)"
                via_c = "var(--pri)" if via == "direct" else "var(--fg-dim)"
                recent_rows.append(
                    f'<div class="kv" style="padding:2px 0">'
                    f'<span class="k" style="color:{color_c}">{"✓" if ok else "✗"}</span>'
                    f'<span class="v mono" style="text-align:left;color:var(--fg)">{host}</span>'
                    f'<span class="k" style="color:{via_c}">{via}</span>'
                    f'<span class="v mono" style="width:64px">{ms}ms</span>'
                    f'</div>'
                )
            if not recent_rows:
                recent_html = ('<div style="color:var(--fg-faint);padding:10px 0">'
                               '暂无请求记录。</div>')
            else:
                recent_html = "\n".join(recent_rows)

        s = self.stats
        req_rate = s["success"] / s["requests"] * 100 if s["requests"] else 0.0
        if s["requests"] == 0:
            rate_cls = "var(--fg-faint)"
        elif req_rate < 70:
            rate_cls = "var(--bad)"
        elif req_rate < 90:
            rate_cls = "var(--warn)"
        else:
            rate_cls = "var(--good)"

        html = f"""<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{APP_NAME} · 本地代理状态看板</title>
<style>
  :root{{
    --bg:#0F1216; --card:#171B21; --bar:#0F1216; --hover:#1F242B;
    --pri:#6B8AFF; --pri-soft:#263258; --fg:#E7E9EC; --fg-dim:#9AA2AC; --fg-faint:#6B7280;
    --good:#2BA47A; --bad:#D64545; --warn:#D9822B; --bd:#2C323B;
  }}
  *{{box-sizing:border-box}}
  body{{margin:0;padding:36px 28px 48px;background:var(--bg);color:var(--fg);
    font-family:"Cascadia Mono","JetBrains Mono","Consolas","Courier New",ui-monospace,monospace;
    font-size:14px;line-height:1.65;}}
  .wrap{{max-width:980px;margin:0 auto}}
  .bar{{display:flex;align-items:center;gap:14px;margin-bottom:18px;color:var(--fg-dim);flex-wrap:wrap}}
  .tag{{display:inline-block;padding:2px 10px;border:1px solid var(--bd);border-radius:999px;color:var(--pri);font-weight:700}}
  .tag.green{{color:var(--good)}}
  .tag.warn{{color:var(--warn)}}
  .tag.bad{{color:var(--bad)}}
  .title{{font-size:22px;font-weight:800;color:var(--pri);letter-spacing:.5px;margin:0 0 4px}}
  .sub{{color:var(--fg-dim);margin:0 0 22px}}
  .grid{{display:grid;grid-template-columns:repeat(2,1fr);gap:14px;margin-bottom:18px}}
  .grid3{{display:grid;grid-template-columns:1.3fr 1fr;gap:14px;margin-bottom:18px}}
  .card{{background:var(--card);border:1px solid var(--bd);border-radius:10px;padding:16px 18px}}
  .card h3{{margin:0 0 10px;font-size:13px;color:var(--pri);letter-spacing:1px}}
  .kv{{display:flex;justify-content:space-between;gap:16px;padding:3px 0;align-items:center}}
  .k{{color:var(--fg-dim)}}
  .v{{color:var(--fg);text-align:right;word-break:break-all}}
  .mono{{font-family:inherit}}
  .sep{{height:1px;background:var(--bd);margin:14px 0}}
  .dash{{color:var(--fg-faint)}}
  .foot{{margin-top:22px;color:var(--fg-faint);font-size:12px}}
  code{{background:var(--hover);padding:1px 6px;border-radius:4px;color:var(--pri);font-family:inherit;font-size:13px}}
  .note{{background:var(--card);border:1px dashed var(--bd);border-radius:10px;padding:14px 16px;color:var(--fg-dim)}}
  .note strong{{color:var(--fg)}}
  table{{width:100%;border-collapse:collapse;font-size:13px}}
  th{{text-align:left;color:var(--fg-faint);font-weight:500;padding:4px 8px 6px 0;border-bottom:1px solid var(--bd);font-size:12px;letter-spacing:1px}}
  th.right{{text-align:right}}
  td{{vertical-align:middle}}
  @media(max-width:820px){{.grid,.grid3{{grid-template-columns:1fr}}}}
</style></head><body>
<div class="wrap">
  <div class="bar">
    <span class="tag">{APP_NAME} v{APP_VERSION}</span>
    <span>· 状态页 · 非代理请求</span>
    <span class="tag {pool_tag_cls}">代理池 {pool_tag}</span>
    <span style="margin-left:auto" class="tag green">● 本地代理 127.0.0.1:{self.port} 运行中</span>
  </div>
  <h1 class="title">· Relay 本地代理状态看板</h1>
  <p class="sub">浏览器代理设 <code>127.0.0.1:{self.port}</code> 即可，本页面只做监控不转发。</p>

  <div class="grid">
    <div class="card">
      <h3>● 上游（当前在用）</h3>
      <div class="kv"><span class="k">分流模式</span><span class="v mono">{mode_label}</span></div>
      <div class="kv"><span class="k">当前节点</span><span class="v mono"><span class="tag {up_tag_cls}">{up_str}</span></span></div>
      <div class="sep"></div>
      <div class="kv"><span class="k">已验证 / 已抓取</span><span class="v mono">{verified_count} / {total_count}</span></div>
      <div class="kv"><span class="k">可用 / 已验证</span><span class="v mono" style="color:var(--good) if alive_count>0 else var(--bad)">{alive_count} / {verified_count if verified_count else 0}</span></div>
      <div class="kv"><span class="k">支持 HTTPS 隧道</span><span class="v mono">{https_ok_count} 个</span></div>
    </div>
    <div class="card">
      <h3>● 统计（当前运行）</h3>
      <div class="kv"><span class="k">总请求</span><span class="v mono">{s['requests']}</span></div>
      <div class="kv"><span class="k">成功 / 失败</span><span class="v mono"><span style="color:var(--good)">{s['success']}</span> <span class="dash">·</span> <span style="color:var(--bad)">{s['fail']}</span></span></div>
      <div class="kv"><span class="k">成功率</span><span class="v mono" style="color:{rate_cls}">{req_rate:.1f}%</span></div>
      <div class="sep"></div>
      <div class="kv"><span class="k">直连 / 代理</span><span class="v mono"><span style="color:var(--pri)">{s['direct']}</span> <span class="dash">·</span> {s['proxy']}</span></div>
      <div class="kv"><span class="k">自动切换</span><span class="v mono">{('开（上游失败自动换下一个）' if getattr(self.rotator, 'auto_switch', True) else '关')}</span></div>
    </div>
  </div>

  <div class="grid3">
    <div class="card">
      <h3>● 候选节点 Top 5（{len(top_candidates)} 个 · 按延迟/速度排序）</h3>
      <table>
        <thead><tr>
          <th style="width:22px">#</th><th>协议</th><th>地址</th>
          <th class="right">延迟</th><th class="right">速度</th><th class="right">HTTPS</th>
        </tr></thead>
        <tbody>
{top_rows_html}
        </tbody>
      </table>
      <div class="sep"></div>
      <div style="color:var(--fg-faint);font-size:12px">
        提示：延迟 <span style="color:var(--good)">&lt;500ms</span> 绿 / <span style="color:var(--warn)">500-1500ms</span> 橙 / <span style="color:var(--bad)">≥1500ms</span> 红；速度 <span style="color:var(--good)">≥50KB/s</span> 优；<span style="color:var(--pri)">●</span> 表示当前正在用。
      </div>
    </div>
    <div class="card">
      <h3>● 最近请求</h3>
      {recent_html}
    </div>
  </div>

  <div class="note">
    <strong>自检清单（Google/YouTube 访问不了？按顺序查）</strong><br>
    ① <span style="color:var(--warn)">99% 的情况</span>：只点了「抓取代理」没点「验证全部」—— 回到 Relay 应用按 <code>[⚡ 一键流程]</code> 让验证筛出可用节点。<br>
    ② 上方 Top 5 里延迟全红或速度 0 KB/s → 当前抓取源（A/B/C/D/E）里就是没优质节点，换一组分类源重新 <code>[抓取 + 验证]</code>。<br>
    ③ 浏览器提示「证书错误」那是目标站的 TLS 自签问题，不是本代理；Relay 走 CONNECT 隧道不碰证书，端到端加密。<br>
    ④ <code>localhost</code> / 路由器后台 / 局域网 IP 会走直连，不会通过上游代理。<br>
    <span style="color:var(--fg-faint)">更多用法：打开 Relay 应用 → 顶栏 <code>[? 帮助]</code>。</span>
  </div>

  <div class="foot">{APP_COPYRIGHT} · 代理端口 <code>{self.host}:{self.port}</code> · 仅本机访问</div>
</div>
</body></html>""".encode("utf-8")
        return html

    def _serve_dashboard(self, client: socket.socket) -> bool:
        """判断是否命中本地状态页，命中则直接返回 True（已写响应）。"""
        body = self._build_status_page()
        head = (b"HTTP/1.1 200 OK\r\n"
                b"Content-Type: text/html; charset=utf-8\r\n"
                + f"Content-Length: {len(body)}\r\n".encode("latin-1")
                + b"Cache-Control: no-store\r\n"
                + b"X-Relay-Panel: 1\r\n\r\n")
        try:
            client.sendall(head + body)
        except Exception:
            pass
        return True

    # ----- 鉴权 -----
    def _check_auth(self, header_bytes: bytes) -> bool:
        if not self.auth_token:
            return True
        head = header_bytes.split(b"\r\n", 1)[0].decode("latin-1", "ignore")
        # 简单校验 Proxy-Authorization 或自定义 X-Relay-Token
        for line in header_bytes.split(b"\r\n"):
            try:
                line_s = line.decode("latin-1")
            except Exception:
                continue
            if line_s.lower().startswith("x-relay-token:"):
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
        ctx = {"start_ts": time.monotonic(), "host": "-", "via": "-",
               "ok": False, "record": False}

        def _extract_host_from_target(method_upper: str, target_raw: str) -> str:
            """从 CONNECT host:port 或 http://host/path 或 host:port 中提取 host[:port]。"""
            try:
                if method_upper == "CONNECT":
                    h, _, p = target_raw.partition(":")
                    return f"{h}:{p}" if p else h
                if "://" in target_raw:
                    from urllib.parse import urlsplit
                    u = urlsplit(target_raw)
                    return u.netloc or "-"
                h, _, p = target_raw.partition(":")
                return f"{h}:{p}" if p else h
            except Exception:
                return "-"

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
                try:
                    client.sendall(b"HTTP/1.1 407 Proxy Authentication Required\r\n"
                                   b"Proxy-Authenticate: Bearer\r\n\r\n")
                except Exception:
                    pass
                ctx["host"] = "auth-failed"
                ctx["via"] = "proxy"
                ctx["ok"] = False
                ctx["record"] = True
                return

            head_line = first.split(b"\r\n", 1)[0].decode("latin-1", "ignore")
            parts = head_line.split()
            if len(parts) < 3:
                return
            method, target, _ = parts[0], parts[1], parts[2]
            method_upper = method.upper()
            self.stats["requests"] += 1
            ctx["host"] = _extract_host_from_target(method_upper, target)
            ctx["record"] = True

            # ----- 状态看板拦截：GET /status 等直接返回 HTML -----
            panel_served = False
            if method_upper in ("GET", "HEAD"):
                path = None
                if target.startswith("/"):
                    path = target.split("?", 1)[0]
                elif "://" in target:
                    from urllib.parse import urlsplit
                    try:
                        u = urlsplit(target)
                        host_port = f"{self.host}:{self.port}"
                        cand = u.netloc.lower()
                        localhosts = {host_port, self.host,
                                      f"localhost:{self.port}", "localhost",
                                      f"127.0.0.1:{self.port}", "127.0.0.1"}
                        if cand in localhosts:
                            path = u.path or "/"
                    except Exception:
                        path = None
                if path is not None:
                    if path in self._status_paths:
                        if method_upper == "GET":
                            self._serve_dashboard(client)
                        else:
                            try:
                                client.sendall(b"HTTP/1.1 204 No Content\r\n"
                                               b"Content-Length: 0\r\n"
                                               b"X-Relay-Panel: 1\r\n\r\n")
                            except Exception:
                                pass
                        panel_served = True
                    elif path == "/favicon.ico":
                        try:
                            client.sendall(b"HTTP/1.1 204 No Content\r\n"
                                           b"Content-Length: 0\r\n\r\n")
                        except Exception:
                            pass
                        panel_served = True
            if panel_served:
                self.stats["success"] += 1
                self.stats["direct"] += 1
                ctx["via"] = "panel"
                ctx["ok"] = True
                return

            # ----- HTTPS CONNECT -----
            if method_upper == "CONNECT":
                host, _, port_s = target.partition(":")
                port = int(port_s or "443")
                # 智能分流：直连 or 代理
                if self.rule_engine.decide(host) == "direct":
                    up_sock = self._connect_direct(host, port)
                    if not up_sock:
                        try:
                            client.sendall(b"HTTP/1.1 502 Direct Connect Failed\r\n\r\n")
                        except Exception:
                            pass
                        self.stats["fail"] += 1
                        ctx["via"] = "direct"
                        ctx["ok"] = False
                        return
                    try:
                        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                    except Exception:
                        pass
                    self.stats["success"] += 1
                    self.stats["direct"] += 1
                    ctx["via"] = "direct"
                    ctx["ok"] = True
                    _pipe(client, up_sock)
                    return
                # CONNECT 隧道：优先使用 HTTPS 可用代理
                upstream = self.rotator.pick_https()
                if not upstream:
                    try:
                        client.sendall(b"HTTP/1.1 502 No Available Upstream\r\n\r\n")
                    except Exception:
                        pass
                    self.stats["fail"] += 1
                    ctx["via"] = "proxy"
                    ctx["ok"] = False
                    return
                ok = None
                max_retries = 5
                tried_ips = set()
                for attempt in range(max_retries):
                    if not upstream:
                        break
                    ip_key = f"{upstream.get('ip','')}:{upstream.get('port','')}"
                    if ip_key in tried_ips:
                        upstream = self.rotator.rotate_https()
                        continue
                    tried_ips.add(ip_key)
                    ok = self._open_upstream_tunnel(upstream, host, port)
                    if ok:
                        break
                    upstream["https_ok"] = False
                    self.rotator.report_fail(upstream)
                    upstream = self.rotator.rotate_https()
                if not ok:
                    direct_sock = self._connect_direct(host, port)
                    if direct_sock:
                        try:
                            client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                        except Exception:
                            pass
                        self.stats["success"] += 1
                        self.stats["direct"] += 1
                        ctx["via"] = "direct-fallback"
                        ctx["ok"] = True
                        header_end = first.find(b"\r\n\r\n") + 4
                        leftover = first[header_end:]
                        if leftover:
                            try:
                                direct_sock.sendall(leftover)
                            except Exception:
                                pass
                        _pipe(client, direct_sock)
                        return
                    try:
                        client.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                    except Exception:
                        pass
                    self.stats["fail"] += 1
                    ctx["via"] = "proxy"
                    ctx["ok"] = False
                    return
                try:
                    client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                except Exception:
                    pass
                self.stats["success"] += 1
                self.stats["proxy"] += 1
                ctx["via"] = "proxy"
                ctx["ok"] = True
                up_sock = ok
                header_end = first.find(b"\r\n\r\n") + 4
                leftover = first[header_end:]
                if leftover:
                    try:
                        up_sock.sendall(leftover)
                    except Exception:
                        pass
                _pipe(client, up_sock)
                return

            # ----- 普通 HTTP 请求 -----
            host_port = target
            if "://" in target:
                from urllib.parse import urlsplit
                u = urlsplit(target)
                host = u.hostname or ""
                port = u.port or 80
            else:
                host, _, port_s = target.partition(":")
                port = int(port_s or "80")

            is_direct = (self.rule_engine.decide(host) == "direct")

            if is_direct:
                first = self._to_relative_first(first, head_line, target)
                up_sock = self._connect_direct(host, port)
                if not up_sock:
                    try:
                        client.sendall(b"HTTP/1.1 502 Direct Connect Failed\r\n\r\n")
                    except Exception:
                        pass
                    self.stats["fail"] += 1
                    ctx["via"] = "direct"
                    ctx["ok"] = False
                    return
                try:
                    up_sock.sendall(first)
                except Exception:
                    pass
                self.stats["success"] += 1
                self.stats["direct"] += 1
                ctx["via"] = "direct"
                ctx["ok"] = True
                _pipe(client, up_sock)
                return
            max_retries = 5
            tried = set()
            upstream = self.rotator.pick()
            up_sock = None
            for attempt in range(max_retries):
                if not upstream:
                    break
                key = f"{upstream.get('ip','')}:{upstream.get('port','')}:{upstream.get('protocol','')}"
                if key in tried:
                    upstream = self.rotator.rotate()
                    continue
                tried.add(key)
                if upstream.get("protocol") in TUNNEL_PROTOCOLS:
                    first = self._to_relative_first(first, head_line, target)
                up_sock = self._connect_upstream(upstream, host, port)
                if up_sock:
                    break
                self.rotator.report_fail(upstream)
                upstream = self.rotator.rotate()
            if not up_sock:
                try:
                    client.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                except Exception:
                    pass
                self.stats["fail"] += 1
                ctx["via"] = "proxy"
                ctx["ok"] = False
                return
            try:
                up_sock.sendall(first)
            except Exception:
                pass
            self.stats["success"] += 1
            self.stats["proxy"] += 1
            ctx["via"] = "proxy"
            ctx["ok"] = True
            _pipe(client, up_sock)
        except Exception:
            self.stats["fail"] += 1
            try:
                client.sendall(b"HTTP/1.1 500 Internal Error\r\n\r\n")
            except Exception:
                pass
            if ctx["host"] == "-":
                ctx["host"] = "error"
            ctx["ok"] = False
            ctx["record"] = True
        finally:
            if ctx["record"]:
                ms = int((time.monotonic() - ctx["start_ts"]) * 1000)
                try:
                    with self._recent_lock:
                        self.recent_requests.append({
                            "host": ctx["host"], "via": ctx["via"],
                            "ok": bool(ctx["ok"]), "ms": ms,
                        })
                except Exception:
                    pass
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
        """普通 HTTP 请求：连接上游并（对隧道类协议）直连到目标 host:port。"""
        proto = upstream.get("protocol", "http")
        if proto in ("http", "https"):
            # HTTP 上游代理：返回连到上游代理的 socket，请求行使用绝对 URI。
            try:
                s = socket.create_connection((upstream["ip"], upstream["port"]),
                                             timeout=10)
                s.settimeout(30)
                return s
            except Exception:
                return None
        if proto == "socks5":
            try:
                s = socket.create_connection((upstream["ip"], upstream["port"]),
                                             timeout=10)
                s.settimeout(30)
            except Exception:
                return None
            if _socks5_connect(s, host, port):
                return s
            try:
                s.close()
            except Exception:
                pass
            return None
        if proto in ENCRYPTED_PROTOCOLS:
            try:
                return connect_proxy(upstream, host, port, timeout=15)
            except Exception:
                return None
        return None

    def _open_upstream_tunnel(self, upstream: Dict, host: str, port: int):
        """HTTPS CONNECT：通过上游建立隧道，返回上游 socket 或 TunnelStream。"""
        proto = upstream.get("protocol", "http")
        if proto in ("http", "https"):
            # 发 CONNECT 给上游代理
            try:
                s = socket.create_connection((upstream["ip"], upstream["port"]),
                                             timeout=15)
                s.settimeout(30)
            except Exception:
                return None
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
            try:
                s = socket.create_connection((upstream["ip"], upstream["port"]),
                                             timeout=15)
                s.settimeout(30)
            except Exception:
                return None
            if _socks5_connect(s, host, port):
                return s
            try:
                s.close()
            except Exception:
                pass
            return None
        if proto in ENCRYPTED_PROTOCOLS:
            # 加密协议本身即"到目标"的隧道，直接建立即可
            try:
                return connect_proxy(upstream, host, port, timeout=15)
            except Exception:
                return None
        return None

    @staticmethod
    def _to_relative_first(first: bytes, head_line: str, target: str) -> bytes:
        """隧道类协议已直达目标站点，需把绝对 URI 改为相对路径再发送。"""
        from urllib.parse import urlsplit
        if "://" not in target:
            return first
        u = urlsplit(target)
        rel = u.path or "/"
        if u.query:
            rel += "?" + u.query
        fl = head_line.split(" ", 2)
        fl[1] = rel
        new_head = " ".join(fl)
        return (new_head + "\r\n").encode("latin-1") + first.split(b"\r\n", 1)[1]

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
