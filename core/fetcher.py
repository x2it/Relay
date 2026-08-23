# -*- coding: utf-8 -*-
"""代理抓取模块：并发抓取多源 + 失败重试 + 镜像回退 + 状态回显。"""
import re
import time
from typing import List, Dict, Callable, Optional
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

# 抑制 InsecureRequestWarning（部分源站点证书不全）
try:
    import urllib3
    urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
except Exception:
    pass

from core import store
from config import FETCH_TIMEOUT, FETCH_WORKERS, FETCH_RETRIES

# 简单的 ip:port 正则
_IP_PORT_RE = re.compile(r"(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):(\d{2,5})")

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")


def _get(url: str, timeout: int = FETCH_TIMEOUT) -> str:
    last_err = None
    for attempt in range(FETCH_RETRIES + 1):
        try:
            r = requests.get(url, headers={"User-Agent": UA}, timeout=timeout,
                             verify=False)
            return r.text
        except Exception as e:
            last_err = e
            if attempt < FETCH_RETRIES:
                time.sleep(0.6)
    raise last_err if last_err else RuntimeError("fetch failed")


def _parse_plain(text: str, protocol: str, source_name: str) -> List[Dict]:
    items = []
    for m in _IP_PORT_RE.finditer(text):
        ip, port = m.group(1), m.group(2)
        if 1 <= int(port) <= 65535:
            items.append(store.new_proxy(ip, int(port), protocol,
                                         source=source_name))
    return items


def _parse_html_table(text: str, protocol: str, source_name: str) -> List[Dict]:
    """适配 free-proxy-list.net 的表格结构。"""
    items = []
    try:
        from bs4 import BeautifulSoup
    except Exception:
        return _parse_plain(text, protocol, source_name)

    soup = BeautifulSoup(text, "html.parser")
    table = soup.find("table")
    if not table:
        return _parse_plain(text, protocol, source_name)

    for row in table.select("tbody tr"):
        cols = row.find_all("td")
        if len(cols) < 2:
            continue
        ip = cols[0].get_text(strip=True)
        port_str = cols[1].get_text(strip=True)
        if not _IP_PORT_RE.match(f"{ip}:{port_str}"):
            continue
        country = cols[3].get_text(strip=True) if len(cols) > 3 else ""
        anonymity = cols[4].get_text(strip=True).lower() if len(cols) > 4 else ""
        https_flag = cols[6].get_text(strip=True).lower() if len(cols) > 6 else "no"
        proto = "https" if https_flag == "yes" else "http"
        items.append(store.new_proxy(ip, int(port_str), proto,
                                     country=country, source=source_name,
                                     anonymity=anonymity))
    return items


def _parse_geonode_json(text: str, protocol: str, source_name: str) -> List[Dict]:
    items = []
    try:
        import json
        data = json.loads(text)
        rows = data.get("data") or data.get("proxies") or []
    except Exception:
        return items
    for r in rows:
        ip = r.get("ip")
        port = r.get("port")
        if not ip or not port:
            continue
        protos = r.get("protocols") or [protocol]
        proto = protos[0] if isinstance(protos, list) else r.get("protocol", protocol)
        items.append(store.new_proxy(
            ip, int(port), str(proto).lower(),
            country=r.get("country", ""),
            source=source_name,
            anonymity=r.get("anonymityLevel", ""),
        ))
    return items


def _parse_subscription(text: str, protocol: str, source_name: str) -> List[Dict]:
    """订阅源解析：Base64 整体解码 + ss/vmess/vless/trojan 分享链接逐行识别。"""
    from core.protocols.links import parse_subscription as _ps
    nodes = _ps(text)
    for node in nodes:
        node["source"] = source_name
        node.setdefault("country", "")
    return nodes


_PARSERS = {
    "plain": _parse_plain,
    "html_table": _parse_html_table,
    "geonode_json": _parse_geonode_json,
    "subscription": _parse_subscription,
}


def fetch_source(source: Dict) -> List[Dict]:
    """抓取单个源。返回代理列表，失败返回空列表（已回写状态）。"""
    name = source.get("name", source.get("url", ""))
    url = source["url"]
    parser = source.get("parser", "plain")
    protocol = source.get("protocol", "http")
    timeout = int(source.get("timeout", FETCH_TIMEOUT) or FETCH_TIMEOUT) / 1000.0
    # 主 URL + 镜像依次尝试
    urls = [url] + list(source.get("mirrors") or [])
    last_err = None
    text = None
    for u in urls:
        try:
            text = _get(u, timeout=timeout)
            break
        except Exception as e:
            last_err = e
    if text is None:
        _write_status(source, "ERR", 0)
        raise last_err if last_err else RuntimeError(f"{name} 抓取失败")
    fn = _PARSERS.get(parser, _parse_plain)
    try:
        items = fn(text, protocol, name)
    except Exception as e:
        _write_status(source, "ERR", 0)
        raise e
    _write_status(source, "OK", len(items))
    return items


def _write_status(source: Dict, status: str, count: int):
    """回写单源抓取状态（供 UI 回显）。"""
    source["last_status"] = status
    source["last_count"] = count
    source["last_fetched_at"] = int(time.time())


def _dedup(items: List[Dict]) -> List[Dict]:
    seen = set()
    uniq = []
    for it in items:
        k = (it["ip"], it["port"], it["protocol"])
        if k not in seen:
            seen.add(k)
            uniq.append(it)
    return uniq


def fetch_all(sources: List[Dict],
              on_progress: Optional[Callable] = None) -> List[Dict]:
    """并发抓取所有源（仅抓 enabled=True 的）。

    on_progress(done, total, name, count, ok)
        done: 已完成数；total: 总数；name: 源名；count: 该源抓到数；ok: 是否成功
    """
    all_items: List[Dict] = []
    # 过滤掉被禁用的源
    active = [s for s in sources if s.get("enabled", True)]
    total = len(active)
    done = 0

    with ThreadPoolExecutor(max_workers=min(FETCH_WORKERS, total or 1)) as ex:
        future_map = {ex.submit(fetch_source, src): src for src in active}
        for fut in as_completed(future_map):
            src = future_map[fut]
            name = src.get("name", "")
            count = 0
            ok = True
            try:
                items = fut.result()
                items = _dedup(items)
                all_items.extend(items)
                count = len(items)
            except Exception as e:
                ok = False
                print(f"[fetcher] {name} 抓取失败: {e}")
            done += 1
            if on_progress:
                on_progress(done, total, name, count, ok)
    return all_items
