# -*- coding: utf-8 -*-
"""分享链接解析测试：ss / vmess / vless / trojan + 订阅文本（含非法输入）。"""
import base64
import json

from core.protocols.links import (
    parse_link, parse_ss, parse_vmess, parse_vless, parse_trojan,
    parse_subscription, to_share_link,
)

UUID = "b831381d-6324-4d53-ab92-7510788b939e"


def _b64(s: str) -> str:
    return base64.b64encode(s.encode()).decode()


# ---------------- ss:// ----------------

def test_parse_ss_sip002_base64_userinfo():
    uri = f"ss://{_b64('aes-256-gcm:password')}@1.2.3.4:8388#%E8%8A%82%E7%82%B9A"
    node = parse_ss(uri)
    assert node is not None
    assert node["protocol"] == "ss"
    assert node["ip"] == "1.2.3.4"
    assert node["port"] == 8388
    assert node["config"]["method"] == "aes-256-gcm"
    assert node["config"]["password"] == "password"
    assert node["name"] == "节点A"


def test_parse_ss_plain_userinfo():
    node = parse_ss("ss://chacha20-ietf-poly1305:secret@5.6.7.8:1234#foo")
    assert node is not None
    assert node["config"]["method"] == "chacha20-ietf-poly1305"
    assert node["config"]["password"] == "secret"
    assert node["port"] == 1234


def test_parse_ss_legacy_whole_base64():
    raw = _b64("aes-128-gcm:pass@9.9.9.9:8388")
    node = parse_ss("ss://" + raw)
    assert node is not None
    assert node["ip"] == "9.9.9.9"
    assert node["config"]["password"] == "pass"


def test_parse_ss_invalid_inputs():
    assert parse_ss("ss://@1.2.3.4:8388") is None          # 无 userinfo
    assert parse_ss("ss://aes-256-gcm@1.2.3.4:8388") is None  # 无密码
    assert parse_ss("ss://aes-256-gcm:pass@1.2.3.4:notaport") is None
    assert parse_ss("ss://aes-256-gcm:pass@1.2.3.4") is None  # 无端口
    assert parse_ss("ss://!!!@@bad@@:8388") is None


# ---------------- vmess:// ----------------

def _vmess_uri(**over):
    d = {
        "v": "2", "ps": "vmess节点", "add": "10.0.0.1", "port": "443",
        "id": UUID, "aid": "0", "net": "tcp", "type": "none",
        "host": "", "path": "", "tls": "tls",
    }
    d.update(over)
    return "vmess://" + base64.b64encode(
        json.dumps(d, ensure_ascii=False).encode()).decode()


def test_parse_vmess_valid_tcp():
    node = parse_vmess(_vmess_uri())
    assert node is not None
    assert node["protocol"] == "vmess"
    assert node["ip"] == "10.0.0.1"
    assert node["port"] == 443
    assert node["config"]["id"] == UUID
    assert node["config"]["tls"] is True
    assert node["name"] == "vmess节点"


def test_parse_vmess_ws_rejected():
    node = parse_vmess(_vmess_uri(net="ws"))
    assert node is None


def test_parse_vmess_missing_fields():
    assert parse_vmess(_vmess_uri(id="")) is None
    assert parse_vmess(_vmess_uri(add="")) is None
    assert parse_vmess(_vmess_uri(port="abc")) is None
    # 非法 base64 / JSON
    assert parse_vmess("vmess://###notbase64###") is None


# ---------------- vless:// ----------------

def test_parse_vless_tls():
    uri = f"vless://{UUID}@203.0.113.5:443?security=tls&sni=cdn.example.com#vless节点"
    node = parse_vless(uri)
    assert node is not None
    assert node["protocol"] == "vless"
    assert node["config"]["id"] == UUID
    assert node["config"]["tls"] is True
    assert node["config"]["sni"] == "cdn.example.com"


def test_parse_vless_plain():
    node = parse_vless(f"vless://{UUID}@203.0.113.6:8443#p")
    assert node is not None
    assert node["config"]["tls"] is False


def test_parse_vless_invalid():
    assert parse_vless("vless://" + UUID + "@203.0.113.7") is None  # 无端口
    assert parse_vless(f"vless://{UUID}@203.0.113.7:443?type=ws") is None
    assert parse_vless("vless://no-at-sign:443") is None


# ---------------- trojan:// ----------------

def test_parse_trojan():
    uri = "trojan://s3cret@198.51.100.9:443?sni=tr.example.com#trojan节点"
    node = parse_trojan(uri)
    assert node is not None
    assert node["protocol"] == "trojan"
    assert node["config"]["password"] == "s3cret"
    assert node["config"]["sni"] == "tr.example.com"
    assert node["config"]["tls"] is True


def test_parse_trojan_invalid():
    assert parse_trojan("trojan://pass@198.51.100.10") is None
    assert parse_trojan("trojan://pass@198.51.100.10:abc") is None
    assert parse_trojan("trojan://no-at-sign") is None


# ---------------- 通用入口 / 订阅 / 回链 ----------------

def test_parse_link_unknown_scheme():
    assert parse_link("http://1.2.3.4:8080") is None
    assert parse_link("ftp://x") is None
    assert parse_link("") is None
    assert parse_link(None) is None


def test_parse_subscription_multiline():
    text = "\n".join([
        f"ss://{_b64('aes-256-gcm:p')}@1.1.1.1:8388#n1",
        "   ",
        "trojan://pw@2.2.2.2:443#n2",
        "garbage line without scheme",
    ])
    nodes = parse_subscription(text)
    assert len(nodes) == 2
    assert {n["protocol"] for n in nodes} == {"ss", "trojan"}


def test_parse_subscription_base64():
    body = f"ss://{_b64('aes-256-gcm:p')}@3.3.3.3:8388#n\n" \
           f"vless://{UUID}@4.4.4.4:443#v\n"
    nodes = parse_subscription(_b64(body))
    assert len(nodes) == 2


def test_parse_subscription_garbage():
    assert parse_subscription("随便一段没有链接的文字") == []
    assert parse_subscription("") == []


def test_to_share_link_roundtrip():
    ss_node = {"protocol": "ss", "ip": "1.2.3.4", "port": 8388,
               "name": "n", "config": {"method": "aes-256-gcm", "password": "p"}}
    link = to_share_link(ss_node)
    assert link.startswith("ss://")
    assert parse_link(link)["config"]["password"] == "p"

    vless_node = {"protocol": "vless", "ip": "5.5.5.5", "port": 443,
                  "name": "v", "config": {"id": UUID, "tls": True, "sni": "x"}}
    link = to_share_link(vless_node)
    assert link.startswith("vless://")
    assert parse_link(link)["config"]["id"] == UUID

    trojan_node = {"protocol": "trojan", "ip": "6.6.6.6", "port": 443,
                   "name": "t", "config": {"password": "pw", "sni": ""}}
    assert to_share_link(trojan_node).startswith("trojan://")

    # 明文代理不支持回链
    assert to_share_link({"protocol": "http", "ip": "1.1.1.1", "port": 8080}) is None
