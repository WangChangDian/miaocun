package local.wangchangdian.videograbber

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * 云控后台客户端：设备注册、Cookie 下发、版本查询、反馈提交。
 *
 * 只用 JDK 自带的 HttpURLConnection，不引入新依赖。
 */
object CloudClient {
    const val DEFAULT_SERVER = "https://music.aiyun-jing.com:8443/downloader-api"

    /** 官网地址（隐私政策 / 用户协议 / 开源声明都挂在这里） */
    val SITE_BASE: String get() = DEFAULT_SERVER.removeSuffix("/downloader-api") + "/download"


    /** 与后台约定的客户端版本：取 Android 构建配置（源头是仓库根目录的 VERSION 文件） */
    val APP_VERSION_NAME: String get() = BuildConfig.VERSION_NAME
    val APP_VERSION_CODE: Int get() = BuildConfig.VERSION_CODE

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val changelog: String,
        val downloadUrl: String,
        val force: Boolean,
        /** 安装包大小与 MD5：用于校验下载是否完整（后台发布时回填） */
        val fileSize: Long = 0L,
        val fileMd5: String = "",
    )

    private fun server(context: Context): String =
        AppState.serverUrl().ifBlank { DEFAULT_SERVER }.trimEnd('/')

    // ------------------------------------------------------------ HTTP

    private fun call(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        retryOnUnauthorized: Boolean = true,
    ): JSONObject {
        val connection = (URL(server(context) + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val parsed = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (parsed.optInt("code", code) != 200) {
                // 令牌失效（服务端换过 Redis 库、清过缓存、令牌过期等）：清掉本地令牌，
                // 重新注册一次再重试，避免用户卡在「设备未注册」上再也用不了
                if (parsed.optInt("code", code) == 401 && !token.isNullOrBlank() && retryOnUnauthorized) {
                    LogStore.log("云控", "令牌已失效，自动重新注册")
                    AppState.setDeviceToken("")
                    val fresh = register(context, force = true)
                    if (fresh.isNotBlank()) {
                        return call(context, method, path, body, fresh, retryOnUnauthorized = false)
                    }
                }
                throw CloudException(parsed.optString("msg").ifBlank { "服务器错误 HTTP $code" })
            }
            return parsed.optJSONObject("data") ?: JSONObject()
        } catch (error: CloudException) {
            throw error
        } catch (error: Exception) {
            throw CloudException("无法连接云控后台（${error.javaClass.simpleName}）")
        } finally {
            connection.disconnect()
        }
    }

    class CloudException(message: String) : Exception(message)

    // -------------------------------------------------------- 设备注册

    private fun deviceId(context: Context): String {
        val existing = AppState.deviceId()
        if (existing.isNotBlank()) return existing
        val raw = runCatching {
            "${Build.MANUFACTURER}-${Build.MODEL}-${Build.FINGERPRINT}"
        }.getOrElse { UUID.randomUUID().toString() }
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val id = "android-$digest"
        AppState.setDeviceId(id)
        return id
    }

    /** 注册 / 续期设备令牌，成功后写入本地 */
    @Synchronized
    fun register(context: Context, force: Boolean = false): String {
        val token = AppState.deviceToken()
        if (token.isNotBlank() && !force) return token
        val payload = JSONObject().apply {
            put("platform", "android")
            put("deviceId", deviceId(context))
            put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("appVersion", APP_VERSION_NAME)
            put("osVersion", "Android ${Build.VERSION.RELEASE}")
        }
        val data = call(context, "POST", "/app/device/register", payload)
        val issued = data.optString("token")
        if (issued.isNotBlank()) AppState.setDeviceToken(issued)
        return issued
    }

    private fun token(context: Context): String {
        val existing = AppState.deviceToken()
        return if (existing.isNotBlank()) existing else register(context)
    }

    // ------------------------------------------------------------ 版本

    fun checkUpdate(context: Context, platform: String = "android"): UpdateInfo? {
        // 版本检查不要求注册/登录：有设备令牌就带上（顺带记活跃），没有也照样能查
        val data = call(
            context,
            "GET",
            "/app/version/latest?platform=$platform&versionCode=$APP_VERSION_CODE",
            token = AppState.deviceToken().ifBlank { null },
        )
        if (!data.optBoolean("hasUpdate")) return null
        return UpdateInfo(
            versionName = data.optString("versionName"),
            versionCode = data.optInt("versionCode"),
            changelog = data.optString("changelog"),
            downloadUrl = data.optString("downloadUrl"),
            force = data.optBoolean("force"),
            fileSize = data.optLong("fileSize", 0L),
            fileMd5 = data.optString("fileMd5"),
        )
    }

    // ------------------------------------------------------------ 反馈

    // ------------------------------------------------------------ 账号（B2：手机号登录 + 云端 Cookie）

    private fun accountToken(context: Context): String {
        val token = AppState.accountToken()
        if (token.isBlank()) error("还没有登录账号，请先在「设置 → 账号」里用手机号登录")
        return token
    }

    fun requestSmsCode(context: Context, phone: String): JSONObject =
        call(context, "POST", "/app/auth/sms-code", org.json.JSONObject().put("phone", phone).put("scene", "LOGIN"))

    fun loginWithSms(context: Context, phone: String, code: String): JSONObject =
        call(
            context, "POST", "/app/auth/sms-login",
            org.json.JSONObject()
                .put("phone", phone)
                .put("code", code)
                .put("deviceName", android.os.Build.MODEL)
                .put("deviceType", "mobile"),
        )

    fun logout(context: Context) {
        runCatching { call(context, "POST", "/app/auth/logout", org.json.JSONObject(), accountToken(context)) }
    }

    fun cookieSyncStatus(context: Context): JSONObject =
        call(context, "GET", "/app/cookie/status", token = accountToken(context))

    fun uploadCookie(context: Context, platform: String, cookie: String): JSONObject =
        call(
            context, "POST", "/app/cookie/upload",
            org.json.JSONObject()
                .put("platform", platform)
                .put("deviceType", "mobile")
                .put("cookie", cookie)
                .put("deviceName", android.os.Build.MODEL),
            accountToken(context),
        )

    fun fetchCookie(context: Context, platform: String, deviceType: String): JSONObject =
        call(
            context, "GET", "/app/cookie/fetch?platform=$platform&deviceType=$deviceType",
            token = accountToken(context),
        )

    fun deleteCloudCookie(context: Context, platform: String?): JSONObject =
        call(
            context, "POST", "/app/cookie/delete",
            org.json.JSONObject().apply { if (!platform.isNullOrBlank()) put("platform", platform) },
            accountToken(context),
        )

    fun sendFeedback(
        context: Context,
        type: String,
        content: String,
        contact: String,
        withLogs: Boolean,
    ): String {
        val payload = JSONObject().apply {
            put("type", type)
            put("content", content)
            put("contact", contact)
            put("platform", "android")
            put("appVersion", APP_VERSION_NAME)
            put("osVersion", "Android ${Build.VERSION.RELEASE}")
            put("deviceId", deviceId(context))
            put("withLogs", withLogs)
        }
        val data = call(context, "POST", "/app/feedback", payload, token = token(context))
        return data.optString("message", "感谢反馈，我们会尽快处理！")
    }

}
