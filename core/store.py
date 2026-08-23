# -*- coding: utf-8 -*-
"""代理列表存储、导入、导出。"""
import json
import os
import time
import csv
import io
from typing import List, Dict, Optional

from config import (PROXY_DB, SOURCES_FILE, SETTINGS_FILE, DEFAULT_SOURCES,
                    DIRECT_DOMAINS_CN, PROXY_DOMAINS)


def _now() -> str:
    import datetime
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def new_proxy(ip: str, port: int, protocol: str = "http",
              country: str = "", source: str = "",
              anonymity: str = "", config: Optional[Dict] = None) -> Dict:
    """构造一个统一的代理数据结构。"""
    return {
        "ip": ip.strip(),
        "port": int(port),
        "protocol": protocol.lower(),
        "country": country,
        "source": source,
        "anonymity": anonymity,
        "config": config or {},      # 加密协议专属参数（method/password/id/sni 等）
        "alive": False,
        "latency_ms": 0,
        "speed_kbps": 0.0,
        "last_check": "",
    }


def load_proxies() -> List[Dict]:
    if not os.path.exists(PROXY_DB):
        return []
    try:
        with open(PROXY_DB, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, list) else []
    except Exception:
        return []


def save_proxies(proxies: List[Dict]) -> None:
    if not isinstance(proxies, list):
        return
    tmp = PROXY_DB + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(proxies, f, ensure_ascii=False, indent=2)
    os.replace(tmp, PROXY_DB)


def merge_proxies(existing: List[Dict], new_items: List[Dict]) -> tuple:
    """合并去重，返回 (合并后列表, 新增数量)。

    加密协议按 (ip, port, protocol, config) 去重，避免同主机不同密钥被误并。
    """
    def key_of(p):
        if p.get("protocol") in ("ss", "vmess", "vless", "trojan"):
            return (p["ip"], p["port"], p["protocol"],
                    json.dumps(p.get("config") or {}, sort_keys=True, ensure_ascii=False))
        return (p["ip"], p["port"], p["protocol"])

    seen = {key_of(p): i for i, p in enumerate(existing)}
    added = 0
    for item in new_items:
        key = key_of(item)
        if key not in seen:
            existing.append(item)
            seen[key] = len(existing) - 1
            added += 1
    return existing, added


def update_proxy_status(proxy: Dict, alive: bool, latency_ms: int,
                        speed_kbps: float) -> Dict:
    proxy["alive"] = alive
    proxy["latency_ms"] = latency_ms
    proxy["speed_kbps"] = round(speed_kbps, 1)
    proxy["last_check"] = _now()
    return proxy


def load_sources() -> List[Dict]:
    if not os.path.exists(SOURCES_FILE):
        save_sources(DEFAULT_SOURCES)
        return list(DEFAULT_SOURCES)
    try:
        with open(SOURCES_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return list(DEFAULT_SOURCES)


def save_sources(sources: List[Dict]) -> None:
    with open(SOURCES_FILE, "w", encoding="utf-8") as f:
        json.dump(sources, f, ensure_ascii=False, indent=2)


def load_settings() -> Dict:
    default = {
        "local_host": "127.0.0.1",
        "local_port": 8888,
        "auth_token": "",          # 非空时本地代理需带 Token 鉴权
        "auto_switch": True,       # 当前代理失效时自动切换
        "max_latency_ms": 3000,
        "min_speed_kbps": 0,
        "mode": "smart",           # global / smart / direct
        "direct_domains": list(DIRECT_DOMAINS_CN),   # 直连域名清单
        "proxy_domains": list(PROXY_DOMAINS),         # 强制代理清单
    }
    if not os.path.exists(SETTINGS_FILE):
        return default
    try:
        with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
            d = json.load(f)
            default.update(d)
            return default
    except Exception:
        return default


def save_settings(s: Dict) -> None:
    with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
        json.dump(s, f, ensure_ascii=False, indent=2)


# ---------- 导入 / 导出 ----------

def export_json(proxies: List[Dict], path: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(proxies, f, ensure_ascii=False, indent=2)


def export_csv(proxies: List[Dict], path: str) -> None:
    cols = ["ip", "port", "protocol", "country", "anonymity", "source",
            "alive", "https_ok", "latency_ms", "speed_kbps", "last_check"]
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for p in proxies:
            w.writerow([p.get(c, "") for c in cols])


def export_txt(proxies: List[Dict], path: str) -> None:
    """按协议分块导出。普通代理为 ip:port；加密代理导出分享链接。"""
    from core.protocols.links import to_share_link
    lines = []
    for proto in ["http", "https", "socks5", "ss", "vmess", "vless", "trojan"]:
        items = [p for p in proxies if p["protocol"] == proto]
        if items:
            lines.append(f"# {proto}")
            for p in items:
                if proto in ("ss", "vmess", "vless", "trojan"):
                    link = to_share_link(p)
                    lines.append(link or f"{p['ip']}:{p['port']}")
                else:
                    lines.append(f"{p['ip']}:{p['port']}")
            lines.append("")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def alive_proxies(proxies: List[Dict]) -> List[Dict]:
    """仅存活的代理（用于导出可用集）。"""
    return [p for p in proxies if p.get("alive")]


def import_file(path: str) -> List[Dict]:
    """自动识别 json/csv/txt 导入。txt 支持 ip:port 与 ss://vmess:// 等分享链接。"""
    ext = os.path.splitext(path)[1].lower()
    if ext == ".json":
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        items = []
        for d in data:
            if "ip" in d and "port" in d:
                p = new_proxy(
                    d["ip"], d["port"],
                    d.get("protocol", "http"),
                    d.get("country", ""),
                    d.get("source", "import"),
                    d.get("anonymity", ""),
                    d.get("config"),
                )
                # 保留验证字段
                p["alive"] = d.get("alive", False)
                p["https_ok"] = d.get("https_ok", False)
                p["latency_ms"] = d.get("latency_ms", 0)
                p["speed_kbps"] = d.get("speed_kbps", 0)
                p["last_check"] = d.get("last_check", "")
                items.append(p)
        return items
    if ext == ".csv":
        items = []
        with open(path, "r", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            for row in reader:
                try:
                    p = new_proxy(
                        row.get("ip", ""),
                        int(row.get("port", 0)),
                        row.get("protocol", "http"),
                        row.get("country", ""),
                        "import",
                        row.get("anonymity", ""),
                    )
                    # 保留验证字段
                    p["alive"] = str(row.get("alive", "")).lower() in ("true", "1", "yes")
                    p["https_ok"] = str(row.get("https_ok", "")).lower() in ("true", "1", "yes")
                    try: p["latency_ms"] = int(row.get("latency_ms", 0) or 0)
                    except: pass
                    try: p["speed_kbps"] = float(row.get("speed_kbps", 0) or 0)
                    except: pass
                    p["last_check"] = row.get("last_check", "")
                    items.append(p)
                except Exception:
                    continue
        return items
    # 当作 txt: 每行 ip:port 或分享链接，可选协议前缀
    from core.protocols.links import parse_link
    items = []
    cur_proto = "http"
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#"):
                tag = line[1:].strip().lower()
                if tag in ("http", "https", "socks5", "ss", "vmess", "vless", "trojan"):
                    cur_proto = tag
                continue
            if "://" in line:
                node = parse_link(line)
                if node:
                    items.append(node)
                    continue
                proto, rest = line.split("://", 1)
                cur_proto = proto.lower()
                line = rest
            if ":" not in line:
                continue
            ip, port = line.rsplit(":", 1)
            try:
                items.append(new_proxy(ip, int(port), cur_proto, source="import"))
            except Exception:
                continue
    return items
