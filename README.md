# Relay

免费代理工具，提供 Windows 桌面版与 Android 版，一键抓取、验证、转发公开代理与加密节点。统一 terminal 暗色风格。

© 2026 知行工作室

## 两个版本

| 版本 | 平台 | 路径 | 当前版本 |
|------|------|------|------|
| Relay Desktop | Windows 桌面 | 根目录 | v1.7.0 |
| Relay Android | Android | `android/` | v1.4.1 |

---

## Relay Desktop（Windows 桌面版）

Python + tkinter 的终端风格代理工具，与 Android 版功能对齐。

### 功能

- **terminal 暗色 UI**：`[HOME][LIST][CONF]` 导航、等宽字体、`[●]/[○]` 状态标记
- **代理抓取**：内置 44 个开源数据源（A/B/C/D/E 五类），GitHub 镜像自动回退，抓取状态实时回显
- **全协议支持**：HTTP / HTTPS / SOCKS4 / SOCKS5 + 加密节点 SS / VMess / VLESS / Trojan
- **订阅解析**：Base64 整体解码 + 分享链接逐行识别（`ss://` `vmess://` `vless://` `trojan://`）
- **代理验证**：连通性 + CONNECT 隧道 + 测速，加密节点走真实隧道检测
- **本地转发**：内置 HTTP 代理服务器（默认 `127.0.0.1:8888`），失效自动切换
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

### 打包

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 portable   # onedir 绿色版
powershell -ExecutionPolicy Bypass -File build.ps1 installer  # 单文件 exe
```

### 技术栈

Python 3.10+ / tkinter / requests / beautifulsoup4 / cryptography / PyInstaller

---

## Relay Android

轻量代理应用，抓取公开代理、四层验证、一键连接 VPN 隧道。

### 功能

- **代理抓取**：内置 44 个公开源（与桌面版同源配置），自动去重
- **四层验证**：L1 TCP → L2 HTTP → L3 HTTPS(Google) → L4 站点(YouTube/Facebook)
- **智能筛选**：跳过 30 分钟内已验证的，只测需要测的；并发数可调
- **一键连接**：sing-box VPN 隧道 + 前台通知 + 实时网速
- **流量统计**：实时速率（KB/s）+ 累计流量
- **智能分流**：国内直连、海外走代理，可排除指定 App
- **加密节点**：支持 VMess / Trojan / VLESS / SS 订阅导入与二维码扫描

### 下载

前往 [Releases](../../releases) 下载最新 APK，直接覆盖安装。

### 技术栈

- Kotlin + Jetpack Compose（Material 3）
- Room + DataStore（持久化）
- VpnService + sing-box（TUN 隧道）
- OkHttp（代理验证）

### 构建

```bash
cd android
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

---

## 项目结构

```
.
├── android/              # Relay Android 版
│   ├── app/src/main/     # Kotlin 源码
│   └── build.gradle.kts
├── core/                 # 桌面版核心（抓取/验证/转发/协议）
│   ├── protocols/        # SS/VMess/VLESS/Trojan 协议实现
│   ├── fetcher.py        # 多源并发抓取 + 镜像回退 + 状态回显
│   ├── checker.py        # 验证与测速
│   └── local_proxy.py    # 本地代理服务器 + 系统代理管理
├── ui/                   # 桌面版 terminal 风格 UI
├── config.py             # 全局配置 + 44 个内置数据源
├── main.py               # 桌面版入口
└── README.md
```

## 许可证

[MIT License](LICENSE)
