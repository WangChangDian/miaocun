package local.wangchangdian.videograbber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置页：保存目录（系统相册 / 自选文件夹）、主题、日志、关于 */
class SettingsActivity : ComponentActivity() {

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            val name = uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { uri.lastPathSegment } ?: uri.toString()
            AppState.setSaveTree(uri.toString(), name)
            LogStore.log("设置", "保存目录切换为: $name ($uri)")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeKey = AppState.theme.collectAsState()
            VideoGrabberTheme(themeKey.value) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        onBack = { finish() },
                        onPickFolder = { pickFolder.launch(null) },
                    )
                    // 设置页里点「检查更新」也能直接弹同一个更新卡片
                    UpdateDialogHost { text -> AppState.cloudMessage.value = text }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从登录页返回后立刻刷新登录态，避免界面还显示「未登录」
        AppState.refreshLoginStates()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit, onPickFolder: () -> Unit) {
    val context = LocalContext.current
    val themeKey = AppState.theme.collectAsState().value
    val saveTree = AppState.saveTree.collectAsState().value
    val cloudText = AppState.cloudMessage.collectAsState().value
    val feedbackResult = AppState.feedbackResult.collectAsState().value
    val loginStates = AppState.loginStates.collectAsState().value
    val loginResult = AppState.loginResult.collectAsState().value
    val maxHeight = AppState.maxHeight.collectAsState().value
    val wxchannelsH265 = AppState.wxchannelsH265.collectAsState().value
    val accountLoggedIn = AppState.accountLoggedIn.collectAsState().value
    val accountPhone = AppState.accountPhone.collectAsState().value
    val accountNotice = AppState.accountNotice.collectAsState().value
    val cloudCookies = AppState.cloudCookies.collectAsState().value
    var phoneInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    // 验证码倒计时（与 Mac / Windows 一致：60 秒内不让重复点）
    var smsCountdown by remember { mutableStateOf(0) }
    LaunchedEffect(smsCountdown) {
        if (smsCountdown > 0) {
            delay(1000)
            smsCountdown -= 1
        }
    }
    var manualPasteKey by remember { mutableStateOf<String?>(null) }
    var manualPasteText by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf(LogStore.snapshot()) }
    var refreshTick by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        Thread { CloudSync.perform(context) }.start()
    }
    // 登录结果只提示一会儿，避免一直挂在设置页上
    androidx.compose.runtime.LaunchedEffect(loginResult) {
        if (loginResult != null) {
            kotlinx.coroutines.delay(10_000)
            AppState.loginResult.value = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("保存位置", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (saveTree.isBlank()) "系统相册（Movies/视频下载）" else "自选文件夹：${AppState.saveTreeName()}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onPickFolder,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Text("选择文件夹") }
                        if (saveTree.isNotBlank()) {
                            OutlinedButton(onClick = {
                                AppState.clearSaveTree()
                                LogStore.log("设置", "保存位置恢复为系统相册")
                            }) { Text("恢复默认") }
                        }
                    }
                    Text(
                        "默认保存到系统相册的「视频下载」目录；选择自选文件夹后会直接保存到该目录（需要授予目录权限）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("登录态管理", fontWeight = FontWeight.SemiBold)
                    Text(
                        "登录态只保存在本机；登录账号后可以加密同步到你的云端，多端共用。" +
                            "X / YouTube 必须登录后才能下载；" +
                            "微信视频号需要先登录腾讯元宝（解析分享链接要用它的登录态）；" +
                            "小红书 / 快手 / B 站 / 微博 / TikTok / Instagram 可选登录（画质和成功率更高）；" +
                            "抖音不需要登录。登录态过期后这里会提示重新登录。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 各平台登录态 + 对应操作（登录态从这里一眼可见）
                    // 与电脑端一致：每个平台一行；不能应用内登录的（西瓜 / Reddit / 音乐平台等）
                    // 不显示「登录」按钮，但保留「手动粘贴」与云端同步按钮
                    CookieStore.PLATFORMS.forEachIndexed { index, meta ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            )
                        }
                        val status = loginStates[meta.key] ?: CookieStore.Status()
                        val (text, tone) = CookieStore.describe(meta.key, status)
                        val color = when (tone) {
                            1 -> Color(0xFF2E7D32)                 // 已登录
                            2 -> Color(0xFFE65100)                 // 建议重新登录
                            3 -> MaterialTheme.colorScheme.error   // 已失效
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 上行：平台名称 · 登录状态 · 登录时间；右侧为当前登录态来源
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(meta.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                // 状态文案占满剩余宽度（最多两行）：
                                // 之前是固定宽度 + 弹簧顶开「来源」胶囊，文案一长（如未登录提示）
                                // 胶囊就被挤成 0 宽、文字逐字折行，把整行撑到几百像素高
                                Text(
                                    text,
                                    fontSize = 12.sp,
                                    color = color,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                val source = CookieStore.sourceOf(meta.key)
                                // 没有 Cookie 文件时不显示来源标记（否则会出现「未登录 + 来源：云端」的矛盾组合）
                                val sourceText = if (status.count > 0) source.ifBlank { "本机" } else ""
                                if (sourceText.isNotBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(999.dp),
                                    ) {
                                        Text(
                                            "来源：$sourceText",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                            // 下行：操作按钮（自动换行 + 整行居中；当前使用的云端端高亮）
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (meta.loginable) {
                                    if (status.loggedIn) {
                                        OutlinedButton(
                                            onClick = { openLoginPage(context, meta.key) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) { Text("重新登录", fontSize = 12.sp) }
                                    } else {
                                        Button(
                                            onClick = { openLoginPage(context, meta.key) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                            ),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        ) { Text("登录 ${meta.label}", fontSize = 12.sp) }
                                    }
                                }
                                if (status.count > 0) {
                                    TextButton(
                                        onClick = {
                                            CookieStore.clear(context, meta.key)
                                            AppState.refreshLoginStates()
                                            AppState.loginResult.value = "已清除${meta.label}的登录态"
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    ) { Text("清除登录态", fontSize = 12.sp) }
                                }
                                // 所有平台都给「手动粘贴」：B 站 4K 这类场景要贴电脑端的整份 Cookie 才拿得到
                                TextButton(
                                    onClick = { manualPasteKey = meta.key },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) { Text("手动粘贴", fontSize = 12.sp) }
                                if (accountLoggedIn) {
                                    val slots = cloudCookies[meta.key]
                                    val currentSource = CookieStore.sourceOf(meta.key)
                                    listOf("mobile" to "使用手机登录态", "pc" to "使用电脑端登录态").forEach { (type, label) ->
                                        val exists = slots?.get(type)?.get("exists") as? Boolean ?: false
                                        val cloudSource = if (type == "mobile") "云端·手机端" else "云端·电脑端"
                                        val typeText = if (type == "mobile") "手机端" else "电脑端"
                                        // 兼容旧版本写入的来源标记（"手机端" / "电脑端"）
                                        val active = currentSource == cloudSource || currentSource == typeText
                                        val switch = {
                                            Thread {
                                                runCatching {
                                                    val data = CloudClient.fetchCookie(context, meta.key, type)
                                                    CookieStore.saveManual(context, meta.key, data.optString("cookie"))
                                                    CookieStore.setSource(meta.key, cloudSource)
                                                    AppState.refreshLoginStates()
                                                    AppState.accountNotice.value = "已改用$typeText 的${meta.label}登录态"
                                                }.onFailure {
                                                    AppState.accountNotice.value = "切换失败：${it.message}"
                                                }
                                            }.start()
                                        }
                                        if (active) {
                                            Button(
                                                onClick = switch,
                                                enabled = exists,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                ),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            ) { Text("✓ $label", fontSize = 11.sp) }
                                        } else {
                                            OutlinedButton(
                                                onClick = switch,
                                                enabled = exists,
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            ) { Text(label, fontSize = 11.sp) }
                                        }
                                    }
                                    // 不选端：一键取云端最新的一份（登录后自动回填失败时手动补）
                                    TextButton(
                                        onClick = {
                                            val device = newestCloudDevice(cloudCookies[meta.key]) ?: return@TextButton
                                            AppState.accountNotice.value = "正在拉取${meta.label}的云端登录态…"
                                            Thread {
                                                runCatching {
                                                    val data = CloudClient.fetchCookie(context, meta.key, device)
                                                    CookieStore.saveManual(context, meta.key, data.optString("cookie"))
                                                    CookieStore.setSource(
                                                        meta.key,
                                                        if (device == "mobile") "云端·手机端" else "云端·电脑端",
                                                    )
                                                    AppState.refreshLoginStates()
                                                    AppState.accountNotice.value =
                                                        "已拉取${meta.label}的云端最新登录态（${if (device == "mobile") "手机端" else "电脑端"}）"
                                                }.onFailure {
                                                    AppState.accountNotice.value = "拉取失败：${it.message}"
                                                }
                                            }.start()
                                        },
                                        enabled = newestCloudDevice(cloudCookies[meta.key]) != null,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) { Text("拉取云端登录态", fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                    Text(
                        "在应用内登录一次即可长期复用；页面打不开或被平台拦截时，用「用系统浏览器登录」或「手动粘贴登录态」。" +
                            "西瓜 / Reddit / Vimeo / Twitch / 微视 / 好看 / 音乐平台不提供应用内登录，" +
                            "用「手动粘贴」，或先登录账号后从云端同步电脑端的登录态。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    loginResult?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
                

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                    Text("账号 · 云端登录态同步", fontWeight = FontWeight.SemiBold)
                    if (accountLoggedIn) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("已登录：$accountPhone", fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                Thread {
                                    CloudClient.logout(context)
                                    AppState.setAccount("", "")
                                    AppState.cloudCookies.value = emptyMap()
                                    AppState.accountNotice.value = "已退出账号"
                                }.start()
                            }) { Text("退出登录", fontSize = 12.sp) }
                        }
                        Text(
                            "登录态会加密保存在你的账号里，只有你自己能看到，随时可以一键清空。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                Thread {
                                    val targets = cloudCookies.mapNotNull { (platform, slots) ->
                                        newestCloudDevice(slots)?.let { platform to it }
                                    }
                                    if (targets.isEmpty()) {
                                        AppState.accountNotice.value = "云端还没有可拉取的登录态"
                                        return@Thread
                                    }
                                    AppState.accountNotice.value = "正在拉取 ${targets.size} 个平台的云端登录态…"
                                    var pulled = 0
                                    for ((platform, device) in targets) {
                                        runCatching {
                                            val data = CloudClient.fetchCookie(context, platform, device)
                                            CookieStore.saveManual(context, platform, data.optString("cookie"))
                                            CookieStore.setSource(
                                                platform,
                                                if (device == "mobile") "云端·手机端" else "云端·电脑端",
                                            )
                                            pulled++
                                        }
                                    }
                                    AppState.refreshLoginStates()
                                    AppState.accountNotice.value = "已拉取 $pulled 个平台的云端登录态到本地"
                                }.start()
                            }) { Text("拉取全部云端登录态到本地", fontSize = 12.sp) }
                            TextButton(onClick = {
                                Thread {
                                    var uploaded = 0
                                    CookieStore.PLATFORMS.forEach { meta ->
                                        val cookie = CookieStore.cookieHeaderFor(context, meta.key)
                                        if (cookie.isNotBlank()) {
                                            runCatching { CloudClient.uploadCookie(context, meta.key, cookie) }
                                                .onSuccess { uploaded++ }
                                        }
                                    }
                                    AppState.accountNotice.value = "已同步 $uploaded 个平台的 Cookie 到云端（加密存储）"
                                    runCatching { CloudClient.cookieSyncStatus(context) }.onSuccess { data ->
                                        AppState.cloudCookies.value = parseCloudCookies(data)
                                    }
                                }.start()
                            }) { Text("同步本机登录态到云端", fontSize = 12.sp) }
                            TextButton(onClick = {
                                Thread {
                                    runCatching { CloudClient.deleteCloudCookie(context, null) }
                                    AppState.cloudCookies.value = emptyMap()
                                    AppState.accountNotice.value = "云端 Cookie 已清空"
                                }.start()
                            }) { Text("清空云端登录态", fontSize = 12.sp) }
                        }
                        Text(
                            "登录后各平台的登录态会自动回填；要换用另一端的会话（例如用电脑端登录态下 B 站 4K），点对应平台的「使用手机登录态 / 使用电脑端登录态」，当前使用的那一端会高亮。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it },
                            label = { Text("手机号") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = codeInput,
                                onValueChange = { codeInput = it },
                                label = { Text("验证码") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                AppState.accountNotice.value = "正在发送验证码…"
                                Thread {
                                    runCatching { CloudClient.requestSmsCode(context, phoneInput) }
                                        .onSuccess { data ->
                                            AppState.accountNotice.value =
                                                "验证码已发送到 ${data.optString("phone", phoneInput)}（5 分钟内有效）"
                                            smsCountdown = 60
                                        }
                                        .onFailure { AppState.accountNotice.value = "发送失败：${it.message}" }
                                }.start()
                            }, enabled = phoneInput.length >= 11 && smsCountdown == 0) {
                                Text(if (smsCountdown > 0) "$smsCountdown 秒后可重发" else "获取验证码", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    AppState.accountNotice.value = "登录中…"
                                    Thread {
                                        runCatching { CloudClient.loginWithSms(context, phoneInput, codeInput) }
                                            .onSuccess { data ->
                                                AppState.setAccount(data.optString("token"), data.optString("phone", phoneInput))
                                                runCatching { CloudClient.cookieSyncStatus(context) }.onSuccess { status ->
                                                    val cloud = parseCloudCookies(status)
                                                    AppState.cloudCookies.value = cloud
                                                    // 登录即回填：本机没有的平台自动用云端登录态（优先手机端，电脑端兜底）
                                                    val applied = autofillCloudCookies(context, cloud)
                                                    AppState.refreshLoginStates()
                                                    AppState.accountNotice.value = if (applied.isEmpty()) {
                                                        "登录成功：${data.optString("phone")}"
                                                    } else {
                                                        "登录成功：${data.optString("phone")}，已自动回填 ${applied.size} 个平台的登录态"
                                                    }
                                                }.onFailure {
                                                    AppState.accountNotice.value = "登录成功：${data.optString("phone")}（云端状态读取失败：${it.message}）"
                                                }
                                            }
                                            .onFailure { AppState.accountNotice.value = "登录失败：${it.message}" }
                                    }.start()
                                },
                                enabled = phoneInput.length >= 11 && codeInput.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) { Text("登录", fontSize = 12.sp) }
                        }
                        Text(
                            "登录后：一端登录过的平台，其它端可以直接用（云端 Cookie 加密保存、仅你可见、可一键清空）。不登录也不影响现有功能。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    accountNotice?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
}
            }



            SettingsCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("下载画质", fontWeight = FontWeight.SemiBold)
                    Text(
                        "清晰度上限：只对 X / YouTube / B 站 / 微博 / TikTok 等走 yt-dlp 的平台生效（抖音、快手、小红书、视频号由平台接口决定）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(0 to "最高画质", 1080 to "≤1080P", 720 to "≤720P").forEach { (value, label) ->
                            val selected = maxHeight == value
                            if (selected) {
                                Button(
                                    onClick = { AppState.setMaxHeight(value) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                ) { Text(label, fontSize = 12.sp) }
                            } else {
                                OutlinedButton(
                                    onClick = { AppState.setMaxHeight(value) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                ) { Text(label, fontSize = 12.sp) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("视频号省流量（H.265）", fontSize = 13.sp)
                            Text(
                                "同分辨率体积约减半；部分老播放器/剪辑软件不支持 HEVC，默认关闭",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = wxchannelsH265,
                            onCheckedChange = { AppState.setWxChannelsH265(it) },
                        )
                    }
                }
            }

            SettingsCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("提建议 / 联系官方", fontWeight = FontWeight.SemiBold)
                    var feedbackType by remember { mutableStateOf("SUGGESTION") }
                    var feedbackContent by remember { mutableStateOf("") }
                    var feedbackContact by remember { mutableStateOf("") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("SUGGESTION" to "功能建议", "BUG" to "问题反馈", "OTHER" to "其它").forEach { (key, label) ->
                            val selected = feedbackType == key
                            Button(
                                onClick = { feedbackType = key },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) { Text(label, fontSize = 12.sp) }
                        }
                    }
                    OutlinedTextField(
                        value = feedbackContent,
                        onValueChange = { feedbackContent = it },
                        label = { Text("建议或问题（请尽量附上出问题的链接，方便我们复现）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = feedbackContact,
                        onValueChange = { feedbackContact = it },
                        label = { Text("联系方式（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                AppState.feedbackResult.value = "提交中…"
                                val type = feedbackType
                                val content = feedbackContent
                                val contact = feedbackContact
                                Thread {
                                    runCatching {
                                        val message = CloudClient.sendFeedback(context, type, content, contact, false)
                                        AppState.feedbackResult.value = message
                                    }.onFailure {
                                        AppState.feedbackResult.value = "提交失败：${it.message}"
                                    }
                                }.start()
                            },
                            enabled = feedbackContent.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Text("提交") }
                        Text(feedbackResult ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SettingsCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("主题", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeOption("浅橙色", "orange", themeKey) { AppState.setTheme("orange") }
                        ThemeOption("浅绿色", "green", themeKey) { AppState.setTheme("green") }
                    }
                }
            }

            SettingsCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("运行日志", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            logText = LogStore.snapshot()
                            refreshTick++
                        }) { Text("刷新") }
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("日志", logText))
                        }) { Text("复制") }
                        TextButton(onClick = {
                            LogStore.clear()
                            logText = ""
                            refreshTick++
                        }) { Text("清空") }
                    }
                    Text(
                        logText.ifBlank { "暂无日志" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }

            SettingsCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("关于", fontWeight = FontWeight.SemiBold)
                    Text("秒存 ${BuildConfig.VERSION_NAME}（Android）", fontSize = 13.sp)
                    Text(
                        "粘贴链接即可下载。下面这些平台已经内置专属解析（无水印 / 原始画质 / 自动合并）：",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SupportedPlatformGrid()
                    Text(
                        "其它平台：微视 / 好看视频（通用解析 + 网页嗅探）；" +
                            "以及 yt-dlp 支持的上千个站点（AcFun、优酷、腾讯视频、爱奇艺、芒果 TV、SoundCloud、Twitter Spaces…）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "下载能力：普通文件直链（PDF / 压缩包 / 文档 / 音视频）、网页资源嗅探、" +
                            "多连接分段加速（最多 8 段，类 IDM）、断点续传、多 CDN 重试；" +
                            "保存到系统相册 / 音乐 / 自选目录。仅供个人学习与备份使用。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "与电脑端（Mac / Windows）的差异：\n" +
                            "· 安卓：应用内登录、应用内更新；下载保存到相册；可被其它 App「分享 → 秒存」直接唤起。\n" +
                            "· 电脑端：可从系统浏览器一键导入 Cookie —— B 站 4K 目前只有走这条路才拿得到" +
                            "（安卓可用「手动粘贴」贴 PC 端的整份登录态达到同样效果）；更新时跳浏览器下载；" +
                            "支持自定义保存目录与命名模板。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 合规入口：隐私政策 / 用户协议 / 开源声明（打开官网页面）
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            "隐私政策" to "/privacy.html",
                            "用户协议" to "/terms.html",
                            "开源声明" to "/opensource.html",
                        ).forEach { (label, path) ->
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(CloudClient.SITE_BASE + path)),
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) { Text(label, fontSize = 12.sp) }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        OutlinedButton(onClick = {
                            AppState.cloudMessage.value = "正在检查更新…"
                            Thread {
                                runCatching {
                                    val info = CloudClient.checkUpdate(context)
                                    AppState.updateInfo.value = info
                                    AppState.cloudOnline.value = true
                                    AppState.cloudMessage.value =
                                        if (info == null) "已是最新版本" else "发现新版本 ${info.versionName}"
                                }.onFailure {
                                    AppState.cloudOnline.value = false
                                    AppState.cloudMessage.value = "检查更新失败：${it.message}"
                                }
                            }.start()
                        }) { Text("检查更新") }
                        if (cloudText.isNotBlank()) {
                            Text(cloudText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 兜底：手动粘贴 Cookie（浏览器登录后复制过来）
    manualPasteKey?.let { pasteKey ->
        val meta = CookieStore.platform(pasteKey)
        AlertDialog(
            onDismissRequest = { manualPasteKey = null },
            title = { Text("手动粘贴${meta?.label ?: pasteKey} Cookie") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "在浏览器里登录后，用开发者工具复制 Cookie 请求头（形如 name=value; name2=value2）粘贴到下面。" +
                            "YouTube 建议复制 youtube.com 的 Cookie。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = manualPasteText,
                        onValueChange = { manualPasteText = it },
                        label = { Text("Cookie") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val count = CookieStore.saveManual(context, pasteKey, manualPasteText)
                        AppState.refreshLoginStates()
                        AppState.loginResult.value = if (count > 0) {
                            "已保存 $count 条${meta?.label ?: pasteKey} Cookie"
                        } else {
                            "没有解析到有效的 Cookie，请检查粘贴内容"
                        }
                        manualPasteText = ""
                        manualPasteKey = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { manualPasteText = ""; manualPasteKey = null }) { Text("取消") }
            },
        )
    }
}

/** 打开应用内登录页 */
private fun openLoginPage(context: Context, key: String) {
    context.startActivity(
        Intent(context, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_KEY, key)
    )
}

/**
 * 登录后自动回填：本机没有 Cookie 的平台，从云端拉取对应端的登录态。
 * 安卓优先用「手机端」的云端 Cookie，「电脑端」没有时才兜底；本机已登录的平台不覆盖。
 */
private fun autofillCloudCookies(
    context: android.content.Context,
    cloud: Map<String, Map<String, Map<String, Any>>>,
): List<String> {
    val applied = mutableListOf<String>()
    for ((platform, slots) in cloud) {
        if (CookieStore.status(context, platform).state != CookieStore.State.NONE) continue
        for (type in listOf("mobile", "pc")) {
            if ((slots[type]?.get("exists") as? Boolean) != true) continue
            val cookie = runCatching {
                CloudClient.fetchCookie(context, platform, type).optString("cookie")
            }.getOrNull().orEmpty()
            if (cookie.isBlank()) continue
            CookieStore.saveManual(context, platform, cookie)
            CookieStore.setSource(platform, if (type == "mobile") "云端·手机端" else "云端·电脑端")
            applied += platform
            break
        }
    }
    return applied
}

/** 把 /app/cookie/status 的返回转成「平台 → 端 → 信息」的嵌套结构 */
private fun parseCloudCookies(data: org.json.JSONObject): Map<String, Map<String, Map<String, Any>>> {
    val platforms = data.optJSONObject("platforms") ?: return emptyMap()
    val result = mutableMapOf<String, Map<String, Map<String, Any>>>()
    val platformKeys = platforms.keys()
    while (platformKeys.hasNext()) {
        val platform = platformKeys.next()
        val slots = platforms.optJSONObject(platform) ?: continue
        val one = mutableMapOf<String, Map<String, Any>>()
        val typeKeys = slots.keys()
        while (typeKeys.hasNext()) {
            val type = typeKeys.next()
            val item = slots.optJSONObject(type) ?: continue
            one[type] = mapOf(
                "exists" to item.optBoolean("exists"),
                "count" to item.optInt("count"),
                "deviceName" to item.optString("deviceName"),
                "updatedAt" to item.optString("updatedAt"),
            )
        }
        result[platform] = one
    }
    return result
}

@Composable
private fun ThemeOption(label: String, key: String, current: String, onClick: () -> Unit) {
    if (current == key) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

/** 云控同步：设置页与主页共用的轻量封装 */
object CloudSync {
    fun perform(context: Context, force: Boolean = false) {
        runCatching {
            CloudClient.register(context, force)
            AppState.cloudOnline.value = true
            // 连上后台是常态，界面上不用常驻一行提示（只有出错或发现新版本时才显示）
            AppState.cloudMessage.value = ""
        }.onFailure { error ->
            AppState.cloudOnline.value = false
            AppState.cloudMessage.value = "连接失败：${error.message}"
            LogStore.log("云控", "同步失败：${error.message}")
        }
    }

    fun platformName(key: String): String = when (key) {
        "douyin" -> "抖音"
        "kuaishou" -> "快手"
        "xiaohongshu" -> "小红书"
        "x" -> "X"
        "youtube" -> "YouTube"
        "weibo" -> "微博"
        "wechat_channels" -> "微信视频号"
        else -> key
    }

}

private fun platformName(key: String) = CloudSync.platformName(key)

/**
 * 云端某一端该用哪一份：两端都存在时取更新时间更晚的那一端
 * （时间相同或缺失时优先手机端）；都没有返回 null。
 */
private fun newestCloudDevice(slots: Map<String, Map<String, Any>>?): String? {
    val mobile = slots?.get("mobile")?.get("exists") as? Boolean ?: false
    val pc = slots?.get("pc")?.get("exists") as? Boolean ?: false
    if (!mobile && !pc) return null
    if (mobile && pc) {
        val mobileTime = slots?.get("mobile")?.get("updatedAt").toString()
        val pcTime = slots?.get("pc")?.get("updatedAt").toString()
        return if (pcTime > mobileTime) "pc" else "mobile"
    }
    return if (mobile) "mobile" else "pc"
}


/** 设置页统一卡片样式：更显眼的圆角 + 细边框 + 轻阴影 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

/** 「关于」里展示的平台徽标：主流平台用「圆形标记 + 名称」 */
private data class PlatformBadge(val short: String, val name: String, val color: Color)

private val SUPPORTED_PLATFORMS = listOf(
    PlatformBadge("抖", "抖音", Color(0xFF161823)),
    PlatformBadge("快", "快手", Color(0xFFFF4906)),
    PlatformBadge("红", "小红书", Color(0xFFFF2442)),
    PlatformBadge("视", "微信视频号", Color(0xFF07C160)),
    PlatformBadge("B", "B 站", Color(0xFF00A1D6)),
    PlatformBadge("博", "微博", Color(0xFFE6162D)),
    PlatformBadge("YT", "YouTube", Color(0xFFFF0000)),
    PlatformBadge("X", "X", Color(0xFF14171A)),
    PlatformBadge("TT", "TikTok", Color(0xFF010101)),
    PlatformBadge("IG", "Instagram", Color(0xFFC13584)),
    PlatformBadge("西", "西瓜视频", Color(0xFF19B394)),
    PlatformBadge("R", "Reddit", Color(0xFFFF4500)),
    PlatformBadge("V", "Vimeo", Color(0xFF1AB7EA)),
    PlatformBadge("TW", "Twitch", Color(0xFF9146FF)),
    PlatformBadge("网", "网易云音乐", Color(0xFFC20C0C)),
    PlatformBadge("QQ", "QQ 音乐", Color(0xFF31C27C)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportedPlatformGrid() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        SUPPORTED_PLATFORMS.forEach { badge ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(999.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Box(
                        modifier = Modifier.size(18.dp).background(badge.color, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            badge.short,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(badge.name, fontSize = 11.sp)
                }
            }
        }
    }
}
