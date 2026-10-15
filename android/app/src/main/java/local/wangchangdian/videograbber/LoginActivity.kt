package local.wangchangdian.videograbber

import android.app.Dialog
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 应用内登录窗口：在 WebView 里登录 X / YouTube，再导出本机 Cookie。
 *
 * 踩过的坑与对应处理：
 * - Android 15+ 强制边到边 → 用 Scaffold + TopAppBar，否则顶栏被状态栏压住
 * - WebView 默认 UA 带 `; wv)`，Google / X 会判成「内嵌的不安全浏览器」→ 去掉该标记
 * - X 的 Google 登录是 window.open 弹窗 → 用真正的第二个 WebView（放在 Dialog 里）承载，
 *   不能把弹窗地址偷到主 WebView（会破坏页面状态，登录完成后卡在加载骨架）
 * - 登录态靠轮询 CookieManager 判断，页面上同时显示当前网址，卡住时能看出卡在哪
 */

/** 页面自检结论：unknown = 判不出来（老行为兜底），in = 页面确实已登录，out = 页面还在要求登录 */
private enum class PageAuth { UNKNOWN, IN, OUT }

/** 导出 Cookie 的触发方式，决定给用户什么提示 */
private enum class ExportMode { MANUAL, AUTO, AUTO_UNVERIFIED }

/** 页面自检的延迟与总等待：SPA 首屏渲染慢，给足时间再判断 */
private const val PAGE_PROBE_DELAY_MS = 1500L
private const val PAGE_PROBE_TIMEOUT_MS = 6000L

/** 抖音登录页必须用桌面 UA，否则只给「下载抖音 App」的墙 */
private const val DOUYIN_DESKTOP_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * 抖音登录态校验（document-start 注入）。
 *
 * 抖音的 /aweme/v1/web/ 系列接口要求请求带 a_bogus 签名：同一份已登录 Cookie，
 * 页面自己的签名请求返回 status_code 0（带 user），我们手工拼的请求一律被判「用户未登录」。
 * 所以这里不再自己发请求，而是在页面加载前挂一层监听，直接读页面自己请求的响应：
 *   window.__VG_DOUYIN_USER__ —— 见到过「已登录」的响应（只置位、不清除，结果最稳）
 *   window.__VG_DOUYIN_LAST__ —— 最近一次「我是谁」接口的 status_code
 * 匿名访客拿不到签名请求的 0 响应；未登录时抖音会把 /user/self 重定向回首页，也不会发这个请求。
 */
private const val DOUYIN_RECORD_JS = """
    (function(){
      try{
        if(window.__VG_DOUYIN_PATCHED__)return;
        window.__VG_DOUYIN_PATCHED__=1;
        window.__VG_DOUYIN_USER__=null;
        window.__VG_DOUYIN_LAST__=null;
        var isSelf=function(u){return !!u&&String(u).indexOf('/aweme/v1/web/user/profile/self/')>=0;};
        var record=function(u,t){
          try{
            // 只认页面自己发出的带签名请求：我们手工发的请求没有 a_bogus，服务端一律判未登录
            if(String(u).indexOf('a_bogus=')<0)return;
            var d=JSON.parse(t);
            var user=d&&d.user;
            if(d&&d.status_code===0&&user&&(user.nickname||user.uid)){
              window.__VG_DOUYIN_USER__={nickname:user.nickname||'',uid:String(user.uid||'')};
            }
            if(isSelf(u)){
              window.__VG_DOUYIN_LAST__={status_code:(d&&typeof d.status_code==='number')?d.status_code:-1,msg:(d&&d.status_msg)||''};
            }
          }catch(e){}
        };
        var of=window.fetch;
        if(of){
          window.fetch=function(input,init){
            var url=(typeof input==='string')?input:((input&&input.url)||'');
            var p=of.apply(this,arguments);
            try{
              p.then(function(r){return r.clone().text();}).then(function(t){record(url,t);}).catch(function(){});
            }catch(e){}
            return p;
          };
        }
        var xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open=function(m,u){this.__vgUrl=u;return xo.apply(this,arguments);};
        XMLHttpRequest.prototype.send=function(){
          var x=this;
          try{
            if(isSelf(x.__vgUrl)){
              x.addEventListener('load',function(){record(x.__vgUrl,x.responseText);});
            }
          }catch(e){}
          return xs.apply(this,arguments);
        };
      }catch(e){}
    })();
"""

class LoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_KEY = "key"
        private const val TAG = "VGLogin"
        private const val AUTO_FINISH_SECONDS = 3
    }

    private var key = "x"
    private var webView: WebView? = null
    private var popupDialog: Dialog? = null
    private val currentUrl = mutableStateOf("")
    private val pageAuth = mutableStateOf(PageAuth.UNKNOWN)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var probeAttempts = 0
    private var lastProbeVerdict: PageAuth? = null

    private val platform: CookieStore.Platform
        get() = CookieStore.platform(key) ?: CookieStore.PLATFORMS.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = intent.getStringExtra(EXTRA_KEY) ?: "x"
        LogStore.log("登录", "打开${platform.label}登录窗口")

        setContent {
            VideoGrabberTheme(AppState.theme.value) {
                LoginScreen(
                    label = platform.label,
                    currentUrl = currentUrl.value,
                    autoFinishSeconds = AUTO_FINISH_SECONDS,
                    pageAuth = { pageAuth.value },
                    readCookies = ::readCookieHeader,
                    hasMarkers = ::hasMarkers,
                    isDouyin = key == "douyin",
                    douyinConfirmedOut = { key == "douyin" && douyinServerVerdict.value == false },
                    douyinVerified = { key == "douyin" && douyinServerVerdict.value == true },
                    douyinNickname = { douyinNickname.value },
                    onExport = { mode -> exportCookie(mode) },
                    onClose = { finish() },
                    onOpenBrowser = ::openInBrowser,
                    onReloadLogin = ::reloadLoginPage,
                    onExpired = ::onBrowserSessionExpired,
                    storedCookieExists = { CookieStore.file(this, key).exists() },
                    refreshAuth = ::refreshPageAuth,
                    webViewContent = { AndroidWebView(platform.loginUrl) },
                )
            }
        }
    }

    /** 统一的 WebView 配置（主窗口和登录弹窗共用） */
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // 关键：默认 UA 里的 `; wv` 会被 Google / X 判成内嵌浏览器，去掉它
            userAgentString = userAgentString?.replace("; wv", "")?.replace(" Version/4.0", "")
            // TikTok / Instagram 对手机 UA 会一直往「打开 App」上引导（跳自定义协议 / 弹窗遮挡）；
            // B 站则相反：手机端会话在风控里拿不到 4K（实测只有 1080P），桌面端会话可以。
            // 统一换成桌面 UA，登录流程稳定、会话也按桌面端下发。
            // 抖音同理：手机 UA 只会给「下载抖音 App」，桌面 UA 才有正常登录入口；
            // 快手 / 小红书（Web 版）也是桌面 UA 才给正常的登录入口，手机 UA 只推「打开 App」
            if (key == "tiktok" || key == "instagram" || key == "bilibili" || key == "douyin" ||
                key == "kuaishou" || key == "xiaohongshu"
            ) {
                userAgentString =
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
            // B 站：登录态抓取时就把「要不要给 4K」的标记写好（4048 = 4K/HDR/杜比）
            if (key == "bilibili") {
                setCookie("https://www.bilibili.com", "CURRENT_FNVAL=4048; path=/; domain=.bilibili.com")
                setCookie("https://www.bilibili.com", "CURRENT_QUALITY=120; path=/; domain=.bilibili.com")
            }
        }
        // 抖音：登录态只能靠"页面自己的签名请求"判断，监控脚本要在文档开始处注入（首屏请求很快）
        if (key == "douyin") installDouyinRecorder(view)
    }

    /**
     * 注入抖音登录检测脚本（只监听、不改页面行为）。
     * document-start 是首选——页面加载时发出的「我是谁」签名请求也能被捕获；
     * 内核太旧不支持时退回 onPageFinished 注入（会漏掉首屏那次，等页面后续刷新）。
     */
    private fun installDouyinRecorder(view: WebView) {
        val supported = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
            .getOrDefault(false)
        if (!supported) {
            LogStore.log("登录", "当前 WebView 内核不支持文档加载前注入，抖音登录检测改为页面加载后监听")
            return
        }
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, DOUYIN_RECORD_JS, setOf("*"))
        }.onFailure {
            LogStore.log("登录", "抖音登录检测注入失败，改为页面加载后监听：${it.message}")
        }
    }

    /** 当前浏览器里该平台的全部 Cookie（`k=v; k2=v2`） */
    private fun readCookieHeader(): String {
        val manager = CookieManager.getInstance()
        return detectionUrls().mapNotNull { manager.getCookie(it) }.joinToString("; ")
    }

    /** 关键 Cookie 名是否命中（只代表"浏览器里存着登录 Cookie"，不代表服务端还没把会话踢下线） */
    private fun hasMarkers(cookie: String): Boolean {
        // 抖音：匿名访客也会被下发 sessionid，名字命中不代表登录 —— 必须问服务端
        if (key == "douyin") return douyinLoginDetected()
        if (cookie.isBlank()) return false
        return if (platform.markers.isEmpty()) {
            cookie.contains("=")
        } else {
            platform.markers.any { cookie.contains("$it=") }
        }
    }

    private fun detectionUrls(): List<String> = when (key) {
        "youtube" -> listOf("https://www.youtube.com/", "https://m.youtube.com/", "https://accounts.google.com/")
        "x" -> listOf("https://x.com/", "https://twitter.com/", "https://api.x.com/", "https://mobile.x.com/")
        // Cookie 存在 www 域下（domain=.xiaohongshu.com），只问根域拿不全
        "xiaohongshu" -> listOf("https://www.xiaohongshu.com/", "https://xiaohongshu.com/")
        else -> listOf("https://" + platform.domains.first().trimStart('.'))
    }

    /** 抖音：页面自己的签名请求给出的服务端结论（null = 还没拿到）；用 State 让界面跟着刷新 */
    private val douyinServerVerdict = mutableStateOf<Boolean?>(null)
    private var douyinLoggedOnce = false
    /** 服务端确认登录后页面返回的抖音昵称（只用于界面提示） */
    private val douyinNickname = mutableStateOf("")

    /**
     * 抖音登录态：只有页面自己发出的「带签名请求」的响应才可信
     * （同一份已登录 Cookie，手工拼的请求一律被判未登录）。
     * 监听脚本在文档开始处注入，这里只读结果；读不到时退回本机登录标记。
     */
    private fun douyinLoginDetected(): Boolean {
        webView?.let { view ->
            runCatching {
                view.evaluateJavascript(
                    "(function(){try{return JSON.stringify({user:window.__VG_DOUYIN_USER__||null,last:window.__VG_DOUYIN_LAST__||null})}catch(e){return ''}})()",
                ) { raw ->
                    val payload = decodeJsString(raw) ?: return@evaluateJavascript
                    if (payload.isBlank()) return@evaluateJavascript
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return@evaluateJavascript
                    val user = obj.optJSONObject("user")
                    val last = obj.optJSONObject("last")
                    when {
                        user != null -> {
                            if (!douyinLoggedOnce) {
                                douyinLoggedOnce = true
                                val nickname = user.optString("nickname")
                                Log.i(TAG, "douyin page signed response -> in ($nickname)")
                                LogStore.log("登录", "抖音页面自检：已登录（$nickname）")
                            }
                            douyinNickname.value = user.optString("nickname")
                            douyinServerVerdict.value = true
                        }
                        last != null -> {
                            if (douyinServerVerdict.value != false) {
                                Log.i(TAG, "douyin server -> status=${last.optInt("status_code")} ${last.optString("msg")}")
                            }
                            douyinServerVerdict.value = false
                        }
                    }
                }
            }
        }
        if (douyinServerVerdict.value == true) return true
        if (douyinServerVerdict.value == false) return false
        // 服务端还没给结论（例如登录窗口刚打开、页面还没发出请求）：退回本机登录标记
        return hasDouyinLoginMarkers()
    }

    /**
     * 本机 Cookie 里的抖音登录标记：sid_tt 由服务端在登录成功后下发（HttpOnly）；
     * 匿名访客只有 ttwid / passport_csrf_token 这类设备 Cookie —— 早期版本把 sessionid 当登录标记，
     * 结果匿名访客也被判成"已登录"，这里改成只认登录后才有的字段。
     */
    private fun hasDouyinLoginMarkers(): Boolean {
        val cookie = readCookieHeader()
        return cookie.contains("sid_tt=") || cookie.contains("login_time=")
    }

    /** evaluateJavascript 的返回值是 JSON 字符串字面量（带引号、转义），先解出原文 */
    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    /**
     * 页面状态自检：浏览器里"有 Cookie"不等于"登录还有效"。
     * 平台会把过期的会话清掉，但 Cookie 往往还留在本机，光看名字会误判成"已登录"，
     * 于是把已经失效的 Cookie 又回填一遍。这里直接问页面：你现在是登录态还是登录页？
     */
    private fun scheduleAuthProbe(view: WebView, delayMs: Long = PAGE_PROBE_DELAY_MS) {
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            view.evaluateJavascript(authProbeScript()) { raw ->
                val verdict = when (raw?.trim()?.trim('"')) {
                    "in" -> PageAuth.IN
                    "out" -> PageAuth.OUT
                    else -> PageAuth.UNKNOWN
                }
                pageAuth.value = verdict
                if (verdict != lastProbeVerdict) {
                    lastProbeVerdict = verdict
                    val text = when (verdict) {
                        PageAuth.IN -> "页面已是登录状态"
                        PageAuth.OUT -> "页面要求重新登录（旧的登录态已过期）"
                        PageAuth.UNKNOWN -> "无法判断页面状态"
                    }
                    LogStore.log("登录", "${platform.label} 页面自检：$text")
                }
                // SPA 首屏可能还没渲染出登录框/头像，判不出来时再补测两次
                if (verdict == PageAuth.UNKNOWN && probeAttempts < 2) {
                    probeAttempts += 1
                    scheduleAuthProbe(view, 2500L)
                }
            }
        }, delayMs)
    }

    /** 各平台"是不是登录页 / 是不是登录态"的判断脚本，返回 'in' / 'out' / 'unknown' */
    private fun authProbeScript(): String = when (key) {
        "wechat_channels" -> """
            (function(){try{
              var t=(document.body&&document.body.innerText)||'';
              var form=/获取验证码|其他登录方式|手机号登录|扫码登录/.test(t);
              var chat=/发消息/.test(t)||!!document.querySelector('textarea,[contenteditable="true"]');
              if(form&&!chat)return 'out';
              if(chat)return 'in';
              return 'unknown';
            }catch(e){return 'unknown'}})()
        """.trimIndent()

        "x" -> """
            (function(){try{
              if(document.querySelector('[data-testid="SideNav_AccountSwitcher_Button"],[data-testid="AppTabBar_Profile_Link"]'))return 'in';
              var t=(document.body&&document.body.innerText)||'';
              if(/Sign in to X|忘记密码|创建账号|使用 Google 登录|手机号、电子邮件地址或用户名/.test(t))return 'out';
              return 'unknown';
            }catch(e){return 'unknown'}})()
        """.trimIndent()

        "youtube" -> """
            (function(){try{
              if(document.querySelector('#avatar-btn,ytd-topbar-menu-button-renderer #avatar-btn,button[aria-label*="Account"],button[aria-label*="账号"]'))return 'in';
              var t=(document.body&&document.body.innerText)||'';
              if(/登录以|Sign in to/.test(t)||document.querySelector('a[href*="ServiceLogin"],a[href^="/signin"]'))return 'out';
              return 'unknown';
            }catch(e){return 'unknown'}})()
        """.trimIndent()

        // 小红书：网页版未登录时会弹扫码/手机号登录框；已登录时左侧栏有「发布笔记」入口。
        // 注意 web_session 这种 Cookie 匿名访客也会拿到，光看 Cookie 名会把游客判成已登录，
        // 所以登录窗口必须靠页面自检来判断。
        "xiaohongshu" -> """
            (function(){try{
              var t=(document.body&&document.body.innerText)||'';
              if(/扫码登录|新用户可直接登录|输入手机号|登录后推荐更懂你的笔记/.test(t))return 'out';
              if(/发布笔记|创作中心/.test(t))return 'in';
              return 'unknown';
            }catch(e){return 'unknown'}})()
        """.trimIndent()

        else -> "(function(){return 'unknown'})()"
    }

    /**
     * 页面在要求重新登录 → 说明内置浏览器里的会话在服务端已经失效。
     * 本机保存的那份 Cookie 大概率也不能用了，标成「已失效」，设置页会红字提示重新登录。
     */
    private fun onBrowserSessionExpired() {
        if (!CookieStore.file(this, key).exists()) return
        CookieStore.markExpired(this, key)
        AppState.refreshLoginStates()
    }

    /** 页面状态重测（登录页里往往不触发页面跳转，得主动问一次才知道用户登好了没有） */
    private fun refreshPageAuth() {
        val view = webView ?: return
        if (isFinishing || isDestroyed) return
        scheduleAuthProbe(view, 0L)
    }

    /**
     * 拦下「唤起 App」的自定义协议（tiktok://、snssdk1233://、instagram://、intent:// …）。
     *
     * 不拦的话 WebView 会直接跳到这个协议上并显示
     * `net::ERR_UNKNOWN_URL_SCHEME` 错误页，把本来正常的登录页顶掉——
     * TikTok 就是打开首页后自动尝试跳 App，结果用户看到「网页无法打开」。
     */
    private fun blockAppScheme(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        val allowed = scheme == null || scheme in setOf("http", "https", "about", "data", "javascript", "blob")
        if (!allowed) {
            LogStore.log("登录", "已拦下 App 跳转协议 ${scheme}://（保持登录页不跳走）")
        }
        return !allowed
    }

    /**
     * 导出 Cookie 到本机文件，返回条数（0 表示没导出到东西）。
     *
     * AUTO 表示"打开页面时浏览器里就已经登录、且页面自检也是登录态"——直接回填，用户不用再走一遍验证码登录；
     * AUTO_UNVERIFIED 表示"有登录 Cookie 但页面状态判不出来"——回填但同时提示可能已失效。
     */
    private fun exportCookie(mode: ExportMode = ExportMode.MANUAL): Int {
        val count = CookieExporter.export(this, key)
        // 用统一的状态判断（看关键 Cookie 在不在），而不是"导出条数大于 0"——
        // 浏览器里只有匿名设备 Cookie 时也会导出成功，但那并不代表登录了
        val loggedIn = CookieStore.status(this, key).loggedIn
        if (loggedIn) {
            CookieStore.clearMark(this, key)
            CookieStore.setSource(key, "本机登录")
        }
        AppState.loginResult.value = when {
            loggedIn && mode == ExportMode.AUTO ->
                "${platform.label} 已使用浏览器中的登录状态（$count 条 Cookie），无需重新登录"
            loggedIn && mode == ExportMode.AUTO_UNVERIFIED ->
                "${platform.label} 已回填浏览器里的登录状态（$count 条 Cookie）；如果下载时提示登录失效，请在登录窗口里重新登录"
            loggedIn -> "${platform.label} 登录成功，已保存 $count 条 Cookie"
            count > 0 -> "${platform.label} 没有检测到有效的登录态：页面可能还在要求重新登录，请登录成功后再点「完成」"
            else -> "${platform.label} 没有保存到 Cookie，请确认已在页面里登录成功"
        }
        AppState.refreshLoginStates()
        return count
    }

    /** 兜底：用系统浏览器登录（内嵌 WebView 被平台拦截时使用） */
    private fun openInBrowser() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(platform.loginUrl)))
            AppState.loginResult.value =
                "已在系统浏览器打开${platform.label}。注意：安卓不允许应用读取浏览器的 Cookie，" +
                    "登录后请复制 Cookie，回到「设置 → Cookie 管理 → 手动粘贴」保存；" +
                    "直接用内置窗口登录则可以自动保存。"
        }.onFailure {
            AppState.loginResult.value = "无法打开浏览器：${it.message}"
        }
        finish()
    }

    /** 页面被第三方登录（Google 等）拦成错误页时，一键回到本平台登录页 */
    private fun reloadLoginPage() {
        webView?.loadUrl(platform.loginUrl)
        AppState.loginResult.value = "${platform.label}：已回到登录页，请改用二维码 / 邮箱密码登录"
    }

    private fun closePopup() {
        popupDialog?.dismiss()
    }

    @Composable
    private fun AndroidWebView(url: String) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    configure(this)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return false
                            return blockAppScheme(target)
                        }

                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return blockAppScheme(url ?: return false)
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            currentUrl.value = url.orEmpty()
                            probeAttempts = 0
                            pageAuth.value = PageAuth.UNKNOWN
                            Log.i(TAG, "start $url")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            currentUrl.value = url.orEmpty()
                            Log.i(TAG, "finish $url")
                            // 兜底：内核不支持 document-start 时，页面加载完再挂监听（脚本自带防重复）
                            if (key == "douyin") runCatching { view?.evaluateJavascript(DOUYIN_RECORD_JS, null) }
                            view?.let { scheduleAuthProbe(it) }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            Log.i(TAG, "console ${msg?.message()} @ ${msg?.sourceId()}:${msg?.lineNumber()}")
                            return true
                        }

                        /** X / Google 的 OAuth 弹窗：用真正的第二个 WebView 承载 */
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?,
                        ): Boolean {
                            val host = view?.context ?: return false
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            val popup = WebView(host).apply {
                                configure(this)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val target = request?.url?.toString() ?: return false
                                        return blockAppScheme(target)
                                    }

                                    @Suppress("DEPRECATION")
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                        return blockAppScheme(url ?: return false)
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onCloseWindow(window: WebView?) {
                                        Log.i(TAG, "弹窗关闭")
                                        closePopup()
                                    }
                                }
                            }
                            popupDialog = Dialog(host, android.R.style.Theme_NoTitleBar_Fullscreen).apply {
                                setContentView(
                                    popup,
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                                setOnDismissListener {
                                    runCatching { popup.destroy() }
                                    popupDialog = null
                                }
                                show()
                            }
                            transport.webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    loadUrl(url)
                }.also { webView = it }
            },
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            CookieManager.getInstance().flush()
            popupDialog?.dismiss()
            webView?.stopLoading()
            webView?.destroy()
        }
        webView = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(
    label: String,
    currentUrl: String,
    autoFinishSeconds: Int,
    pageAuth: () -> PageAuth,
    readCookies: () -> String,
    hasMarkers: (String) -> Boolean,
    isDouyin: Boolean,
    douyinConfirmedOut: () -> Boolean,
    douyinVerified: () -> Boolean,
    douyinNickname: () -> String,
    onExport: (ExportMode) -> Int,
    onClose: () -> Unit,
    onOpenBrowser: () -> Unit,
    onReloadLogin: () -> Unit,
    onExpired: () -> Unit,
    storedCookieExists: () -> Boolean,
    refreshAuth: () -> Unit,
    webViewContent: @Composable () -> Unit,
) {
    var detected by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var showMissing by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf(false) }
    var autoFilled by remember { mutableStateOf(false) }
    var autoFillMode by remember { mutableStateOf(ExportMode.MANUAL) }
    var expired by remember { mutableStateOf(false) }
    var autoFinishArmed by remember { mutableStateOf(false) }

    fun finishUp() {
        if (closed) return
        closed = true
        // 打开时已自动回填、中途又没重新登录过 → 提示"用的是浏览器里的登录状态"，
        // 而不是让用户以为刚刚又登录了一次
        onExport(if (autoFilled && !autoFinishArmed) autoFillMode else ExportMode.MANUAL)
        onClose()
    }

    /** 浏览器里本来就登录着：只回填登录态，不关窗口（用户想换账号可以在这里退出） */
    fun finishUpAutoFill(verified: Boolean) {
        if (closed) return
        onExport(if (verified) ExportMode.AUTO else ExportMode.AUTO_UNVERIFIED)
    }

    LaunchedEffect(Unit) {
        // 先等页面自检：浏览器里"存着 Cookie"不等于"登录还有效"，
        // 有些平台的过期会话会把 Cookie 名留在本机，看着像已登录，其实服务端早把会话踢了。
        var waited = 0L
        while (!closed && pageAuth() == PageAuth.UNKNOWN && waited < PAGE_PROBE_TIMEOUT_MS) {
            delay(200)
            waited += 200
        }
        val baseline = readCookies()
        when {
            // 抖音：页面自己的签名请求返回「未登录」→ 浏览器里的会话确实失效了，别回填旧 Cookie
            isDouyin && douyinConfirmedOut() -> {
                expired = true
                LogStore.log("登录", "抖音服务端确认浏览器里的会话未登录，需要在窗口里重新登录")
                onExpired()
            }
            // 页面还在要求登录 → 浏览器里的会话过期了：不回填，等用户重新登录
            pageAuth() == PageAuth.OUT -> {
                expired = true
                LogStore.log("登录", "浏览器里的$label 登录态已过期，需要在窗口里重新登录")
                onExpired()
            }
            hasMarkers(baseline) -> {
                detected = true
                autoFilled = true
                val verified = pageAuth() == PageAuth.IN || (isDouyin && douyinVerified())
                autoFillMode = if (verified) ExportMode.AUTO else ExportMode.AUTO_UNVERIFIED
                // 页面确认是登录态才说"已登录"；判不出来时回填但提示可能已失效
                LogStore.log("登录", "浏览器里已是$label 登录状态，自动回填${if (verified) "" else "（页面状态未确认）"}")
                finishUpAutoFill(verified)
            }
        }
        var tick = 0
        while (!closed) {
            delay(2000)
            tick += 1
            // 过期后每 4 秒重测一次页面：登录页里通常不会触发 onPageFinished，
            // 不主动问的话，用户重新登录成功了我们也发现不了
            if (expired && tick % 2 == 0) refreshAuth()
            val now = readCookies()
            // 关键两条，缺一不可：
            // 1) Cookie 值真的变了——过期场景下浏览器里的旧 Cookie 还在，
            //    且元宝这类站点每次打开页面都会刷新几个 Cookie，光看"变了"会误判
            // 2) 页面自检不再是"要求登录"——确认这次是真的登进去了
            // 抖音的结论来自服务端（页面自己的签名请求），不要求 Cookie 必须变化——
            // 打开登录窗口时就已经登录的情况，Cookie 本来就不会变
            if (!detected && hasMarkers(now) && (now != baseline || isDouyin) &&
                pageAuth() != PageAuth.OUT && !(isDouyin && douyinConfirmedOut())
            ) {
                detected = true
                autoFinishArmed = true
                LogStore.log("登录", "已检测到 $label 登录态")
            }
        }
    }

    LaunchedEffect(autoFinishArmed) {
        if (!autoFinishArmed) return@LaunchedEffect
        for (left in autoFinishSeconds downTo 1) {
            countdown = left
            delay(1000)
        }
        countdown = 0
        finishUp()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("登录 $label", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onClose) { Text("取消") } },
                actions = {
                    Button(
                        onClick = { if (detected) finishUp() else showMissing = true },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("完成") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        // 所有子项固定存在（不用 if 增删），否则会让 WebView 被重建、页面反复重载
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (detected) {
                    Text(
                        when {
                            countdown > 0 -> "✅ 已检测到登录，$countdown 秒后自动返回"
                            isDouyin && autoFilled && douyinNickname().isNotBlank() ->
                                "✅ 浏览器里已是登录状态（抖音账号：${douyinNickname()}），已自动回填"
                            isDouyin && douyinNickname().isNotBlank() ->
                                "✅ 已检测到登录（抖音账号：${douyinNickname()}）"
                            autoFilled -> "✅ 浏览器里已是登录状态，已自动回填；要换账号请先在页面里退出登录"
                            else -> "✅ 已检测到登录"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D32),
                    )
                } else if (expired) {
                    Text(
                        if (storedCookieExists()) {
                            "⚠️ 浏览器里的登录态已过期，请在上方页面重新登录（登录成功后会自动返回）"
                        } else {
                            "⚠️ 还没有$label 的登录态：请在上方页面完成登录（登录成功后会自动返回）"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFFE65100),
                    )
                } else {
                    Text(
                        when {
                            !isDouyin -> "尚未检测到登录，请先在下方完成登录"
                            douyinConfirmedOut() ->
                                "抖音服务端确认当前未登录：请点页面右上角「登录」，用手机号+验证码或扫码完成登录"
                            else -> "正在确认抖音登录状态：请点页面右上角「登录」完成登录（登录成功后会自动返回）"
                        },
                        fontSize = 12.sp,
                        color = if (isDouyin) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Cookie 只保存在本机，不会上传",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = onReloadLogin) { Text("回到登录页", fontSize = 12.sp) }
                    TextButton(onClick = onOpenBrowser) { Text("用系统浏览器登录", fontSize = 12.sp) }
                }
            }
            // Google 明确禁止在应用内浏览器里登录 Google 账号（会提示「浏览器不安全」），
            // 这里直接告诉用户改用平台自己的登录方式，省得卡在错误页
            if (label == "TikTok" || label == "Instagram") {
                Text(
                    "提示：用 Google / Facebook 登录可能被平台拦下（提示「浏览器可能不安全」），" +
                        "请改用二维码登录（用手机 App 扫）或邮箱、手机号登录。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            // 显示当前网址：卡住时能一眼看出卡在哪一步
            Text(
                currentUrl.ifBlank { "正在打开…" },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
            webViewContent()
        }
    }

    if (showMissing) {
        AlertDialog(
            onDismissRequest = { showMissing = false },
            title = { Text("尚未检测到登录") },
            text = {
                Text(
                    if (expired) {
                        "浏览器里的登录态已经过期（页面正在要求重新登录）。现在保存只会把旧的、已失效的 Cookie 再存一遍，下载依然会失败。仍要保存吗？"
                    } else {
                        "现在保存可能导出 0 条 Cookie，下载依然会失败。仍要保存吗？"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { showMissing = false; finishUp() }) { Text("仍要保存") }
            },
            dismissButton = {
                TextButton(onClick = { showMissing = false }) { Text("继续登录") }
            },
        )
    }
}
