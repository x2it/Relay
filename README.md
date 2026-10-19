# Relay · 免费代理工具箱

[![CI](https://github.com/x2it/Relay/actions/workflows/ci.yml/badge.svg)](https://github.com/x2it/Relay/actions/workflows/ci.yml)
![Version](https://img.shields.io/github/v/release/x2it/Relay)

免费代理工具箱：一键抓取、多级验证、转发公开代理与加密节点。Windows 桌面版 + Android 版，极简极客视觉，知行工作室出品。

**Relay** is a free proxy toolbox with a Windows desktop edition (Python + tkinter) and an Android edition (Kotlin + Compose). It fetches public proxies from 44 open sources, validates them in layers, and forwards traffic through a local HTTP proxy or a VPN tunnel.

<img src="https://raw.githubusercontent.com/x2it/Relay/main/banner.png" alt="Relay · 免费代理工具箱" width="100%">

## 两个版本

| 版本 | 平台 | 路径 | 当前版本 |
|------|------|------|------|
| Relay Desktop | Windows 桌面 | 根目录 | v2.0.0 |
| Relay Android | Android | `android/` | v2.0.0 |

---

## Relay Desktop（Windows 桌面版）

Python + tkinter 的桌面代理工具，与 Android 版功能对齐。

### 功能

- **代理抓取**：内置 44 个开源数据源（A/B/C/D/E 五类），GitHub 镜像自动回退，抓取状态实时回显
- **全协议转发**：HTTP / HTTPS / SOCKS4 / SOCKS4a / SOCKS5 + 加密节点 SS / VMess / VLESS / Trojan（内置协议实现）
- **订阅解析**：Base64 整体解码 + 分享链接逐行识别（`ss://` `vmess://` `vless://` `trojan://`）
- **代理验证**：连通性 + CONNECT 隧道 + 测速，加密节点走真实隧道检测
- **本地转发**：内置 HTTP 代理服务器（默认 `127.0.0.1:8888`），失效自动切换，可锁定上游
- **智能分流**：global / smart / direct 三模式，国内直连、海外走代理
- **系统代理**：一键开启 Windows 系统代理（两阶段提交 + 必达恢复 + 紧急恢复按钮）
- **导入导出**：JSON / CSV / TXT（加密节点导出为分享链接）

### 数据源分类

| 类别 | 说明 | 数量 |
|------|------|------|
| A | JSON/API 源 | 4 |
| B | GitHub RAW（含镜像） | 27 |
| C | HTML 表格 | 2 |
| D | TXT/CSV 镜像池 | 5 |
| E | 加密节点订阅源 | 6 |

### 运行

```bash
pip install -r requirements.txt
python main.py
```

### 测试

```bash
pip install -r requirements-dev.txt
pytest -q      # 单元测试（协议解析 / 转发握手 / 验证 / 存储 / 锁定）
ruff check .   # 静态检查
```

CI 已接入 GitHub Actions（编译检查 + ruff + pytest，见 `.github/workflows/ci.yml`）。

### 打包

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 portable   # onedir 绿色版
powershell -ExecutionPolicy Bypass -File build.ps1 installer  # 单文件 exe
```

### 技术栈

Python 3.10+ / tkinter / requests / beautifulsoup4 / cryptography / PyInstaller

---

## Relay Android

轻量代理应用：抓取公开代理、多级验证、一键连接 VPN 隧道，加密节点经 sing-box 内核转发。

### 功能

- **代理抓取**：内置 44 个公开源（与桌面版同源配置），自动去重
- **多级验证**：TCP 连通 → HTTP → HTTPS 站点标记，跳过 30 分钟内已验证节点，并发数可调
- **一键连接**：明文节点走内建 VPN 隧道；SS / VMess / VLESS / Trojan 加密节点经 **sing-box v1.14.3（libbox）内核** 转发
- **流量统计**：实时速率（KB/s）+ 累计流量
- **智能分流**：国内直连、海外走代理，可排除指定 App
- **节点导入**：订阅解析、二维码扫描、分享链接
- **正式签名**：release APK 使用独立 keystore 签名（keystore 不入库）

### 下载

前往 [Releases](../../releases) 下载最新 APK。

### 技术栈

- Kotlin + Jetpack Compose（Material 3）
- Room + DataStore（持久化）
- VpnService + sing-box libbox（VPN 隧道与加密节点内核）
- OkHttp（代理验证）

### 构建

```bash
# 1) 构建 libbox 内核 AAR（需 Go 1.23+ 与 Android NDK 26；产物不入库）
bash android/scripts/build-libbox.sh

# 2) 构建 APK（需 JDK 17 + Android SDK）
cd android
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

CI 中由 GitHub Actions 自动执行上述两步。

---

## 项目结构

```
.
├── android/              # Relay Android 版
│   ├── app/src/main/     # Kotlin 源码
│   ├── scripts/          # libbox 内核构建脚本
│   └── build.gradle.kts
├── core/                 # 桌面版核心（抓取/验证/转发/协议）
│   ├── protocols/        # SS/VMess/VLESS/Trojan 协议实现
│   ├── fetcher.py        # 多源并发抓取 + 镜像回退 + 状态回显
│   ├── checker.py        # 验证与测速
│   └── local_proxy.py    # 本地代理服务器 + 系统代理管理
├── tests/                # 桌面版单元测试（pytest）
├── ui/                   # 桌面版极简极客风格 UI
├── .github/workflows/    # CI（Python 检查 + Android 构建）
├── config.py             # 全局配置 + 44 个内置数据源
├── main.py               # 桌面版入口
└── README.md
```

## 许可证

[MIT](LICENSE) © 2026 知行工作室 · [https://w3b.pub/](https://w3b.pub/) · support@w3b.pub
