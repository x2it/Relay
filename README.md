# LiteProxy / Relay

免费代理工具，提供桌面版与 Android 版，一键抓取、验证、转发公开代理。

## 两个版本

| 版本 | 平台 | 路径 | 状态 |
|------|------|------|------|
| LiteProxy | Windows 桌面 | 根目录 | 稳定 |
| Relay | Android | `android/` | v1.2.0 |

---

## Relay（Android 版）

轻量代理应用，抓取公开代理、四层验证、一键连接 VPN 隧道。

### 功能

- 代理抓取：内置 20+ 公开源，自动去重
- 四层验证：L1 TCP → L2 HTTP → L3 HTTPS(Google) → L4 站点(YouTube/Facebook)
- 智能筛选：跳过 30 分钟内已验证的，只测需要测的；并发数可调（默认 8）
- 一键连接：VPN 隧道 + 前台通知 + 实时网速
- 流量统计：实时速率（KB/s）+ 累计流量
- 智能分流：国内直连、海外走代理，可排除指定 App

### 下载

前往 [Releases](../../releases) 下载最新 APK，直接覆盖安装。

### 技术栈

- Kotlin + Jetpack Compose（Material 3）
- Room + DataStore（持久化）
- VpnService（TUN 隧道）
- OkHttp（代理验证）
- targetSdk 33（兼容 Android 14）

### 构建

```bash
cd android
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

---

## LiteProxy（桌面版）

Windows 桌面代理工具，GUI 基于 tkinter。

### 功能

- 代理抓取：内置 20+ 公开代理源
- 代理验证：连通性 + CONNECT 隧道 + TLS + 测速
- 本地转发：内置 HTTP 代理服务器
- 智能分流：国内直连、海外走代理
- 系统代理：一键开启 Windows 系统代理

### 运行

```bash
pip install -r requirements.txt
python main.py
```

### 打包

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 portable
```

### 技术栈

Python 3.10+ / tkinter / requests / beautifulsoup4 / PyInstaller

---

## 项目结构

```
.
├── android/              # Relay Android 版
│   ├── app/src/main/     # Kotlin 源码
│   └── build.gradle.kts
├── core/                 # LiteProxy 桌面版核心
├── ui/                   # LiteProxy 桌面版 UI
├── main.py               # 桌面版入口
└── README.md
```

## 许可证

[MIT License](LICENSE)