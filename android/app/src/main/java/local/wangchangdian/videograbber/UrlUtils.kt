package local.wangchangdian.videograbber

import java.net.URL

object UrlUtils {
    private val urlRegex = Regex("""https?://[^\s\u4e00-\u9fff，。！？；：、“”‘’《》【】（）()"'<>\\^`{|}]+""")
    private const val TRAILING = ".,，。；;!！?？、)）】》>\"'"

    val douyinHosts = listOf("v.douyin.com", "www.douyin.com", "douyin.com", "iesdouyin.com", "m.douyin.com")
    val kuaishouHosts = listOf("v.kuaishou.com", "www.kuaishou.com", "kuaishou.com", "kuaishou.cn", "c.kuaishou.com")
    val xhsHosts = listOf("xhslink.com", "xhslink.cn", "www.xiaohongshu.com", "xiaohongshu.com", "www.rednote.com", "rednote.com")
    val xHosts = listOf("x.com", "twitter.com", "t.co", "mobile.twitter.com", "mobile.x.com")
    /** 视频号分享链接形如 weixin.qq.com/sph/xxx（同域名下还有别的页面，所以还要看路径） */
    val wechatChannelsHosts = listOf("weixin.qq.com")
    val youtubeHosts = listOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtu.be", "youtube-nocookie.com",
    )
    /** 以下四个平台交给内置 yt-dlp 解析（登录 Cookie 可选，能提高清晰度/成功率） */
    val bilibiliHosts = listOf("bilibili.com", "www.bilibili.com", "m.bilibili.com", "b23.tv", "bili2233.cn")
    val weiboHosts = listOf("weibo.com", "www.weibo.com", "m.weibo.cn", "weibo.cn", "video.weibo.com")
    val tiktokHosts = listOf("tiktok.com", "www.tiktok.com", "m.tiktok.com", "vm.tiktok.com", "vt.tiktok.com")
    val instagramHosts = listOf("instagram.com", "www.instagram.com", "m.instagram.com")
    /** 第三梯队：yt-dlp 原生支持 */
    val ixiguaHosts = listOf("ixigua.com", "www.ixigua.com")
    val redditHosts = listOf("reddit.com", "www.reddit.com", "old.reddit.com", "redd.it", "v.redd.it")
    val vimeoHosts = listOf("vimeo.com", "www.vimeo.com", "player.vimeo.com")
    val twitchHosts = listOf("twitch.tv", "www.twitch.tv", "m.twitch.tv", "clips.twitch.tv")
    /** 微视 / 好看视频：yt-dlp 无专用解析器，走通用解析 + 网页嗅探兜底 */
    val weishiHosts = listOf("isee.weishi.qq.com", "weishi.qq.com", "v.weishi.qq.com")
    val haokanHosts = listOf("haokan.baidu.com", "haokan.hao123.com")
    /** 音频平台（yt-dlp 原生支持） */
    val neteaseHosts = listOf("music.163.com", "y.music.163.com", "163cn.tv")
    val qqMusicHosts = listOf("y.qq.com", "c.y.qq.com", "i.y.qq.com", "c6.y.qq.com")

    /** 从分享文本中提取链接 */
    fun extractUrl(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = urlRegex.find(raw) ?: return null
        return match.value.trimEnd { it in TRAILING }
    }

    /**
     * 从整段文本里提取**全部**链接（多链接批量用）。
     * 规则与单链接、桌面端/引擎保持一致：中文标点不吞入、去尾随标点、保序去重、最多 [limit] 条。
     */
    fun extractAllUrls(text: String?, limit: Int = 50): List<String> {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (match in urlRegex.findAll(raw)) {
            val url = match.value.trimEnd { it in TRAILING }
            if (url.isNotBlank()) seen.add(url)
            if (seen.size >= limit) break
        }
        return seen.toList()
    }

    fun detectPlatform(url: String?): Platform {
        if (url.isNullOrBlank()) return Platform.UNKNOWN
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return Platform.UNKNOWN
        fun matches(hosts: List<String>) = hosts.any { host == it || host.endsWith(".$it") }
        return when {
            matches(douyinHosts) -> Platform.DOUYIN
            matches(kuaishouHosts) -> Platform.KUAISHOU
            matches(xhsHosts) -> Platform.XIAOHONGSHU
            matches(wechatChannelsHosts) && url.contains("/sph/") -> Platform.WECHAT_CHANNELS
            matches(bilibiliHosts) -> Platform.BILIBILI
            matches(weiboHosts) -> Platform.WEIBO
            matches(tiktokHosts) -> Platform.TIKTOK
            matches(instagramHosts) -> Platform.INSTAGRAM
            matches(xHosts) -> Platform.X
            matches(youtubeHosts) -> Platform.YOUTUBE
            matches(ixiguaHosts) -> Platform.XIGUA
            matches(redditHosts) -> Platform.REDDIT
            matches(vimeoHosts) -> Platform.VIMEO
            matches(twitchHosts) -> Platform.TWITCH
            matches(weishiHosts) -> Platform.WEISHI
            matches(haokanHosts) -> Platform.HAOKAN
            matches(neteaseHosts) -> Platform.NETEASE
            matches(qqMusicHosts) -> Platform.QQMUSIC
            else -> Platform.UNKNOWN
        }
    }

    /** 抖音作品页（用于 WebView 打开） */
    fun douyinVideoUrl(id: String) = "https://www.douyin.com/video/$id"

    /**
     * 跟随短链跳转拿最终地址（抖音 v.douyin.com/xxxx 这类短码要先解析出作品 ID）。
     * 会带上本机 Cookie——抖音短链在无 Cookie 时可能直接跳登录页。请在 IO 线程调用。
     */
    fun resolveRedirect(
        url: String,
        cookieHeader: String? = null,
        timeoutMs: Int = 10_000,
    ): String = runCatching {
        val mobileUa =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        val connection = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", mobileUa)
            if (!cookieHeader.isNullOrBlank()) setRequestProperty("Cookie", cookieHeader)
        }
        try {
            connection.connect()
            val finalUrl = connection.url?.toString().orEmpty()
            // 有些短链是 302 到 App 唤起页/登录页，这时退回原链接，交给页面自身处理
            if (finalUrl.isBlank()) url else finalUrl
        } finally {
            runCatching { connection.disconnect() }
        }
    }.getOrDefault(url)

    /**
     * 抖音短链 → 作品 ID。
     *
     * 短链会 302 到 `www.iesdouyin.com/xg/video/<id>/` 或 `www.douyin.com/video/<id>`，
     * 正常情况从最终地址就能拿到 ID；个别情况（登录页 / 风控页）拿不到，再从响应体里兜底找。
     * 请在 IO 线程调用，失败返回 null（调用方回退成直接打开原链接）。
     */
    fun resolveDouyinAwemeId(url: String, cookieHeader: String? = null, timeoutMs: Int = 10_000): String? {
        val mobileUa =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        return runCatching {
            val connection = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", mobileUa)
                if (!cookieHeader.isNullOrBlank()) setRequestProperty("Cookie", cookieHeader)
            }
            try {
                connection.connect()
                douyinId(connection.url?.toString().orEmpty())?.let { return@runCatching it }
                // 兜底：从页面内容里找作品 ID（有的链路返回的是 HTML 里带 ID 的跳转页）
                val body = runCatching {
                    connection.inputStream.bufferedReader().use { it.readText().take(300_000) }
                }.getOrDefault("")
                Regex(""""aweme_id"\s*:\s*"?(\d{6,})""").find(body)?.groupValues?.get(1)
                    ?: Regex("""(?:video|note)/(\d{6,})""").find(body)?.groupValues?.get(1)
            } finally {
                runCatching { connection.disconnect() }
            }
        }.getOrNull()
    }

    /**
     * 小红书 App 分享链接归一化。
     *
     * App 分享出来的形如 `xiaohongshu.com/discovery/item/<id>?app_platform=android&...`，
     * 这个页面把笔记数据直接内嵌在 HTML（SSR）里，不走 `/api/sns/web/` 接口，
     * 内置的响应钩子拿不到内容。换成标准笔记页后页面会正常调接口。
     */
    fun normalizeXiaohongshu(url: String): String {
        val id = Regex("""/(?:discovery/item|explore)/([0-9a-zA-Z]+)""")
            .find(url)?.groupValues?.get(1) ?: return url
        val token = Regex("""[?&]xsec_token=([^&]+)""").find(url)?.groupValues?.get(1)
        return buildString {
            append("https://www.xiaohongshu.com/explore/").append(id)
            if (!token.isNullOrBlank()) {
                append("?xsec_token=").append(token).append("&xsec_source=pc_share")
            }
        }
    }

    fun douyinId(url: String): String? =
        Regex("""(?:video|note|share/video)/(\d{6,})""").find(url)?.groupValues?.get(1)
            ?: Regex("""(\d{15,})""").find(url)?.groupValues?.get(1)

    fun kuaishouPhotoId(url: String): String? {
        val patterns = listOf(
            """/short-video/([A-Za-z0-9_-]+)""",
            """/fw/photo/([A-Za-z0-9_-]+)""",
            """/fw/long-video/([A-Za-z0-9_-]+)""",
            """[?&]photoId=([A-Za-z0-9_-]+)""",
        )
        for (pattern in patterns) {
            Regex(pattern).find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    fun kuaishouMobileUrl(photoId: String) = "https://v.m.chenzhongtech.com/fw/photo/$photoId"

    /** YouTube 视频 ID（watch?v= / youtu.be / shorts） */
    fun youtubeId(url: String): String? {
        val patterns = listOf(
            """[?&]v=([A-Za-z0-9_-]{6,})""",
            """youtu\.be/([A-Za-z0-9_-]{6,})""",
            """/shorts/([A-Za-z0-9_-]{6,})""",
            """/embed/([A-Za-z0-9_-]{6,})""",
        )
        for (pattern in patterns) {
            Regex(pattern).find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** 桌面版页面才会稳定请求 youtubei/v1/player（移动版在 WebView 里常不初始化播放器） */
    fun youtubeWatchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    /** X / Twitter 的推文 ID */
    fun tweetId(url: String): String? =
        Regex("""/status(?:es)?/(\d{6,})""").find(url)?.groupValues?.get(1)

    private val directFileExtensions = setOf(
        "mp4", "mov", "m4v", "mkv", "webm", "flv", "avi", "ts", "m3u8",
        "mp3", "m4a", "aac", "flac", "wav",
        "jpg", "jpeg", "png", "webp", "heic", "gif", "avif",
        "zip", "rar", "7z", "pdf", "apk", "dmg", "exe",
    )

    /** 普通直链（非平台作品链接）也允许直接下载 */
    fun isDirectFile(url: String): Boolean {
        val path = runCatching { URL(url).path.lowercase() }.getOrNull() ?: return false
        val ext = path.substringAfterLast('.', "")
        if (ext.isEmpty()) return false
        // 网页类扩展名交给「页面嗅探」流程处理
        if (ext in setOf("html", "htm", "php", "asp", "aspx", "jsp", "shtml", "do")) return false
        // 任何带扩展名的都视为文件（不只白名单）
        return ext.length in 1..6 && ext.all { it.isLetterOrDigit() } || ext in directFileExtensions
    }

    /** 按扩展名给出打开方式对应的 MIME 类型 */
    fun mimeFor(nameOrUrl: String): String {
        val ext = nameOrUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v", "mov", "mkv", "webm", "flv", "avi", "ts", "3gp" -> "video/*"
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus" -> "audio/*"
            "jpg", "jpeg", "png", "webp", "heic", "gif", "avif", "bmp" -> "image/*"
            "pdf" -> "application/pdf"
            "zip", "rar", "7z", "tar", "gz" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "txt", "md", "log", "json", "xml", "csv" -> "text/plain"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            else -> "*/*"
        }
    }

    /** 从分享文本里提取标题/作者提示（小红书分享文本形如「标题 - 作者 | 小红书」） */
    fun shareHints(text: String?): Pair<String, String> {
        val raw = text.orEmpty()
        Regex("""【(.+?)\s*-\s*(.+?)\s*[|｜]""").find(raw)?.let {
            return it.groupValues[1].trim() to it.groupValues[2].trim()
        }
        Regex("""(.+?)\s*-\s*([^|\n]{1,20})\s*[|｜]\s*(小红书|快手|抖音)""").find(raw)?.let {
            return it.groupValues[1].trim() to it.groupValues[2].trim()
        }
        return "" to ""
    }
}
