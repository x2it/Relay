package com.freeproxy.app.vpn

import android.content.Context
import android.net.VpnService

/**
 * 路由模式：
 *   GLOBAL   全局代理（全流量走 TUN）
 *   SMART    智能分流（CN IP 不建 tunnel 让真实网卡重试；GLOBAL 保持全代理）
 */
enum class RouteMode(val code: Int, val label: String, val desc: String) {
    GLOBAL(0, "全局", "所有流量都走代理，简单但国内网站可能变慢"),
    SMART (1, "智能", "推荐：国内 IP 直连，海外 IP 才走代理，最均衡");

    companion object {
        fun fromCode(c: Int): RouteMode = entries.firstOrNull { it.code == c } ?: SMART
    }
}

/**
 * 建议一键排除的国内常用 App 包名。
 * 开启「应用建议排除」开关时 excludedApps 与之做并集；关闭时从 excludedApps 中减去它们。
 */
val SUGGESTED_EXCLUDED_PACKAGES: List<String> = listOf(
    "com.tencent.mm",                 // 微信
    "com.eg.android.AlipayGphone",    // 支付宝
    "com.ss.android.ugc.aweme",       // 抖音
    "com.xingin.xhs",                 // 小红书
    "com.pinduoduo",                  // 拼多多
    "com.ss.android.article.news",    // 今日头条
    "com.tencent.mobileqq",           // QQ
    "com.tencent.tim",                // TIM
    "com.sina.weibo",                 // 微博
    "com.taobao.taobao",              // 淘宝
    "com.jingdong.app.mall",          // 京东
    "com.tencent.qqlive",             // 腾讯视频
    "com.qiyi.video",                 // 爱奇艺
    "com.youku.phone",                // 优酷
    "com.netease.cloudmusic",         // 网易云音乐
    "com.tencent.karaoke",            // QQ音乐
    "com.ss.android.ugc.live",        // 抖音火山
    "com.smile.gifmaker",             // 快手
    "com.tencent.weread",             // 微信读书
    "com.tencent.androidqqmail",      // QQ邮箱
)

/**
 * 基于 APNIC CN delegated 的精简版 CIDR 列表（压缩约 250 条主段）。
 * ChinaIpList 初始化时会将其转成 (start, end) Pair 并按 start 排序，支持二分查找。
 */
val CN_CIDRS: List<Pair<String, Int>> = listOf(
    "1.0.1.0" to 24, "1.0.2.0" to 23, "1.1.0.0" to 22, "1.2.0.0" to 21,
    "1.8.0.0" to 21, "1.24.0.0" to 21, "1.44.0.0" to 20, "1.56.0.0" to 20,
    "1.180.0.0" to 19, "1.192.0.0" to 18, "14.0.0.0" to 21, "14.16.0.0" to 20,
    "14.104.0.0" to 20, "14.128.0.0" to 21, "14.144.0.0" to 20, "14.204.0.0" to 20,
    "14.208.0.0" to 20, "27.0.0.0" to 22, "27.8.0.0" to 18, "27.54.128.0" to 20,
    "27.102.0.0" to 21, "27.112.0.0" to 19, "27.128.0.0" to 19, "27.152.0.0" to 19,
    "27.176.0.0" to 20, "27.184.0.0" to 21, "27.192.0.0" to 18, "36.0.0.0" to 19,
    "36.32.0.0" to 21, "36.40.0.0" to 18, "36.96.0.0" to 19, "36.128.0.0" to 19,
    "36.192.0.0" to 20, "36.248.0.0" to 20, "39.0.0.0" to 18, "39.64.0.0" to 19,
    "39.128.0.0" to 19, "39.192.0.0" to 19, "40.72.0.0" to 20, "40.124.0.0" to 20,
    "40.188.0.0" to 20, "40.240.0.0" to 18, "42.0.0.0" to 18, "42.48.0.0" to 20,
    "42.80.0.0" to 20, "42.96.0.0" to 19, "42.120.0.0" to 20, "42.156.0.0" to 18,
    "42.236.0.0" to 20, "42.242.0.0" to 19, "43.128.0.0" to 18, "43.192.0.0" to 18,
    "43.224.0.0" to 20, "43.240.0.0" to 19, "43.248.0.0" to 20, "47.92.0.0" to 21,
    "47.96.0.0" to 20, "47.100.0.0" to 20, "47.104.0.0" to 20, "47.108.0.0" to 20,
    "47.112.0.0" to 19, "47.128.0.0" to 19, "47.144.0.0" to 20, "47.240.0.0" to 20,
    "49.0.0.0" to 19, "49.32.0.0" to 19, "49.64.0.0" to 21, "49.112.0.0" to 19,
    "49.128.0.0" to 19, "49.192.0.0" to 19, "52.80.0.0" to 20, "52.82.0.0" to 21,
    "58.0.0.0" to 19, "58.32.0.0" to 19, "58.64.0.0" to 18, "58.128.0.0" to 19,
    "58.192.0.0" to 20, "58.208.0.0" to 20, "58.220.0.0" to 19, "58.240.0.0" to 19,
    "59.32.0.0" to 19, "59.64.0.0" to 19, "59.108.0.0" to 20, "59.128.0.0" to 19,
    "59.192.0.0" to 18, "60.0.0.0" to 19, "60.32.0.0" to 18, "60.160.0.0" to 19,
    "60.208.0.0" to 19, "60.232.0.0" to 19, "61.0.0.0" to 18, "61.48.0.0" to 19,
    "61.128.0.0" to 18, "61.232.0.0" to 19, "61.240.0.0" to 19,
    "101.0.0.0" to 19, "101.32.0.0" to 19, "101.64.0.0" to 20, "101.80.0.0" to 19,
    "103.0.0.0" to 20, "103.8.0.0" to 20, "103.20.0.0" to 20, "103.28.0.0" to 20,
    "103.40.0.0" to 19, "103.72.0.0" to 20, "103.96.0.0" to 20, "103.224.0.0" to 20,
    "106.0.0.0" to 19, "106.32.0.0" to 18, "106.80.0.0" to 18, "106.120.0.0" to 19,
    "106.192.0.0" to 19, "110.0.0.0" to 18, "110.88.0.0" to 20, "110.96.0.0" to 18,
    "110.176.0.0" to 18, "110.240.0.0" to 19,
    "111.0.0.0" to 19, "111.32.0.0" to 18, "111.128.0.0" to 18, "111.192.0.0" to 19,
    "112.0.0.0" to 18, "113.0.0.0" to 18, "114.0.0.0" to 19, "114.32.0.0" to 18,
    "114.128.0.0" to 19, "114.208.0.0" to 19, "115.0.0.0" to 18, "116.0.0.0" to 19,
    "116.48.0.0" to 18, "116.128.0.0" to 19, "116.192.0.0" to 19, "116.240.0.0" to 20,
    "117.0.0.0" to 19, "117.32.0.0" to 18, "117.128.0.0" to 19, "117.192.0.0" to 19,
    "118.0.0.0" to 19, "118.32.0.0" to 18, "118.128.0.0" to 19, "118.192.0.0" to 18,
    "119.0.0.0" to 18, "120.0.0.0" to 19, "120.32.0.0" to 19, "120.64.0.0" to 19,
    "120.128.0.0" to 19, "120.192.0.0" to 19, "121.0.0.0" to 19, "121.32.0.0" to 18,
    "121.128.0.0" to 19, "121.192.0.0" to 19, "122.0.0.0" to 18, "123.0.0.0" to 19,
    "123.64.0.0" to 18, "123.128.0.0" to 18, "123.240.0.0" to 19, "124.0.0.0" to 18,
    "125.0.0.0" to 19, "125.32.0.0" to 19, "125.64.0.0" to 19, "125.128.0.0" to 19,
    "125.192.0.0" to 19, "129.204.0.0" to 18, "132.232.0.0" to 19, "139.0.0.0" to 18,
    "150.0.0.0" to 19, "150.128.0.0" to 19, "153.0.0.0" to 19, "153.32.0.0" to 19,
    "153.96.0.0" to 18, "153.192.0.0" to 19, "157.0.0.0" to 18, "163.0.0.0" to 19,
    "163.64.0.0" to 18, "163.128.0.0" to 19, "163.192.0.0" to 19,
    "171.0.0.0" to 19, "171.32.0.0" to 19, "171.64.0.0" to 18, "171.128.0.0" to 19,
    "175.0.0.0" to 19, "175.32.0.0" to 19, "175.64.0.0" to 19, "175.128.0.0" to 18,
    "175.240.0.0" to 19, "180.0.0.0" to 18, "180.96.0.0" to 19, "180.128.0.0" to 18,
    "182.0.0.0" to 19, "182.32.0.0" to 19, "182.64.0.0" to 19, "182.112.0.0" to 20,
    "182.128.0.0" to 19, "182.192.0.0" to 19, "182.240.0.0" to 20, "183.0.0.0" to 19,
    "183.64.0.0" to 19, "183.128.0.0" to 18,
    "192.140.96.0" to 19, "192.168.0.0" to 16, "198.175.96.0" to 19,
    "202.0.0.0" to 19, "202.32.0.0" to 19, "202.64.0.0" to 19, "202.96.0.0" to 19,
    "202.128.0.0" to 19, "202.160.0.0" to 19, "202.192.0.0" to 18, "203.0.0.0" to 20,
    "203.16.0.0" to 20, "203.32.0.0" to 20, "203.64.0.0" to 19, "203.86.0.0" to 19,
    "203.92.0.0" to 19, "203.128.0.0" to 19, "203.160.0.0" to 19, "203.192.0.0" to 19,
    "210.0.0.0" to 19, "210.32.0.0" to 19, "210.64.0.0" to 18, "210.128.0.0" to 19,
    "210.176.0.0" to 19, "210.192.0.0" to 18, "211.64.0.0" to 19, "211.96.0.0" to 19,
    "211.128.0.0" to 18, "211.160.0.0" to 19, "211.224.0.0" to 19,
    "218.0.0.0" to 19, "218.32.0.0" to 18, "218.96.0.0" to 19, "218.160.0.0" to 19,
    "218.192.0.0" to 19, "218.240.0.0" to 19,
    "219.0.0.0" to 19, "219.64.0.0" to 19, "219.128.0.0" to 18, "219.216.0.0" to 19,
    "219.232.0.0" to 19, "219.240.0.0" to 19,
    "220.96.0.0" to 19, "220.112.0.0" to 18, "220.160.0.0" to 19, "220.192.0.0" to 19,
    "220.232.0.0" to 19, "220.248.0.0" to 19,
    "221.0.0.0" to 18, "221.128.0.0" to 18, "221.192.0.0" to 19, "221.208.0.0" to 19,
    "221.224.0.0" to 19,
    "222.0.0.0" to 19, "222.32.0.0" to 19, "222.64.0.0" to 18, "222.128.0.0" to 18,
    "222.192.0.0" to 19, "222.240.0.0" to 19,
    "223.0.0.0" to 18, "223.128.0.0" to 19,
)

/**
 * 基于 CN_CIDRS 构建的（start..end）查找表。
 * IP 用无符号 32-bit Long 表示，按 start 升序，支持 binarySearch。
 */
object ChinaIpList {
    private val ranges: List<Pair<Long, Long>> by lazy {
        CN_CIDRS.mapNotNull { (ip, prefix) ->
            runCatching {
                val ipInt = parseIpInt(ip)
                val mask = if (prefix == 0) 0 else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val start = (ipInt.toLong() and 0xFFFFFFFFL) and mask
                val hostBits = (1L shl (32 - prefix)) - 1
                val end = start + hostBits
                start to end
            }.getOrNull()
        }.sortedBy { it.first }
    }

    /** 把 IPv4 字符串转成无符号 32-bit 的 Long 表示的整数。 */
    private fun parseIpInt(ip: String): Int {
        val parts = ip.split(".")
        require(parts.size == 4)
        return (parts[0].toInt() shl 24) or
                (parts[1].toInt() shl 16) or
                (parts[2].toInt() shl 8) or
                parts[3].toInt()
    }

    /** 判断一个 IP（signed 32-bit Int）是否属于中国大陆段。 */
    fun isCn(ipInt: Int): Boolean {
        val key = ipInt.toLong() and 0xFFFFFFFFL
        val list = ranges
        var lo = 0; var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val (s, e) = list[mid]
            if (key < s) hi = mid - 1
            else if (key > e) lo = mid + 1
            else return true
        }
        return false
    }

    /** 调试用：返回 CIDR 条数 */
    fun rangeCount(): Int = ranges.size
}

/** 0.0.0.0/0 全局路由 */
private val ALL_V4: Pair<String, Int> = "0.0.0.0" to 0

/**
 * 路由策略：给 VpnService.Builder 加 addRoute。
 *
 * 说明：
 *   - GLOBAL 直接加 0.0.0.0/0，简单直接
 *   - SMART 加一组近似"非 CN 路由"作为 TUN 路由（见 approxNonCn），但 Android 限制
 *     单 Builder 最多 ~128 条 addRoute，不可能精确覆盖所有"非 CN 段"，因此 SMART 下
 *     我们在 ProxyVpnService.handleTcpPacket 内再做一次兜底：对命中 CN 段的 SYN 包
 *     默默 drop，系统会在 ~1s 内重试真实网卡，从而实现 CN IP 直连。
 */
object RoutePolicy {

    fun applyRoutes(builder: VpnService.Builder, mode: RouteMode) {
        when (mode) {
            RouteMode.GLOBAL -> {
                builder.addRoute(ALL_V4.first, ALL_V4.second)
            }
            RouteMode.SMART -> {
                // 近似"非 CN 段"，宁漏勿错；漏掉的海外段会默认直连（用户可切 GLOBAL）
                val approxNonCn: List<Pair<String, Int>> = listOf(
                    "2.0.0.0" to 7, "3.0.0.0" to 8, "4.0.0.0" to 6, "8.0.0.0" to 8,
                    "9.0.0.0" to 8, "11.0.0.0" to 8, "12.0.0.0" to 6, "15.0.0.0" to 8,
                    "16.0.0.0" to 5, "24.0.0.0" to 8, "25.0.0.0" to 8, "26.0.0.0" to 7,
                    "28.0.0.0" to 6, "32.0.0.0" to 6, "48.0.0.0" to 8, "50.0.0.0" to 8,
                    "51.0.0.0" to 8, "52.0.0.0" to 7, "54.0.0.0" to 8, "55.0.0.0" to 8,
                    "56.0.0.0" to 7, "62.0.0.0" to 7, "63.0.0.0" to 8,
                    "64.0.0.0" to 4, "128.0.0.0" to 8, "129.0.0.0" to 9, "129.128.0.0" to 10,
                    "130.0.0.0" to 7, "140.0.0.0" to 7, "144.0.0.0" to 5, "154.0.0.0" to 8,
                    "155.0.0.0" to 8, "156.0.0.0" to 8, "158.0.0.0" to 7, "160.0.0.0" to 7,
                    "162.0.0.0" to 7, "164.0.0.0" to 7, "166.0.0.0" to 7, "168.0.0.0" to 7,
                    "170.0.0.0" to 7, "172.0.0.0" to 8, "173.0.0.0" to 8, "174.0.0.0" to 8,
                    "176.0.0.0" to 6, "184.0.0.0" to 7, "186.0.0.0" to 7, "188.0.0.0" to 6,
                    "192.0.0.0" to 9, "192.128.0.0" to 11, "192.160.0.0" to 13,
                    "192.172.0.0" to 14, "192.176.0.0" to 12, "192.192.0.0" to 11,
                    "192.224.0.0" to 11, "193.0.0.0" to 8, "194.0.0.0" to 7,
                    "196.0.0.0" to 7, "197.0.0.0" to 8, "198.0.0.0" to 12,
                    "198.16.0.0" to 13, "198.32.0.0" to 11, "198.64.0.0" to 10,
                    "198.128.0.0" to 10, "198.160.0.0" to 11, "198.192.0.0" to 12,
                    "198.208.0.0" to 12, "198.224.0.0" to 11, "199.0.0.0" to 8,
                    "200.0.0.0" to 7, "201.0.0.0" to 8, "204.0.0.0" to 7, "205.0.0.0" to 8,
                    "206.0.0.0" to 7, "207.0.0.0" to 8, "208.0.0.0" to 6, "212.0.0.0" to 7,
                    "213.0.0.0" to 8, "214.0.0.0" to 7, "216.0.0.0" to 7, "217.0.0.0" to 8,
                    "224.0.0.0" to 3, "172.16.0.0" to 12, "100.64.0.0" to 10,
                )
                approxNonCn.forEach { (ip, prefix) ->
                    runCatching { builder.addRoute(ip, prefix) }
                }
            }
        }
    }

    /**
     * 把用户勾选的"排除 App 列表"交给 VPN Builder（Android 系统级排除，非常可靠）。
     * 每个包名用 runCatching 包裹：某些 ROM 不允许排除某些包 / 包不存在时静默忽略。
     */
    fun applyAppExclusions(
        builder: VpnService.Builder,
        ctx: Context,
        excluded: Set<String>,
    ) {
        excluded.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
        }
    }
}
