package local.wangchangdian.videograbber

import org.json.JSONObject

enum class Platform(val key: String, val label: String) {
    DOUYIN("douyin", "抖音"),
    KUAISHOU("kuaishou", "快手"),
    XIAOHONGSHU("xiaohongshu", "小红书"),
    WECHAT_CHANNELS("wechat_channels", "微信视频号"),
    BILIBILI("bilibili", "B 站"),
    WEIBO("weibo", "微博"),
    TIKTOK("tiktok", "TikTok"),
    INSTAGRAM("instagram", "Instagram"),
    X("x", "X"),
    YOUTUBE("youtube", "YouTube"),
    // 第三梯队：yt-dlp 原生支持（西瓜 / Reddit / Vimeo / Twitch）；微视 / 好看走通用解析 + 网页嗅探
    XIGUA("ixigua", "西瓜视频"),
    WEISHI("weishi", "微视"),
    HAOKAN("haokan", "好看视频"),
    REDDIT("reddit", "Reddit"),
    VIMEO("vimeo", "Vimeo"),
    TWITCH("twitch", "Twitch"),
    // 音频平台（yt-dlp 原生支持；QQ 音乐多数歌曲要登录 Cookie）
    NETEASE("netease", "网易云音乐"),
    QQMUSIC("qqmusic", "QQ 音乐"),
    GENERIC("generic", "通用解析"),
    WEB("web", "网页"),
    FILE("file", "文件"),
    UNKNOWN("unknown", "其他");

    companion object {
        fun fromKey(key: String?): Platform = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/** 解析结果：待下载的媒体地址 + 元信息 */
data class ExtractResult(
    val platform: Platform,
    val title: String,
    val author: String,
    val mediaUrls: List<String>,
    val kind: String = KIND_VIDEO,
    val audioUrl: String? = null,
    /**
     * 多条目作品（如抖音「由多个短视频组成的图文」）里每一项的类型（KIND_VIDEO / KIND_IMAGE），
     * 与 [mediaUrls] 一一对应；为空表示按 [kind] 统一处理。
     */
    val mediaKinds: List<String> = emptyList(),
) {
    val isVideo: Boolean get() = kind == KIND_VIDEO

    fun toJson(): String = JSONObject().apply {
        put("platform", platform.key)
        put("title", title)
        put("author", author)
        put("kind", kind)
        if (!audioUrl.isNullOrBlank()) put("audioUrl", audioUrl)
        put("mediaUrls", org.json.JSONArray(mediaUrls))
        if (mediaKinds.isNotEmpty()) put("mediaKinds", org.json.JSONArray(mediaKinds))
    }.toString()

    companion object {
        const val KIND_VIDEO = "video"
        const val KIND_IMAGE = "image"
        const val KIND_FILE = "file"
        const val KIND_YTDLP = "ytdlp"
        /** 音频（网易云 / QQ 音乐等）：存到系统「音乐」目录 */
        const val KIND_AUDIO = "audio"

        fun fromJson(raw: String): ExtractResult {
            val json = JSONObject(raw)
            val array = json.optJSONArray("mediaUrls") ?: org.json.JSONArray()
            val urls = (0 until array.length()).map { array.optString(it) }
            val kindsArray = json.optJSONArray("mediaKinds")
            val kinds = kindsArray?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()
            val kind = json.optString("kind").ifEmpty {
                if (json.optBoolean("isVideo", true)) KIND_VIDEO else KIND_IMAGE
            }
            return ExtractResult(
                platform = Platform.fromKey(json.optString("platform")),
                title = json.optString("title"),
                author = json.optString("author"),
                mediaUrls = urls,
                kind = kind,
                audioUrl = json.optString("audioUrl").takeIf { it.isNotBlank() },
                mediaKinds = kinds,
            )
        }
    }
}

data class DownloadState(
    val platform: Platform,
    val title: String,
    val author: String,
    val stage: String,
    val percent: Int? = null,
    val bytesDownloaded: Long = 0,
    val bytesTotal: Long = 0,
    val finished: Boolean = false,
    val failed: Boolean = false,
    val message: String = "",
    val filePath: String = "",
    val uri: String = "",
    val fileNames: List<String> = emptyList(),
    val fileUris: List<String> = emptyList(),
    /** 每个文件的大小（与 fileUris 一一对应；缺失时界面会去查一次） */
    val fileSizes: List<Long> = emptyList(),
)

data class HistoryEntry(
    val platform: Platform,
    val title: String,
    val author: String,
    val filePath: String,
    val sizeText: String,
    val quality: String,
    val finishedAt: String,
    val uri: String = "",
    val uris: List<String> = emptyList(),
    val fileNames: List<String> = emptyList(),
    val sizes: List<Long> = emptyList(),
)

/** 下载结果的文件类型：决定卡片里用什么方式展示（缩略图 / 图标 / 普通行） */
enum class MediaKind { IMAGE, VIDEO, AUDIO, PDF, OTHER }

fun mediaKindOf(fileName: String): MediaKind =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp" -> MediaKind.IMAGE
        "mp4", "mov", "mkv", "webm", "m4v", "3gp", "avi", "ts" -> MediaKind.VIDEO
        "mp3", "m4a", "aac", "flac", "wav", "opus", "ogg" -> MediaKind.AUDIO
        "pdf" -> MediaKind.PDF
        else -> MediaKind.OTHER
    }

data class QueueItem(
    val id: String,
    val title: String,
    val platform: Platform,
    var state: String = "排队中",
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
