# -*- coding: utf-8 -*-
"""目标地址编码，供 SS / Trojan / VLess / VMess 各协议复用。

两种格式：
- SOCKS5 风格（SS / Trojan 使用）：
  [atyp(1)] [addr...] [port(2, big-endian)]
  atyp: 1=IPv4, 3=域名, 4=IPv6
- v2ray 风格（VLess / VMess 使用）：
  [port(2, big-endian)] [atyp(1)] [addr...]
  atyp: 1=IPv4, 2=域名, 3=IPv6
"""
import socket


# ---------- SOCKS5 风格编码 ----------

def encode_socks_addr(host: str, port: int) -> bytes:
    """atyp(1) + addr + port(2)。"""
    try:
        return b"\x01" + socket.inet_pton(socket.AF_INET, host) + port.to_bytes(2, "big")
    except OSError:
        pass
    try:
        return b"\x04" + socket.inet_pton(socket.AF_INET6, host) + port.to_bytes(2, "big")
    except OSError:
        pass
    hb = host.encode("idna")
    if len(hb) > 255:
        hb = hb[:255]
    return b"\x03" + bytes([len(hb)]) + hb + port.to_bytes(2, "big")


def decode_socks_addr(data: bytes, offset: int = 0):
    """从 data 的 offset 处解析 SOCKS5 风格地址，返回 (host, port, consumed)。"""
    if offset + 3 > len(data):
        raise ValueError("地址数据不足")
    atyp = data[offset]
    offset += 1
    if atyp == 0x01:  # IPv4
        if offset + 6 > len(data):
            raise ValueError("IPv4 地址不足")
        host = socket.inet_ntop(socket.AF_INET, data[offset:offset + 4])
        offset += 4
    elif atyp == 0x04:  # IPv6
        if offset + 18 > len(data):
            raise ValueError("IPv6 地址不足")
        host = socket.inet_ntop(socket.AF_INET6, data[offset:offset + 16])
        offset += 16
    elif atyp == 0x03:  # 域名
        if offset + 1 > len(data):
            raise ValueError("域名长度不足")
        dlen = data[offset]
        offset += 1
        if offset + dlen + 2 > len(data):
            raise ValueError("域名数据不足")
        host = data[offset:offset + dlen].decode("idna", "ignore")
        offset += dlen
    else:
        raise ValueError(f"未知地址类型: {atyp}")
    port = int.from_bytes(data[offset:offset + 2], "big")
    return host, port, offset + 2


# ---------- v2ray 风格编码（port 在前） ----------

_ATYP_IPV4 = 1
_ATYP_DOMAIN = 2
_ATYP_IPV6 = 3


def encode_v2ray_addr(host: str, port: int) -> bytes:
    """port(2) + atyp(1) + addr。atyp: 1=IPv4, 2=域名, 3=IPv6。"""
    out = port.to_bytes(2, "big")
    try:
        return out + bytes([_ATYP_IPV4]) + socket.inet_pton(socket.AF_INET, host)
    except OSError:
        pass
    try:
        return out + bytes([_ATYP_IPV6]) + socket.inet_pton(socket.AF_INET6, host)
    except OSError:
        pass
    hb = host.encode("idna")
    if len(hb) > 255:
        hb = hb[:255]
    return out + bytes([_ATYP_DOMAIN, len(hb)]) + hb


def decode_v2ray_addr(data: bytes, offset: int = 0):
    """从 data 的 offset 处解析 v2ray 风格地址，返回 (host, port, consumed)。"""
    if offset + 3 > len(data):
        raise ValueError("地址数据不足")
    port = int.from_bytes(data[offset:offset + 2], "big")
    atyp = data[offset + 2]
    offset += 3
    if atyp == _ATYP_IPV4:
        if offset + 4 > len(data):
            raise ValueError("IPv4 地址不足")
        host = socket.inet_ntop(socket.AF_INET, data[offset:offset + 4])
        offset += 4
    elif atyp == _ATYP_IPV6:
        if offset + 16 > len(data):
            raise ValueError("IPv6 地址不足")
        host = socket.inet_ntop(socket.AF_INET6, data[offset:offset + 16])
        offset += 16
    elif atyp == _ATYP_DOMAIN:
        if offset + 1 > len(data):
            raise ValueError("域名长度不足")
        dlen = data[offset]
        offset += 1
        if offset + dlen > len(data):
            raise ValueError("域名数据不足")
        host = data[offset:offset + dlen].decode("idna", "ignore")
        offset += dlen
    else:
        raise ValueError(f"未知地址类型: {atyp}")
    return host, port, offset
