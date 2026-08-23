# Relay

极客 terminal 风格 Android 代理工具 —— 一键抓取、验证、连接公开代理与加密节点。

```
[HOME] [LIST] [CONF]
[ ] → [○]   选择即所见
```

## 特性

- **抓取发现**：45 个内置开源数据源，覆盖 HTTP / HTTPS / SOCKS4 / SOCKS5 / 加密节点订阅
  - A: JSON 原生 API · B: GitHub RAW · C: HTML 表 · D: TXT/CSV 镜像池 · E: 订阅（share-link / Base64）
  - 支持自定义数据源增删改、镜像、定时抓取状态回显
- **加密节点**：VMess / Trojan / VLESS / Shadowsocks，集成 sing-box 核心出站
  - 分享链接（vmess://、trojan://、vless://、ss://）自动解析
  - 整体 Base64 订阅自动解码
- **扫码添加**：内置 CameraX + ML Kit 扫码，支持从相册导入二维码
- **验证**：四层协议验证 + 多线程测速 + 智能筛选
- **连接**：本地 VPN 隧道转发，smart / global 路由

## 构建

```bash
# 1. 放置 sing-box 核心（本仓库不含，42MB 二进制）
#    从 sing-box Android 官方 release 下载 libbox.aar 放到 app/libs/

# 2. 编译
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 版本

v1.4.1 (17) · 见 GitHub Releases
