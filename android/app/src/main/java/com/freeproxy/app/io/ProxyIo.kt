package com.freeproxy.app.io

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.freeproxy.app.data.ProxyRepository
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.regex.Pattern

/**
 * 代理导入导出：支持 JSON（全字段）和纯文本（每行一个，支持 scheme://host:port）
 */
class ProxyIo(private val ctx: Context, private val repo: ProxyRepository) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val URL_REGEX = Pattern.compile(
        """^(?:(\w+)://)?(?:([^:@\s]+):([^:@\s]+)@)?([^\s:]+|\[[0-9a-fA-F:]+\]):(\d{1,5})"""
    )

    /**
     * 导入：自动根据后缀/内容判断是 JSON 还是 TXT
     */
    suspend fun importFromUri(uri: Uri, mime: String? = null): Int = withContext(Dispatchers.IO) {
        val text = ctx.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: return@withContext 0

        val list = when {
            mime == "application/json" ||
                uri.path?.endsWith(".json", true) == true ||
                text.trimStart().let { it.startsWith("{") || it.startsWith("[") } -> parseJson(text)
            else -> parsePlain(text)
        }
        repo.addAllIfAbsent(list)
    }

    /**
     * 导出
     */
    suspend fun exportToUri(uri: Uri, format: Format = Format.JSON, onlyUsable: Boolean = true): Int =
        withContext(Dispatchers.IO) {
            val all = repo.getAll().let { if (onlyUsable) it.filter { p -> p.isUsable() } else it }
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out).use { w ->
                    when (format) {
                        Format.JSON -> w.write(gson.toJson(all.map(::toJson)))
                        Format.TEXT -> w.write(all.joinToString("\n") { toPlain(it) })
                    }
                    w.flush()
                }
            }
            all.size
        }

    enum class Format { JSON, TEXT }

    // ---------- JSON ----------
    private fun parseJson(text: String): List<ProxyInfo> = runCatching {
        val root = gson.fromJson(text, JsonArray::class.java) ?: return emptyList()
        root.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val host = o.optString("host") ?: return@mapNotNull null
            val port = o.optInt("port", -1).takeIf { it in 1..65535 } ?: return@mapNotNull null
            ProxyInfo(
                host = host,
                port = port,
                type = ProxyType.from(o.optString("type")),
                username = o.optString("username")?.takeIf { it.isNotBlank() },
                password = o.optString("password")?.takeIf { it.isNotBlank() },
                country = o.optString("country")?.takeIf { it.isNotBlank() },
                countryCode = o.optString("countryCode")?.takeIf { it.isNotBlank() },
                anonymity = com.freeproxy.app.data.model.AnonymityLevel.from(o.optString("anonymity")),
                https = o.optBoolean("https"),
                latencyMs = o.optLong("latencyMs")?.takeIf { ms -> ms > 0L },
                downloadKbps = o.optLong("downloadKbps")?.takeIf { kb -> kb > 0L },
                source = o.optString("source")?.takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    private fun toJson(p: ProxyInfo): Map<String, Any?> = buildMap {
        put("host", p.host); put("port", p.port)
        put("type", p.type.value)
        p.username?.let { put("username", it) }
        p.password?.let { put("password", it) }
        p.country?.let { put("country", it) }
        p.countryCode?.let { put("countryCode", it) }
        put("anonymity", p.anonymity.value)
        put("https", p.https)
        p.latencyMs?.let { put("latencyMs", it) }
        p.downloadKbps?.let { put("downloadKbps", it) }
        p.source?.let { put("source", it) }
        if (p.workCount > 0 || p.failCount > 0) {
            put("workCount", p.workCount); put("failCount", p.failCount)
        }
    }

    // ---------- Plain / TXT ----------
    private fun parsePlain(text: String): List<ProxyInfo> {
        val out = mutableListOf<ProxyInfo>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val m = URL_REGEX.matcher(line)
            if (!m.find()) continue
            val scheme = m.group(1)
            val user = m.group(2); val pass = m.group(3)
            val host = (m.group(4) ?: "").trim('[', ']')
            val port = m.group(5)?.toIntOrNull() ?: continue
            if (port !in 1..65535 || host.isBlank()) continue
            val type = scheme?.let { ProxyType.from(it) }
                ?: (if (port == 443 || port == 8443) ProxyType.HTTPS else ProxyType.HTTP)
            out.add(ProxyInfo(host = host, port = port, type = type, username = user, password = pass))
        }
        return out
    }

    private fun toPlain(p: ProxyInfo): String = buildString {
        append(p.type.value.lowercase()).append("://")
        if (!p.username.isNullOrBlank()) {
            append(p.username)
            if (!p.password.isNullOrBlank()) append(":").append(p.password)
            append("@")
        }
        append(p.host).append(":").append(p.port)
    }

    private fun JsonObject.optString(k: String): String? =
        if (has(k) && !get(k).isJsonNull) get(k).asString else null
    private fun JsonObject.optInt(k: String, def: Int): Int =
        if (has(k) && !get(k).isJsonNull) get(k).asInt else def
    private fun JsonObject.optLong(k: String): Long? =
        if (has(k) && !get(k).isJsonNull) get(k).asLong else null
    private fun JsonObject.optBoolean(k: String): Boolean =
        has(k) && !get(k).isJsonNull && get(k).asBoolean
}
