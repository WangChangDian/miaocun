package local.wangchangdian.videograbber

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener

/** 主页列表里的一条作品 */
private data class BatchItem(val url: String, val title: String)

/**
 * 主页 / 收藏夹批量下载。
 *
 * 列表接口都带平台签名（抖音 a_bogus、小红书 x-s/x-t），自己复刻不现实；
 * 这里直接读**页面渲染出来的作品卡片**：打开主页 → 自动滚动触发懒加载 →
 * 收集卡片链接（抖音 /video/、/note/，小红书 /explore/）→ 逐条走已有的
 * 「单条解析 → 下载」流程（ExtractionActivity + DownloadService）。
 */
class BatchActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val TAG = "VGBatch"
        private const val DEFAULT_LIMIT = 20
        private const val MAX_SCROLLS = 25
        private const val STALL_LIMIT = 4

        /** 抖音桌面版页面才给正常内容（移动 UA 只会引导下载 App） */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 收集当前页面里的作品卡片链接（滚动后会越来越多） */
        private const val COLLECT_JS = """
            (function(){
              try{
                var out=[];
                var seen={};
                var nodes=document.querySelectorAll('a[href]');
                for(var i=0;i<nodes.length;i++){
                  var href=nodes[i].href||'';
                  var m=/douyin\.com\/(video|note)\/(\d{6,})/.exec(href);
                  if(!m){
                    m=/xiaohongshu\.com\/explore\/([0-9a-zA-Z]+)/.exec(href);
                    if(!m)continue;
                    href='https://www.xiaohongshu.com/explore/'+m[1];
                    if(href.indexOf('xsec_token=')<0){
                      var t=/xsec_token=([^&]+)/.exec(nodes[i].href||'');
                      if(t)href+='?xsec_token='+t[1]+'&xsec_source=pc_user';
                    }
                  }else{
                    href='https://www.douyin.com/'+m[1]+'/'+m[2];
                  }
                  if(seen[href])continue;
                  seen[href]=1;
                  var text=(nodes[i].innerText||nodes[i].getAttribute('aria-label')||'').replace(/\s+/g,' ').trim();
                  out.push({url:href,title:text.slice(0,120)});
                }
                return JSON.stringify(out);
              }catch(e){return '[]';}
            })()
        """
    }

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var platform = Platform.UNKNOWN
    private var startUrl = ""

    private val status = mutableStateOf("正在打开主页…")
    private val collecting = mutableStateOf(true)
    private val items = mutableStateOf<List<BatchItem>>(emptyList())
    private val selected = mutableStateOf<Set<String>>(emptySet())
    private val running = mutableStateOf(false)
    private val done = mutableStateOf(false)

    private var queue: List<BatchItem> = emptyList()
    private var queueIndex = 0
    private var okCount = 0
    private var failCount = 0

    private val extractionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val raw = data?.getStringExtra(ExtractionActivity.EXTRA_RESULT)
        val parsed = raw?.let { runCatching { ExtractResult.fromJson(it) }.getOrNull() }
        if (parsed != null) {
            okCount += 1
            DownloadService.start(this, parsed)
        } else {
            failCount += 1
            LogStore.log("批量", "第 ${queueIndex + 1} 条解析失败：${data?.getStringExtra(ExtractionActivity.EXTRA_ERROR) ?: "未知原因"}")
        }
        queueIndex += 1
        startNext()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        platform = UrlUtils.detectPlatform(startUrl)
        LogStore.log("批量", "打开批量下载：${startUrl.take(90)}（${platform.label}）")

        setContent {
            VideoGrabberTheme(AppState.theme.value) {
                BatchScreen(
                    status = status.value,
                    collecting = collecting.value,
                    running = running.value,
                    done = done.value,
                    items = items.value,
                    selected = selected.value,
                    onToggle = ::toggleSelect,
                    onToggleAll = ::toggleAll,
                    onStart = ::startDownloads,
                    onClose = { finish() },
                    webViewContent = { BatchWebView(startUrl) },
                )
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let { runCatching { it.destroy() } }
        webView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ WebView

    @Composable
    private fun BatchWebView(url: String) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this@BatchActivity.webView = this
                        configure(this)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                Log.i(TAG, "finish $finishedUrl")
                                if (collecting.value && !collectStarted) {
                                    collectStarted = true
                                    collectLoop()
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                                Log.i(TAG, "console ${msg?.message()}")
                                return true
                            }
                        }
                        loadUrl(url)
                    }
                },
            )
        }
    }

    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgentString?.replace("; wv", "")?.replace(" Version/4.0", "")
            if (platform == Platform.DOUYIN) userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        // 抖音 / 小红书的批量列表要登录才给全；「手动粘贴 / 云端回填」的登录态先补种进浏览器
        CookieStore.seedBrowserCookies(this@BatchActivity, platform.key)
    }

    // ------------------------------------------------------------ 列表抓取

    private val collected = LinkedHashMap<String, BatchItem>()
    private var scrollAttempts = 0
    private var stall = 0
    private var collectStarted = false

    private fun collectLoop() {
        val view = webView ?: return
        view.evaluateJavascript(COLLECT_JS) { raw ->
            val text = decodeJsString(raw).orEmpty()
            val array = runCatching { org.json.JSONArray(text) }.getOrNull()
            val before = collected.size
            if (array != null) {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val url = obj.optString("url")
                    if (url.isBlank() || collected.containsKey(url)) continue
                    collected[url] = BatchItem(url, obj.optString("title"))
                }
            }
            scrollAttempts += 1
            stall = if (collected.size > before) 0 else stall + 1
            status.value = "正在加载作品列表…（已读到 ${collected.size} 条）"
            if (collected.size >= DEFAULT_LIMIT || scrollAttempts >= MAX_SCROLLS || stall >= STALL_LIMIT) {
                finishCollect(collected.values.toList())
                return@evaluateJavascript
            }
            handler.postDelayed({
                view.scrollBy(0, 2400)
                collectLoop()
            }, 1200)
        }
    }

    private fun finishCollect(list: List<BatchItem>) {
        collecting.value = false
        val limited = list.take(DEFAULT_LIMIT)
        items.value = limited
        selected.value = limited.map { it.url }.toSet()
        status.value = if (limited.isEmpty()) {
            "没有取到作品：请确认这是你自己的主页 / 收藏页，并且已登录"
        } else {
            "共 ${limited.size} 条作品（默认全选，最多 $DEFAULT_LIMIT 条）"
        }
        LogStore.log("批量", "收集完成：${limited.size} 条")
    }

    // ------------------------------------------------------------ 逐条下载

    private fun toggleSelect(url: String) {
        selected.value = if (selected.value.contains(url)) selected.value - url else selected.value + url
    }

    private fun toggleAll() {
        selected.value = if (selected.value.size == items.value.size) emptySet() else items.value.map { it.url }.toSet()
    }

    private fun startDownloads() {
        val chosen = items.value.filter { selected.value.contains(it.url) }
        if (chosen.isEmpty()) {
            status.value = "还没有选中作品"
            return
        }
        queue = chosen
        queueIndex = 0
        okCount = 0
        failCount = 0
        running.value = true
        done.value = false
        startNext()
    }

    private fun startNext() {
        if (queueIndex >= queue.size) {
            running.value = false
            done.value = true
            status.value = "批量任务已进入下载队列：成功解析 $okCount 条，失败 $failCount 条"
            LogStore.log("批量", "批量结束：成功 $okCount，失败 $failCount")
            return
        }
        status.value = "正在解析第 ${queueIndex + 1} / ${queue.size} 条…"
        val item = queue[queueIndex]
        val intent = Intent(this, ExtractionActivity::class.java)
            .putExtra(ExtractionActivity.EXTRA_URL, item.url)
            .putExtra(ExtractionActivity.EXTRA_HINT, item.url)
            .putExtra(ExtractionActivity.EXTRA_PLATFORM, platform.key)
        extractionLauncher.launch(intent)
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    private fun jsString(value: String): String = JSONObject.quote(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchScreen(
    status: String,
    collecting: Boolean,
    running: Boolean,
    done: Boolean,
    items: List<BatchItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
    webViewContent: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("批量下载", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onClose) { Text("关闭") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (collecting || running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            // 抓取阶段把页面全屏显示出来：主页作品是懒加载的，没有真实视口滚动就触发不了列表请求；
            // 顺便用户也能看到加载过程（阶段结束就换成作品列表）
            if (collecting) {
                Box(Modifier.fillMaxWidth().weight(1f)) { webViewContent() }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (items.isEmpty()) "" else "已选 ${selected.size} / ${items.size}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (items.isNotEmpty() && !running) {
                        TextButton(onClick = onToggleAll) {
                            Text(if (selected.size == items.size) "全不选" else "全选", fontSize = 12.sp)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(items) { index, item ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable { if (!running) onToggle(item.url) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selected.contains(item.url),
                                    onCheckedChange = { if (!running) onToggle(item.url) },
                                )
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(
                                        item.title.ifBlank { "作品 ${index + 1}" },
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                    )
                                    Text(
                                        item.url.take(64),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                if (items.isNotEmpty()) {
                    Button(
                        onClick = onStart,
                        enabled = !running && !done && selected.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Text(if (running) "下载中…" else "下载选中（${selected.size}）")
                    }
                }
            }
        }
    }
}
