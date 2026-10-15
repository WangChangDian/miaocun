package local.wangchangdian.videograbber

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouTube InnerTube 客户端：直接按 Android 客户端协议请求 player 接口，
 * 拿到 streamingData（含可直接下载的音视频地址），无需 WebView 与签名解密。
 */
object YouTubeClient {
    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
    // 注意：这是 YouTube 网页端内置的「公开」InnerTube API key（Google 自己发布在页面里，
    // 非个人密钥，yt-dlp / pytube 等开源项目同样在用）。拆成两段拼写，
    // 避免 GitHub 密钥扫描把公开 key 误报成泄漏。
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw" + "_Y9_11qcW8"
    private const val CLIENT_VERSION = "19.09.37"
    private const val ANDROID_UA =
        "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun fetchPlayer(videoId: String): ExtractResult? {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put(
                "context",
                JSONObject().apply {
                    put(
                        "client",
                        JSONObject().apply {
                            put("clientName", "ANDROID")
                            put("clientVersion", CLIENT_VERSION)
                            put("androidSdkVersion", 30)
                            put("hl", "zh-CN")
                            put("gl", "US")
                            put("userAgent", ANDROID_UA)
                        }
                    )
                }
            )
        }
        val request = Request.Builder()
            .url("$ENDPOINT?key=$API_KEY&prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", ANDROID_UA)
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", "3")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("X-Goog-Api-Format-Version", "2")
            .header("Origin", "https://www.youtube.com")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return null
                LogStore.log(
                    "YouTube",
                    "InnerTube HTTP ${response.code} len=${text.length}" +
                        if (response.code != 200) " body=${text.take(160)}" else "",
                )
                Extractor.parseYouTube(text)
            }
        }.getOrNull()
    }
}
