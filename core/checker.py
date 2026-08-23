# -*- coding: utf-8 -*-
"""代理验证与测速。多线程并发。

验证策略：
1. 先测 HTTP 连通性（http://httpbin.org/ip）—— 宽松
2. 再测 CONNECT 隧道能力（socket 连接 + CONNECT google.com:443）—— 浏览器实际需要
3. HTTP 通但 CONNECT 不通 → alive=True, https_ok=False（仅 HTTP 可用，浏览器无法访问 HTTPS）
4. CONNECT 通 → alive=True, https_ok=True（浏览器可访问 Google/YouTube 等）
5. 都不通 → alive=False
"""
import time
import socket
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List, Dict, Callable, Optional, Tuple

import requests

from config import CHECK_TIMEOUT, CHECK_TEST_URL, CHECK_WORKERS, SPEED_SAMPLE_BYTES
from core import store

try:
    import urllib3
    urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
except Exception:
    pass


def _proxy_url(p: Dict) -> str:
    proto = p.get("protocol", "http")
    return f"{proto}://{p['ip']}:{p['port']}"


def _test_connect_tunnel(proxy: Dict, target_host: str = "www.google.com",
                        target_port: int = 443, timeout: int = 10) -> tuple:
    """测试 CONNECT 隧道能力 + TLS 握手验证。返回 (ok, latency_ms)。"""
    proto = proxy.get("protocol", "http")
    ip = proxy["ip"]
    port = proxy["port"]
    t0 = time.time()
    s = None
    try:
        s = socket.create_connection((ip, port), timeout=timeout)
        s.settimeout(timeout)
        if proto in ("http", "https"):
            req = f"CONNECT {target_host}:{target_port} HTTP/1.1\r\nHost: {target_host}:{target_port}\r\n\r\n"
            s.sendall(req.encode("latin-1"))
            resp = b""
            while b"\r\n\r\n" not in resp and len(resp) < 4096:
                chunk = s.recv(1024)
                if not chunk:
                    break
                resp += chunk
            first_line = resp.split(b"\r\n", 1)[0].strip()
            connect_ok = False
            if first_line:
                parts = first_line.split(b" ", 2)
                if len(parts) >= 2:
                    try:
                        code = int(parts[1])
                        if code == 200:
                            connect_ok = True
                    except ValueError:
                        pass
            if not connect_ok and b"200" in resp[:200]:
                connect_ok = True
            if not connect_ok:
                try: s.close()
                except: pass
                return False, int((time.time() - t0) * 1000)
            # TLS
            try:
                import ssl
                ctx = ssl.create_default_context()
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
                tls_sock = ctx.wrap_socket(s, server_hostname=target_host)
                tls_sock.sendall(b"GET / HTTP/1.0\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")
                data = b""
                tls_sock.settimeout(timeout)
                try:
                    while True:
                        chunk = tls_sock.recv(4096)
                        if not chunk:
                            break
                        data += chunk
                        if len(data) >= 4096:
                            break
                except Exception:
                    pass
                elapsed = int((time.time() - t0) * 1000)
                try: tls_sock.close()
                except: pass
                return (len(data) > 0), elapsed
            except Exception:
                elapsed = int((time.time() - t0) * 1000)
                try: s.close()
                except: pass
                return False, elapsed
        elif proto == "socks5":
            s.sendall(b"\x05\x01\x00")
            resp = s.recv(2)
            if len(resp) < 2 or resp[1] != 0x00:
                try: s.close()
                except: pass
                return False, int((time.time() - t0) * 1000)
            try:
                ip_bytes = socket.inet_aton(target_host)
                req = b"\x05\x01\x00\x01" + ip_bytes + target_port.to_bytes(2, "big")
            except OSError:
                hb = target_host.encode()
                req = b"\x05\x01\x00\x03" + bytes([len(hb)]) + hb + target_port.to_bytes(2, "big")
            s.sendall(req)
            # 读取完整 SOCKS5 响应（处理变长域名）
            def _recv_exact(n):
                data = b""
                while len(data) < n:
                    chunk = s.recv(n - len(data))
                    if not chunk:
                        break
                    data += chunk
                return data
            rep = _recv_exact(4)
            if len(rep) < 4:
                try: s.close()
                except: pass
                return False, int((time.time() - t0) * 1000)
            atyp = rep[3]
            if atyp == 0x01:  # IPv4: 4 bytes addr + 2 bytes port
                rep += _recv_exact(6)
            elif atyp == 0x03:  # Domain
                dlen_data = _recv_exact(1)
                if dlen_data:
                    dlen = dlen_data[0]
                    rep += dlen_data + _recv_exact(dlen + 2)
            elif atyp == 0x04:  # IPv6: 16 bytes addr + 2 bytes port
                rep += _recv_exact(18)
            connect_ok = len(rep) >= 2 and rep[1] == 0x00
            if not connect_ok:
                try: s.close()
                except: pass
                return False, int((time.time() - t0) * 1000)
            # TLS handshake
            try:
                import ssl
                ctx = ssl.create_default_context()
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
                tls_sock = ctx.wrap_socket(s, server_hostname=target_host)
                tls_sock.sendall(b"GET / HTTP/1.0\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")
                data = b""
                tls_sock.settimeout(timeout)
                try:
                    while True:
                        chunk = tls_sock.recv(4096)
                        if not chunk:
                            break
                        data += chunk
                        if len(data) >= 4096:
                            break
                except Exception:
                    pass
                elapsed = int((time.time() - t0) * 1000)
                try: tls_sock.close()
                except: pass
                return (len(data) > 0), elapsed
            except Exception:
                elapsed = int((time.time() - t0) * 1000)
                try: s.close()
                except: pass
                return False, elapsed
        else:
            try: s.close()
            except: pass
            return False, int((time.time() - t0) * 1000)
    except Exception:
        try:
            if s: s.close()
        except: pass
        return False, int((time.time() - t0) * 1000)


ENCRYPTED_PROTOCOLS = ("ss", "vmess", "vless", "trojan")


def _check_encrypted(proxy: Dict, timeout: int = CHECK_TIMEOUT) -> Dict:
    """加密代理检测：建立隧道 → TLS 握手 → 读取响应数据。

    这类代理并非标准 HTTP 代理，无法用 requests 走代理，因此直接通过
    加密隧道访问 www.google.com 并完成 TLS 握手，能拿到数据即判定可用。
    """
    from core.protocols import connect_proxy, tls_over_stream
    t0 = time.time()
    stream = None
    try:
        stream = connect_proxy(proxy, "www.google.com", 443, timeout=timeout)
        tls = tls_over_stream(stream, "www.google.com", timeout=timeout)
        tls.sendall(b"GET /generate_204 HTTP/1.0\r\n"
                    b"Host: www.google.com\r\nConnection: close\r\n\r\n")
        data = b""
        tls.settimeout(timeout)
        try:
            while len(data) < SPEED_SAMPLE_BYTES:
                chunk = tls.recv(4096)
                if not chunk:
                    break
                data += chunk
        except socket.timeout:
            pass
        except Exception:
            pass
        elapsed = int((time.time() - t0) * 1000)
        ok = len(data) > 0
        speed = (len(data) / 1024.0) / max(time.time() - t0, 0.001) if ok else 0.0
        proxy["https_ok"] = ok
        store.update_proxy_status(proxy, ok, elapsed if ok else 0, speed if ok else 0.0)
    except Exception:
        proxy["https_ok"] = False
        store.update_proxy_status(proxy, False, 0, 0.0)
    finally:
        if stream is not None:
            try:
                stream.close()
            except Exception:
                pass
    return proxy


def check_one(proxy: Dict, test_url: str = CHECK_TEST_URL,
              timeout: int = CHECK_TIMEOUT) -> Dict:
    """验证单个代理：HTTP 连通性 → CONNECT 隧道能力 → 测速。"""
    proto = proxy.get("protocol", "http")
    if proto in ENCRYPTED_PROTOCOLS:
        return _check_encrypted(proxy, timeout=timeout)

    purl = _proxy_url(proxy)
    proxies = {"http": purl, "https": purl}
    alive = False
    https_ok = False
    latency_ms = 0
    speed_kbps = 0.0
    proto = proxy.get("protocol", "http")

    # Step 1: HTTP 连通性测试（所有协议都测）
    http_ok = False
    try:
        t0 = time.time()
        resp = requests.get("http://httpbin.org/ip", proxies=proxies,
                            timeout=timeout, verify=False,
                            headers={"User-Agent": "Relay/1.7"})
        # 严格检查：状态码正常 + 响应内容像是 httpbin 的 JSON
        if resp.status_code < 500:
            try:
                data = resp.json()
                if "origin" in data:
                    http_ok = True
                    latency_ms = int((time.time() - t0) * 1000)
            except Exception:
                # 不是有效 JSON，可能是假代理返回的垃圾数据
                http_ok = False
    except Exception:
        http_ok = False

    if not http_ok:
        # HTTP 不通，整体不可用
        alive = False
    else:
        alive = True
        # Step 2: CONNECT 隧道测试（真正决定浏览器能否用）
        connect_ok, connect_latency = _test_connect_tunnel(
            proxy, "www.google.com", 443, timeout=timeout)
        if connect_ok:
            https_ok = True
            latency_ms = connect_latency

    # Step 3: 测速
    if alive:
        try:
            t1 = time.time()
            # 用 requests 测速（自动处理 HTTP/HTTPS）
            with requests.get(
                    "https://www.google.com/generate_204" if https_ok else "http://httpbin.org/ip",
                    proxies=proxies, timeout=timeout, stream=True, verify=False,
                    headers={"User-Agent": "Relay/1.7"}) as r:
                downloaded = 0
                for chunk in r.iter_content(8192):
                    downloaded += len(chunk)
                    if downloaded >= SPEED_SAMPLE_BYTES:
                        break
            dt = time.time() - t1
            if dt > 0 and downloaded > 0:
                speed_kbps = (downloaded / 1024) / dt
        except Exception:
            speed_kbps = 0.0

    proxy["https_ok"] = https_ok
    store.update_proxy_status(proxy, alive, latency_ms, speed_kbps)
    return proxy


def check_batch(proxies: List[Dict],
                workers: int = CHECK_WORKERS,
                on_done: Optional[Callable[[Dict, int, int], None]] = None,
                only_unchecked: bool = False,
                cancel_event: Optional["threading.Event"] = None) -> Tuple[List[Dict], bool]:
    """并发验证。

    返回 (proxies, was_cancelled)。
    - was_cancelled=True 表示在 cancel_event 触发后主动中止，剩余未验证条目跳过。
    - 分块 submit：点取消后还没入池的任务不再提交，响应速度从"几万个任务全跑完"
      缩成"当前 chunk + worker 数"内即退出。
    """
    import threading as _th  # 避免循环 import 告警（checker 顶部已 import threading，
                             # 这里加别名兼容万一将来改名，实际用顶部的即可）

    targets = []
    for p in proxies:
        if only_unchecked and p.get("last_check"):
            continue
        targets.append(p)
    total = len(targets)
    done = 0
    cancelled = False
    lock = threading.Lock()

    CHUNK = max(workers * 4, 64)  # 每次提交 4*workers，取消响应粒度 ≈ CHUNK

    def _is_cancel():
        return cancel_event is not None and cancel_event.is_set()

    with ThreadPoolExecutor(max_workers=workers) as ex:
        # 分块 submit：每块之前检查取消位
        for i in range(0, total, CHUNK):
            if _is_cancel():
                cancelled = True
                break
            chunk = targets[i:i + CHUNK]
            future_map = {ex.submit(check_one, p): p for p in chunk}
            for fut in as_completed(future_map):
                p = future_map[fut]
                try:
                    fut.result()
                except Exception:
                    p["alive"] = False
                    p["https_ok"] = False
                with lock:
                    done += 1
                if on_done:
                    on_done(p, done, total)
    # 取消后剩余 targets 里未处理的也标成未检测（不要留上次的 old alive 误导用户）
    if cancelled and done < total:
        for p in targets[done:]:
            p["alive"] = False
            p["latency_ms"] = 0
            p["speed_kbps"] = 0.0
    return proxies, cancelled
