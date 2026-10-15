package local.wangchangdian.videograbber

import android.content.Context
import android.webkit.CookieManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 各平台本机 Cookie 的存放与「登录态」判断。
 *
 * 全部只在本机（`files/cookies-<平台>.txt`），不上传、不共享。
 *
 * 登录态怎么判断（本地拿不到服务端状态，所以用三个信号综合）：
 *  1. 关键 Cookie 字段在不在 —— 不在就一定没登录（NONE）
 *  2. 文件多久没更新 —— 超过 [STALE_DAYS] 天标记为 STALE（可能已失效，建议重新登录）
 *  3. 下载时被平台拒绝（引擎报登录相关错误）—— 标记为 EXPIRED
 */
object CookieStore {

    enum class State { NONE, ACTIVE, STALE, EXPIRED }

    data class Platform(
        val key: String,
        val label: String,
        /** 提示文案：没配置时界面上显示什么 */
        val hint: String,
        /** 是否提供「登录」入口（抖音下载不需要 Cookie，就不给按钮免得误解） */
        val loginable: Boolean,
        /** 是否必须登录才能下载（X / YouTube 是必须的） */
        val required: Boolean,
        /** 判定「已登录」的关键 Cookie 名，任一命中即可 */
        val markers: List<String>,
        /** 写 Netscape 文件时用的域 */
        val domains: List<String>,
        /** 在应用内登录时打开的地址 */
        val loginUrl: String,
    )

    /**
     * 平台清单，顺序与电脑端（Mac / Windows）设置页保持一致，方便对照。
     *
     * 登录态来源三种：应用内登录（[loginable]）、手动粘贴、云端同步。
     * 抖音 / 快手 / 小红书这些平台的解析走真实 WebView，只认浏览器里的会话 ——
     * 另外两种来源写在 cookies-<key>.txt，下载前由 [seedBrowserCookies] 补种进 CookieManager。
     */
    val PLATFORMS = listOf(
        // 抖音：正常下载不需要 Cookie；「主页 / 收藏夹批量」要登录才给列表
        Platform(
            key = "douyin", label = "抖音",
            hint = "未登录（下载不需要；批量下载主页/收藏需要登录）",
            loginable = true, required = false,
            // 匿名访客也会拿到 sessionid（早期版本因此把"没登录"误判成"已登录"）；
            // sid_tt 由服务端在登录成功后下发，login_time 也是登录后才写入，用这两个才准
            markers = listOf("sid_tt", "login_time"),
            domains = listOf(".douyin.com", ".iesdouyin.com"),
            // 落在「我的主页」：已登录时页面会请求带签名的 user/profile/self（登录检测靠它）；
            // 未登录时抖音会自动跳回首页，登录入口照常可见
            loginUrl = "https://www.douyin.com/user/self",
        ),
        // 快手：下载走 WebView 注入钩子，登录后画质与成功率更高（与电脑端一致）
        Platform(
            key = "kuaishou", label = "快手",
            hint = "未登录（不登录也能下；登录后画质与成功率更高）",
            loginable = true, required = false,
            // did / didv 是匿名设备号；web_ph / web_st 由登录接口下发，passToken / userId 也是登录后才写入
            markers = listOf("kuaishou.server.web_ph", "kuaishou.server.web_st", "passToken", "userId"),
            // 登录在网页版 kuaishou.com；而解析走的是 v.m.chenzhongtech.com，两个域都要种
            domains = listOf(".kuaishou.com", ".chenzhongtech.com"),
            loginUrl = "https://www.kuaishou.com/",
        ),
        // 小红书：下载走 WebView 注入钩子，登录后可拿原始画质（与电脑端一致）
        Platform(
            key = "xiaohongshu", label = "小红书",
            hint = "未登录（不登录也能下；登录后可拿原始画质）",
            loginable = true, required = false,
            // a1 / webId 是匿名设备标识（没登录也有），web_session 是登录后下发的会话，用它才准
            markers = listOf("web_session"),
            domains = listOf(".xiaohongshu.com", ".xhslink.com"),
            loginUrl = "https://www.xiaohongshu.com/",
        ),
        Platform(
            key = "wechat_channels", label = "微信视频号",
            hint = "未登录（需要先登录腾讯元宝）",
            loginable = true, required = true,
            // 视频号解析用的是「腾讯元宝」的登录态，不是视频号本身的 Cookie
            markers = listOf("hy_user", "hy_token"),
            domains = listOf(".tencent.com"),
            loginUrl = "https://yuanbao.tencent.com/",
        ),
        // 下面几个：不带 Cookie 也能下，登录后清晰度更高、成功率更高
        Platform(
            key = "bilibili", label = "B 站",
            hint = "未登录（不登录也能下，登录后可下 1080P 以上）",
            loginable = true, required = false,
            markers = listOf("SESSDATA", "DedeUserID"),
            domains = listOf(".bilibili.com"),
            // 用桌面登录页（扫码/账号密码），会话按桌面端下发，4K 才拿得到
            loginUrl = "https://passport.bilibili.com/login",
        ),
        Platform(
            key = "weibo", label = "微博",
            hint = "未登录（部分视频需要登录才能看）",
            loginable = true, required = false,
            markers = listOf("SUB", "SUBP"),
            domains = listOf(".weibo.com", ".weibo.cn"),
            loginUrl = "https://m.weibo.cn/",
        ),
        Platform(
            key = "tiktok", label = "TikTok",
            hint = "未登录（国内需要开代理）",
            loginable = true, required = false,
            markers = listOf("sessionid", "sid_tt"),
            domains = listOf(".tiktok.com"),
            // 直接给登录页，避免落在首页信息流里还得自己找入口
            loginUrl = "https://www.tiktok.com/login",
        ),
        Platform(
            key = "instagram", label = "Instagram",
            hint = "未登录（国内需要开代理，多数内容要登录）",
            loginable = true, required = false,
            markers = listOf("sessionid", "ds_user_id"),
            domains = listOf(".instagram.com"),
            loginUrl = "https://www.instagram.com/accounts/login/",
        ),
        Platform(
            key = "x", label = "X",
            hint = "未登录（必须登录才能下载）",
            loginable = true, required = true,
            // auth_token 是登录后才有；twid 作为兜底（两种都存在才算稳）
            markers = listOf("auth_token", "twid"),
            domains = listOf(".x.com", ".twitter.com"),
            loginUrl = "https://x.com/login",
        ),
        Platform(
            key = "youtube", label = "YouTube",
            hint = "未登录（必须登录才能下载）",
            loginable = true, required = true,
            markers = listOf("SAPISID", "__Secure-3PSID", "SID", "LOGIN_INFO"),
            // 只用 .youtube.com：浏览器本身也不会把 .google.com 的 Cookie 发给 youtube.com，
            // 混在一起会被 Google 判成 CookieMismatch
            domains = listOf(".youtube.com"),
            loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube",
        ),
        // 第三梯队：西瓜 / Reddit / Vimeo / Twitch 走 yt-dlp，Cookie 用于清晰度与受限内容；
        // 微视 / 好看走网页嗅探，登录态影响页面能看到的内容。
        // 这几个不提供应用内登录（要开代理 + 平台风控严），用「手动粘贴」或云端·电脑端即可
        Platform(
            key = "ixigua", label = "西瓜视频",
            hint = "未登录（公开视频不登录也能下）",
            loginable = false, required = false,
            markers = emptyList(),
            domains = listOf(".ixigua.com"),
            loginUrl = "https://www.ixigua.com/",
        ),
        Platform(
            key = "reddit", label = "Reddit",
            hint = "未登录（国内需代理；公开视频不登录也能下）",
            loginable = false, required = false,
            markers = listOf("reddit_session"),
            domains = listOf(".reddit.com"),
            loginUrl = "https://www.reddit.com/login/",
        ),
        Platform(
            key = "vimeo", label = "Vimeo",
            hint = "未登录（国内需代理；Vimeo 需要登录）",
            loginable = false, required = false,
            markers = listOf("vimeo"),
            domains = listOf(".vimeo.com"),
            loginUrl = "https://vimeo.com/log_in",
        ),
        Platform(
            key = "twitch", label = "Twitch",
            hint = "未登录（国内需代理；录像/剪辑不登录也能下）",
            loginable = false, required = false,
            markers = listOf("auth-token"),
            domains = listOf(".twitch.tv"),
            loginUrl = "https://www.twitch.tv/login",
        ),
        Platform(
            key = "weishi", label = "微视",
            hint = "未登录（没登录也能下，登录态影响页面能看到的内容）",
            loginable = false, required = false,
            markers = emptyList(),
            domains = listOf(".weishi.qq.com", ".qq.com"),
            loginUrl = "https://isee.weishi.qq.com/",
        ),
        Platform(
            key = "haokan", label = "好看视频",
            hint = "未登录（没登录也能下，登录态影响页面能看到的内容）",
            loginable = false, required = false,
            markers = emptyList(),
            domains = listOf(".haokan.baidu.com", ".baidu.com"),
            loginUrl = "https://haokan.baidu.com/",
        ),
        // 音乐平台：QQ 音乐多数歌曲要登录；网易云未登录时常常只能下到试听片段
        Platform(
            key = "netease", label = "网易云音乐",
            hint = "未登录（不登录常常只能下 45 秒试听片段）",
            loginable = false, required = false,
            markers = listOf("MUSIC_U"),
            domains = listOf(".music.163.com", ".163.com"),
            loginUrl = "https://music.163.com/",
        ),
        Platform(
            key = "qqmusic", label = "QQ 音乐",
            hint = "未登录（多数歌曲需要登录）",
            loginable = false, required = false,
            markers = listOf("qqmusic_key", "uin"),
            domains = listOf(".y.qq.com", ".qq.com"),
            loginUrl = "https://y.qq.com/",
        ),
    )

    /** 超过这么多天没更新，就提示「可能已失效」 */
    const val STALE_DAYS = 30

    /**
     * 各平台在内置浏览器（WebView）里对应的地址。
     * 登录窗口从这里打开、Cookie 从这里导出，「清除」时也按这些地址把浏览器里的会话一并清掉。
     */
    val COOKIE_URLS: Map<String, List<String>> = mapOf(
        "x" to listOf("https://x.com", "https://twitter.com", "https://api.x.com"),
        "youtube" to listOf("https://www.youtube.com", "https://youtube.com", "https://accounts.google.com"),
        // 视频号解析用的是腾讯元宝的登录态，所以存的是元宝域下的 Cookie
        "wechat_channels" to listOf("https://yuanbao.tencent.com", "https://www.tencent.com"),
        "bilibili" to listOf("https://www.bilibili.com", "https://api.bilibili.com"),
        "weibo" to listOf("https://m.weibo.cn", "https://www.weibo.com", "https://passport.weibo.cn"),
        "tiktok" to listOf("https://www.tiktok.com"),
        "instagram" to listOf("https://www.instagram.com"),
        "douyin" to listOf("https://www.douyin.com", "https://www.iesdouyin.com"),
        "kuaishou" to listOf("https://www.kuaishou.com"),
        "xiaohongshu" to listOf("https://www.xiaohongshu.com"),
        // 下面这些没有应用内登录，列出来只为「手动粘贴 / 云端同步」后能补种到内置浏览器
        "ixigua" to listOf("https://www.ixigua.com"),
        "vimeo" to listOf("https://vimeo.com"),
        "weishi" to listOf("https://isee.weishi.qq.com"),
        "haokan" to listOf("https://haokan.baidu.com"),
        "netease" to listOf("https://music.163.com"),
        "qqmusic" to listOf("https://y.qq.com"),
        "reddit" to listOf("https://www.reddit.com"),
        "twitch" to listOf("https://www.twitch.tv"),
    )

    data class Status(
        val state: State = State.NONE,
        val count: Int = 0,
        val updatedAt: Long = 0L,
    ) {
        /** 是否算「已经登录过」（STALE 也算，只提示建议重登） */
        val loggedIn: Boolean get() = state == State.ACTIVE || state == State.STALE

        fun timeText(): String = if (updatedAt <= 0) "" else
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(updatedAt))
    }

    fun platform(key: String): Platform? = PLATFORMS.firstOrNull { it.key == key }

    /** 当前这份 Cookie 的来源：本机登录 / 手动粘贴 / 云端·手机端 / 云端·电脑端 */
    fun setSource(key: String, source: String) {
        AppState.preferences().edit().putString("cookie_source_$key", source).apply()
    }

    fun sourceOf(key: String): String =
        AppState.preferences().getString("cookie_source_$key", "").orEmpty()

    fun file(context: Context, key: String): File = File(context.filesDir, "cookies-$key.txt")

    /** 把本机保存的 Cookie 文件还原成 HTTP 请求头可用的 `k=v; k2=v2` 字符串 */
    fun cookieHeaderFor(context: Context, key: String): String {
        val target = file(context, key)
        if (!target.exists() || target.length() == 0L) return ""
        return runCatching {
            target.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size < 7) null else "${parts[5].trim()}=${parts[6].trim()}"
                }
                .distinct()
                .joinToString("; ")
        }.getOrDefault("")
    }

    /**
     * 把本机保存的登录态（文件）补种进内置 WebView 的 CookieManager。
     *
     * 抖音 / 快手 / 小红书等平台的解析走真实 WebView，只认 CookieManager 里的会话；
     * 而「手动粘贴」「云端回填」写入的是 cookies-<key>.txt —— 不补种就等于配了不生效。
     * 关键 Cookie 的值和浏览器里已经一致时跳过（例如刚在登录窗口登录并回填过），
     * 避免每次下载都重复写一遍。
     */
    fun seedBrowserCookies(context: Context, key: String) {
        val target = file(context, key)
        if (!target.exists() || target.length() == 0L) return
        val meta = platform(key) ?: return
        if (meta.domains.isEmpty()) return
        val manager = CookieManager.getInstance()
        val pairs = target.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 7) null else parts[5].trim() to parts[6].trim()
            }
            .filter { (name, value) -> name.isNotBlank() && value.isNotBlank() }
            .distinct()
        if (pairs.isEmpty()) return
        // 关键 Cookie（如 web_session）值一致就不再种：说明浏览器里就是这份登录态。
        // 匿名访客也会拿到同名 Cookie（实测小红书会），所以只能比对值，不能只看名字。
        val marker = meta.markers.firstOrNull()
        if (marker != null) {
            val fileValue = pairs.firstOrNull { it.first == marker }?.second
            val browserValue = meta.domains.asSequence()
                .mapNotNull { manager.getCookie("https://" + it.trimStart('.')) }
                .mapNotNull { cookie ->
                    Regex("(?:^|; )" + Regex.escape(marker) + "=([^;]*)")
                        .find(cookie)?.groupValues?.get(1)
                }
                .firstOrNull()
            if (fileValue != null && fileValue == browserValue) return
        }
        for (domain in meta.domains) {
            val url = "https://" + domain.trimStart('.')
            for ((name, value) in pairs) {
                manager.setCookie(url, "$name=$value; path=/; domain=$domain")
            }
        }
        manager.flush()
        LogStore.log("Cookie", "$key 已把本机登录态补种到内置浏览器（${pairs.size} 条）")
    }

    private fun expiredFlag(context: Context, key: String): File =
        File(context.filesDir, "cookies-$key.expired")

    /** 判断某个平台当前的登录态 */
    fun status(context: Context, key: String): Status {
        val target = file(context, key)
        if (!target.exists() || target.length() == 0L) return Status()

        val meta = platform(key)
        val rows = target.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val count = rows.size
        if (count == 0) return Status()

        // 必须登录的平台：关键字段缺失就当没登录（可能是手动粘贴了不完整的 Cookie）
        // Netscape 每行是 域 / 是否含子域 / 路径 / 是否仅 HTTPS / 过期时间 / 名 / 值
        if (meta != null && meta.markers.isNotEmpty()) {
            val names = rows.mapNotNull { it.split("\t").getOrNull(5)?.trim() }
            if (meta.markers.none { it in names }) {
                return Status(State.NONE, count, target.lastModified())
            }
        }

        if (expiredFlag(context, key).exists()) {
            return Status(State.EXPIRED, count, target.lastModified())
        }

        val ageDays = (System.currentTimeMillis() - target.lastModified()) / 86_400_000L
        val state = if (ageDays >= STALE_DAYS) State.STALE else State.ACTIVE
        return Status(state, count, target.lastModified())
    }

    fun allStatuses(context: Context): Map<String, Status> =
        PLATFORMS.associate { it.key to status(context, it.key) }

    /** 引擎报「需要登录」类错误时调用；下次重新登录会清掉这个标记 */
    fun markExpired(context: Context, key: String) {
        if (file(context, key).exists()) {
            runCatching { expiredFlag(context, key).writeText(System.currentTimeMillis().toString()) }
        }
    }

    fun clearMark(context: Context, key: String) {
        runCatching { expiredFlag(context, key).delete() }
    }

    /** 手动粘贴 Cookie（兜底方案）：把 `k=v; k2=v2` 写成 yt-dlp 用的 Netscape 文件 */
    fun saveManual(context: Context, key: String, raw: String): Int {
        val meta = platform(key) ?: return 0
        val cleaned = raw.trim().removePrefix("Cookie:").trim()
        val pairs = cleaned.split(";").map { it.trim() }.filter { it.contains("=") }
            .mapNotNull { pair ->
                val name = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
        if (pairs.isEmpty()) return 0
        val lines = mutableListOf("# Netscape HTTP Cookie File", "")
        for (domain in meta.domains) {
            for ((name, value) in pairs) {
                lines.add(listOf(domain, "TRUE", "/", "TRUE", "0", name, value).joinToString("\t"))
            }
        }
        file(context, key).writeText(lines.joinToString("\n") + "\n")
        clearMark(context, key)
        setSource(key, "手动粘贴")
        LogStore.log("Cookie", "$key 手动保存 ${pairs.size} 条 Cookie")
        return pairs.size
    }

    fun clear(context: Context, key: String) {
        file(context, key).delete()
        clearMark(context, key)
        setSource(key, "")
        // 注意：这里**故意不清内置浏览器的登录状态**。
        // 实测元宝的会话会自行恢复（清 Cookie 和 localStorage 后 1-2 秒又写回来），
        // 而且保留浏览器登录态反而更好用：下次打开登录页会自动回填，用户不用再收一次验证码。
        // 换账号的路径是在登录页里退出登录，而不是靠这里的「清除」。
    }

    /**
     * B 站接口按 `CURRENT_FNVAL` 决定返回哪些清晰度：4048 = 含 4K / HDR / 杜比。
     * 浏览器里播放过视频才会带上它，应用内登录抓到的 Cookie 常常没有 —— 缺了就补两行，
     * 否则即使登录了，B 站也只给到 1080P（实测差异就在这两个 Cookie）。
     */
    fun ensureBilibiliQuality(context: Context): Boolean {
        val target = file(context, "bilibili")
        if (!target.exists() || target.length() == 0L) return false
        val lines = target.readLines().toMutableList()
        val names = lines.filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { it.split("\t").getOrNull(5)?.trim() }
        var changed = false
        for ((name, value) in listOf("CURRENT_FNVAL" to "4048", "CURRENT_QUALITY" to "120")) {
            if (name !in names) {
                lines.add(listOf(".bilibili.com", "TRUE", "/", "TRUE", "0", name, value).joinToString("\t"))
                changed = true
            }
        }
        if (changed) {
            target.writeText(lines.joinToString("\n") + "\n")
            LogStore.log("Cookie", "B 站 Cookie 已补上 CURRENT_FNVAL=4048（4K/HDR 必需）")
        }
        return changed
    }

    /** 状态在界面上的文案与语义色（0 灰 / 1 绿 / 2 橙 / 3 红） */
    fun describe(key: String, status: Status): Pair<String, Int> = when (status.state) {
        State.ACTIVE -> "已登录 · ${status.timeText()}" to 1
        State.STALE -> "已登录（${status.timeText()}）· 建议重新登录" to 2
        State.EXPIRED -> "登录已失效，请重新登录" to 3
        State.NONE -> (platform(key)?.hint ?: "未配置") to 0
    }
}
