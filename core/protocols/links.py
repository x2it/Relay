# -*- coding: utf-8 -*-
"""分享链接解析：ss:// vmess:// vless:// trojan:// 及订阅文本。

输出的每条节点为与 store 兼容的代理字典：
{
  "ip", "port", "protocol",
  "name", "country", "source",
  "config": { 协议专属参数 }
}
"""
import base64
import json
import urllib.parse


def _b64decode(s: str) -> str:
    s = (s or "").strip().replace("-", "+").replace("_", "/")
    pad = len(s) % 4
    if pad:
        s += "=" * (4 - pad)
    return base64.b64decode(s).decode("utf-8", "ignore")


def _unquote(s: str) -> str:
    try:
        return urllib.parse.unquote(s)
    except Exception:
        return s


def _node(protocol, host, port, config, name="", country="", source="订阅"):
    return {
        "ip": host.strip(),
        "port": int(port),
        "protocol": protocol,
        "name": name,
        "country": country,
        "source": source,
        "config": config,
    }


# ---------------- ss:// ----------------

def parse_ss(uri: str):
    rest = uri[5:]
    name = ""
    if "#" in rest:
        rest, name = rest.rsplit("#", 1)
        name = _unquote(name)
    # SIP002：userinfo 为 base64；旧式：整体 base64
    if "@" not in rest:
        try:
            rest = _b64decode(rest)
        except Exception:
            return None
    userinfo, hostport = rest.rsplit("@", 1)
    # userinfo 可能仍是 base64(method:password)
    method, password = None, None
    if ":" in userinfo:
        method, password = userinfo.split(":", 1)
    else:
        try:
            dec = _b64decode(userinfo)
            if ":" in dec:
                method, password = dec.split(":", 1)
        except Exception:
            pass
    if not method or not password:
        return None
    if ":" not in hostport:
        return None
    host, port_s = hostport.rsplit(":", 1)
    try:
        port = int(port_s)
    except ValueError:
        return None
    return _node("ss", _unquote(host), port,
                 {"method": method, "password": password}, name)


# ---------------- vmess:// ----------------

def parse_vmess(uri: str):
    b64 = uri[8:].split("#", 1)[0]
    try:
        data = json.loads(_b64decode(b64))
    except Exception:
        return None
    host = data.get("add") or data.get("address")
    port = data.get("port")
    uid = data.get("id")
    if not host or not port or not uid:
        return None
    net = (data.get("net") or "tcp").lower()
    # 仅支持 TCP 传输（raw），ws/http/h2/grpc 暂不支持
    if net not in ("tcp", ""):
        return None
    try:
        port = int(port)
    except (TypeError, ValueError):
        return None
    name = _unquote(data.get("ps") or "")
    tls = (data.get("tls") or "") == "tls"
    return _node("vmess", host, port,
                 {"id": uid, "tls": tls, "sni": data.get("sni") or "",
                  "net": "tcp", "security": data.get("scy") or "auto"},
                 name)


# ---------------- vless:// ----------------

def parse_vless(uri: str):
    rest = uri[8:]
    name = ""
    if "#" in rest:
        rest, name = rest.rsplit("#", 1)
        name = _unquote(name)
    if "@" not in rest:
        return None
    uid, hostport = rest.rsplit("@", 1)
    params = {}
    if "?" in hostport:
        hostport, qs = hostport.split("?", 1)
        params = dict(urllib.parse.parse_qsl(qs))
    if ":" not in hostport:
        return None
    host, port_s = hostport.rsplit(":", 1)
    try:
        port = int(port_s)
    except ValueError:
        return None
    net = (params.get("type") or "tcp").lower()
    if net not in ("tcp", ""):
        return None
    tls = (params.get("security") or "none").lower() != "none"
    return _node("vless", _unquote(host), port,
                 {"id": uid, "tls": tls, "sni": params.get("sni") or "",
                  "net": "tcp", "flow": params.get("flow") or ""},
                 name)


# ---------------- trojan:// ----------------

def parse_trojan(uri: str):
    rest = uri[9:]
    name = ""
    if "#" in rest:
        rest, name = rest.rsplit("#", 1)
        name = _unquote(name)
    if "@" not in rest:
        return None
    password, hostport = rest.rsplit("@", 1)
    params = {}
    if "?" in hostport:
        hostport, qs = hostport.split("?", 1)
        params = dict(urllib.parse.parse_qsl(qs))
    if ":" not in hostport:
        return None
    host, port_s = hostport.rsplit(":", 1)
    try:
        port = int(port_s)
    except ValueError:
        return None
    return _node("trojan", _unquote(host), port,
                 {"password": password, "sni": params.get("sni") or "",
                  "tls": True},
                 name)


# ---------------- 导出为分享链接 ----------------

def to_share_link(p: dict):
    """把代理字典转回分享链接（仅加密协议）。不支持时返回 None。"""
    proto = (p.get("protocol") or "").lower()
    cfg = p.get("config") or {}
    host = p.get("ip", "")
    try:
        port = int(p.get("port", 0))
    except (TypeError, ValueError):
        return None
    name_q = urllib.parse.quote(p.get("name") or "")

    if proto == "ss":
        userinfo = base64.b64encode(
            f"{cfg.get('method', 'aes-256-gcm')}:{cfg.get('password', '')}".encode()
        ).decode()
        s = f"ss://{userinfo}@{host}:{port}"
        return s + (f"#{name_q}" if name_q else "")

    if proto == "vmess":
        d = {
            "v": "2",
            "ps": p.get("name") or "",
            "add": host,
            "port": str(port),
            "id": cfg.get("id", ""),
            "aid": "0",
            "net": cfg.get("net", "tcp"),
            "type": "none",
            "host": "",
            "path": "",
            "tls": "tls" if cfg.get("tls") else "",
        }
        b64 = base64.b64encode(json.dumps(d, ensure_ascii=False).encode()).decode()
        return "vmess://" + b64

    if proto == "vless":
        params = {"encryption": "none", "type": "tcp"}
        if cfg.get("tls"):
            params["security"] = "tls"
            if cfg.get("sni"):
                params["sni"] = cfg["sni"]
        qs = urllib.parse.urlencode(params)
        s = f"vless://{cfg.get('id', '')}@{host}:{port}?{qs}"
        return s + (f"#{name_q}" if name_q else "")

    if proto == "trojan":
        qs = ""
        if cfg.get("sni"):
            qs = "?" + urllib.parse.urlencode({"sni": cfg["sni"]})
        s = f"trojan://{cfg.get('password', '')}@{host}:{port}{qs}"
        return s + (f"#{name_q}" if name_q else "")

    return None


# ---------------- 通用入口 ----------------

_PARSERS = {
    "ss": parse_ss,
    "vmess": parse_vmess,
    "vless": parse_vless,
    "trojan": parse_trojan,
}


def parse_link(uri: str):
    uri = (uri or "").strip()
    if "://" not in uri:
        return None
    scheme = uri.split("://", 1)[0].lower()
    parser = _PARSERS.get(scheme)
    if not parser:
        return None
    try:
        return parser(uri)
    except Exception:
        return None


def _try_decode_b64(text: str):
    s = "".join((text or "").split())
    if len(s) < 8:
        return None
    if not all(c in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=_-"
               for c in s):
        return None
    try:
        return _b64decode(s)
    except Exception:
        return None


def parse_subscription(text: str):
    """解析订阅文本（链接列表或纯 base64），返回代理字典列表。"""
    nodes = []
    text = text or ""
    # 1) 按行直接解析
    for line in text.splitlines():
        line = line.strip()
        if "://" in line:
            p = parse_link(line)
            if p:
                nodes.append(p)
    if nodes:
        return nodes
    # 2) 纯 base64 订阅
    dec = _try_decode_b64(text)
    if dec:
        for line in dec.splitlines():
            line = line.strip()
            if "://" in line:
                p = parse_link(line)
                if p:
                    nodes.append(p)
    return nodes
