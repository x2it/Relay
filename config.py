# -*- coding: utf-8 -*-
"""Relay 桌面版 · 全局配置与常量（对齐 Android v1.4.1）。

terminal 暗色主题 + 45 个内置开源数据源（A/B/C/D/E 五类，含加密节点订阅）。
"""
import os
import sys

# 应用基础信息
APP_NAME = "Relay"
APP_VERSION = "1.7.0"

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

# ---------- terminal 暗色主题（对齐 Android Theme.kt） ----------
COLOR_BG = "#0F1216"              # 页面底：深灰黑
COLOR_CARD = "#171B21"            # 卡片底：深灰
COLOR_BAR = "#0F1216"
COLOR_HOVER = "#1F242B"           # 次级底
COLOR_PRIMARY = "#6B8AFF"         # 主色：蓝紫
COLOR_PRIMARY_HOVER = "#5A7AFF"
COLOR_PRIMARY_SOFT = "#263258"    # 主色淡底
COLOR_DANGER = "#D64545"
COLOR_DANGER_HOVER = "#B93B3B"
COLOR_TEXT = "#E7E9EC"            # 主文字：接近白
COLOR_TEXT_MUTED = "#9AA2AC"      # 次文字：中性灰
COLOR_TEXT_FAINT = "#6B7280"      # 更弱辅助
COLOR_BORDER = "#2C323B"          # 描边：深灰
COLOR_ROW_ALT = "#171B21"
COLOR_ROW_HOVER = "#1F242B"
COLOR_GOOD = "#2BA47A"            # 可用绿（降饱和）
COLOR_BAD = "#D64545"             # 不可用红
COLOR_WARN = "#D9822B"            # 警告橙

FONT_FAMILY = "Consolas"          # 等宽：terminal 感
FONT_SIZE = 10


# ---------- 内置数据源 ----------
def _src(name, url, parser="plain", protocol="http", category="B",
         mirrors=None, timeout=20_000):
    return {
        "name": name, "url": url, "parser": parser, "protocol": protocol,
        "category": category, "mirrors": mirrors or [],
        "timeout": timeout, "enabled": True,
        "last_status": "N", "last_count": 0, "last_fetched_at": 0,
    }


def _gh_mirrors(url):
    """GitHub RAW 镜像：gh-proxy.com / ghfast.top（与 Android 一致）。"""
    origin = "https://raw.githubusercontent.com/"
    if not url.startswith(origin):
        return []
    suffix = url[len(origin):]
    return [
        "https://gh-proxy.com/https://raw.githubusercontent.com/" + suffix,
        "https://ghfast.top/https://raw.githubusercontent.com/" + suffix,
    ]


_GH = "https://raw.githubusercontent.com/"

# 45 个内置开源数据源（A/B/C/D/E 五类，覆盖 HTTP/HTTPS/SOCKS4/SOCKS5 + 加密节点订阅）
DEFAULT_SOURCES = [
    # ============== A 类：JSON/API 源 (4) ==============
    _src("GeoNode API",
         "https://proxylist.geonode.com/api/proxy-list?limit=500&page=1&sort_by=lastChecked&sort_type=desc",
         parser="geonode_json", category="A", timeout=20_000),
    _src("PubProxy API",
         "http://pubproxy.com/api/proxy?limit=20&format=json&type=http",
         parser="geonode_json", protocol="http", category="A", timeout=15_000),
    _src("ProxyScrape HTTP",
         "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=10000&country=all&ssl=all&anonymity=all",
         protocol="http", category="A"),
    _src("proxy-list.download HTTP",
         "https://www.proxy-list.download/api/v1/get?type=http",
         protocol="http", category="A"),

    # ============== B 类：GitHub RAW (25) ==============
    _src("github/TheSpeedX HTTP", _GH + "TheSpeedX/PROXY-List/master/http.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "TheSpeedX/PROXY-List/master/http.txt")),
    _src("github/TheSpeedX SOCKS4", _GH + "TheSpeedX/PROXY-List/master/socks4.txt",
         protocol="socks4", category="B", mirrors=_gh_mirrors(_GH + "TheSpeedX/PROXY-List/master/socks4.txt")),
    _src("github/TheSpeedX SOCKS5", _GH + "TheSpeedX/PROXY-List/master/socks5.txt",
         protocol="socks5", category="B", mirrors=_gh_mirrors(_GH + "TheSpeedX/PROXY-List/master/socks5.txt")),
    _src("github/ShiftyTR HTTP", _GH + "ShiftyTR/Proxy-List/master/http.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "ShiftyTR/Proxy-List/master/http.txt")),
    _src("github/ShiftyTR HTTPS", _GH + "ShiftyTR/Proxy-List/master/https.txt",
         protocol="https", category="B", mirrors=_gh_mirrors(_GH + "ShiftyTR/Proxy-List/master/https.txt")),
    _src("github/hookzof SOCKS5", _GH + "hookzof/socks5_list/master/proxy.txt",
         protocol="socks5", category="B", mirrors=_gh_mirrors(_GH + "hookzof/socks5_list/master/proxy.txt")),
    _src("github/clarketm", _GH + "clarketm/proxy-list/master/proxy-list-raw.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "clarketm/proxy-list/master/proxy-list-raw.txt")),
    _src("github/monosans HTTP", _GH + "monosans/proxy-list/main/proxies/http.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "monosans/proxy-list/main/proxies/http.txt")),
    _src("github/monosans SOCKS4", _GH + "monosans/proxy-list/main/proxies/socks4.txt",
         protocol="socks4", category="B", mirrors=_gh_mirrors(_GH + "monosans/proxy-list/main/proxies/socks4.txt")),
    _src("github/monosans SOCKS5", _GH + "monosans/proxy-list/main/proxies/socks5.txt",
         protocol="socks5", category="B", mirrors=_gh_mirrors(_GH + "monosans/proxy-list/main/proxies/socks5.txt")),
    _src("github/prxchk HTTP", _GH + "prxchk/proxy-list/main/http.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "prxchk/proxy-list/main/http.txt")),
    _src("github/prxchk SOCKS4", _GH + "prxchk/proxy-list/main/socks4.txt",
         protocol="socks4", category="B", mirrors=_gh_mirrors(_GH + "prxchk/proxy-list/main/socks4.txt")),
    _src("github/prxchk SOCKS5", _GH + "prxchk/proxy-list/main/socks5.txt",
         protocol="socks5", category="B", mirrors=_gh_mirrors(_GH + "prxchk/proxy-list/main/socks5.txt")),
    _src("github/jetkai HTTP", _GH + "jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt")),
    _src("github/jetkai SOCKS4", _GH + "jetkai/proxy-list/main/online-proxies/txt/proxies-socks4.txt",
         protocol="socks4", category="B", mirrors=_gh_mirrors(_GH + "jetkai/proxy-list/main/online-proxies/txt/proxies-socks4.txt")),
    _src("github/jetkai SOCKS5", _GH + "jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt",
         protocol="socks5", category="B", mirrors=_gh_mirrors(_GH + "jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt")),
    _src("github/ALIILAPRO HTTP", _GH + "ALIILAPRO/Proxy/main/http.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "ALIILAPRO/Proxy/main/http.txt")),
    _src("github/officialputuid HTTP", _GH + "officialputuid/Proxy-List/main/http.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "officialputuid/Proxy-List/main/http.txt")),
    _src("github/zuoxiaodongai 聚合", _GH + "zuoxiaodongai/proxies/main/all.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "zuoxiaodongai/proxies/main/all.txt")),
    _src("github/zloi-user HTTP", _GH + "zloi-user/hideip.me/main/http.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "zloi-user/hideip.me/main/http.txt")),
    _src("github/zloi-user HTTPS", _GH + "zloi-user/hideip.me/main/https.txt",
         protocol="https", category="B", mirrors=_gh_mirrors(_GH + "zloi-user/hideip.me/main/https.txt")),
    _src("github/zloi-user SOCKS4", _GH + "zloi-user/hideip.me/main/socks4.txt",
         protocol="socks4", category="B", mirrors=_gh_mirrors(_GH + "zloi-user/hideip.me/main/socks4.txt")),
    _src("github/zloi-user SOCKS5", _GH + "zloi-user/hideip.me/main/socks5.txt",
         protocol="socks5", category="B", mirrors=_gh_mirrors(_GH + "zloi-user/hideip.me/main/socks5.txt")),
    _src("github/roosterkid HTTPS", _GH + "roosterkid/openproxylist/main/HTTPS_RAW.txt",
         protocol="https", category="B", mirrors=_gh_mirrors(_GH + "roosterkid/openproxylist/main/HTTPS_RAW.txt")),
    _src("github/roosterkid SOCKS5", _GH + "roosterkid/openproxylist/main/SOCKS5_RAW.txt",
         protocol="socks5", category="B", mirrors=_gh_mirrors(_GH + "roosterkid/openproxylist/main/SOCKS5_RAW.txt")),
    _src("github/vakhov HTTP", _GH + "vakhov/fresh-proxy-list/master/http.txt",
         protocol="http", category="B", mirrors=_gh_mirrors(_GH + "vakhov/fresh-proxy-list/master/http.txt")),
    _src("github/vakhov SOCKS5", _GH + "vakhov/fresh-proxy-list/master/socks5.txt",
         protocol="socks5", category="B", mirrors=_gh_mirrors(_GH + "vakhov/fresh-proxy-list/master/socks5.txt")),

    # ============== C 类：HTML 表格 (2) ==============
    _src("free-proxy-list.net", "https://free-proxy-list.net/",
         parser="html_table", protocol="http", category="C", timeout=20_000),
    _src("sslproxies.org", "https://www.sslproxies.org/",
         parser="html_table", protocol="https", category="C", timeout=20_000),

    # ============== D 类：TXT/CSV 镜像池 (5) ==============
    _src("openproxylist.xyz HTTP", "https://openproxylist.xyz/http.txt",
         protocol="http", category="D"),
    _src("openproxylist.xyz SOCKS5", "https://openproxylist.xyz/socks5.txt",
         protocol="socks5", category="D"),
    _src("ProxyScrape SOCKS4",
         "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks4&timeout=10000&country=all",
         protocol="socks4", category="D"),
    _src("ProxyScrape SOCKS5",
         "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=10000&country=all",
         protocol="socks5", category="D"),
    _src("spys.me HTTP", "https://spys.me/proxy.txt",
         protocol="http", category="D"),

    # ============== E 类：订阅源 (6)（分享链接/Base64 → VMess/Trojan/VLESS/SS） ==============
    _src("sub/Pawdroid", _GH + "Pawdroid/Free-servers/main/sub",
         parser="subscription", protocol="auto", category="E", timeout=25_000,
         mirrors=_gh_mirrors(_GH + "Pawdroid/Free-servers/main/sub")),
    _src("sub/aiboboxx", _GH + "aiboboxx/v2rayfree/main/v2",
         parser="subscription", protocol="auto", category="E", timeout=25_000,
         mirrors=_gh_mirrors(_GH + "aiboboxx/v2rayfree/main/v2")),
    _src("sub/mfuu", _GH + "mfuu/v2ray/master/v2ray",
         parser="subscription", protocol="auto", category="E", timeout=25_000,
         mirrors=_gh_mirrors(_GH + "mfuu/v2ray/master/v2ray")),
    _src("sub/ermaozi", _GH + "ermaozi/get_subscribe/main/subscribe/v2ray.txt",
         parser="subscription", protocol="auto", category="E", timeout=25_000,
         mirrors=_gh_mirrors(_GH + "ermaozi/get_subscribe/main/subscribe/v2ray.txt")),
    _src("sub/NoMoreWalls", _GH + "peasoft/NoMoreWalls/master/list.txt",
         parser="subscription", protocol="auto", category="E", timeout=25_000,
         mirrors=_gh_mirrors(_GH + "peasoft/NoMoreWalls/master/list.txt")),
    _src("sub/V2RayAggregator", _GH + "mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
         parser="subscription", protocol="auto", category="E", timeout=25_000,
         mirrors=_gh_mirrors(_GH + "mahdibland/V2RayAggregator/master/sub/sub_merge.txt")),
]

# 分类标签（terminal 展示用）
CATEGORY_LABELS = {
    "A": "json/api",
    "B": "github raw",
    "C": "html table",
    "D": "txt/csv pool",
    "E": "sub(encrypted)",
    "CUSTOM": "custom",
}

# 协议标签
PROTOCOL_LABELS = {
    "http": "HTTP", "https": "HTTPS", "socks4": "SOCKS4", "socks5": "SOCKS5",
    "ss": "SS", "vmess": "VMess", "vless": "VLESS", "trojan": "Trojan",
    "auto": "AUTO",
}
ALL_PROTOCOLS = list(PROTOCOL_LABELS.keys())
ENCRYPTED_PROTOCOLS = ("ss", "vmess", "vless", "trojan")


# ---------- 代理模式 ----------
# global: 全局走代理；smart: 按规则自动分流；direct: 全直连(调试)
PROXY_MODE_GLOBAL = "global"
PROXY_MODE_SMART = "smart"
PROXY_MODE_DIRECT = "direct"
PROXY_MODES = [PROXY_MODE_GLOBAL, PROXY_MODE_SMART, PROXY_MODE_DIRECT]
MODE_LABELS = {
    PROXY_MODE_GLOBAL: "global",
    PROXY_MODE_SMART: "smart",
    PROXY_MODE_DIRECT: "direct",
}

# 智能模式下默认直连的国内常见域名（匹配域名后缀，子域命中即直连）
DIRECT_DOMAINS_CN = [
    "baidu.com", "qq.com", "tencent.com", "sogou.com", "so.com",
    "163.com", "sina.com.cn", "sina.com", "sohu.com", "ifeng.com",
    "taobao.com", "tmall.com", "jd.com", "pinduoduo.com", "1688.com",
    "suning.com", "douyin.com", "douyincdn.com",
    "bilibili.com", "bilibili.cn", "hdslb.com", "iqiyi.com", "youku.com",
    "v.qq.com", "mgvtv.com", "kuaishou.com",
    "weibo.com", "weibo.cn", "zhihu.com", "xiaohongshu.com", "tieba.baidu.com",
    "aliyuncs.com", "aliyun.com", "tencentcloudapi.com", "myqcloud.com",
    "qcloud.com", "huaweicloud.com", "cdn.douyinpic.com",
    "alipay.com", "alipayobjects.com", "amap.com", "bdstatic.com",
    "bdimg.com", "gtimg.com", "qpic.cn",
    "gov.cn", "edu.cn", "ac.cn",
    "cn",
]

# 默认强制走代理的域名（优先级更高）
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
