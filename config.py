# -*- coding: utf-8 -*-
"""全局配置与常量。"""
import os
import sys

# 应用基础信息
APP_NAME = "LiteProxy"
APP_VERSION = "1.6.0"

# 数据目录：打包后放在 exe 同级，绿色版可携带
if getattr(sys, "frozen", False):
    _BASE = os.path.dirname(sys.executable)
    # PyInstaller onefile: 内嵌数据解压到 sys._MEIPASS
    # 首次运行时，将内嵌的 data/ 拷贝到 exe 同级目录（持久化）
    _BUNDLE_DATA = os.path.join(sys._MEIPASS, "data")
    if os.path.isdir(_BUNDLE_DATA):
        import shutil
        for fname in os.listdir(_BUNDLE_DATA):
            src = os.path.join(_BUNDLE_DATA, fname)
            dst = os.path.join(os.path.join(_BASE, "data"), fname)
            if not os.path.exists(dst):
                os.makedirs(os.path.join(_BASE, "data"), exist_ok=True)
                try:
                    shutil.copy2(src, dst)
                except Exception:
                    pass
else:
    _BASE = os.path.dirname(os.path.abspath(__file__))

DATA_DIR = os.path.join(_BASE, "data")
os.makedirs(DATA_DIR, exist_ok=True)

# 代理列表持久化文件
PROXY_DB = os.path.join(DATA_DIR, "proxies.json")
# 自定义抓取源配置
SOURCES_FILE = os.path.join(DATA_DIR, "sources.json")
# 用户设置
SETTINGS_FILE = os.path.join(DATA_DIR, "settings.json")

# 默认本地代理监听
DEFAULT_LOCAL_HOST = "127.0.0.1"
DEFAULT_LOCAL_PORT = 8888

# 验证测速参数
CHECK_TIMEOUT = 8            # 单代理连通性超时(秒)
CHECK_TEST_URL = "https://www.google.com/generate_204"
CHECK_SPEED_URL = "https://www.google.com/generate_204"
CHECK_WORKERS = 40           # 并发线程数
SPEED_SAMPLE_BYTES = 200_000  # 测速下载字节数

# 抓取参数
FETCH_TIMEOUT = 60           # 单源抓取超时(秒) - CDN下载大文件需更长
FETCH_WORKERS = 6            # 并发抓取源数
FETCH_RETRIES = 2            # 单源失败重试次数

# 默认抓取源（优先使用国内可访问的 jsdelivr CDN 镜像）
DEFAULT_SOURCES = [
    {
        "name": "TheSpeedX-HTTP (CDN)",
        "url": "https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/http.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "TheSpeedX-SOCKS5 (CDN)",
        "url": "https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/socks5.txt",
        "parser": "plain",
        "protocol": "socks5",
    },
    {
        "name": "Monosans-HTTP (CDN)",
        "url": "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/http.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "Monosans-SOCKS5 (CDN)",
        "url": "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/socks5.txt",
        "parser": "plain",
        "protocol": "socks5",
    },
    {
        "name": "Clarketm-HTTP (CDN)",
        "url": "https://cdn.jsdelivr.net/gh/clarketm/proxy-list@master/proxy-list-raw.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "Roosterkid-HTTP (CDN)",
        "url": "https://cdn.jsdelivr.net/gh/roosterkid/openproxylist@main/HTTPS_RAW.txt",
        "parser": "plain",
        "protocol": "https",
    },
    {
        "name": "ProxyScrape-HTTP",
        "url": "https://api.proxyscrape.com/v2/?request=getproxies&protocol=http&timeout=10000&country=all&ssl=all&anonymity=all",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "ProxyScrape-SOCKS5",
        "url": "https://api.proxyscrape.com/v2/?request=getproxies&protocol=socks5&timeout=10000&country=all",
        "parser": "plain",
        "protocol": "socks5",
    },
    {
        "name": "Thordata-HTTP",
        "url": "https://raw.githubusercontent.com/Thordata/awesome-free-proxy-list/main/proxies/http.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "Thordata-SOCKS5",
        "url": "https://raw.githubusercontent.com/Thordata/awesome-free-proxy-list/main/proxies/socks5.txt",
        "parser": "plain",
        "protocol": "socks5",
    },
    {
        "name": "Thordata-TopHTTP",
        "url": "https://raw.githubusercontent.com/Thordata/awesome-free-proxy-list/main/proxies/top-http.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "jetkai-HTTP",
        "url": "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-HTTP.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "jetkai-SOCKS5",
        "url": "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-SOCKS5.txt",
        "parser": "plain",
        "protocol": "socks5",
    },
    {
        "name": "Proxifly-HTTP",
        "url": "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "Proxifly-SOCKS5",
        "url": "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt",
        "parser": "plain",
        "protocol": "socks5",
    },
    {
        "name": "VPSLab-HTTP",
        "url": "https://raw.githubusercontent.com/VPSLabCloud/VPSLab-Free-Proxy-List/main/http_all.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "VPSLab-SOCKS5",
        "url": "https://raw.githubusercontent.com/VPSLabCloud/VPSLab-Free-Proxy-List/main/socks5_all.txt",
        "parser": "plain",
        "protocol": "socks5",
    },
    {
        "name": "DPangestuw-HTTP",
        "url": "https://raw.githubusercontent.com/dpangestuw/Free-Proxy/main/http_proxies.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "DPangestuw-SOCKS5",
        "url": "https://raw.githubusercontent.com/dpangestuw/Free-Proxy/main/socks5_proxies.txt",
        "parser": "plain",
        "protocol": "socks5",
    },
    {
        "name": "Databay-HTTP",
        "url": "https://raw.githubusercontent.com/databay-labs/free-proxy-list/master/http.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "Databay-SOCKS5",
        "url": "https://raw.githubusercontent.com/databay-labs/free-proxy-list/master/socks5.txt",
        "parser": "plain",
        "protocol": "socks5",
    },
    {
        "name": "SoliSpirit-HTTP",
        "url": "https://raw.githubusercontent.com/SoliSpirit/proxy-list/main/http.txt",
        "parser": "plain",
        "protocol": "http",
    },
    {
        "name": "SoliSpirit-SOCKS5",
        "url": "https://raw.githubusercontent.com/SoliSpirit/proxy-list/main/socks5.txt",
        "parser": "plain",
        "protocol": "socks5",
    },
]

# ---------- 代理模式 ----------
# global: 全局走代理；smart: 按规则自动分流；direct: 全直连(调试)
PROXY_MODE_GLOBAL = "global"
PROXY_MODE_SMART = "smart"
PROXY_MODE_DIRECT = "direct"
PROXY_MODES = [PROXY_MODE_GLOBAL, PROXY_MODE_SMART, PROXY_MODE_DIRECT]
MODE_LABELS = {
    PROXY_MODE_GLOBAL: "全局代理",
    PROXY_MODE_SMART: "智能分流",
    PROXY_MODE_DIRECT: "本地直连",
}

# 智能模式下默认直连的国内常见域名（匹配域名后缀，子域命中即直连）
# 命中规则：host 等于该项，或以 .该项 结尾
DIRECT_DOMAINS_CN = [
    # 搜索/门户
    "baidu.com", "qq.com", "tencent.com", "sogou.com", "so.com",
    "163.com", "sina.com.cn", "sina.com", "sohu.com", "ifeng.com",
    # 电商
    "taobao.com", "tmall.com", "jd.com", "pinduoduo.com", "1688.com",
    "suning.com", "douyin.com", "douyincdn.com",
    # 视频/直播
    "bilibili.com", "bilibili.cn", "hdslb.com", "iqiyi.com", "youku.com",
    "v.qq.com", "mgvtv.com", "kuaishou.com",
    # 社交/社区
    "weibo.com", "weibo.cn", "zhihu.com", "xiaohongshu.com", "tieba.baidu.com",
    # 云服务/CDN
    "aliyuncs.com", "aliyun.com", "tencentcloudapi.com", "myqcloud.com",
    "qcloud.com", "huaweicloud.com", "cdn.douyinpic.com",
    # 工具/支付
    "alipay.com", "alipayobjects.com", "amap.com", "bdstatic.com",
    "bdimg.com", "gtimg.com", "qpic.cn",
    # 政务/教育
    "gov.cn", "edu.cn", "ac.cn",
    # 通用后缀：国内域名后缀整体直连
    "cn",
]

# 默认强制走代理的域名（即使命中直连表也走代理，优先级更高）
PROXY_DOMAINS = [
    "google.com", "googleapis.com", "gstatic.com", "googlevideo.com",
    "youtube.com", "ytimg.com", "facebook.com", "fbcdn.net",
    "twitter.com", "twimg.com", "x.com",
    "github.com", "githubusercontent.com", "githubassets.com",
    "wikipedia.org", "wikimedia.org",
    "instagram.com", "whatsapp.com", "telegram.org",
    "openai.com", "claude.ai", "anthropic.com",
    "netflix.com", "nflxvideo.net", "spotify.com", "disneyplus.com",
]

# ---------- 精致优雅主题 ----------
COLOR_BG = "#f4f5f7"              # 页面背景：柔和浅灰
COLOR_CARD = "#ffffff"            # 卡片/面板：纯白
COLOR_BAR = "#ffffff"             # 工具栏
COLOR_HOVER = "#eef1f5"           # hover 浅灰
COLOR_PRIMARY = "#3b6cf6"         # 主色：精致蓝
COLOR_PRIMARY_HOVER = "#2f5bd6"   # 主色 hover
COLOR_PRIMARY_SOFT = "#e8efff"    # 主色浅底（标签/选中行）
COLOR_DANGER = "#e5484d"          # 危险红
COLOR_DANGER_HOVER = "#c93a3f"
COLOR_TEXT = "#1a1d23"            # 主文字：近黑
COLOR_TEXT_MUTED = "#8b909a"      # 次要文字：中灰
COLOR_TEXT_FAINT = "#b4b9c2"      # 更淡：表头/占位
COLOR_BORDER = "#e8eaee"          # 边框：极淡
COLOR_ROW_ALT = "#fafbfc"         # 隔行底
COLOR_ROW_HOVER = "#f0f3f8"       # 行 hover
COLOR_GOOD = "#22a06b"            # 可用绿
COLOR_BAD = "#d44950"             # 不可用红
COLOR_WARN = "#e8a317"            # 警告橙

FONT_FAMILY = "Segoe UI"
FONT_SIZE = 9
