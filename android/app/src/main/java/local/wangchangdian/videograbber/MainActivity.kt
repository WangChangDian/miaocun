package local.wangchangdian.videograbber

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val inputText = MutableStateFlow("")
    /** 多链接批量：驱动状态 */
    private var batchId: String = ""
    private var batchIndexInFlight: Int = -1

    private val extractionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val batchIndex = batchIndexInFlight
            batchIndexInFlight = -1
            busy.value = false
            fun fail(text: String, cancelled: Boolean = false) {
                if (batchIndex < 0) {
                    message.value = text
                    return
                }
                val currentStatus = AppState.batch.value?.items?.getOrNull(batchIndex)?.status
                if (cancelled && currentStatus != null && currentStatus.terminal) {
                    // 已经有失败原因（例如 yt-dlp 先失败、回退嗅探又被返回键关掉）：保留原因，直接继续
                    processNextBatchLink()
                    return
                }
                if (cancelled) {
                    AppState.updateBatchItem(batchIndex, BatchItemStatus.CANCELLED, message = text)
                    processNextBatchLink()
                } else {
                    failBatchItem(batchIndex, text)
                }
            }
            if (result.resultCode != Activity.RESULT_OK) {
                fail("解析已取消", cancelled = true)
                return@registerForActivityResult
            }
            val data = result.data
            val error = data?.getStringExtra(ExtractionActivity.EXTRA_ERROR)
            if (!error.isNullOrBlank()) {
                fail(error)
                return@registerForActivityResult
            }
            val raw = data?.getStringExtra(ExtractionActivity.EXTRA_RESULT)
            if (raw.isNullOrBlank()) {
                fail("解析失败，请重试")
                return@registerForActivityResult
            }
            val extracted = ExtractResult.fromJson(raw)
            if (extracted.mediaUrls.isEmpty()) {
                fail("未找到可下载的媒体地址")
                return@registerForActivityResult
            }
            enqueueForBatch(extracted, batchIndex)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleShareIntent(intent, autoStart = true)
        checkCloudOnStart()

        setContent {
            val themeState = AppState.theme.collectAsState()
            VideoGrabberTheme(themeState.value) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        themeKey = themeState.value,
                        input = inputText.collectAsState().value,
                        onInputChange = { inputText.value = it },
                        busy = busy.collectAsState().value,
                        message = message.collectAsState().value,
                        downloadState = AppState.download.collectAsState().value,
                        batch = AppState.batch.collectAsState().value,
                        queue = AppState.queue.collectAsState().value,
                        history = AppState.history.collectAsState().value,
                        liveLog = AppState.liveLog.collectAsState().value,
                        onToggleTheme = {
                            AppState.setTheme(if (themeState.value == "green") "orange" else "green")
                        },
                        onPaste = {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (text.isNotBlank()) inputText.value = text
                        },
                        onDownload = { startDownloadFlow(inputText.value) },
                        onBatch = { startBatch(inputText.value) },
                        batchable = isBatchUrl(inputText.value),
                        onDownloadFinished = { inputText.value = "" },
                        onDismissMessage = { message.value = null },
                        onOpenUri = { uri -> openUri(uri) },
                        onShare = { uris -> shareUris(uris) },
                        onStopParsing = { stopParsing() },
                        onOpenHistory = { entry -> openHistoryEntry(entry) },
                        onClearHistory = { confirmClearHistory() },
                        onDeleteHistory = { index -> AppState.deleteHistoryItem(index) },
                        onDeleteFile = { index, uri -> AppState.deleteHistoryFile(index, uri) },
                        onDeleteCurrent = { AppState.deleteCurrentDownload() },
                        onDeleteCurrentFile = { uri -> AppState.deleteCurrentDownloadFile(uri) },
                        onCancelBatch = { cancelBatch() },
                        onCloseBatch = { AppState.clearBatch() },
                        onCopyLink = { url -> copyLink(url) },
                        onDeleteAllBatch = { deleteBatchAll() },
                        onDeleteBatchFile = { uri ->
                            val index = AppState.historyIndexOfFile(uri)
                            if (index >= 0) AppState.deleteHistoryFile(index, uri)
                        },
                    )
                    // 有新版时弹出更新卡片（应用内下载 + 安装）
                    UpdateDialogHost { text -> message.value = text }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent, autoStart = true)
    }

    private fun handleShareIntent(intent: Intent?, autoStart: Boolean) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isBlank()) return
        inputText.value = text
        if (autoStart) startDownloadFlow(text)
    }

    private fun startExtraction(text: String, resolveShortLink: Boolean = true, batchIndex: Int = -1) {
        val batchUrl = UrlUtils.extractUrl(text)
        if (batchIndex < 0 && batchUrl != null && isBatchUrl(batchUrl)) {
            message.value = "这是主页链接：要批量下载请点旁边的「批量」按钮"
        }
        val url = UrlUtils.extractUrl(text)
        if (url.isNullOrBlank()) {
            if (batchIndex >= 0) failBatchItem(batchIndex, "没有识别到链接")
            else message.value = "没有识别到链接，请检查粘贴内容"
            return
        }
        val platform = UrlUtils.detectPlatform(url)
        if (platform == Platform.UNKNOWN) {
            // 明确是文件直链 → 直接下载；其余先让内置 yt-dlp 试一次（覆盖它支持的上千个站点），
            // 认不出来再自动回退到「网页资源嗅探」，两条路都不通才报错
            if (UrlUtils.isDirectFile(url)) {
                enqueueForBatch(
                    ExtractResult(
                        platform = Platform.FILE,
                        title = url.substringAfterLast('/').substringBefore('?'),
                        author = "",
                        mediaUrls = listOf(url),
                        kind = ExtractResult.KIND_FILE,
                    ),
                    batchIndex,
                )
            } else {
                startGenericWithFallback(url, text, batchIndex)
            }
            return
        }
        // 抖音短链（v.douyin.com/xxxx）里是短码不是作品 ID：先跟随跳转拿到真实 ID，
        // 否则 WebView 会落到移动分享页，拿不到 /aweme/v1/web/aweme/detail/ 响应
        if (resolveShortLink && platform == Platform.DOUYIN && UrlUtils.douyinId(url) == null) {
            busy.value = true
            message.value = "正在解析抖音短链…"
            AppState.clearLiveLog()
            lifecycleScope.launch {
                val awemeId = withContext(Dispatchers.IO) {
                    UrlUtils.resolveDouyinAwemeId(url, CookieStore.cookieHeaderFor(this@MainActivity, "douyin"))
                }
                busy.value = false
                message.value = null
                if (awemeId != null) {
                    LogStore.log("解析", "抖音短链解析到作品 ID: $awemeId")
                    startExtraction("https://www.douyin.com/video/$awemeId", resolveShortLink = false, batchIndex = batchIndex)
                } else {
                    // 解析不出 ID 时不要卡住用户：直接按原链接打开（页面里还有 DOM/接口兜底）
                    LogStore.log("解析", "抖音短链没解析出作品 ID，回退直接打开")
                    startExtraction(url, resolveShortLink = false, batchIndex = batchIndex)
                }
            }
            return
        }
        if (platform == Platform.X || platform == Platform.YOUTUBE ||
            platform == Platform.BILIBILI || platform == Platform.WEIBO || platform == Platform.TIKTOK ||
            platform == Platform.INSTAGRAM || platform == Platform.XIGUA || platform == Platform.REDDIT ||
            platform == Platform.VIMEO || platform == Platform.TWITCH ||
            platform == Platform.NETEASE || platform == Platform.QQMUSIC
        ) {
            // X / YouTube / B 站 / 微博 / TikTok / Instagram / 西瓜 / Reddit / Vimeo / Twitch：
            // 交给内置 yt-dlp（Python + QuickJS + ffmpeg）
            if (!AppState.ytDlpReady.value) {
                // 首次启动需要解压 Python/yt-dlp，这里等待完成后再自动继续
                busy.value = true
                message.value = "内置 yt-dlp 正在初始化（首次约 20-30 秒）…"
                lifecycleScope.launch {
                    val ready = withTimeoutOrNull(90_000) {
                        AppState.ytDlpReady.first { it }
                    } ?: false
                    busy.value = false
                    if (ready) {
                        message.value = null
                        startExtraction(text, batchIndex = batchIndex)
                    } else {
                        if (batchIndex >= 0) failBatchItem(batchIndex, "yt-dlp 初始化失败")
                        else message.value = "yt-dlp 初始化失败，请在设置页查看运行日志"
                    }
                }
                return
            }
            enqueueForBatch(
                ExtractResult(
                    platform = platform,
                    title = url,
                    author = "",
                    mediaUrls = listOf(url),
                    kind = ExtractResult.KIND_YTDLP,
                ),
                batchIndex,
            )
            return
        }
        if (platform == Platform.WECHAT_CHANNELS) {
            // 视频号：分享页拿不到视频地址，先在后台向腾讯元宝换令牌解析出真实地址，再交给下载服务
            AppState.clearLiveLog()
            busy.value = true
            LogStore.log("视频号", "开始解析分享链接: ${url.take(80)}")
            lifecycleScope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    runCatching { WxChannelsClient.resolve(this@MainActivity, url) }
                }
                busy.value = false
                val info = resolved.getOrNull()
                if (info != null) {
                    LogStore.log("视频号", "解析成功：${info.author} / ${info.title.take(40)}")
                    enqueueForBatch(
                        ExtractResult(
                            platform = platform,
                            title = info.title.ifBlank { "微信视频号" },
                            author = info.author,
                            mediaUrls = listOf(info.videoUrl),
                            kind = ExtractResult.KIND_VIDEO,
                        ),
                        batchIndex,
                    )
                } else {
                    val error = resolved.exceptionOrNull()
                    val text = error?.message ?: "视频号解析失败"
                    // 服务端明确拒绝时，把登录态标成「已失效」，设置页会红字提示重新登录
                    if (error is WxChannelsClient.WxChannelsException && error.needsRelogin) {
                        CookieStore.markExpired(this@MainActivity, "wechat_channels")
                        AppState.refreshLoginStates()
                    }
                    if (batchIndex >= 0) failBatchItem(batchIndex, text) else message.value = text
                    LogStore.log("视频号", "解析失败：$text")
                }
            }
            return
        }
        if (platform == Platform.INSTAGRAM) {
            // Instagram：图片 / 视频 / 轮播都走它自己的 web 接口（yt-dlp 处理不了图片帖）
            AppState.clearLiveLog()
            busy.value = true
            LogStore.log("Instagram", "开始解析: ${url.take(80)}")
            lifecycleScope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    runCatching { InstagramClient.resolve(this@MainActivity, url) }
                }
                busy.value = false
                val info = resolved.getOrNull()
                if (info != null) {
                    val images = info.items.filter { !it.isVideo }
                    val videos = info.items.filter { it.isVideo }
                    val result = when {
                        images.isEmpty() -> ExtractResult(
                            platform = platform,
                            title = info.title.ifBlank { "Instagram" },
                            author = info.author,
                            mediaUrls = videos.map { it.url },
                            kind = ExtractResult.KIND_VIDEO,
                        )
                        videos.isEmpty() -> ExtractResult(
                            platform = platform,
                            title = info.title.ifBlank { "Instagram" },
                            author = info.author,
                            mediaUrls = images.map { it.url },
                            kind = ExtractResult.KIND_IMAGE,
                        )
                        else -> {
                            // 混合帖：先把图片存下来（视频流单独一条记录，避免和图片混在一个任务里）
                            LogStore.log("Instagram", "混合帖：先下载 ${images.size} 张图片，视频 ${videos.size} 个稍后再传链接下载")
                            message.value = "这是图文+视频混合帖，先下载 ${images.size} 张图片"
                            ExtractResult(
                                platform = platform,
                                title = info.title.ifBlank { "Instagram" },
                                author = info.author,
                                mediaUrls = images.map { it.url },
                                kind = ExtractResult.KIND_IMAGE,
                            )
                        }
                    }
                    enqueueForBatch(result, batchIndex)
                } else {
                    val error = resolved.exceptionOrNull()
                    val text = error?.message ?: "Instagram 解析失败"
                    if (batchIndex >= 0) failBatchItem(batchIndex, text) else message.value = text
                    LogStore.log("Instagram", "解析失败：${error?.message}")
                }
            }
            return
        }
        AppState.clearLiveLog()
        busy.value = true
        LogStore.log("解析", "开始解析 ${platform.label}: ${url.take(80)}")
        val intent = Intent(this, ExtractionActivity::class.java)
            .putExtra(ExtractionActivity.EXTRA_URL, url)
            .putExtra(ExtractionActivity.EXTRA_HINT, text)
            .putExtra(ExtractionActivity.EXTRA_PLATFORM, platform.key)
        batchIndexInFlight = batchIndex
        extractionLauncher.launch(intent)
    }

    /** 主页 / 收藏夹：打开批量窗口，抓取作品列表后逐条下载 */
    private fun startBatch(text: String) {
        val url = UrlUtils.extractUrl(text) ?: text.trim()
        if (!isBatchUrl(url)) {
            message.value = "批量下载只支持抖音 / 小红书的主页、收藏页链接"
            return
        }
        LogStore.log("批量", "打开批量下载窗口: ${url.take(90)}")
        startActivity(
            Intent(this, BatchActivity::class.java).putExtra(BatchActivity.EXTRA_URL, url),
        )
    }

    private fun enqueue(result: ExtractResult) {
        AppState.queue.value = AppState.queue.value + QueueItem(
            id = System.currentTimeMillis().toString(),
            title = result.title.ifBlank { result.mediaUrls.firstOrNull()?.takeLast(40) ?: "任务" },
            platform = result.platform,
        )
        AppState.download.value = DownloadState(
            platform = result.platform,
            title = result.title,
            author = result.author,
            stage = "准备下载",
        )
        DownloadService.start(this, result)
    }

    /**
     * 通用解析：先让 yt-dlp 试一次；它认不出这个链接时，自动回退到网页资源嗅探。
     *
     * 回退需要界面还在（嗅探要 WebView），所以这里由 Activity 盯着下载状态；
     * 如果用户已经退出界面，就只提示一句，不做回退。
     */
    private fun startGenericWithFallback(url: String, text: String, batchIndex: Int = -1) {
        AppState.clearLiveLog()
        LogStore.log("通用解析", "交给 yt-dlp 试一次: ${url.take(80)}")
        enqueueForBatch(
            ExtractResult(
                platform = Platform.GENERIC,
                title = url.substringAfterLast('/').substringBefore('?').take(60).ifBlank { url.take(60) },
                author = "",
                mediaUrls = listOf(url),
                kind = ExtractResult.KIND_YTDLP,
            ),
            batchIndex,
        )
        lifecycleScope.launch {
            val failed = if (batchIndex >= 0) {
                val snapshot = withTimeoutOrNull(180_000) {
                    AppState.batch.first { state ->
                        val status = state?.items?.getOrNull(batchIndex)?.status
                        status != null && status.terminal
                    }
                }
                snapshot?.items?.getOrNull(batchIndex)?.status == BatchItemStatus.FAILED
            } else {
                val state = withTimeoutOrNull(180_000) {
                    AppState.download.first { current ->
                        current?.platform == Platform.GENERIC && (current.failed || current.finished)
                    }
                }
                state?.failed == true
            }
            if (failed) {
                LogStore.log("通用解析", "yt-dlp 认不出这个链接，回退网页嗅探")
                if (batchIndex < 0) message.value = "yt-dlp 认不出这个链接，正在改用网页嗅探…"
                busy.value = true
                batchIndexInFlight = batchIndex
                extractionLauncher.launch(
                    Intent(this@MainActivity, ExtractionActivity::class.java)
                        .putExtra(ExtractionActivity.EXTRA_URL, url)
                        .putExtra(ExtractionActivity.EXTRA_HINT, text)
                        .putExtra(ExtractionActivity.EXTRA_PLATFORM, Platform.UNKNOWN.key)
                )
            }
        }
    }

    // ---------------------------------------------------------------- 多链接批量

    /** 输入框入口：1 条链接保持原逻辑，≥2 条进入多链接批量 */
    private fun startDownloadFlow(text: String) {
        val urls = UrlUtils.extractAllUrls(text, limit = 500)
        if (urls.size >= 2) {
            startLinkBatch(urls)
            return
        }
        startExtraction(text)
    }

    private fun startLinkBatch(urls: List<String>) {
        if (AppState.batch.value?.finished == false) {
            message.value = "批量任务进行中，请先完成或取消"
            return
        }
        if (busy.value) {
            message.value = "正在解析中，请稍候再试"
            return
        }
        val capped = urls.take(50)
        val items = capped.map { url ->
            if (isBatchUrl(url)) {
                BatchLinkItem(
                    url = url,
                    platform = UrlUtils.detectPlatform(url),
                    status = BatchItemStatus.SKIPPED,
                    message = "主页链接请单独使用「批量下载」",
                )
            } else {
                BatchLinkItem(url = url, platform = UrlUtils.detectPlatform(url))
            }
        }
        batchId = "batch-${System.currentTimeMillis()}"
        batchIndexInFlight = -1
        AppState.startBatch(batchId, items)
        LogStore.log("批量", "开始多链接批量：${items.size} 条（原始 ${urls.size} 条）")
        message.value = if (urls.size > capped.size) {
            "单次最多处理 50 条链接，已截取前 50 条"
        } else {
            null
        }
        processNextBatchLink()
    }

    private fun processNextBatchLink() {
        val state = AppState.batch.value ?: return
        if (state.finished) return
        val index = state.items.indexOfFirst { it.status == BatchItemStatus.WAITING }
        if (index < 0) {
            AppState.refreshBatchFinished()
            return
        }
        AppState.setBatchActive(index)
        AppState.updateBatchItem(index, BatchItemStatus.PARSING)
        startExtraction(state.items[index].url, batchIndex = index)
    }

    private fun enqueueForBatch(result: ExtractResult, batchIndex: Int) {
        if (batchIndex >= 0 && batchId.isNotBlank()) {
            enqueue(result.copy(batchId = batchId, batchIndex = batchIndex))
            AppState.updateBatchItem(batchIndex, BatchItemStatus.QUEUED, message = "等待下载")
            processNextBatchLink()
        } else {
            enqueue(result)
        }
    }

    private fun failBatchItem(batchIndex: Int, text: String) {
        if (batchIndex < 0) {
            message.value = text
            return
        }
        LogStore.log("批量", "第 ${batchIndex + 1} 条失败：$text")
        AppState.updateBatchItem(batchIndex, BatchItemStatus.FAILED, message = text)
        processNextBatchLink()
    }

    private fun cancelBatch() {
        val state = AppState.batch.value ?: return
        if (state.finished) {
            AppState.clearBatch()
            return
        }
        ExtractionActivity.stopCurrent()
        busy.value = false
        batchIndexInFlight = -1
        DownloadService.cancelAll()
        AppState.cancelRemainingBatchItems()
        LogStore.log("批量", "用户取消批量任务")
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("链接", url))
        message.value = "已复制链接"
    }

    /** 删除整张批量卡片：所有成功条目的文件与历史记录一并删除，然后收起卡片 */
    private fun deleteBatchAll() {
        val state = AppState.batch.value ?: return
        state.items.forEach { item ->
            val primary = item.files.firstOrNull() ?: return@forEach
            val index = AppState.historyIndexOfFile(primary)
            if (index >= 0) AppState.deleteHistoryItem(index)
        }
        AppState.clearBatch()
    }

    private fun openHistoryEntry(entry: HistoryEntry) {
        val target = entry.uri.ifBlank { entry.filePath }
        if (target.isBlank()) {
            message.value = "这条记录没有可打开的地址（历史遗留记录）"
            return
        }
        openUri(target)
    }

    private fun stopParsing() {
        val stopped = ExtractionActivity.stopCurrent()
        busy.value = false
        LogStore.log("解析", if (stopped) "用户手动停止解析" else "没有正在进行的解析")
        if (!stopped) message.value = "当前没有正在进行的解析任务"
    }

    private fun shareUris(uris: List<String>) {
        val valid = uris.filter { it.isNotBlank() }
        if (valid.isEmpty()) {
            message.value = "没有可分享的文件"
            return
        }
        val parsed = valid.map { Uri.parse(it) }
        val mime = UrlUtils.mimeFor(valid.first())
        val intent = if (parsed.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, parsed.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(parsed))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(intent, "分享到")) }
            .onFailure { message.value = "没有可用的分享应用" }
        LogStore.log("分享", "分享 ${parsed.size} 个文件")
    }

    private fun openUri(target: String) {
        if (target.isBlank()) return
        val uri = if (target.startsWith("content://")) Uri.parse(target) else Uri.fromFile(java.io.File(target))
        val mime = UrlUtils.mimeFor(target)
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_VIEW).apply {   // 未知类型：交给系统选择（常见为文件管理器）
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        for (intent in intents) {
            val opened = runCatching { startActivity(intent); true }.getOrDefault(false)
            if (opened) return
        }
        message.value = "没有可打开该文件的应用，可在「文件管理」的下载目录中查看"
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空下载记录？")
            .setMessage("只清空列表，还是连下载好的文件一起删除？删除后无法恢复。")
            .setPositiveButton("清空并删除文件") { _, _ ->
                val records = AppState.history.value.size
                val deleted = AppState.clearHistory(deleteFiles = true)
                message.value = "已清空 $records 条记录，并删除 $deleted 个文件"
            }
            .setNeutralButton("仅清空记录") { _, _ ->
                val records = AppState.history.value.size
                AppState.clearHistory(deleteFiles = false)
                message.value = "已清空 $records 条记录（文件保留）"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 冷启动：后台注册设备 + 检查版本更新（Cookie 走本机登录，不经过后台） */
    private fun checkCloudOnStart() {
        Thread {
            CloudSync.perform(this)
            runCatching {
                val info = CloudClient.checkUpdate(this)
                if (info != null) {
                    AppState.updateInfo.value = info
                }
            }
        }.start()
    }
}

// ---------------------------------------------------------------- UI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    themeKey: String,
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    message: String?,
    downloadState: DownloadState?,
    batch: BatchLinkState?,
    queue: List<QueueItem>,
    history: List<HistoryEntry>,
    liveLog: List<String>,
    onToggleTheme: () -> Unit,
    onPaste: () -> Unit,
    onDownload: () -> Unit,
    onBatch: () -> Unit,
    batchable: Boolean,
    onDownloadFinished: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenUri: (String) -> Unit,
    onShare: (List<String>) -> Unit,
    onStopParsing: () -> Unit,
    onOpenHistory: (HistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteHistory: (Int) -> Unit,
    onDeleteFile: (Int, String) -> Unit,
    onDeleteCurrent: () -> Unit,
    onDeleteCurrentFile: (String) -> Unit,
    onCancelBatch: () -> Unit,
    onCloseBatch: () -> Unit,
    onCopyLink: (String) -> Unit,
    onDeleteAllBatch: () -> Unit,
    onDeleteBatchFile: (String) -> Unit,
) {
    val context = LocalContext.current
    val gradient = AppThemeColors.gradient(themeKey)
    val detectedUrls = remember(input) { UrlUtils.extractAllUrls(input) }
    var logExpanded by remember { mutableStateOf(true) }
    val fileSelected = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(downloadState?.fileUris) {
        downloadState?.fileUris?.forEach { fileSelected[it] = fileSelected[it] ?: true }
    }

    // 下载完成后自动清空输入框，方便直接粘贴下一条
    LaunchedEffect(downloadState?.finished) {
        if (downloadState?.finished == true) onDownloadFinished()
    }

    // 解析中 / 下载中：把运行日志直接显示在主页，下载完成后自动隐藏（批量模式下由批量卡片展示状态）
    val downloading = downloadState != null && !downloadState.finished && !downloadState.failed
    val showLog = (busy || downloading) && batch == null

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("秒存", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text(
                            "抖音 / 快手 / 小红书 / 直链 · 粘贴即可下载",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    PillTextButton(text = "设置", onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    })
                    Spacer(Modifier.width(8.dp))
                    PillTextButton(if (themeKey == "green") "换橙色" else "换绿色", onClick = onToggleTheme)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(AppThemeColors.backgroundGradient(themeKey))),
        ) {
            // 整页就是一个可滚动列表：卡片再长也不会把「下载记录」挤到滑不动
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // 顶部区域（输入 / 提示 / 解析日志 / 当前下载 / 等待队列）作为一个整体
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppCard {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(108.dp),
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("粘贴作品链接、分享文本（可一次多条），或 mp4 等文件直链…") },
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onPaste, shape = RoundedCornerShape(12.dp)) { Text("粘贴") }
                        GradientButton(
                            text = when {
                                busy -> "解析中…"
                                detectedUrls.size >= 2 -> "下载 ${detectedUrls.size} 条"
                                else -> "下载"
                            },
                            enabled = !busy && input.isNotBlank(),
                            gradient = gradient,
                            onClick = onDownload,
                        )
                        // 主页 / 收藏夹链接才显示「批量」：抓取该主页作品后逐条下载
                        if (batchable) {
                            OutlinedButton(
                                onClick = onBatch,
                                shape = RoundedCornerShape(12.dp),
                                enabled = !busy,
                            ) { Text("批量") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "可一次粘贴多条链接：换行 / 逗号 / 分号分隔均可，自动去重、自动识别平台。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "主页 / 收藏页链接请用「批量下载」；部分平台需先在 设置 → Cookie 管理 登录。单次最多 50 条。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detectedUrls.size >= 2) {
                        Spacer(Modifier.height(4.dp))
                        val summary = detectedUrls
                            .map { UrlUtils.detectPlatform(it).label }
                            .groupingBy { it }
                            .eachCount()
                            .entries
                            .joinToString(" · ") { "${it.key} ${it.value}" }
                        Text(
                            "已识别 ${detectedUrls.size} 条：$summary",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                message?.let { text ->
                    AppCard(container = MaterialTheme.colorScheme.primaryContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text,
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            TextButton(onClick = onDismissMessage) { Text("知道了") }
                        }
                    }
                }

                if (showLog) {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("解析日志", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            if (busy) {
                                LinearProgressIndicator(
                                    modifier = Modifier.width(64.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    "下载完成后自动隐藏",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (busy) {
                                PillTextButton(
                                    text = "停止",
                                    onClick = onStopParsing,
                                    container = MaterialTheme.colorScheme.surfaceVariant,
                                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            TextButton(onClick = { logExpanded = !logExpanded }) {
                                Text(
                                    if (logExpanded) "收起" else "展开",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (logExpanded) {
                            Spacer(Modifier.height(8.dp))
                            val listState = rememberLazyListState()
                            LaunchedEffect(liveLog.size) {
                                if (liveLog.isNotEmpty()) listState.scrollToItem(liveLog.size - 1)
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp),
                            ) {
                                if (liveLog.isEmpty()) {
                                    item {
                                        Text(
                                            "等待解析输出…",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                items(liveLog.takeLast(60)) { line ->
                                    Text(
                                        line,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                batch?.let { batchState ->
                    BatchDownloadCard(
                        state = batchState,
                        downloadState = downloadState,
                        onCancel = onCancelBatch,
                        onClose = onCloseBatch,
                        onCopyLink = onCopyLink,
                        onOpen = onOpenUri,
                        onShare = onShare,
                        onDeleteAll = onDeleteAllBatch,
                        onDeleteFile = onDeleteBatchFile,
                    )
                }

                if (batch == null) downloadState?.let { state ->
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlatformChip(state.platform.label)
                            Spacer(Modifier.width(8.dp))
                            Text(state.stage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            if (state.finished && state.uri.isNotBlank()) {
                                val selectedUris = state.fileUris.ifEmpty { listOf(state.uri) }
                                    .filter { fileSelected[it] != false }
                                // 与下载记录一致：打开 / 分享都用图标按钮
                                IconAction(Icons.Default.PlayArrow, "打开") { onOpenUri(state.uri) }
                                IconAction(
                                    Icons.Default.Share,
                                    if (selectedUris.size > 1) "分享（$selectedUris.size 个文件）" else "分享",
                                ) { onShare(selectedUris) }
                                // 与下载记录一致：这一条也能整体删除（连同源文件，收起卡片）
                                IconAction(
                                    Icons.Default.Delete,
                                    "删除这次下载及其文件",
                                    tint = MaterialTheme.colorScheme.error,
                                ) { onDeleteCurrent() }
                            }
                        }
                        if (state.title.isNotBlank()) {
                            Text(state.title, fontSize = 14.sp, maxLines = 2, fontWeight = FontWeight.Medium)
                        }
                        if (!state.finished && !state.failed) {
                            Spacer(Modifier.height(6.dp))
                            val percent = state.percent
                            if (percent != null) {
                                LinearProgressIndicator(
                                    progress = { percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        val detail = when {
                            state.bytesTotal > 0 -> "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.bytesTotal)}"
                            else -> state.message
                        }
                        if (detail.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.fileUris.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (state.fileUris.size > 1) {
                                    "共 ${state.fileUris.size} 个文件 · 点缩略图可放大 / 播放"
                                } else {
                                    "点缩略图可放大 / 播放"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            FileGallery(
                                uris = state.fileUris,
                                names = state.fileNames,
                                sizes = state.fileSizes,
                                selected = fileSelected,
                                onToggleSelected = { uri -> fileSelected[uri] = fileSelected[uri] == false },
                                onPreview = { index ->
                                    context.startActivity(
                                        MediaPreviewActivity.intent(
                                            context,
                                            state.fileUris,
                                            state.fileNames,
                                            state.fileSizes,
                                            index,
                                        ),
                                    )
                                },
                                onOpen = onOpenUri,
                                onShare = { uri -> onShare(listOf(uri)) },
                                onDelete = { uri -> onDeleteCurrentFile(uri) },
                            )
                        }
                    }
                }

                if (batch == null && queue.isNotEmpty()) {
                    Column {
                        Text("等待队列（${queue.size}）", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        queue.take(3).forEach { pending ->
                            Text(
                                "· ${pending.platform.label} ${pending.title.take(30)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                    }
                }

                item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下载记录", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    if (history.isNotEmpty()) {
                        PillTextButton(
                            text = "清空",
                            onClick = onClearHistory,
                            container = MaterialTheme.colorScheme.surfaceVariant,
                            content = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }

                if (history.isEmpty()) {
                    item {
                        Text(
                            "还没有下载记录",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                        itemsIndexed(history) { index, item ->
                            AppCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            PlatformChip(item.platform.label)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                item.title.ifBlank { item.filePath },
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            listOf(item.finishedAt, item.sizeText, item.quality)
                                                .filter { it.isNotBlank() }
                                                .joinToString(" · "),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    // 打开 / 分享 / 删除（图标按钮；删除在最后，无确认，连同源文件一起删）
                                    IconAction(Icons.Default.PlayArrow, "打开") { onOpenHistory(item) }
                                    IconAction(Icons.Default.Share, "分享") {
                                        onShare(item.uris.ifEmpty { listOf(item.uri) }.filter { it.isNotBlank() })
                                    }
                                    IconAction(
                                        Icons.Default.Delete,
                                        "删除这条记录及其文件",
                                        tint = MaterialTheme.colorScheme.error,
                                    ) { onDeleteHistory(index) }
                                }
                                // 缩略图：图片点开可放大、视频/音频点开即播放（老记录没有大小就现查）
                                val historyUris = item.uris.ifEmpty { listOf(item.uri) }.filter { it.isNotBlank() }
                                if (historyUris.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    FileGallery(
                                        uris = historyUris,
                                        names = item.fileNames,
                                        sizes = item.sizes,
                                        selected = null,
                                        onToggleSelected = {},
                                        onPreview = { index ->
                                            context.startActivity(
                                                MediaPreviewActivity.intent(
                                                    context,
                                                    historyUris,
                                                    item.fileNames,
                                                    item.sizes,
                                                    index,
                                                ),
                                            )
                                        },
                                        onOpen = onOpenUri,
                                        onShare = { uri -> onShare(listOf(uri)) },
                                        onDelete = { uri -> onDeleteFile(index, uri) },
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
}

/** 多链接批量卡片：汇总 + 按链接分组（默认折叠），组内复用文件缩略图 */
@Composable
private fun BatchDownloadCard(
    state: BatchLinkState,
    downloadState: DownloadState?,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onCopyLink: (String) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (List<String>) -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteFile: (String) -> Unit,
) {
    val context = LocalContext.current
    val expanded = remember(state.id) { mutableStateMapOf<Int, Boolean>() }
    val activePercent = if (!state.finished) downloadState?.percent ?: 0 else 0
    val progress = (state.doneCount * 100f + activePercent) /
        state.items.size.coerceAtLeast(1) / 100f
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("批量下载", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "共 ${state.items.size} 条",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (state.finished) {
                val allFiles = state.items.flatMap { it.files }.filter { it.isNotBlank() }
                if (allFiles.isNotEmpty()) {
                    IconAction(Icons.Default.Share, "分享全部文件") { onShare(allFiles) }
                    IconAction(
                        Icons.Default.Delete,
                        "删除这批下载及其文件",
                        tint = MaterialTheme.colorScheme.error,
                    ) { onDeleteAll() }
                }
                PillTextButton(
                    text = "关闭",
                    onClick = onClose,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PillTextButton(
                    text = "取消",
                    onClick = onCancel,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "成功 ${state.succeeded}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            if (state.failed > 0) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "失败 ${state.failed}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (state.cancelled > 0) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "已取消 ${state.cancelled}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.skipped > 0) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "已跳过 ${state.skipped}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.items.any { it.status == BatchItemStatus.SUCCESS && it.files.isNotEmpty() }) {
                val allOpen = state.items.indices.all { expanded[it] == true }
                TextButton(onClick = {
                    state.items.indices.forEach { expanded[it] = !allOpen }
                }) { Text(if (allOpen) "收起全部" else "展开全部", fontSize = 11.sp) }
            }
        }
        if (!state.finished) {
            val activeIndex = state.items.indexOfFirst { it.status == BatchItemStatus.DOWNLOADING }
                .let { if (it >= 0) it else state.items.indexOfFirst { it.status == BatchItemStatus.PARSING } }
            if (activeIndex >= 0) {
                Text(
                    "正在处理第 ${activeIndex + 1} 条：${
                        state.items[activeIndex].title.ifBlank {
                            state.items[activeIndex].url.takeLast(42)
                        }
                    }",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.items.forEachIndexed { index, item ->
            val isOpen = expanded[index] == true
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformChip(item.platform.label)
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title.ifBlank { item.url.takeLast(46) },
                        fontSize = 12.sp,
                        maxLines = 1,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        batchStatusText(
                            item,
                            if (item.status == BatchItemStatus.DOWNLOADING) downloadState?.percent else null,
                        ),
                        fontSize = 11.sp,
                        maxLines = 2,
                        color = when (item.status) {
                            BatchItemStatus.FAILED -> MaterialTheme.colorScheme.error
                            BatchItemStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (item.status == BatchItemStatus.SUCCESS && item.files.isNotEmpty()) {
                    Text(
                        "${item.files.size} 个文件",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.status == BatchItemStatus.FAILED) {
                    TextButton(onClick = { onCopyLink(item.url) }) { Text("复制", fontSize = 11.sp) }
                }
                if (item.files.isNotEmpty()) {
                    IconAction(
                        if (isOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        if (isOpen) "收起" else "展开",
                        boxSize = 24.dp,
                        iconSize = 16.dp,
                    ) { expanded[index] = !isOpen }
                }
            }
            if (isOpen && item.files.isNotEmpty()) {
                FileGallery(
                    uris = item.files,
                    names = item.fileNames,
                    sizes = item.sizes,
                    selected = null,
                    onToggleSelected = {},
                    onPreview = { fileIndex ->
                        context.startActivity(
                            MediaPreviewActivity.intent(context, item.files, item.fileNames, item.sizes, fileIndex),
                        )
                    },
                    onOpen = onOpen,
                    onShare = { uri -> onShare(listOf(uri)) },
                    onDelete = { uri -> onDeleteFile(uri) },
                )
            }
        }
    }
}

/** 批量条目的状态文案（失败原因 + 登录提示） */
private fun batchStatusText(item: BatchLinkItem, percent: Int?): String {
    val base = when (item.status) {
        BatchItemStatus.WAITING -> "等待"
        BatchItemStatus.PARSING -> "解析中…"
        BatchItemStatus.QUEUED -> "等待下载"
        BatchItemStatus.DOWNLOADING -> if (percent != null) "下载中 $percent%" else "下载中…"
        BatchItemStatus.SUCCESS -> "成功"
        BatchItemStatus.FAILED -> "失败：${item.message.ifBlank { "未知错误" }}"
        BatchItemStatus.SKIPPED -> item.message.ifBlank { "已跳过" }
        BatchItemStatus.CANCELLED -> "已取消"
    }
    val needsLogin = item.status == BatchItemStatus.FAILED &&
        (item.message.contains("登录") || item.message.contains("Cookie", ignoreCase = true))
    return if (needsLogin) "$base · 请到 设置 → Cookie 管理 登录后重试" else base
}

/** 统一卡片样式：更大圆角 + 明显阴影 */
@Composable
private fun AppCard(
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp, pressedElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

/**
 * 是不是「主页 / 收藏夹」链接（批量下载入口只在识别到这类链接时出现）：
 * 抖音 https://www.douyin.com/user/xxx、小红书 https://www.xiaohongshu.com/user/profile/xxx
 */
private fun isBatchUrl(text: String): Boolean {
    val url = UrlUtils.extractUrl(text) ?: text.trim()
    return when (UrlUtils.detectPlatform(url)) {
        Platform.DOUYIN -> url.contains("douyin.com/user/")
        Platform.XIAOHONGSHU -> url.contains("xiaohongshu.com/user/profile/")
        else -> false
    }
}

/** 渐变主按钮 */
@Composable
private fun GradientButton(
    text: String,
    enabled: Boolean,
    gradient: List<Color>,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) Brush.horizontalGradient(gradient)
                else Brush.horizontalGradient(listOf(Color(0xFFE0D6CC), Color(0xFFD6CCC2)))
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun PlatformChip(label: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** 胶囊文字按钮（顶栏 / 清空等次级操作） */
@Composable
private fun PillTextButton(
    text: String,
    onClick: () -> Unit,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = content, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** 图标操作按钮（Material 内置开源图标）：纯图标无文字，用于记录与文件的打开 / 分享 / 删除 */
@Composable
private fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    boxSize: androidx.compose.ui.unit.Dp = 30.dp,
    iconSize: androidx.compose.ui.unit.Dp = 17.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 卡片里的文件展示：
 * - 图片 / 视频 / PDF：缩略图（视频带播放角标，音频用音符图标），点一下放大预览
 * - 其它类型（安装包 / 压缩包 / 文本等）：保持原来的「勾选 + 名字 + 打开 / 分享」行样式
 * 每个文件都会显示自己的大小。
 */
@Composable
private fun FileGallery(
    uris: List<String>,
    names: List<String>,
    sizes: List<Long>,
    selected: Map<String, Boolean>?,
    onToggleSelected: (String) -> Unit,
    onPreview: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    /** 传 nil（不传）时不显示删除按钮，如「刚下载完」的临时任务卡片 */
    onDelete: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val kinds = remember(uris, names) {
        uris.mapIndexed { index, uri -> MediaMeta.kindFor(context, uri, names.getOrNull(index).orEmpty()) }
    }
    val mediaIndexes = kinds.indices.filter { kinds[it] != MediaKind.OTHER }
    val otherIndexes = kinds.indices.filter { kinds[it] == MediaKind.OTHER }

    if (mediaIndexes.isNotEmpty()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(mediaIndexes) { index ->
                MediaTile(
                    uri = uris[index],
                    name = names.getOrNull(index).orEmpty(),
                    knownSize = sizes.getOrNull(index) ?: 0L,
                    kind = kinds[index],
                    showCheck = selected != null,
                    checked = selected?.get(uris[index]) != false,
                    onToggleSelected = { onToggleSelected(uris[index]) },
                    onPreview = { onPreview(index) },
                    onOpen = { onOpen(uris[index]) },
                    onShare = { onShare(uris[index]) },
                    onDelete = onDelete?.let { handler -> { handler(uris[index]) } },
                )
            }
        }
    }
    if (otherIndexes.isNotEmpty()) {
        Column {
            otherIndexes.forEach { index ->
                OtherFileRow(
                    uri = uris[index],
                    name = names.getOrNull(index).orEmpty().ifBlank { "文件 ${index + 1}" },
                    knownSize = sizes.getOrNull(index) ?: 0L,
                    showCheck = selected != null,
                    checked = selected?.get(uris[index]) != false,
                    onToggleSelected = { onToggleSelected(uris[index]) },
                    onOpen = { onOpen(uris[index]) },
                    onShare = { onShare(uris[index]) },
                    onDelete = onDelete?.let { handler -> { handler(uris[index]) } },
                )
            }
        }
    }
}

/** 单个缩略图：图片/视频/PDF 显示缩略图，音频显示音符；下面是文件大小 */
@Composable
private fun MediaTile(
    uri: String,
    name: String,
    knownSize: Long,
    kind: MediaKind,
    showCheck: Boolean,
    checked: Boolean,
    onToggleSelected: () -> Unit,
    onPreview: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, uri, kind) {
        value = withContext(Dispatchers.IO) { MediaMeta.thumb(context, uri, kind) }
    }
    val size by produceState(knownSize, uri, knownSize) {
        value = if (knownSize > 0) knownSize else withContext(Dispatchers.IO) { MediaMeta.size(context, uri) }
    }
    // 格式标签：优先文件名扩展名，没有就按类型兜底（如 MP4 / JPG / PDF）
    val formatLabel = remember(name, kind) {
        name.substringAfterLast('.', "").trim().uppercase().takeIf { it.length in 1..5 }
            ?: when (kind) {
                MediaKind.VIDEO -> "MP4"
                MediaKind.AUDIO -> "AUDIO"
                MediaKind.PDF -> "PDF"
                MediaKind.IMAGE -> "IMAGE"
                else -> "FILE"
            }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    RoundedCornerShape(12.dp),
                )
                .clickable { onPreview() },
            contentAlignment = Alignment.Center,
        ) {
            val image = thumb
            when {
                image != null -> Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(88.dp),
                )
                kind == MediaKind.AUDIO -> Text("♪", fontSize = 30.sp, color = MaterialTheme.colorScheme.primary)
                kind == MediaKind.PDF -> Text("PDF", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                else -> Text("加载中…", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) {
                // 播放角标：点一下直接播放，不再弹「选择应用」
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x88000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▶", fontSize = 13.sp, color = Color.White)
                }
            }
            if (showCheck) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (checked) MaterialTheme.colorScheme.primary else Color(0x66000000))
                        .clickable { onToggleSelected() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) Text("✓", fontSize = 11.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            if (size > 0) "$formatLabel · ${formatBytes(size)}" else formatLabel,
            fontSize = 10.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 打开 / 分享 /（历史记录里还有）删除：图标按钮，居中排在文件下面
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconAction(Icons.Default.PlayArrow, "打开", boxSize = 24.dp, iconSize = 15.dp) { onOpen() }
            IconAction(Icons.Default.Share, "分享", boxSize = 24.dp, iconSize = 15.dp) { onShare() }
            if (onDelete != null) {
                IconAction(
                    Icons.Default.Delete,
                    "删除这个文件",
                    tint = MaterialTheme.colorScheme.error,
                    boxSize = 24.dp,
                    iconSize = 15.dp,
                ) { onDelete() }
            }
        }
    }
}

/** 不能预览的文件（安装包 / 压缩包 / 文本等）：保持原来的行样式，名字后面补上大小 */
@Composable
private fun OtherFileRow(
    uri: String,
    name: String,
    knownSize: Long,
    showCheck: Boolean,
    checked: Boolean,
    onToggleSelected: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val size by produceState(knownSize, uri, knownSize) {
        value = if (knownSize > 0) knownSize else withContext(Dispatchers.IO) { MediaMeta.size(context, uri) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showCheck) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggleSelected() },
                modifier = Modifier.size(26.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (name.length > 18) "…" + name.takeLast(17) else name,
                fontSize = 11.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (size > 0) {
                Text(
                    formatBytes(size),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        // 打开 / 分享 /（历史记录里还有）删除：图标按钮
        IconAction(Icons.Default.PlayArrow, "打开", boxSize = 28.dp, iconSize = 16.dp) { onOpen() }
        IconAction(Icons.Default.Share, "分享", boxSize = 28.dp, iconSize = 16.dp) { onShare() }
        if (onDelete != null) {
            IconAction(
                Icons.Default.Delete,
                "删除这个文件",
                tint = MaterialTheme.colorScheme.error,
                boxSize = 28.dp,
                iconSize = 16.dp,
            ) { onDelete() }
        }
    }
}
