package local.wangchangdian.videograbber

import org.json.JSONArray
import org.json.JSONObject

/**
 * 各平台页面数据解析：输入 WebView 截获到的 JSON / HTML 文本，输出可下载的媒体地址。
 * 与桌面端保持同一套思路：让真实浏览器去发请求（自带签名），我们只解析结果。
 */
object Extractor {

    /** 图文作品（aweme_type=68）的 play_addr 多为背景音乐，不能当视频下载 */
    private val DOUYIN_AUDIO_MARKERS = listOf("/ies-music/", ".mp3", ".m4a")

    private fun isAudioUrl(url: String): Boolean {
        val lowered = url.lowercase()
        return DOUYIN_AUDIO_MARKERS.any { lowered.contains(it) }
    }

    // ---------------- 抖音 ----------------
    fun parseDouyin(json: String): ExtractResult? {
        val payload = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val detail = payload.optJSONObject("aweme_detail") ?: return null
        val video = detail.optJSONObject("video") ?: JSONObject()

        // 收集某段 video 的候选地址：按码率降序去重，排除背景音乐
        fun rankedUrls(videoObj: JSONObject): List<String> {
            data class Candidate(val bitrate: Int, val url: String)
            val candidates = mutableListOf<Candidate>()
            videoObj.optJSONArray("bit_rate")?.let { list ->
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val urls = item.optJSONObject("play_addr")?.optJSONArray("url_list") ?: continue
                    val url = urls.optString(0)
                    if (url.isNotEmpty()) candidates.add(Candidate(item.optInt("bit_rate"), url))
                }
            }
            if (candidates.isEmpty()) {
                videoObj.optJSONObject("play_addr")?.optJSONArray("url_list")?.optString(0)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { candidates.add(Candidate(0, it)) }
            }
            return candidates
                .filterNot { isAudioUrl(it.url) }
                .sortedByDescending { it.bitrate }
                .map { it.url.replace("playwm", "play") }
                .distinct()
        }

        // 图文作品优先：只要 images 非空就按图文处理——外层 video.play_addr 往往是
        // 背景音乐（URL 不一定带 mp3/ies-music 标记，靠后缀判断会漏），不能当作品视频下载。
        // 每个条目既可能是图片，也可能是短视频（「多视频图文」），逐条记录类型。
        detail.optJSONArray("images")?.takeIf { it.length() > 0 }?.let { list ->
            val urlList = mutableListOf<String>()
            val kindList = mutableListOf<String>()
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val videoUrls = rankedUrls(item.optJSONObject("video") ?: JSONObject())
                val imageUrl = item.optJSONArray("url_list")?.optString(0)?.takeIf { it.isNotEmpty() }
                when {
                    videoUrls.isNotEmpty() -> {
                        urlList.add(videoUrls.first())
                        kindList.add(ExtractResult.KIND_VIDEO)
                    }
                    imageUrl != null -> {
                        urlList.add(imageUrl)
                        kindList.add(ExtractResult.KIND_IMAGE)
                    }
                }
            }
            // 诊断：条目数与可用地址数（排查"只下到一部分"）
            LogStore.log(
                "解析",
                "抖音图文：条目=${list.length()}，可用=${urlList.size}（视频 ${kindList.count { it == ExtractResult.KIND_VIDEO }}，" +
                    "图片 ${kindList.count { it == ExtractResult.KIND_IMAGE }}）",
            )
            if (urlList.isNotEmpty()) {
                return ExtractResult(
                    platform = Platform.DOUYIN,
                    title = detail.optString("desc"),
                    author = detail.optJSONObject("author")?.optString("nickname").orEmpty(),
                    mediaUrls = urlList,
                    kind = if (kindList.all { it == ExtractResult.KIND_VIDEO }) {
                        ExtractResult.KIND_VIDEO
                    } else {
                        ExtractResult.KIND_IMAGE
                    },
                    mediaKinds = kindList,
                )
            }
        }

        // 普通视频作品
        val urls = rankedUrls(video)
        if (urls.isNotEmpty()) {
            return ExtractResult(
                platform = Platform.DOUYIN,
                title = detail.optString("desc"),
                author = detail.optJSONObject("author")?.optString("nickname").orEmpty(),
                mediaUrls = urls,
                kind = ExtractResult.KIND_VIDEO,
            )
        }
        return null
    }

    // ---------------- 快手（移动端页面 INIT_STATE） ----------------
    fun parseKuaishou(html: String): ExtractResult? {
        val json = extractAssignedJson(html, "window.INIT_STATE") ?: return null
        val photo = findPhotoObject(runCatching { JSONObject(json) }.getOrNull()) ?: return null
        val urls = mutableListOf<String>()
        photo.optJSONArray("mainMvUrls")?.let { list ->
            for (i in 0 until list.length()) {
                list.optJSONObject(i)?.optString("url")?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
            }
        }
        if (urls.isEmpty()) {
            photo.optJSONObject("manifest")?.optJSONArray("adaptationSet")?.let { set ->
                for (i in 0 until set.length()) {
                    val reps = set.optJSONObject(i)?.optJSONArray("representation") ?: continue
                    for (j in 0 until reps.length()) {
                        reps.optJSONObject(j)?.optString("url")?.takeIf { it.isNotEmpty() }?.let { urls.add(it) }
                    }
                }
            }
        }
        if (urls.isNotEmpty()) {
            return ExtractResult(
                platform = Platform.KUAISHOU,
                title = photo.optString("caption"),
                author = photo.optString("userName"),
                mediaUrls = urls.distinct(),
                kind = ExtractResult.KIND_VIDEO,
            )
        }
        return null
    }

    private fun findPhotoObject(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.has("mainMvUrls") && node.has("caption")) return node
                for (key in node.keys()) {
                    findPhotoObject(node.opt(key))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findPhotoObject(node.opt(i))?.let { return it }
                }
            }
            is String -> {
                if (node.trimStart().startsWith("{")) {
                    runCatching { findPhotoObject(JSONObject(node)) }.getOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    // ---------------- 小红书 ----------------
    fun parseXiaohongshu(payload: String): ExtractResult? {
        val nicknameFallback = listOf("nickname", "nickName", "userName")
            .firstNotNullOfOrNull { key ->
                Regex(""""$key"\s*:\s*"([^"]{1,30})"""").find(payload)?.groupValues?.get(1)
            }
            .orEmpty()
        // 1) 接口 JSON（/api/sns/web/v1/feed 等）
        if (payload.trimStart().startsWith("{")) {
            runCatching { JSONObject(payload) }.getOrNull()?.let { json ->
                findNoteObject(json)?.let { note -> buildXhsResult(note, nicknameFallback)?.let { return it } }
            }
        }
        // 2) 页面内嵌状态
        val stateJson = extractAssignedJson(payload, "window.__INITIAL_STATE__")
            ?: extractAssignedJson(payload, "window.__INITIAL_SSR_STATE__")
        if (stateJson != null) {
            val state = runCatching { JSONObject(stateJson) }.getOrNull()
            findNoteObject(state)?.let { note ->
                val result = buildXhsResult(note, nicknameFallback)
                if (result != null) return result
            }
        }
        // 3) 兜底：直接从文本里找 sns-video 链接（优先非预览版本）
        val videoKeys = Regex("""https://sns-video[^"\\\s]+""").findAll(payload).map { it.value }.toList()
        if (videoKeys.isNotEmpty()) {
            return ExtractResult(Platform.XIAOHONGSHU, "", "", videoKeys.distinct(), ExtractResult.KIND_VIDEO)
        }
        return null
    }

    private fun buildXhsResult(note: JSONObject, nicknameFallback: String = ""): ExtractResult? {
        val title = note.optString("title").ifEmpty { note.optString("desc") }
        val author = note.optJSONObject("user")?.optString("nickname").orEmpty().ifEmpty { nicknameFallback }
        data class Candidate(val score: Int, val url: String)
        val candidates = mutableListOf<Candidate>()

        val video = note.optJSONObject("video")
        if (video != null) {
            // 原始文件（通常画质最高）
            video.optJSONObject("consumer")?.optString("originVideoKey")?.takeIf { it.isNotEmpty() }?.let {
                candidates.add(Candidate(Int.MAX_VALUE, "https://sns-video-bd.xhscdn.com/$it"))
            }
            val stream = video.optJSONObject("media")?.optJSONObject("stream")
            for (codec in listOf("h264", "h265", "av1")) {
                val list = stream?.optJSONArray(codec) ?: continue
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val score = (item.optInt("width") * item.optInt("height"))
                        .takeIf { it > 0 }
                        ?: item.optInt("avgBitrate", 0)
                    item.optString("masterUrl").takeIf { it.isNotEmpty() }?.let { candidates.add(Candidate(score, it)) }
                    item.optJSONArray("backupUrls")?.let { backups ->
                        for (j in 0 until backups.length()) {
                            backups.optString(j).takeIf { it.isNotEmpty() }?.let { candidates.add(Candidate(score, it)) }
                        }
                    }
                }
            }
        }
        val urls = candidates.sortedByDescending { it.score }.map { it.url }.distinct()
        if (urls.isNotEmpty()) {
            android.util.Log.i("VideoGrabber", "xhs candidates=${urls.map { it.take(60) }}")
            return ExtractResult(Platform.XIAOHONGSHU, title, author, urls.distinct(), ExtractResult.KIND_VIDEO)
        }

        val images = mutableListOf<String>()
        note.optJSONArray("imageList")?.let { list ->
            for (i in 0 until list.length()) {
                // 注意 optString 缺键时返回空串（不是 null），所以要用 ifBlank 兜底：
                // PC 接口的图有 urlDefault，H5 内嵌状态的图只有 url
                val item = list.optJSONObject(i)
                val url = item?.optString("urlDefault").orEmpty()
                    .ifBlank { item?.optString("url").orEmpty() }
                url.takeIf { it.isNotEmpty() }?.let { images.add(it) }
            }
        }
        if (images.isNotEmpty()) {
            return ExtractResult(Platform.XIAOHONGSHU, title, author, images.distinct(), ExtractResult.KIND_IMAGE)
        }
        return null
    }

    private fun findNoteObject(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                val video = node.optJSONObject("video")
                val hasVideo = video != null &&
                    (video.has("media") || video.has("consumer") || configLooksLikeVideo(video))
                if (hasVideo && (node.has("title") || node.has("desc") || node.has("noteId"))) return node
                // 图文笔记：H5 内嵌状态里的 note 可能只有 imageList（标题在分享文本里），
                // 所以这里只要求有图片列表，标题交给上层用分享文本补齐
                val images = node.optJSONArray("imageList")
                if (images != null && images.length() > 0) return node
                for (key in node.keys()) {
                    findNoteObject(node.opt(key))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findNoteObject(node.opt(i))?.let { return it }
                }
            }
            is String -> {
                if (node.trimStart().startsWith("{")) {
                    runCatching { findNoteObject(JSONObject(node)) }.getOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private fun configLooksLikeVideo(video: JSONObject): Boolean =
        video.keys().asSequence().any { it.contains("stream", true) || it.contains("media", true) }

    // ---------------- 通用工具 ----------------

    // ---------------- X（Twitter） ----------------
    fun parseX(json: String): ExtractResult? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

        data class Variant(val bitrate: Int, val url: String)
        val variants = mutableListOf<Variant>()
        var text = ""
        var author = ""

        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("video_info")?.optJSONArray("variants")?.let { list ->
                        for (i in 0 until list.length()) {
                            val item = list.optJSONObject(i) ?: continue
                            val type = item.optString("content_type")
                            val url = item.optString("url")
                            if (type.contains("mp4") && url.isNotEmpty()) {
                                variants.add(Variant(item.optInt("bitrate"), url))
                            }
                        }
                    }
                    if (text.isEmpty()) {
                        node.optString("full_text").takeIf { it.isNotBlank() }?.let { text = it }
                    }
                    if (author.isEmpty()) {
                        node.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() }
                            ?.let { author = it }
                    }
                    for (key in node.keys()) walk(node.opt(key))
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
                is String -> if (node.trimStart().startsWith("{")) {
                    runCatching { walk(JSONObject(node)) }
                }
            }
        }
        walk(root)
        val urls = variants.sortedByDescending { it.bitrate }.map { it.url }.distinct()
        if (urls.isEmpty()) return null
        return ExtractResult(
            platform = Platform.X,
            title = text.replace("\n", " ").take(120),
            author = author,
            mediaUrls = urls,
            kind = ExtractResult.KIND_VIDEO,
        )
    }

    // ---------------- YouTube ----------------
    fun parseYouTube(json: String): ExtractResult? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val streaming = root.optJSONObject("streamingData") ?: return null
        val details = root.optJSONObject("videoDetails")
        val title = details?.optString("title").orEmpty()
        val author = details?.optString("author").orEmpty()

        data class Format(val height: Int, val bitrate: Int, val url: String, val mime: String, val hasAudio: Boolean)
        val formats = mutableListOf<Format>()

        fun collect(array: JSONArray?, adaptive: Boolean) {
            array ?: return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.isEmpty()) continue
                val mime = item.optString("mimeType")
                if (!mime.contains("mp4")) continue
                formats.add(
                    Format(
                        height = item.optInt("height"),
                        bitrate = item.optInt("bitrate"),
                        url = url,
                        mime = mime,
                        hasAudio = !adaptive && item.has("audioQuality"),
                    )
                )
            }
        }
        collect(streaming.optJSONArray("formats"), adaptive = false)
        collect(streaming.optJSONArray("adaptiveFormats"), adaptive = true)

        val progressive = formats.filter { it.mime.startsWith("video/") && it.hasAudio }
            .maxByOrNull { it.height * 10_000 + it.bitrate }
        if (progressive != null) {
            return ExtractResult(
                Platform.YOUTUBE, title, author, listOf(progressive.url), ExtractResult.KIND_VIDEO,
            )
        }

        val video = formats.filter { it.mime.startsWith("video/") }
            .maxByOrNull { it.height * 10_000 + it.bitrate } ?: return null
        val audio = formats.filter { it.mime.startsWith("audio/") }.maxByOrNull { it.bitrate }
        if (audio == null) {
            return ExtractResult(Platform.YOUTUBE, title, author, listOf(video.url), ExtractResult.KIND_VIDEO)
        }
        android.util.Log.i("VideoGrabber", "youtube: video=${video.height}p audio=${audio.bitrate} 将合并")
        return ExtractResult(
            platform = Platform.YOUTUBE,
            title = title,
            author = author,
            mediaUrls = listOf(video.url),
            kind = ExtractResult.KIND_VIDEO,
            audioUrl = audio.url,
        )
    }

    /** 从 HTML 中提取 `window.X = {...}` 的 JSON 主体（按括号配平） */
    fun extractAssignedJson(html: String, marker: String): String? {
        val start = html.indexOf(marker)
        if (start < 0) return null
        val braceStart = html.indexOf('{', start)
        if (braceStart < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in braceStart until html.length) {
            val ch = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(braceStart, i + 1)
                }
            }
        }
        return null
    }

    /** 从 HTML 兜底找 mp4 直链 */
    fun fallbackMp4Urls(html: String): List<String> =
        Regex("""https?://[^"\\\s]+?\.mp4[^"\\\s]*""").findAll(html).map { it.value }.toList()

    /** 从页面 DOM 文本里找抖音 CDN 视频地址（douyinvod 等） */
    fun douyinDomUrls(text: String): List<String> {
        val patterns = listOf(
            // RSC 数据里的 URL 带 JSON 转义（& 写作 \u0026），字符集要允许 \ 进入匹配
            """https?://[^"'\s]*douyinvod[^"'\s]*""",
            """https?://[^"'\s]*\.mp4[^"'\s]*""",
            """"play_addr"[^}]*"url_list":\["([^"]+)"""",
        )
        val found = mutableListOf<String>()
        for (pattern in patterns) {
            Regex(pattern).findAll(text).forEach { match ->
                val value = match.groupValues.last()
                if (value.startsWith("http")) {
                    val normalized = value
                        .trimEnd('\\')
                        .replace("\\u002F", "/").replace("\\/", "/").replace("&amp;", "&")
                        .replace("\\u0026", "&").replace("\\u003d", "=").replace("\\u003f", "?")
                        .replace("playwm", "play")
                    // 排除两类脏地址：
                    // 1) video_id 后面又塞了一整个 URL 的畸形播放地址（请求它只会拿到空响应）
                    // 2) 背景音乐等音频地址（图文作品的 play_addr 常是 mp3）
                    if (normalized.contains("video_id=http", ignoreCase = true) || isAudioUrl(normalized)) {
                        return@forEach
                    }
                    // 3) 静态资源站（JS/CSS/字体等）：桌面页里常有 bytednsdoc 之类的地址，
                    //    之前被当成视频下载下来，得到一个十几 MB 的垃圾文件
                    if (!DOUYIN_MEDIA_HOSTS.any { normalized.contains(it) }) {
                        return@forEach
                    }
                    if (normalized !in found) found.add(normalized)
                }
            }
        }
        return found
    }

    /**
     * 把 DOM 里抓到的播放地址按「资源」分组：
     * 「多视频图文」的作品页一次会渲染出多条视频，每条视频有多个 CDN 镜像
     * （同路径不同域名，如 v11-weba / v26-web）。按路径（不含 query）分组，
     * 组按首次出现排序，组内是镜像候选。
     */
    fun douyinDomEntryGroups(text: String): List<List<String>> {
        val groups = linkedMapOf<String, MutableList<String>>()
        douyinDomUrls(text).forEach { url ->
            // 资源唯一标识是路径最后一段（如 .../oMeqzUxWhAwpD9kDwIFsynnDUf9hyGJEEABvIE）：
            // 不同镜像的前缀签名 hash / 时间戳各不相同，不能拿整段路径当 key
            // 注意：资源名后面可能还有一个尾斜杠（.../oMeqz.../?a=...），要先去掉
            val key = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
            groups.getOrPut(key) { mutableListOf() }.add(url)
        }
        return groups.values.map { it.toList() }
    }

    /**
     * 从页面 DOM 里找抖音图片地址（图文作品兜底）。
     * 优先取带 `aweme-images` 模板的正文图，避免把头像、封面小图混进来。
     */
    fun douyinDomImages(text: String): List<String> {
        val candidates = mutableListOf<String>()
        Regex("""https?://[^"'<>\\\s]*douyinpic\.com[^"'<>\\\s]*""").findAll(text).forEach { match ->
            val url = match.value
                .replace("&amp;", "&")
                .replace("\\u002F", "/")
                .replace("\\/", "/")
                .trimEnd(')', ',', ';', '"', '\'')
            if (url.length > 40 && !url.contains("avatar") && url !in candidates) {
                candidates.add(url)
            }
        }
        val postImages = candidates.filter { it.contains("aweme-images") || it.contains("/tos-cn-i-") }
        return if (postImages.isNotEmpty()) postImages else candidates
    }

    /** 抖音真实媒体所在的域名特征，用于剔除静态资源等无关地址 */
    private val DOUYIN_MEDIA_HOSTS = listOf(
        "douyinvod", "douyinstatic", "aweme.snssdk.com", "douyinpic",
    )

    /** 从抖音页面里取标题/作者（og 标签优先） */
    fun douyinDomMeta(html: String): Pair<String, String> {
        fun meta(name: String): String {
            val patterns = listOf(
                """<meta[^>]+(?:property|name)=["']$name["'][^>]*content=["']([^"']*)["']""",
                """<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']$name["']""",
            )
            for (pattern in patterns) {
                Regex(pattern, RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
            }
            return ""
        }
        val title = meta("og:title").ifEmpty {
            Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1).orEmpty()
        }.trim()
            .removeSuffix(" - 抖音").removeSuffix(" - 快手").trim()
        val desc = meta("og:description")
        // 作者：优先取作品头像的 alt（渲染后的页面里作者头像形如 <img ... aweme-avatar ... alt="作者名">），
        // 比 og:description 的 @名 / nickname 正则可靠（后者可能匹配到页面里的其他人）
        val authorFromAvatar = Regex("""<img[^>]*aweme-avatar[^>]*>""").findAll(html)
            .map { it.value }
            .firstNotNullOfOrNull { tag ->
                // 页面上可能同时有别人（推荐位等）的头像，alt 形如「XX头像」的是头像控件本身的描述，跳过
                Regex("""alt="([^"]*)"""").find(tag)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() && !it.endsWith("头像") }
            }
            ?: ""
        val authorFallback = Regex("""@([^@\s<>"]{2,24})""").find(desc)?.groupValues?.get(1)
            ?: Regex(""""nickname"\s*:\s*"([^"]{1,24})"""").find(html)?.groupValues?.get(1)
            ?: ""
        return title to (authorFromAvatar.ifBlank { authorFallback }).trim()
    }
}
