# LiteProxy

轻量级免费代理工具，一键抓取、验证、转发公开代理，支持智能分流。

## 功能

- **代理抓取**：内置 20+ 公开代理源（TheSpeedX、Monosans、ProxyScrape 等），并发抓取，自动去重
- **代理验证**：多线程验证连通性 + CONNECT 隧道能力 + TLS 握手 + 测速
- **本地转发**：内置 HTTP 代理服务器，支持 HTTP / SOCKS5 上游代理
- **智能分流**：国内域名直连、海外域名走代理，可自定义规则
- **系统代理**：一键开启/关闭 Windows 系统代理，异常自动恢复
- **数据管理**：JSON / CSV / TXT 导入导出，代理锁定，排序过滤

## 快速开始

### 运行源码

```bash
pip install -r requirements.txt
python main.py
```

### 打包为 exe

```powershell
# 便携版（onedir，推荐）
powershell -ExecutionPolicy Bypass -File build.ps1 portable

# 单文件版（onefile）
powershell -ExecutionPolicy Bypass -File build.ps1 installer
```

## 使用流程

1. 点击「抓取」从公开源获取代理列表
2. 点击「验证」测试代理连通性和速度
3. 点击「启动」开启本地代理服务器
4. 点击「系统代理」将 Windows 代理指向本地端口
5. 浏览器访问目标网站即可

## 项目结构

```
proxy_tool/
├── main.py              # 程序入口
├── config.py            # 配置与常量
├── build.ps1            # 打包脚本
├── LiteProxy.spec       # PyInstaller 配置
├── requirements.txt     # 依赖
├── core/
│   ├── fetcher.py       # 代理抓取
│   ├── checker.py       # 代理验证与测速
│   ├── local_proxy.py   # 本地代理服务器 + 系统代理管理
│   └── store.py         # 数据存储与导入导出
├── ui/
│   ├── main_window.py   # 主窗口
│   ├── dialogs.py       # 设置/源管理/分流规则对话框
│   └── style.py         # 主题样式
└── data/
    ├── sources.json      # 代理源配置
    └── proxies.json     # 代理数据（运行时生成）
```

## 技术栈

- Python 3.10+
- tkinter（GUI）
- requests（HTTP 请求）
- beautifulsoup4（HTML 解析）
- PyInstaller（打包）

## 许可证

[MIT License](LICENSE)
