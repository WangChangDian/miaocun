package local.wangchangdian.videograbber

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用真实 WebView 打开作品页，并在 shouldInterceptRequest 里"代理"目标请求：
 * 由 WebView 发出（自带签名/风控参数），我们拿到响应体后解析出媒体地址。
 */
class ExtractionActivity : ComponentActivity() {

    companion object {
        /** 当前正在运行的解析实例（用于主页「停止」按钮） */
        @Volatile
        private var current: java.lang.ref.WeakReference<ExtractionActivity>? = null

        fun stopCurrent(): Boolean {
            val activity = current?.get() ?: return false
            activity.runOnUiThread { activity.finishWithError("已手动停止解析") }
            return true
        }

        const val EXTRA_URL = "url"
        const val EXTRA_HINT = "hint"
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_RESULT = "result"
        const val EXTRA_ERROR = "error"
        private const val TAG = "VideoGrabber"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        // 抖音桌面版页面才会请求 /aweme/v1/web/aweme/detail/，移动端会引导下载 App
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 页面资源嗅探脚本：收集 img / video / source / og 标签 / 背景图 */
        private const val SNIFF_JS = """
            (function(){
              var urls = [];
              function add(u){
                try {
                  if (!u) return;
                  u = String(u);
                  if (u.indexOf('data:') === 0) return;
                  if (u.indexOf('http') !== 0) { u = new URL(u, location.href).href; }
                  if (u.indexOf('http') !== 0) return;
                  if (urls.indexOf(u) < 0) urls.push(u);
                } catch(e) {}
              }
              document.querySelectorAll('img').forEach(function(el){ add(el.currentSrc || el.src); });
              document.querySelectorAll('video').forEach(function(el){ add(el.currentSrc || el.src); add(el.poster); });
              document.querySelectorAll('source').forEach(function(el){ add(el.src); });
              document.querySelectorAll('meta[property="og:image"],meta[property="og:video"],meta[name="twitter:image"]')
                .forEach(function(el){ add(el.content); });
              // 微信公众号文章里的「视频号」组件：data-url 就是视频文件地址
              document.querySelectorAll('mp-common-videosnap[data-url],[data-pluginname="mpvideosnap"][data-url]')
                .forEach(function(el){ add(el.getAttribute('data-url')); });
              document.querySelectorAll('iframe.video_iframe[data-src],.js_video_iframe[data-src]')
                .forEach(function(el){ add(el.getAttribute('data-src')); });
              document.querySelectorAll('a[href]').forEach(function(el){
                var h = el.getAttribute('href') || '';
                if (/\.(jpg|jpeg|png|webp|gif|avif|mp4|mov|webm)(\?|$)/i.test(h)) add(h);
              });
              // 微信公众号文章：视频地址在 window.__videoPageInfos[].mp_video_trans_info 里
              try {
                (window.__videoPageInfos || []).forEach(function(item){
                  var tracks = (item && item.mp_video_trans_info) || [];
                  tracks.sort(function(a, b){ return (b.filesize || 0) - (a.filesize || 0); });
                  tracks.forEach(function(track){ add(track && track.url); });
                });
              } catch (e) {}
              // 同源 iframe 里也看一眼（微信公众号文章的视频就嵌在 iframe 中）
              document.querySelectorAll('iframe').forEach(function(frame){
                try {
                  var doc = frame.contentDocument;
                  if (!doc) return;
                  doc.querySelectorAll('video').forEach(function(el){ add(el.currentSrc || el.src); add(el.poster); });
                  doc.querySelectorAll('source').forEach(function(el){ add(el.src); });
                  doc.querySelectorAll('img').forEach(function(el){ add(el.currentSrc || el.src); });
                } catch (e) {}
              });
              return JSON.stringify({ title: document.title || '', urls: urls.slice(0, 120) });
            })();
        """
    }

    private lateinit var webView: WebView
    private var finished = false
    private var platform = Platform.DOUYIN
    private var startUrl = ""
    /** 抖音短链在 WebView 里跳转后，只换一次分享页，避免来回重载 */
    private var douyinSwitchedToShare = false
    private var rawHint = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 解析窗口完全透明且不接收触摸，保证主页的「停止」等按钮仍可点击
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        AppState.clearLiveLog()
        current = java.lang.ref.WeakReference(this)
        platform = Platform.fromKey(intent.getStringExtra(EXTRA_PLATFORM))
        startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        rawHint = intent.getStringExtra(EXTRA_HINT).orEmpty()
        if (startUrl.isBlank()) {
            finishWithError("缺少链接")
            return
        }
        // 小红书 App 分享页把笔记数据内嵌在 HTML（不走接口），换成标准笔记页再加载
        if (platform == Platform.XIAOHONGSHU) {
            startUrl = UrlUtils.normalizeXiaohongshu(startUrl)
        }

        // 抖音与 YouTube 都要用桌面 UA：
        // 抖音按 UA 判断终端的 —— 移动 UA 打开 www.douyin.com/video/<id> 会被 302 回
        // m.douyin.com/share/video/<id>，而那个页面不会请求 /aweme/v1/web/aweme/detail/，
        // 解析只能退化到扫 DOM，图文作品就会扫出背景音乐地址（实测下载 0 字节）。
        val userAgent = if (platform == Platform.YOUTUBE || platform == Platform.DOUYIN) DESKTOP_UA else UA
        // 抖音：移动端分享页在 App 内 WebView 中更稳定
        val pageUrl = when (platform) {
            Platform.DOUYIN ->
                // 用移动分享页 + 桌面 UA：实测（持久化配置的真实浏览器）只有这个组合稳定触发
                // /aweme/v1/web/aweme/detail/。移动 UA 打开桌面页会被 302 回移动页且不请求详情接口
                UrlUtils.douyinId(startUrl)?.let { "https://m.douyin.com/share/video/$it" } ?: startUrl
            Platform.YOUTUBE ->
                UrlUtils.youtubeId(startUrl)?.let { UrlUtils.youtubeWatchUrl(it) } ?: startUrl
            else -> startUrl
        }
        Log.i(TAG, "load: $pageUrl")
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = userAgent
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }
        // 铺满窗口但近乎全透明：页面需要"真实渲染"才会触发播放器请求（YouTube / X）
        webView.alpha = 0.01f
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addContentView(webView, params)

        webView.webViewClient = object : WebViewClient() {
            /**
             * 拦下「唤起 App」的自定义协议（xhsdiscover://、snssdk://、bytedance:// …）。
             * 不拦的话页面会跳到这个协议上，显示 net::ERR_UNKNOWN_URL_SCHEME，
             * 把本来正常的作品页顶掉 —— 小红书就是打开笔记后自动尝试跳 App。
             */
            private fun blockAppScheme(url: String?): Boolean {
                val scheme = runCatching { android.net.Uri.parse(url).scheme?.lowercase() }.getOrNull()
                val allowed = scheme == null || scheme in setOf("http", "https", "about", "data", "javascript", "blob")
                if (!allowed) {
                    Log.i(TAG, "blocked app scheme: $scheme://")
                    // 小红书 App 分享页会尝试跳 xhsdiscover://，而这条链接里带着完整笔记数据
                    // （h5NotePreloadInfo：标题 + 图片列表），是 SSR 之外的第二条兜底路径
                    if (scheme == "xhsdiscover" && url != null) {
                        tryParseXhsSchemePayload(url)
                    }
                }
                return !allowed
            }

            /** 从 xhsdiscover:// 唤起链接的 h5NotePreloadInfo 里解析笔记（图文） */
            private fun tryParseXhsSchemePayload(url: String) {
                if (platform != Platform.XIAOHONGSHU) return
                val raw = runCatching {
                    android.net.Uri.parse(url).getQueryParameter("h5NotePreloadInfo")
                }.getOrNull() ?: return
                val payload = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return
                val images = payload.optJSONArray("images_list") ?: org.json.JSONArray()
                val urls = mutableListOf<String>()
                for (index in 0 until images.length()) {
                    val item = images.optJSONObject(index) ?: continue
                    // 优先 1080 大图，退回落图
                    val link = item.optString("url_size_large").ifBlank { item.optString("url") }
                    if (link.isNotBlank()) urls.add(link)
                }
                if (urls.isEmpty()) return
                val title = payload.optString("title").ifBlank { payload.optString("desc") }
                Log.i(TAG, "xhs scheme payload -> ${urls.size} images, title=$title")
                finishWithResult(
                    ExtractResult(
                        platform = Platform.XIAOHONGSHU,
                        title = title,
                        author = "",
                        mediaUrls = urls.distinct(),
                        kind = ExtractResult.KIND_IMAGE,
                    ),
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return blockAppScheme(request.url?.toString())
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                return blockAppScheme(url)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
                val isMainFrame = request.isForMainFrame
                if (!isTarget(url, isMainFrame)) return null
                val bytes = fetchBytes(url, request.requestHeaders) ?: return null
                val text = String(bytes, Charsets.UTF_8)
                Log.i(TAG, "intercept $url (main=$isMainFrame, ${bytes.size}B)")
                LogStore.log("解析", "拦截 ${url.take(90)} ($isMainFrame, ${bytes.size}B)")
                val result = parsePayload(url, text)
                if (result != null) {
                    Log.i(TAG, "parsed -> ${result.mediaUrls.size} urls, title=${result.title.take(20)}")
                    finishWithResult(result)
                    return null
                }
                return WebResourceResponse(mimeOf(text, url), "utf-8", ByteArrayInputStream(bytes))
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.i(TAG, "page finished: $url")
                // 抖音短链：WebView 跟随跳转后落到 iesdouyin.com/share/... ，
                // 该页面不会请求 /aweme/v1/web/aweme/detail/；换成标准作品页再加载一次
                if (platform == Platform.DOUYIN && !douyinSwitchedToShare) {
                    val awemeId = UrlUtils.douyinId(url)
                    // /note/ 是图文作品的正确页面（数据由页面自身异步加载），不能切走——
                    // 之前统一切到 share/video 会丢掉图文数据
                    if (awemeId != null && !url.contains("m.douyin.com/share/video/") && !url.contains("/note/")) {
                        douyinSwitchedToShare = true
                        Log.i(TAG, "douyin share page -> $awemeId")
                        view.loadUrl("https://m.douyin.com/share/video/$awemeId")
                        return
                    }
                }
                // 触发播放器初始化（静音自动播放），否则 YouTube 不会请求 player 接口
                view.evaluateJavascript(
                    """(function(){
                        var v = document.querySelector('video');
                        if (v) { try { v.muted = true; v.play(); } catch(e) {} }
                        try { var b = document.querySelector('.ytp-large-play-button'); if (b) b.click(); } catch(e) {}
                    })();""",
                    null,
                )
                if (platform == Platform.YOUTUBE) {
                    // 页面自带的播放器数据里通常已经有可用直链
                    view.evaluateJavascript(
                        """(function(){
                            try {
                                var pr = window.ytInitialPlayerResponse;
                                if (!pr) return 'none';
                                var sd = pr.streamingData || {};
                                var all = (sd.formats || []).concat(sd.adaptiveFormats || []);
                                var direct = all.filter(function(f){ return !!f.url; }).length;
                                if (direct > 0) { window.__VG_PR__ = JSON.stringify(pr); }
                                return all.length + '/' + direct;
                            } catch (e) { return 'err'; }
                        })();""",
                    ) { value ->
                        val text = decodeJsString(value) ?: value?.trim('"') ?: "none"
                        LogStore.log("YouTube", "player 数据（格式/直链）: $text")
                        val direct = text.substringAfter('/').toIntOrNull() ?: 0
                        if (direct > 0) {
                            view.evaluateJavascript("window.__VG_PR__ || ''") { payload ->
                                val decoded = decodeJsString(payload)
                                if (!decoded.isNullOrBlank()) {
                                    Extractor.parseYouTube(decoded)?.let { finishWithResult(it) }
                                }
                            }
                        }
                    }
                }
                // 抖音的图文页数据是 SPA 异步加载的，onPageFinished 时往往还没到——
                // 由 dumpDomWithRetry 轮询等待；其它平台维持"加载完即抓一次"的行为
                if (platform == Platform.DOUYIN) {
                    if (domDumpStarted.compareAndSet(false, true)) {
                        dumpDomWithRetry(view, url, 0)
                    }
                } else {
                    dumpDomWithRetry(view, url, 0)
                }
                if (platform == Platform.UNKNOWN || platform == Platform.WEB) {
                    // 普通网页：嗅探页面里的图片 / 视频资源
                    view.evaluateJavascript(SNIFF_JS) { value ->
                        val decoded = decodeJsString(value)
                        val json = runCatching { org.json.JSONObject(decoded ?: "") }.getOrNull()
                        val urls = mutableListOf<String>()
                        json?.optJSONArray("urls")?.let { array ->
                            for (i in 0 until array.length()) {
                                array.optString(i).takeIf { it.startsWith("http") }?.let { urls.add(it) }
                            }
                        }
                        LogStore.log("嗅探", "页面资源 ${urls.size} 个: ${urls.take(2)}")
                        if (urls.isNotEmpty()) {
                            finishWithResult(
                                ExtractResult(
                                    platform = Platform.WEB,
                                    title = json?.optString("title").orEmpty().ifBlank { "页面资源" },
                                    author = "",
                                    mediaUrls = urls.distinct(),
                                    kind = ExtractResult.KIND_IMAGE,
                                )
                            )
                        }
                    }
                }
            }
        }

        installResponseHook()
        // 「手动粘贴 / 云端回填」的登录态写在文件里，先补种进 CookieManager，WebView 请求才带得上
        CookieStore.seedBrowserCookies(this, platform.key)
        webView.loadUrl(pageUrl)
        // 超时保护
        scope.launch {
            val timeoutMs = when (platform) {
                Platform.X, Platform.YOUTUBE -> 25_000L
                else -> 60_000L
            }
            delay(timeoutMs)
            if (!finished) {
                LogStore.log("解析", "超时未取到数据")
                val hint = when (platform) {
                    Platform.X -> "X 现在需要登录才能读取推文：请先用电脑版下载，或在浏览器登录 X 后使用桌面端「刷新 Cookie」。"
                    Platform.YOUTUBE -> "YouTube 现在需要浏览器令牌，手机端暂时无法直接解析：请用电脑版下载（桌面端已支持 YouTube 4K）。"
                    else -> "解析超时（可能需要登录或网络受限）"
                }
                finishWithError(hint)
            }
        }
        // 轮询页面注入钩子捕获到的响应（抖音等 SPA 用）
        scope.launch {
            repeat(55) {
                delay(1000)
                if (finished) return@launch
                webView.evaluateJavascript(
                    "JSON.stringify({data: window.__VG_DATA__ || {}, seen: window.__VG_SEEN__ || [], stats: window.__VG_STATS__ || {}})"
                ) { value ->
                    val decoded = decodeJsString(value)
                    if (!decoded.isNullOrBlank()) {
                        runCatching { org.json.JSONObject(decoded) }.getOrNull()?.let { payload ->
                            val seen = payload.optJSONArray("seen")
                            if (seen != null && seen.length() != lastSeenCount) {
                                lastSeenCount = seen.length()
                                val summary = (0 until seen.length()).joinToString(" | ") {
                                    seen.optString(it).substringAfter("/web/").take(28)
                                }
                                Log.i(TAG, "seen(${seen.length()}): $summary")
                            }
                            val data = payload.optJSONObject("data") ?: return@let
                            payload.optJSONObject("stats")?.let { stats ->
                                if (stats.optInt("xhrLoad") > 0 || stats.optInt("fetchResp") > 0 || stats.optString("err").isNotEmpty()) {
                                    Log.i(TAG, "stats: $stats")
                                }
                            }
                            for (key in data.keys()) {
                                val body = data.optString(key)
                                Log.i(TAG, "captured[$key] len=${body.length} head=${body.take(90)}")
                                if (body.isNotBlank()) {
                                    parsePayload(key, body)?.let { finishWithResult(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 在页面文档开始执行前注入钩子，直接读取页面自身请求到的响应体（不受 CSP 限制） */
    private fun installResponseHook() {
        val markers = when (platform) {
            // 普通视频请求 /aweme/v1/web/aweme/detail/，图文（note）请求 .../note/detail/。
            // 两个都挂钩子：早期只挂 aweme/detail，图文作品的数据响应一直抓不到。
            Platform.DOUYIN -> listOf("aweme/detail", "note/detail")
            Platform.KUAISHOU -> listOf("chenzhongtech.com/fw/", "kuaishou.com/short-video")
            Platform.XIAOHONGSHU -> listOf("/api/sns/web/")
            Platform.X -> listOf("/graphql/")
            Platform.YOUTUBE -> listOf("youtubei/v1/player")
            // 视频号不走 WebView 嗅探：分享页只给封面，真实地址由 MainActivity 调腾讯元宝解析
            Platform.WECHAT_CHANNELS -> emptyList()
            // B 站 / 微博 / TikTok / Instagram / 通用：都走 yt-dlp，不需要 WebView 嗅探
            Platform.BILIBILI, Platform.WEIBO, Platform.TIKTOK, Platform.INSTAGRAM -> emptyList()
            Platform.XIGUA, Platform.WEISHI, Platform.HAOKAN, Platform.REDDIT,
            Platform.VIMEO, Platform.TWITCH, Platform.NETEASE, Platform.QQMUSIC -> emptyList()
            Platform.GENERIC, Platform.WEB, Platform.FILE, Platform.UNKNOWN -> emptyList()
        }
        if (markers.isEmpty()) return
        val markerLiteral = markers.joinToString(", ") { "\"$it\"" }
        // 小红书 App 分享页把笔记数据直接内嵌在文档里（不走 /api/sns/web/ 接口）：
        // 定期把内嵌状态喂给同一套解析逻辑（保留 window.__INITIAL_STATE__ 标记供解析器识别）
        val ssrCapture = if (platform == Platform.XIAOHONGSHU) """
              try {
                function vgFindNote(node, depth){
                  if (!node || depth > 8) return null;
                  if (typeof node !== 'object') return null;
                  try {
                    if (node.imageList && node.imageList.length) return node;
                    if (node.video && (node.title || node.desc || node.noteId)) return node;
                  } catch(e){}
                  for (var key in node){
                    try {
                      var found = vgFindNote(node[key], depth + 1);
                      if (found) return found;
                    } catch(e){}
                  }
                  return null;
                }
                setInterval(function(){
                  try {
                    var key = '/api/sns/web/';
                    if (window.__VG_DATA__[key]) return;
                    var state = window.__INITIAL_STATE__ || window.__INITIAL_SSR_STATE__;
                    if (!state) return;
                    var note = vgFindNote(state.noteData, 0) || vgFindNote(state, 0);
                    if (!note) return;
                    if (!note.title && !note.desc) {
                      try {
                        var meta = document.querySelector('meta[property="og:title"]');
                        var text = meta ? (meta.content || '') : (document.title || '');
                        if (text) note.title = text;
                      } catch(e){}
                    }
                    var payload = JSON.stringify(note);
                    if (payload && payload.length > 80 && payload.length < 900000) {
                      window.__VG_DATA__[key] = payload;
                    }
                  } catch(e){}
                }, 1500);
              } catch(e){}
        """.trimIndent() else ""
        val script = """
            (function(){
              if (window.__VG_HOOKED__) return;
              window.__VG_HOOKED__ = true;
              window.__VG_DATA__ = {};
              window.__VG_SEEN__ = [];
              window.__VG_STATS__ = { xhrLoad: 0, fetchResp: 0, reports: 0, lastLen: -9, lastType: '', err: '' };
              var TARGETS = [$markerLiteral];
              function remember(url){ try { var u = String(url||''); if (u.indexOf('aweme') >= 0 || u.indexOf('sns/web') >= 0 || u.indexOf('/fw/') >= 0 || u.indexOf('youtube') >= 0 || u.indexOf('/player') >= 0 || u.indexOf('graphql') >= 0) { window.__VG_SEEN__.push(u.slice(0,150)); if (window.__VG_SEEN__.length > 30) window.__VG_SEEN__.shift(); } } catch(e){} }
              function match(url){ try { url = String(url||''); } catch(e) { return null; }
                for (var i=0;i<TARGETS.length;i++){ if (url.indexOf(TARGETS[i]) >= 0) return TARGETS[i]; }
                return null;
              }
              function report(url, text){ try { var key = match(url); if (key && !window.__VG_DATA__[key]) window.__VG_DATA__[key] = text; } catch(e){} }
              function readBody(xhr){
                try { if (typeof xhr.responseText === 'string' && xhr.responseText.length > 0) return xhr.responseText; } catch(e){}
                try {
                  var r = xhr.response;
                  if (typeof r === 'string' && r.length > 0) return r;
                  if (r instanceof ArrayBuffer) return new TextDecoder('utf-8').decode(new Uint8Array(r));
                  if (r && r.buffer) return new TextDecoder('utf-8').decode(new Uint8Array(r.buffer));
                } catch(e){}
                return null;
              }
              try {
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url){ try { this.__vgUrl = url; remember(url); } catch(e){} return origOpen.apply(this, arguments); };
                XMLHttpRequest.prototype.send = function(){
                  var self = this;
                  try { this.addEventListener('load', function(){
                    try {
                      window.__VG_STATS__.xhrLoad++;
                      window.__VG_STATS__.lastType = Object.prototype.toString.call(self.response);
                      var body = readBody(self);
                      window.__VG_STATS__.lastLen = body ? body.length : -1;
                      if (body) { report(self.__vgUrl, body); window.__VG_STATS__.reports++; }
                    } catch(e) { window.__VG_STATS__.err = String(e); }
                  }); } catch(e){}
                  return origSend.apply(this, arguments);
                };
              } catch(e){}
              try {
                var origFetch = window.fetch;
                if (origFetch) {
                  window.fetch = function(){
                    var args = arguments;
                    var url = '';
                    try { url = (args[0] && args[0].url) ? args[0].url : String(args[0]||''); } catch(e){}
                    remember(url);
                    return origFetch.apply(this, args).then(function(resp){
                      try { if (match(url)) { resp.clone().text().then(function(text){ window.__VG_STATS__.fetchResp++; report(url, text); window.__VG_STATS__.reports++; }); } } catch(e){}
                      return resp;
                    });
                  };
                }
              } catch(e){}
              $ssrCapture
            })();
        """.trimIndent()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            Log.i(TAG, "已注入响应钩子（markers=$markers）")
        } else {
            Log.w(TAG, "当前 WebView 不支持 DOCUMENT_START_SCRIPT，退回拦截模式")
        }
    }

    private var lastSeenCount = 0
    private val captured = mutableSetOf<String>()
    private val finishing = AtomicBoolean(false)
    /** 抖音的 DOM 轮询抓取是否已启动（onPageFinished 可能多次触发，避免多轮轮询并发） */
    private val domDumpStarted = AtomicBoolean(false)

    /**
     * 抓取渲染后的 DOM 并解析。
     * 抖音的图文（note）页数据由 SPA 异步请求加载，onPageFinished 时通常还没到——
     * 因此找不到视频地址时轮询重试（每 1.5 秒、最多约 10 秒），直到拿到数据或放弃。
     */
    private fun dumpDomWithRetry(view: WebView, url: String, attempt: Int, lastCount: Int = -1) {
        view.evaluateJavascript("document.documentElement.outerHTML") { html ->
            val decoded = decodeJsString(html) ?: return@evaluateJavascript
            val domUrls = if (platform == Platform.DOUYIN) Extractor.douyinDomUrls(decoded) else emptyList()
            Log.i(
                TAG,
                "dom dump(第${attempt + 1}次) ${decoded.length} chars, douyinUrls=${domUrls.size} " +
                    domUrls.take(2).joinToString { it.take(70) },
            )
            if (domUrls.isNotEmpty()) {
                if (platform == Platform.DOUYIN) {
                    // 「多视频图文」：页面上渲染出多组不同资源时，按条目逐个下载（组内是镜像候选）
                    val groups = Extractor.douyinDomEntryGroups(decoded)
                    val multi = groups.size > 1
                    Log.i(TAG, "抖音 DOM 命中：${groups.size} 个条目（多条目=$multi）")
                    // 页面是渐进渲染的（先出封面/第一条，随后补齐其余条目）：
                    // 条目数还在变化时再等一轮，稳定了才完成，避免只下到第一条
                    if (groups.size != lastCount && attempt < 6 && !finishing.get()) {
                        view.postDelayed({ dumpDomWithRetry(view, url, attempt + 1, groups.size) }, 1500)
                        return@evaluateJavascript
                    }
                    view.evaluateJavascript("document.title") { titleValue ->
                        val pageTitle = decodeJsString(titleValue).orEmpty()
                            .removeSuffix(" - 抖音").trim()
                        val (metaTitle, author) = Extractor.douyinDomMeta(decoded)
                        val title = metaTitle.ifBlank { pageTitle }
                        Log.i(TAG, "dom meta title=$title author=$author")
                        if (multi) {
                            finishWithResult(
                                ExtractResult(
                                    Platform.DOUYIN, title, author,
                                    groups.map { it.first() },
                                    ExtractResult.KIND_VIDEO,
                                    mediaKinds = groups.map { ExtractResult.KIND_VIDEO },
                                )
                            )
                        } else {
                            finishWithResult(
                                ExtractResult(Platform.DOUYIN, title, author, domUrls.take(3), ExtractResult.KIND_VIDEO)
                            )
                        }
                    }
                    return@evaluateJavascript
                }
            }
            if (platform == Platform.DOUYIN) {
                // 图文作品：先等页面自身的数据加载（轮询），最后才退回 DOM 里的图片
                if (attempt < 6 && !finishing.get()) {
                    view.postDelayed({ dumpDomWithRetry(view, url, attempt + 1) }, 1500)
                    return@evaluateJavascript
                }
                val domImages = Extractor.douyinDomImages(decoded)
                Log.i(TAG, "dom images=${domImages.size} ${domImages.take(2).map { it.take(70) }}")
                if (domImages.isNotEmpty()) {
                    val (title, author) = Extractor.douyinDomMeta(decoded)
                    finishWithResult(
                        ExtractResult(Platform.DOUYIN, title, author, domImages.distinct(), ExtractResult.KIND_IMAGE)
                    )
                } else {
                    parsePayload(url, decoded)?.let { finishWithResult(it) }
                }
                return@evaluateJavascript
            }
            parsePayload(url, decoded)?.let { finishWithResult(it) }
        }
    }

    /** 只代理真正含有作品数据的请求，避免破坏页面自身的 JS/CSS/图片加载 */
    private fun isTarget(url: String, isMainFrame: Boolean): Boolean = when (platform) {
        Platform.DOUYIN -> url.contains("/aweme/v1/web/aweme/detail/") || url.contains("/aweme/v1/web/note/detail/")
        Platform.KUAISHOU -> isMainFrame &&
            (url.contains("chenzhongtech.com/fw/") || url.contains("kuaishou.com/short-video") || url.contains("kuaishou.com/fw/"))
        Platform.XIAOHONGSHU -> url.contains("/api/sns/web/") || (isMainFrame && url.contains("xiaohongshu.com"))
        Platform.X -> url.contains("/graphql/")
        Platform.YOUTUBE -> url.contains("youtubei/v1/player")
        Platform.WECHAT_CHANNELS -> false
        Platform.BILIBILI, Platform.WEIBO, Platform.TIKTOK, Platform.INSTAGRAM -> false
        // 第三梯队 + 音乐平台同样交给内置 yt-dlp
        Platform.XIGUA, Platform.WEISHI, Platform.HAOKAN, Platform.REDDIT,
        Platform.VIMEO, Platform.TWITCH, Platform.NETEASE, Platform.QQMUSIC -> false
        Platform.GENERIC, Platform.WEB, Platform.FILE, Platform.UNKNOWN -> false
    }

    private fun mimeOf(text: String, url: String): String = when {
        text.trimStart().startsWith("{") || text.trimStart().startsWith("[") -> "application/json"
        url.endsWith(".js") -> "application/javascript"
        else -> "text/html"
    }

    private fun parsePayload(url: String, payload: String): ExtractResult? = when (platform) {
        Platform.DOUYIN -> if (payload.trimStart().startsWith("{")) Extractor.parseDouyin(payload) else null
        Platform.KUAISHOU -> Extractor.parseKuaishou(payload)
            ?: Extractor.fallbackMp4Urls(payload).takeIf { it.isNotEmpty() }?.let {
                ExtractResult(Platform.KUAISHOU, "", "", it.distinct(), ExtractResult.KIND_VIDEO)
            }
        Platform.XIAOHONGSHU -> Extractor.parseXiaohongshu(payload)
            ?: Extractor.fallbackMp4Urls(payload).takeIf { it.isNotEmpty() }?.let {
                ExtractResult(Platform.XIAOHONGSHU, "", "", it.distinct(), ExtractResult.KIND_VIDEO)
            }
        Platform.X -> if (payload.trimStart().startsWith("{")) Extractor.parseX(payload) else null
        Platform.YOUTUBE -> if (payload.trimStart().startsWith("{")) Extractor.parseYouTube(payload) else null
        Platform.WECHAT_CHANNELS -> null
        Platform.BILIBILI, Platform.WEIBO, Platform.TIKTOK, Platform.INSTAGRAM -> null
        Platform.XIGUA, Platform.WEISHI, Platform.HAOKAN, Platform.REDDIT,
        Platform.VIMEO, Platform.TWITCH, Platform.NETEASE, Platform.QQMUSIC -> null
        Platform.GENERIC, Platform.WEB, Platform.FILE, Platform.UNKNOWN -> null
    }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? = try {
        val builder = Request.Builder().url(url).get()
        val skip = setOf("host", "content-length", "connection", "accept-encoding", "cookie")
        headers.forEach { (key, value) ->
            if (key.lowercase() !in skip) {
                builder.header(key, value)
            }
        }
        if (headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", UA)
        CookieManager.getInstance().getCookie(url)?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            response.body?.bytes()
        }
    } catch (error: Exception) {
        Log.w(TAG, "fetch failed: $url (${error.message})")
        null
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            org.json.JSONArray("[$raw]").getString(0)
        }.getOrNull()
    }

    private fun finishWithResult(result: ExtractResult) {
        if (!finishing.compareAndSet(false, true)) return
        finished = true
        val (hintTitle, hintAuthor) = UrlUtils.shareHints(rawHint)
        // 分享文本来自用户本人分享，优先级高于页面里可能匹配错的对象
        val merged = result.copy(
            title = hintTitle.ifBlank { result.title },
            author = hintAuthor.ifBlank { result.author },
        )
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_RESULT, merged.toJson()),
        )
        finish()
    }

    private fun finishWithError(message: String) {
        if (!finishing.compareAndSet(false, true)) return
        finished = true
        LogStore.log("解析", "结束: $message")
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_ERROR, message),
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (current?.get() === this) current = null
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
    }
}
