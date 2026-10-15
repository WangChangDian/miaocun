package local.wangchangdian.videograbber

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Random

/**
 * 微信视频号解析。
 *
 * 视频号的分享链接（weixin.qq.com/sph/xxx）打不开视频：网页端被刻意限制，只返回封面和元数据。
 * 真正能拿到地址的链路是：
 *
 *   ① 拿分享链接去「腾讯元宝」换一个 generalToken（需要元宝登录态，见 CookieStore 里的 wechat_channels）
 *   ② 带着 generalToken 调视频号公开接口 get_feed_info，这次才返回视频地址
 *   ③ 直接下载（明文 MP4，支持 Range，不需要解密）
 *
 * 只用 HttpURLConnection，不引入新依赖。元宝接口是私有接口，腾讯随时可能改；失效时只影响视频号。
 */
object WxChannelsClient {

    private const val PARSE_URL = "https://yuanbao.tencent.com/api/weixin/get_parse_result"
    /**
     * 注意：这个 URL 里带 `%2F`（编码后的 /），**不能用 String.format 拼**——
     * `%2F` 会被当成格式符，抛 `Conversion = 'F'`。所以用字符串拼接。
     */
    private fun feedUrl(): String =
        "https://channels.weixin.qq.com/finder-preview/api/feed/get_feed_info" +
            "?_rid=" + rid() +
            "&_pageUrl=https:%2F%2Fchannels.weixin.qq.com%2Ffinder-preview%2Fpages%2Ffeed"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class Resolved(
        val videoUrl: String,
        val title: String,
        val author: String,
    )

    /** needsRelogin = 服务端明确拒绝（登录态失效），调用方据此把状态标成「已失效」 */
    class WxChannelsException(message: String, val needsRelogin: Boolean = false) : Exception(message)

    private fun rid(): String =
        java.lang.Long.toHexString(System.currentTimeMillis() / 1000) + "-" +
            (1..8).map { "0123456789abcdef"[Random().nextInt(16)] }.joinToString("")

    private fun postJson(url: String, body: JSONObject, headers: Map<String, String>, timeoutMs: Int = 25000): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                if (code == 401 || code == 403) {
                    throw WxChannelsException("腾讯元宝登录态已失效：请到「设置 → Cookie 管理 → 微信视频号」重新登录", true)
                }
                throw WxChannelsException("接口返回 HTTP $code")
            }
            return text
        } catch (error: WxChannelsException) {
            throw error
        } catch (error: Exception) {
            throw WxChannelsException("网络请求失败：${error.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    /** 分享链接 → 可下载的视频信息（需要 context 读取本机保存的腾讯元宝登录态） */
    fun resolve(context: Context, shareUrl: String): Resolved {
        val cookie = CookieStore.cookieHeaderFor(context, "wechat_channels")
        if (cookie.isBlank()) {
            throw WxChannelsException("视频号需要先登录腾讯元宝：设置 → Cookie 管理 → 微信视频号 → 登录")
        }

        // ① 元宝解析：分享链接 → generalToken
        val parseBody = JSONObject()
            .put("type", "video_channel_url")
            .put("url", shareUrl)
            .put("scene", 1)
        val parseText = postJson(
            PARSE_URL, parseBody,
            mapOf(
                "accept" to "application/json, text/plain, */*",
                "origin" to "https://yuanbao.tencent.com",
                "referer" to "https://yuanbao.tencent.com/chat/naQivTmsDa",
                "user-agent" to UA,
                "x-agentid" to "naQivTmsDa/cf4d0079-ed1b-4c55-a3f3-2ca1379727d1",
                "x-platform" to "android",
                "x-source" to "web",
                "x-language" to "zh-CN",
                "x-requested-with" to "XMLHttpRequest",
                "x-webversion" to "2.69.0",
                "cookie" to cookie,
            ),
        )
        val parse = runCatching { JSONObject(parseText) }.getOrElse {
            throw WxChannelsException("元宝返回了无法解析的数据")
        }
        if (parse.optInt("code", -1) != 0) {
            throw WxChannelsException("元宝解析失败：${parse.optString("msg").ifBlank { "code=${parse.optInt("code")}" }}")
        }
        val info = parse.optJSONObject("data") ?: JSONObject()
        val playable = info.optString("playable_url")
        val exportId = info.optString("wx_export_id")
        var token = ""
        var eid = exportId
        runCatching {
            val query = URL(playable).query.orEmpty()
            query.split("&").forEach { pair ->
                val key = pair.substringBefore("=")
                val value = java.net.URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
                if (key == "token") token = value
                if (key == "eid" && value.isNotBlank()) eid = value
            }
        }
        if (token.isBlank() || eid.isBlank()) {
            throw WxChannelsException("没有拿到解析令牌，可能这条分享链接已失效")
        }

        // ② 用令牌换视频详情（这一步才返回视频地址）
        val feedBody = JSONObject()
            .put("baseReq", JSONObject().put("generalToken", token))
            .put("exportId", eid)
        val referer = "https://channels.weixin.qq.com/finder-preview/pages/feed" +
            "?entry_card_type=48&comment_scene=39&appid=0" +
            "&token=${URLEncoder.encode(token, "UTF-8")}&entry_scene=0&eid=${URLEncoder.encode(eid, "UTF-8")}"
        val feedText = postJson(
            feedUrl(), feedBody,
            mapOf(
                "accept" to "application/json, text/plain, */*",
                "origin" to "https://channels.weixin.qq.com",
                "referer" to referer,
                "user-agent" to UA,
            ),
        )
        val feed = runCatching { JSONObject(feedText) }.getOrElse {
            throw WxChannelsException("视频号返回了无法解析的数据")
        }
        if (feed.optInt("errCode", 0) != 0) {
            throw WxChannelsException("视频号返回错误：${feed.optString("errMsg").ifBlank { "errCode=${feed.optInt("errCode")}" }}")
        }
        val data = feed.optJSONObject("data") ?: JSONObject()
        val feedInfo = data.optJSONObject("feedInfo") ?: JSONObject()
        // 默认优先 h264（相册/播放器兼容性最好）；开了「省流量」则优先 h265（同分辨率体积约减半）
        val h264 = feedInfo.optJSONObject("h264VideoInfo")?.optString("videoUrl").orEmpty()
            .ifBlank { feedInfo.optString("videoUrl") }
        val h265 = feedInfo.optJSONObject("h265VideoInfo")?.optString("videoUrl").orEmpty()
        val videoUrl = if (AppState.wxchannelsH265.value && h265.isNotBlank()) h265 else h264.ifBlank { h265 }
        if (videoUrl.isBlank()) {
            throw WxChannelsException("没有解析到视频地址（可能是图文作品或已被删除）")
        }
        val author = data.optJSONObject("authorInfo")?.optString("nickname").orEmpty()
        return Resolved(
            videoUrl = videoUrl,
            title = feedInfo.optString("description").trim(),
            author = author,
        )
    }
}
