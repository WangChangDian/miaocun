package local.wangchangdian.videograbber

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 前台下载服务：
 *  - 多任务排队（先进先出，逐个下载）
 *  - 多连接分段下载（类 IDM，最多 8 段并发），失败自动回退单连接
 *  - 断点续传（Range）+ 多 CDN 地址重试
 *  - 写入系统相册（视频/图片）或下载目录（普通文件）
 */
class DownloadService : Service() {

    companion object {
        const val EXTRA_RESULT_JSON = "result_json"
        private const val NOTIFICATION_ID = 1001
        private const val FOLDER = "视频下载"
        private const val SEGMENT_THRESHOLD = 4L * 1024 * 1024
        private const val MAX_SEGMENTS = 8

        private val pending = ConcurrentLinkedQueue<ExtractResult>()
        @Volatile private var workerRunning = false

        fun start(context: Context, result: ExtractResult) {
            pending.add(result)
            syncQueueState()
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_RESULT_JSON, result.toJson())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun syncQueueState() {
            val items = pending.mapIndexed { index, item ->
                QueueItem(
                    id = System.currentTimeMillis().toString() + index,
                    title = item.title.ifBlank { item.mediaUrls.firstOrNull().orEmpty() },
                    platform = item.platform,
                )
            }
            AppState.queue.value = items
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification("准备下载", 0, "视频下载器"))
        if (!workerRunning) {
            workerRunning = true
            scope.launch {
                try {
                    while (true) {
                        val next = pending.poll() ?: break
                        syncQueueState()
                        runDownload(next)
                    }
                } finally {
                    workerRunning = false
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(stage: String, percent: Int, title: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("秒存 · $stage")
            .setContentText(title.take(40))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(percent in 1..99)
            .setProgress(100, percent, percent <= 0)
            .setContentIntent(openIntent)
            .build()
    }

    private suspend fun runDownload(result: ExtractResult) {
        val baseName = buildName(result.author, result.title)
        try {
            var totalBytes = 0L
            var savedCount = 0
            val savedUris = mutableListOf<String>()
            val savedNames = mutableListOf<String>()
            val savedSizes = mutableListOf<Long>()

            var displayTitle = result.title
            if (result.kind == ExtractResult.KIND_YTDLP) {
                val ytResult = downloadWithYtDlp(result)
                totalBytes += ytResult.bytes
                savedCount += ytResult.uris.size
                savedUris.addAll(ytResult.uris)
                savedNames.addAll(ytResult.names)
                savedSizes.addAll(ytResult.sizes)
                if (ytResult.title.isNotBlank()) displayTitle = ytResult.title
            } else if (result.mediaKinds.isEmpty() &&
                (result.kind == ExtractResult.KIND_VIDEO || result.kind == ExtractResult.KIND_FILE)
            ) {
                val fileName = if (result.kind == ExtractResult.KIND_VIDEO) "$baseName.mp4" else baseName
                val saved = downloadOne(result.mediaUrls, fileName, result, savedUris, savedNames)
                totalBytes += saved
                savedCount++
                savedSizes.add(saved)
            } else {
                for ((index, url) in result.mediaUrls.withIndex()) {
                    val suffix = if (result.mediaUrls.size > 1) "-${index + 1}" else ""
                    // 多条目作品（如「多视频图文」）逐项类型可能不同：条目类型优先，缺省跟随整体 kind
                    val itemKind = result.mediaKinds.getOrNull(index) ?: result.kind
                    // 图集/嗅探出来的图片：保留「作者-标题-序号」的命名（不要被 CDN 原始文件名覆盖）
                    val isImage = itemKind == ExtractResult.KIND_IMAGE
                    val imageExt = url.substringBefore('?').substringAfterLast('.', "")
                        .lowercase()
                        .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "heic", "gif") }
                        ?: "jpg"
                    val fileName = when {
                        isImage -> "$baseName$suffix.$imageExt"
                        itemKind == ExtractResult.KIND_VIDEO -> "$baseName$suffix.mp4"
                        else -> "$baseName$suffix"
                    }
                    // 网页嗅探/图集里可能混进 HTML 之类的非媒体链接，逐个容错，别因为一条失败整单失败
                    val saved = runCatching {
                        downloadOne(
                            listOf(url),
                            fileName,
                            result.copy(kind = itemKind),
                            savedUris,
                            savedNames,
                        )
                    }.onFailure { error ->
                        LogStore.log("下载", "跳过第 ${index + 1} 个资源：${error.message}")
                    }.getOrDefault(0L)
                    if (saved > 0) {
                        totalBytes += saved
                        savedCount++
                        savedSizes.add(saved)
                    }
                }
                if (savedCount == 0) error("页面里没有可下载的媒体资源")
            }

            val sizeText = formatBytes(totalBytes)
            AppState.download.value = DownloadState(
                platform = result.platform,
                title = displayTitle,
                author = result.author,
                stage = "已完成",
                percent = 100,
                finished = true,
                message = sizeText,
                uri = savedUris.firstOrNull().orEmpty(),
                fileNames = savedNames.toList(),
                fileUris = savedUris.toList(),
                fileSizes = savedSizes.toList(),
            )
            AppState.addHistory(
                HistoryEntry(
                    platform = result.platform,
                    title = displayTitle.ifEmpty { baseName },
                    author = result.author,
                    filePath = "",
                    sizeText = sizeText,
                    quality = qualityText,
                    finishedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date()),
                    uri = savedUris.firstOrNull().orEmpty(),
                    uris = savedUris.toList(),
                    fileNames = savedNames.toList(),
                    sizes = savedSizes.toList(),
                )
            )
            notifyFinal("下载完成", "${displayTitle.take(30)} · $sizeText · $savedCount 个文件")
            LogStore.log("下载", "任务完成: ${displayTitle.take(30)} ($sizeText)")
        } catch (error: Exception) {
            LogStore.log("下载", "任务失败: ${error.message}")
            AppState.download.value = DownloadState(
                platform = result.platform,
                title = result.title,
                author = result.author,
                stage = "失败",
                failed = true,
                message = error.message ?: "下载失败",
            )
            notifyFinal("下载失败", error.message ?: "未知错误")
        }
    }

    private var qualityText: String = ""

    private fun updateProgress(stage: String, percent: Int, downloaded: Long, total: Long, result: ExtractResult) {
        AppState.download.value = DownloadState(
            platform = result.platform,
            title = result.title,
            author = result.author,
            stage = stage,
            percent = percent.takeIf { it in 1..100 },
            bytesDownloaded = downloaded,
            bytesTotal = total,
        )
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(stage, percent, result.title))
    }

    private fun notifyFinal(stage: String, text: String) {
        val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("秒存 · $stage")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID + 1, notification)
    }

    // ------------------------------------------------------------------ 下载

    private suspend fun downloadOne(
        urls: List<String>,
        fileName: String,
        result: ExtractResult,
        savedUris: MutableList<String>,
        savedNames: MutableList<String>,
    ): Long = withContext(Dispatchers.IO) {
        // 普通链接：先探测真实类型/文件名（支持任意格式）
        var finalName = fileName
        var kind = result.kind
        var mime = when (result.kind) {
            ExtractResult.KIND_VIDEO -> "video/mp4"
            // 图片按真实扩展名给 MIME：给 image/jpeg 配 .webp 文件名时，
            // 系统相册会把名字改写成「xxx.webp.jpg」，部分应用就按 JPEG 打开失败
            ExtractResult.KIND_IMAGE -> when (finalName.substringAfterLast('.', "").lowercase()) {
                "webp" -> "image/webp"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "heic" -> "image/heic"
                "avif" -> "image/avif"
                else -> "image/jpeg"
            }
            else -> guessMime(fileName)
        }
        if (result.kind == ExtractResult.KIND_FILE) {
            probeRemote(urls.first(), result)?.let { info ->
                if (info.isHtml) error("这是网页链接，不是可下载的文件；如需嗅探页面资源请直接粘贴页面链接")
                if (info.fileName.isNotBlank()) finalName = info.fileName
                kind = info.kind
                mime = info.mime.ifBlank { guessMime(finalName) }
                LogStore.log("下载", "探测到: $finalName ($mime)")
            }
        }
        val cacheDir = File(cacheDir, "downloads").apply { mkdirs() }
        val cacheFile = File(cacheDir, finalName + ".part")
        val audioUrl = result.audioUrl
        if (audioUrl.isNullOrBlank()) {
            downloadToCache(urls, cacheFile, result)
        } else {
            // YouTube 高清流是分离的音视频，分别下载后用 MediaMuxer 无损合成
            LogStore.log("下载", "开始下载视频流与音频流（分离合并模式）")
            val videoPart = File(cacheDir, fileName + ".video.part")
            val audioPart = File(cacheDir, fileName + ".audio.part")
            downloadToCache(urls, videoPart, result)
            downloadToCache(listOf(audioUrl), audioPart, result)
            val merged = File(cacheDir, fileName + ".merged.mp4")
            remux(videoPart, audioPart, merged)
            cacheFile.delete()
            merged.renameTo(cacheFile)
            videoPart.delete()
            audioPart.delete()
            merged.delete()
            LogStore.log("下载", "音视频合并完成: ${cacheFile.length()} 字节")
        }

        val customTree = AppState.saveTree.value
        if (customTree.isNotBlank()) {
            val docUri = runCatching {
                DocumentsContract.createDocument(contentResolver, Uri.parse(customTree), mime, finalName)
            }.getOrNull() ?: error("无法写入所选文件夹（请重新选择目录授权）")
            var written = 0L
            contentResolver.openOutputStream(docUri)?.use { output ->
                cacheFile.inputStream().use { input ->
                    val buffer = ByteArray(512 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                    }
                    output.flush()
                }
            } ?: error("无法写入所选文件夹")
            savedUris.add(docUri.toString())
            qualityText = readQuality(docUri, result)
            cacheFile.delete()
            savedNames.add(finalName)
            LogStore.log("下载", "已保存到自选目录: $finalName ($written 字节)")
            return@withContext written
        }
        val collection = when (kind) {
            ExtractResult.KIND_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ExtractResult.KIND_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ExtractResult.KIND_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val directory = when (kind) {
            ExtractResult.KIND_VIDEO -> Environment.DIRECTORY_MOVIES
            ExtractResult.KIND_IMAGE -> Environment.DIRECTORY_PICTURES
            ExtractResult.KIND_AUDIO -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(collection, values) ?: error("无法写入目标目录")
        var written = 0L
        contentResolver.openOutputStream(uri)?.use { output ->
            cacheFile.inputStream().use { input ->
                val buffer = ByteArray(512 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
                output.flush()
            }
        } ?: error("无法写入文件")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        qualityText = if (kind == ExtractResult.KIND_VIDEO) readQuality(uri, result) else ""
        savedUris.add(uri.toString())
        savedNames.add(finalName)
        cacheFile.delete()
        LogStore.log("下载", "已保存: $finalName ($written 字节)")
        written
    }


    /** yt-dlp 下载结果：字节数 + 保存后的 URI/文件名 + 从文件名还原出来的标题 */
    private data class YtDlpResult(
        val bytes: Long,
        val uris: List<String>,
        val names: List<String>,
        val sizes: List<Long>,
        val title: String,
    )

    /** 用内置 yt-dlp（Python + QuickJS + ffmpeg）下载 X / YouTube / B 站 / 微博 / TikTok 等 */
    private suspend fun downloadWithYtDlp(
        result: ExtractResult,
    ): YtDlpResult = withContext(Dispatchers.IO) {
        val url = result.mediaUrls.first()
        val staging = File(cacheDir, "ytdlp-${System.currentTimeMillis()}").apply { mkdirs() }
        val request = com.yausername.youtubedl_android.YoutubeDLRequest(url)
        request.addOption("-o", "${staging.absolutePath}/%(title).120B [%(id)s].%(ext)s")
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        // 默认取「最高画质」：B 站 4K/HDR 只有 HEVC / AV1，写死 H.264 会被卡在 1080P；
        // 设置里选过「≤1080P / ≤720P」则按上限收窄（合并仍统一成 mp4）
        val maxHeight = AppState.maxHeight.value
        request.addOption(
            "-f",
            if (maxHeight <= 0) {
                "bv*+ba/b"
            } else {
                "bv*[height<=$maxHeight]+ba/b[height<=$maxHeight]/bv*[height<=$maxHeight]+ba/b"
            },
        )
        request.addOption("--merge-output-format", "mp4")
        request.addOption("--js-runtimes", "quickjs")
        // X / YouTube 的 Cookie 来自应用内登录（只存本机，不上传）
        // B 站：补齐 CURRENT_FNVAL=4048，否则登录了也拿不到 4K
        if (result.platform == Platform.BILIBILI) {
            CookieStore.ensureBilibiliQuality(this@DownloadService)
        }
        cookieFileFor(result.platform)?.takeIf { it.exists() && it.length() > 0 }?.let {
            request.addOption("--cookies", it.absolutePath)
            LogStore.log("yt-dlp", "使用登录 Cookie: ${it.name}")
        } ?: run {
            if (result.platform == Platform.X || result.platform == Platform.YOUTUBE) {
                LogStore.log("Cookie", "本机还没有 ${result.platform.label} 的登录 Cookie，可能下载失败")
            } else if (result.platform == Platform.BILIBILI || result.platform == Platform.WEIBO ||
                result.platform == Platform.TIKTOK || result.platform == Platform.INSTAGRAM ||
                result.platform == Platform.NETEASE || result.platform == Platform.QQMUSIC
            ) {
                LogStore.log("Cookie", "未配置 ${result.platform.label} 登录 Cookie：清晰度可能受限，需要登录的内容可能失败")
            }
        }
        LogStore.log("yt-dlp", "开始解析: ${url.take(80)}")
        var lastPercent = 0
        val tail = StringBuilder()
        try {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request) { progress, _, line ->
                val percent = progress.toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    updateProgress("下载中（yt-dlp）", percent, 0, 0, result)
                }
                if (line.isNotBlank()) {
                    LogStore.log("yt-dlp", line.take(120))
                    if (tail.length < 4000) tail.appendLine(line)
                }
            }
        } catch (error: Exception) {
            markCookieExpiredIfNeeded(result.platform, tail.toString())
            throw error
        }
        val produced = staging.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
        if (produced.isEmpty()) {
            markCookieExpiredIfNeeded(result.platform, tail.toString())
            error("yt-dlp 未产出文件（可能需要登录或该视频受限）")
        }
        var total = 0L
        val uris = mutableListOf<String>()
        val names = mutableListOf<String>()
        val sizes = mutableListOf<Long>()
        produced.forEach { file ->
            val kind = when (file.extension.lowercase()) {
                in setOf("jpg", "jpeg", "png", "webp") -> ExtractResult.KIND_IMAGE
                // 音乐平台下出来的是纯音频：存到系统「音乐」目录（Music/视频下载）
                in setOf("mp3", "m4a", "aac", "flac", "wav", "opus", "ogg") -> ExtractResult.KIND_AUDIO
                else -> ExtractResult.KIND_VIDEO
            }
            val uri = saveLocalFile(file, file.name, kind)
            uris.add(uri.toString())
            names.add(file.name)
            sizes.add(file.length())
            total += file.length()
            file.delete()
        }
        LogStore.log("yt-dlp", "完成 ${produced.size} 个文件，共 ${total} 字节")
        // 通用解析等场景没有标题，用 yt-dlp 产出的文件名还原一个（形如「标题 [id].mp4」）
        val restoredTitle = names.firstOrNull()
            ?.substringBeforeLast('.')
            ?.replace(Regex("""\s*\[[^\]]{3,}\]$"""), "")
            ?.trim()
            .orEmpty()
        // 下载成功说明 Cookie 是可用的，清掉之前可能误标的「登录已失效」
        when (result.platform) {
            Platform.X -> CookieStore.clearMark(this@DownloadService, "x")
            Platform.YOUTUBE -> CookieStore.clearMark(this@DownloadService, "youtube")
            Platform.WECHAT_CHANNELS -> CookieStore.clearMark(this@DownloadService, "wechat_channels")
            Platform.BILIBILI -> CookieStore.clearMark(this@DownloadService, "bilibili")
            Platform.WEIBO -> CookieStore.clearMark(this@DownloadService, "weibo")
            Platform.TIKTOK -> CookieStore.clearMark(this@DownloadService, "tiktok")
            Platform.INSTAGRAM -> CookieStore.clearMark(this@DownloadService, "instagram")
            else -> Unit
        }
        YtDlpResult(total, uris, names, sizes, restoredTitle)
    }

    private fun cookieFileFor(platform: Platform): File? = when (platform) {
        Platform.X -> File(filesDir, "cookies-x.txt")
        Platform.YOUTUBE -> File(filesDir, "cookies-youtube.txt")
        Platform.BILIBILI -> File(filesDir, "cookies-bilibili.txt")
        Platform.WEIBO -> File(filesDir, "cookies-weibo.txt")
        Platform.TIKTOK -> File(filesDir, "cookies-tiktok.txt")
        Platform.INSTAGRAM -> File(filesDir, "cookies-instagram.txt")
        // 第三梯队（国内需代理）：有登录态时才带上，没有就照旧匿名下载
        Platform.XIGUA -> File(filesDir, "cookies-ixigua.txt")
        Platform.REDDIT -> File(filesDir, "cookies-reddit.txt")
        Platform.VIMEO -> File(filesDir, "cookies-vimeo.txt")
        Platform.TWITCH -> File(filesDir, "cookies-twitch.txt")
        // 音乐平台：QQ 音乐多数歌曲要登录；网易云未登录常常只给试听片段
        Platform.NETEASE -> File(filesDir, "cookies-netease.txt")
        Platform.QQMUSIC -> File(filesDir, "cookies-qqmusic.txt")
        else -> null
    }

    /**
     * 引擎报出的错误里如果带登录相关字样，就把该平台标记为「登录已失效」，
     * 这样设置页会重新显示登录按钮（下次重新登录会自动清掉标记）。
     */
    private fun markCookieExpiredIfNeeded(platform: Platform, output: String) {
        val key = when (platform) {
            Platform.X -> "x"
            Platform.YOUTUBE -> "youtube"
            Platform.BILIBILI -> "bilibili"
            Platform.WEIBO -> "weibo"
            Platform.TIKTOK -> "tiktok"
            Platform.INSTAGRAM -> "instagram"
            else -> return
        }
        if (!cookieFileFor(platform)!!.exists()) return
        val text = output.lowercase()
        val hints = listOf("sign in", "signin", "log in", "login", "cookies", "authentication", "not authorized")
        if (hints.any { it in text }) {
            CookieStore.markExpired(this, key)
            AppState.refreshLoginStates()
            LogStore.log("Cookie", "${platform.label} 登录态可能已失效，设置页会提示重新登录")
        }
    }

    /** 把本地文件保存到相册 / 自选目录 */
    private fun saveLocalFile(localFile: File, displayName: String, kind: String): Uri {
        val mime = guessMime(displayName)
        val customTree = AppState.saveTree.value
        if (customTree.isNotBlank()) {
            val docUri = runCatching {
                DocumentsContract.createDocument(contentResolver, Uri.parse(customTree), mime, displayName)
            }.getOrNull() ?: error("无法写入所选文件夹（请重新选择目录授权）")
            contentResolver.openOutputStream(docUri)?.use { output ->
                localFile.inputStream().use { it.copyTo(output) }
            } ?: error("无法写入所选文件夹")
            return docUri
        }
        val collection = when (kind) {
            ExtractResult.KIND_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ExtractResult.KIND_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ExtractResult.KIND_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val directory = when (kind) {
            ExtractResult.KIND_VIDEO -> Environment.DIRECTORY_MOVIES
            ExtractResult.KIND_IMAGE -> Environment.DIRECTORY_PICTURES
            ExtractResult.KIND_AUDIO -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(collection, values) ?: error("无法写入目标目录")
        contentResolver.openOutputStream(uri)?.use { output ->
            localFile.inputStream().use { it.copyTo(output) }
        } ?: error("无法写入文件")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    private data class RemoteInfo(
        val fileName: String,
        val mime: String,
        val kind: String,
        val isHtml: Boolean,
    )

    /** 普通链接：探测内容类型与文件名，决定保存位置与打开方式 */
    private fun probeRemote(url: String, result: ExtractResult): RemoteInfo? = runCatching {
        val request = Request.Builder()
            .url(url)
            .head()
            .platformHeaders(result.platform)
            .build()
        client.newCall(request).execute().use { response ->
            val mime = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            val disposition = response.header("Content-Disposition").orEmpty()
            val nameFromHeader = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            val nameFromUrl = java.net.URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "UTF-8")
            val fileName = (nameFromHeader ?: nameFromUrl).ifBlank { "download-${System.currentTimeMillis()}" }
            val isHtml = mime.contains("text/html")
            val kind = when {
                mime.startsWith("video/") -> ExtractResult.KIND_VIDEO
                mime.startsWith("image/") -> ExtractResult.KIND_IMAGE
                else -> ExtractResult.KIND_FILE
            }
            LogStore.log("下载", "HEAD: HTTP ${response.code} type=$mime name=$fileName")
            RemoteInfo(fileName = fileName, mime = mime.ifBlank { guessMime(fileName) }, kind = kind, isHtml = isHtml)
        }
    }.getOrNull()

    /** 先尝试多连接分段下载，失败则回退单连接（带断点续传） */
    private suspend fun downloadToCache(urls: List<String>, cacheFile: File, result: ExtractResult) {
        if (urls.isEmpty()) error("没有可用的下载地址")
        val probe = probe(urls.first(), result)
        LogStore.log("下载", "探测: 大小=${probe?.first ?: -1} 支持Range=${probe?.second ?: false} url=${urls.first().take(80)}")
        if (probe != null && probe.first > SEGMENT_THRESHOLD && probe.second) {
            runCatching {
                segmentedDownload(urls, cacheFile, probe.first, result)
                return
            }.onFailure { /* 回退 */ }
        }
        singleConnectionDownload(urls, cacheFile, result, probe?.first ?: -1L)
    }

    /** 用 MediaMuxer 把分离的视频流与音频流无损合并为 mp4 */
    private fun remux(video: File, audio: File, output: File) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoExtractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        try {
            var videoTrack = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoExtractor.selectTrack(i)
                    videoTrack = muxer.addTrack(format)
                    break
                }
            }
            var audioTrack = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioExtractor.selectTrack(i)
                    audioTrack = muxer.addTrack(format)
                    break
                }
            }
            if (videoTrack < 0 || audioTrack < 0) error("音视频轨道解析失败")
            muxer.start()
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((extractor, track) in listOf(videoExtractor to videoTrack, audioExtractor to audioTrack)) {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(track, buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop()
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching { muxer.release() }
        }
    }

    /** HEAD 探测：返回 (文件大小, 是否支持 Range) */
    private fun probe(url: String, result: ExtractResult): Pair<Long, Boolean>? = runCatching {
        val request = Request.Builder()
            .url(url)
            .head()
            .platformHeaders(result.platform)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val ranges = response.header("Accept-Ranges")?.contains("bytes", true) == true
            if (length > 0) length to ranges else null
        }
    }.getOrNull()

    /** 多连接分段下载：每段独立重试，最后拼接 */
    private suspend fun segmentedDownload(
        urls: List<String>,
        cacheFile: File,
        total: Long,
        result: ExtractResult,
    ) = withContext(Dispatchers.IO) {
        val segments = minOf(MAX_SEGMENTS, maxOf(2, (total / SEGMENT_THRESHOLD).toInt()))
        LogStore.log("下载", "启用 $segments 线程分段下载，总大小 $total")
        val chunk = total / segments
        val progress = AtomicLong(0)
        val lastTick = AtomicLong(0)
        val parts = mutableListOf<File>()

        val jobs = (0 until segments).map { index ->
            val start = index * chunk
            val end = if (index == segments - 1) total - 1 else start + chunk - 1
            val partFile = File(cacheFile.parentFile, cacheFile.name + ".seg$index")
            parts.add(partFile)
            async(Dispatchers.IO) {
                downloadSegment(urls, partFile, start, end, result) { delta ->
                    val done = progress.addAndGet(delta)
                    val now = System.currentTimeMillis()
                    if (now - lastTick.get() > 300) {
                        lastTick.set(now)
                        val percent = (done * 100 / total).toInt()
                        updateProgress("下载中（$segments 线程）", percent, done, total, result)
                    }
                }
            }
        }
        jobs.awaitAll()

        cacheFile.delete()
        cacheFile.outputStream().use { output ->
            parts.forEach { part ->
                part.inputStream().use { it.copyTo(output) }
                part.delete()
            }
        }
        if (cacheFile.length() < total) error("分段下载不完整")
    }

    private fun downloadSegment(
        urls: List<String>,
        partFile: File,
        start: Long,
        end: Long,
        result: ExtractResult,
        onBytes: (Long) -> Unit,
    ) {
        var offset = start + (if (partFile.exists()) partFile.length() else 0L)
        var attempt = 0
        while (offset <= end && attempt < 6) {
            val url = urls[attempt % urls.size]
            attempt++
            try {
                val request = Request.Builder()
                    .url(url)
                    .platformHeaders(result.platform)
                    .header("Range", "bytes=$offset-$end")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("响应为空")
                    RandomAccessFile(partFile, "rw").use { raf ->
                        raf.seek(partFile.length())
                        body.byteStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (offset <= end) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                raf.write(buffer, 0, read)
                                offset += read
                                onBytes(read.toLong())
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                offset = start + (if (partFile.exists()) partFile.length() else 0L)
                if (attempt >= 6) throw error
            }
        }
        if (offset <= end) error("分段下载失败")
    }

    /** 单连接 + Range 断点续传 + 多地址重试 */
    private fun singleConnectionDownload(
        urls: List<String>,
        cacheFile: File,
        result: ExtractResult,
        knownSize: Long = -1L,
    ) {
        var downloaded = if (cacheFile.exists()) cacheFile.length() else 0L
        // .part 已经不小于探测到的大小：说明上次其实下完了（例如保存相册那一步失败），
        // 直接用这份缓存去保存；再发 Range 会被判无效 —— 抖音图片这类签名地址对续传一律回 416
        if (knownSize > 0 && downloaded >= knownSize) {
            LogStore.log("下载", "临时文件已下满（$downloaded/$knownSize），直接使用")
            return
        }
        var total = 0L
        var lastError: Exception? = null
        val maxAttempts = maxOf(4, urls.size * 3)

        for (attempt in 0 until maxAttempts) {
            val url = urls[attempt % urls.size]
            try {
                val builder = Request.Builder()
                    .url(url)
                    .platformHeaders(result.platform)
                if (downloaded > 0) builder.header("Range", "bytes=$downloaded-")
                client.newCall(builder.build()).execute().use { response ->
                    // 416 = 这个续传位置服务器不认（签名地址常见）：删掉断点从头下，别把资源判死
                    if (response.code == 416 && downloaded > 0) {
                        cacheFile.delete()
                        downloaded = 0
                        error("HTTP 416（续传位置无效，已从头重下）")
                    }
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    if (downloaded > 0 && response.code == 200) {
                        cacheFile.delete()
                        downloaded = 0
                    }
                    val body = response.body ?: error("响应为空")
                    val contentLength = body.contentLength()
                    LogStore.log(
                        "下载",
                        "HTTP ${response.code} 长度=${contentLength} 地址=${url.take(110)}",
                    )
                    total = if (downloaded > 0 && response.code == 206 && contentLength > 0) {
                        downloaded + contentLength
                    } else {
                        contentLength.takeIf { it > 0 } ?: 0L
                    }
                    RandomAccessFile(cacheFile, "rw").use { raf ->
                        raf.seek(downloaded)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var lastTick = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                raf.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastTick > 300) {
                                    lastTick = now
                                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                                    updateProgress("下载中", percent, downloaded, total, result)
                                }
                            }
                        }
                    }
                }
                if (total > 0 && downloaded >= total) return
                if (total == 0L && downloaded > 0) return
                lastError = IllegalStateException(
                    "连接提前结束（$downloaded/$total），地址：${url.take(90)}",
                )
            } catch (error: Exception) {
                lastError = error
                LogStore.log("下载", "单连接重试($attempt): ${error.message}")
                AppState.download.value = AppState.download.value?.copy(stage = "网络波动，重试中")
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    private fun readQuality(uri: Uri, result: ExtractResult): String {
        if (result.kind != ExtractResult.KIND_VIDEO) return ""
        return runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            val duration = if (durationMs > 0) {
                String.format("%d:%02d", durationMs / 60000, (durationMs / 1000) % 60)
            } else ""
            listOf(if (width > 0 && height > 0) "${width}×${height}" else "", duration)
                .filter { it.isNotBlank() }.joinToString(" · ")
        }.getOrDefault("")
    }

    private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp4", "mov", "mkv", "webm" -> "video/*"
        "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
        "mp3", "m4a", "aac", "flac", "wav" -> "audio/*"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun refererFor(platform: Platform) = when (platform) {
        Platform.DOUYIN -> "https://www.douyin.com/"
        Platform.KUAISHOU -> "https://v.m.chenzhongtech.com/"
        Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/"
        Platform.X -> "https://x.com/"
        Platform.YOUTUBE -> "https://www.youtube.com/"
        Platform.WECHAT_CHANNELS -> "https://channels.weixin.qq.com/"
        Platform.BILIBILI -> "https://www.bilibili.com/"
        Platform.WEIBO -> "https://m.weibo.cn/"
        Platform.TIKTOK -> "https://www.tiktok.com/"
        Platform.INSTAGRAM -> "https://www.instagram.com/"
        Platform.XIGUA -> "https://www.ixigua.com/"
        Platform.REDDIT -> "https://www.reddit.com/"
        Platform.VIMEO -> "https://vimeo.com/"
        Platform.TWITCH -> "https://www.twitch.tv/"
        Platform.WEISHI -> "https://isee.weishi.qq.com/"
        Platform.HAOKAN -> "https://haokan.baidu.com/"
        Platform.NETEASE -> "https://music.163.com/"
        Platform.QQMUSIC -> "https://y.qq.com/"
        Platform.GENERIC, Platform.WEB, Platform.FILE, Platform.UNKNOWN -> ""
    }

    /**
     * 平台相关的请求头：UA + Referer。
     * 抖音的 CDN 直链（douyinvod）会校验会话：不带 Cookie 直接 403——需要带上内置 WebView
     * 里 www.douyin.com 的会话（匿名 ttwid/odin_tt 就够），与解析时的会话保持一致。
     */
    private fun Request.Builder.platformHeaders(platform: Platform): Request.Builder {
        header("User-Agent", UA)
        header("Referer", refererFor(platform))
        if (platform == Platform.DOUYIN) {
            val cookie = runCatching {
                android.webkit.CookieManager.getInstance().getCookie("https://www.douyin.com/")
            }.getOrNull().orEmpty()
            if (cookie.isNotEmpty()) header("Cookie", cookie)
        }
        return this
    }

    private fun buildName(author: String, title: String): String {
        val raw = listOf(author, title).filter { it.isNotBlank() }.joinToString("-")
            .ifBlank { "video-${System.currentTimeMillis()}" }
        val cleaned = raw.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
        // 按 UTF-8 字节数截断：中文一个字 3 字节，只按字数截（80 字）能到 240+ 字节，
        // 再加上系统的 .pending-xxx- 前缀与「-1.webp」这类后缀，就超过文件系统 255 字节上限，
        // 相册会直接报 Invalid file path（实测长标题的抖音图文就是死在这里）
        return truncateBytes(cleaned, 150)
    }

    /** 按 UTF-8 字节数截断（不会切碎多字节字符） */
    private fun truncateBytes(raw: String, maxBytes: Int): String {
        if (raw.toByteArray(Charsets.UTF_8).size <= maxBytes) return raw
        val builder = StringBuilder()
        var used = 0
        for (ch in raw) {
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            builder.append(ch)
            used += size
        }
        return builder.toString().trimEnd()
    }

    private val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
