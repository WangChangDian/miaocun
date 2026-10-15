package local.wangchangdian.videograbber

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Instagram 解析。
 *
 * yt-dlp 只处理视频帖，图片帖（含轮播）会直接报 "no video in this post"，
 * 所以这里改用 Instagram 自己的 web 接口：
 *   短码 → media id → /api/v1/media/<id>/info/
 * 一次拿到整帖（图片 / 视频 / 轮播）。接口需要登录态，未登录会被 429 拦住，
 * 因此必须先在「设置 → Cookie 管理 → Instagram」登录一次（国内还要开代理）。
 */
object InstagramClient {

    private const val APP_ID = "936619743392459"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Media(val url: String, val isVideo: Boolean)

    data class Resolved(val items: List<Media>, val title: String, val author: String)

    class InstagramException(message: String) : Exception(message)

    fun shortcode(url: String): String? =
        Regex("""instagram\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)

    /** 短码是 base64 变体编码的数字 id */
    fun mediaId(shortcode: String): Long {
        var value = 0L
        for (char in shortcode) {
            val index = ALPHABET.indexOf(char)
            if (index < 0) continue
            value = value * 64 + index
        }
        return value
    }

    fun resolve(context: Context, url: String): Resolved {
        val shortcode = shortcode(url)
            ?: throw InstagramException("不是 Instagram 作品链接（需要 /p/、/reel/ 这样的地址）")
        val cookie = CookieStore.cookieHeaderFor(context, "instagram")
        if (cookie.isBlank()) {
            throw InstagramException(
                "Instagram 需要先登录：设置 → Cookie 管理 → Instagram → 登录一次" +
                    "（国内还需要开代理）"
            )
        }

        val api = "https://www.instagram.com/api/v1/media/${mediaId(shortcode)}/info/"
        val body = runCatching { get(api, cookie) }.getOrNull()
        if (body != null) {
            val post = runCatching { JSONObject(body).optJSONArray("items")?.optJSONObject(0) }.getOrNull()
            if (post != null) {
                val carousel = post.optJSONArray("carousel_media")
                val nodes = mutableListOf<JSONObject>()
                if (carousel != null && carousel.length() > 0) {
                    for (i in 0 until carousel.length()) carousel.optJSONObject(i)?.let { nodes.add(it) }
                } else {
                    nodes.add(post)
                }
                val items = nodes.mapNotNull { mediaOf(it) }
                if (items.isNotEmpty()) {
                    val title = post.optJSONObject("caption")?.optString("text").orEmpty()
                        .replace("\n", " ").take(120)
                    val author = post.optJSONObject("user")?.optString("username").orEmpty()
                    LogStore.log("Instagram", "解析到 ${items.size} 个资源（${items.count { it.isVideo }} 个视频）")
                    return Resolved(items, title, author)
                }
            }
        }

        // 接口没通（未登录/被限流）：退化为抓页面里的第一张图
        val html = runCatching { get("https://www.instagram.com/p/$shortcode/", cookie) }.getOrNull()
            ?: throw InstagramException("Instagram 接口没有返回内容（检查代理与登录态）")
        val image = Regex("""<meta property="og:image" content="([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex(""""display_url":"(https:[^"]+)"""").find(html)?.groupValues?.get(1)
            ?: throw InstagramException("没有解析到内容：请在设置里登录 Instagram 后重试")
        val decoded = image.replace("\\u0026", "&").replace("&amp;", "&")
        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        return Resolved(listOf(Media(decoded, false)), title.take(120), "")
    }

    private fun mediaOf(media: JSONObject): Media? {
        if (media.optInt("media_type") == 2) {
            val video = media.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url").orEmpty()
            if (video.isNotBlank()) return Media(video, true)
        }
        val candidates = media.optJSONObject("image_versions2")?.optJSONArray("candidates")
        val image = if (candidates != null && candidates.length() > 0) {
            candidates.optJSONObject(0)?.optString("url").orEmpty()
        } else {
            ""
        }
        return if (image.isBlank()) null else Media(image, false)
    }

    private fun get(url: String, cookie: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("X-IG-App-ID", APP_ID)
            .header("Accept", "*/*")
            .header("Referer", "https://www.instagram.com/")
            .header("Cookie", cookie)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return text
        }
    }
}
