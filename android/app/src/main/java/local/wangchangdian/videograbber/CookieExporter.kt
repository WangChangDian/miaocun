package local.wangchangdian.videograbber

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * 把内置 WebView 的登录 Cookie 导出成 yt-dlp 可用的 Netscape 格式。
 * 用户在「登录 X / YouTube」窗口里登录一次即可长期复用。
 */
object CookieExporter {
    /** 各平台在内置浏览器里对应的地址（统一放在 CookieStore，导出与清除共用） */
    private val domains: Map<String, List<String>> get() = CookieStore.COOKIE_URLS

    fun file(context: Context, key: String): File = File(context.filesDir, "cookies-$key.txt")

    fun export(context: Context, key: String): Int {
        val urls = domains[key] ?: return 0
        val manager = CookieManager.getInstance()
        val lines = mutableListOf("# Netscape HTTP Cookie File", "")
        val seen = mutableSetOf<String>()
        for (url in urls) {
            val cookie = manager.getCookie(url) ?: continue
            val host = url.removePrefix("https://")
            for (pair in cookie.split("; ")) {
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) continue
                if (!seen.add("$name=$value")) continue
                lines.add(
                    listOf(
                        if (host.contains("google")) ".google.com" else ".$host",
                        "TRUE", "/", "TRUE", "0", name, value,
                    ).joinToString("\t")
                )
            }
        }
        val target = file(context, key)
        return if (lines.size <= 2) {
            LogStore.log("Cookie", "$key 没有可导出的登录 Cookie（请先在窗口里登录）")
            0
        } else {
            target.writeText(lines.joinToString("\n") + "\n")
            LogStore.log("Cookie", "$key 导出成功：${lines.size - 2} 条 → ${target.name}")
            lines.size - 2
        }
    }
}
