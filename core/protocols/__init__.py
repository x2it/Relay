# -*- coding: utf-8 -*-
"""加密代理协议客户端集合（SS / VMess / VLess / Trojan）。

对外提供统一入口 connect_proxy()，本地代理与连通性检测复用同一套逻辑。
"""
from .base import TunnelStream
from . import ss, trojan, vless, vmess
from .tlsio import tls_over_stream
from .links import parse_link, parse_subscription, to_share_link

# 加密隧道协议（连上服务器后即直达目标站点）
ENCRYPTED_PROTOCOLS = ("ss", "vmess", "vless", "trojan")
# 隧道类协议：连接建立后即为"到目标站点"的直连隧道（与 HTTP 上游不同）
TUNNEL_PROTOCOLS = ("socks5",) + ENCRYPTED_PROTOCOLS

# 全部支持的协议（用于 UI 过滤 / 排序）
ALL_PROTOCOLS = ("http", "https", "socks5") + ENCRYPTED_PROTOCOLS

# 协议中文名（用于 UI 展示）
PROTOCOL_LABELS = {
    "http": "HTTP", "https": "HTTPS", "socks5": "SOCKS5",
    "ss": "Shadowsocks", "vmess": "VMess", "vless": "VLess", "trojan": "Trojan",
}


def connect_proxy(proxy, target_host, target_port, timeout=15):
    """建立到目标站点的加密隧道，返回 TunnelStream（或抛异常）。

    参数 proxy 需包含: protocol / ip / port / config。
    """
    proto = (proxy.get("protocol") or "http").lower()
    if proto not in ENCRYPTED_PROTOCOLS:
        raise ValueError(f"非加密协议: {proto}")
    cfg = proxy.get("config") or {}
    ip = proxy["ip"]
    port = int(proxy["port"])

    if proto == "ss":
        return ss.connect(ip, port, cfg.get("method", "aes-256-gcm"),
                          cfg.get("password", ""), target_host, target_port,
                          timeout=timeout)
    if proto == "trojan":
        return trojan.connect(ip, port, cfg.get("password", ""),
                              target_host, target_port,
                              sni=cfg.get("sni", ""),
                              verify_cert=bool(cfg.get("verify_cert")),
                              timeout=timeout)
    if proto == "vless":
        return vless.connect(ip, port, cfg.get("id", ""), target_host, target_port,
                             tls=bool(cfg.get("tls")), sni=cfg.get("sni", ""),
                             verify_cert=bool(cfg.get("verify_cert")),
                             timeout=timeout)
    if proto == "vmess":
        return vmess.connect(ip, port, cfg.get("id", ""), target_host, target_port,
                             tls=bool(cfg.get("tls")), sni=cfg.get("sni", ""),
                             verify_cert=bool(cfg.get("verify_cert")),
                             timeout=timeout)
    raise ValueError(f"不支持的协议: {proto}")
