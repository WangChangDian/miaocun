package local.wangchangdian.videograbber

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 版本更新弹窗（卡片式，跟随主题配色）。
 *
 * 一个弹窗走完整个流程：介绍更新内容 → 应用内下载（带进度、可取消）→ 调起系统安装器。
 * 主界面冷启动和设置页「检查更新」共用这一份，两边只要把 [AppState.updateInfo] 填上就会弹。
 */
@Composable
fun UpdateDialogHost(onMessage: (String) -> Unit = {}) {
    val info by AppState.updateInfo.collectAsState()
    val themeKey by AppState.theme.collectAsState()
    val update = info ?: return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = AppThemeColors.scheme(themeKey).primary
    val accentSoft = AppThemeColors.scheme(themeKey).primaryContainer

    var downloading by remember(update.versionCode) { mutableStateOf(false) }
    var needPermission by remember(update.versionCode) { mutableStateOf(false) }
    var percent by remember(update.versionCode) { mutableStateOf(0) }
    var detail by remember(update.versionCode) { mutableStateOf("") }
    var errorText by remember(update.versionCode) { mutableStateOf<String?>(null) }
    val cancelled = remember(update.versionCode) { AtomicBoolean(false) }
    var job by remember(update.versionCode) { mutableStateOf<Job?>(null) }

    fun close() {
        AppState.updateInfo.value = null
    }

    fun startDownload() {
        if (!UpdateInstaller.canInstall(context)) {
            needPermission = true
            return
        }
        needPermission = false
        errorText = null
        val target = UpdateInstaller.apkFile(context, update.versionCode)
        if (target.exists() && target.length() > 0L) {
            if (UpdateInstaller.isPackageValid(target, update)) {
                runCatching { UpdateInstaller.startInstall(context, target) }
                    .onFailure { errorText = "调起安装失败：${it.message}" }
                if (errorText == null) close()
                return
            }
            // 缓存里的包和后台当前发布的不是同一份 → 丢掉重下
            UpdateInstaller.clearCached(target)
        }
        cancelled.set(false)
        downloading = true
        percent = 0
        detail = "准备下载…"
        job = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    UpdateInstaller.download(update.downloadUrl, target, cancelled) { done, total ->
                        val next = if (total > 0) ((done * 100) / total).toInt() else 0
                        if (next != percent) {
                            percent = next
                            val totalText = if (total > 0) "${total / 1024 / 1024}MB" else "未知大小"
                            detail = "${done / 1024 / 1024}MB / $totalText"
                        }
                    }
                }
                if (!UpdateInstaller.isPackageValid(file, update)) {
                    UpdateInstaller.clearCached(file)
                    throw IllegalStateException("安装包校验失败（与后台发布的不一致），请重试或改用浏览器下载")
                }
                downloading = false
                UpdateInstaller.startInstall(context, file)
                close()
                onMessage("更新包已下载，请在系统提示里完成安装")
            } catch (cancelledError: CancellationException) {
                downloading = false
                throw cancelledError
            } catch (error: Exception) {
                downloading = false
                errorText = error.message ?: "下载失败"
            }
        }
    }

    Dialog(onDismissRequest = { if (!update.force && !downloading) close() }) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 头部：图标 + 版本号（沿用主题渐变）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(AppThemeColors.gradient(themeKey))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("↑", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("发现新版本", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            update.versionName,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (update.force) {
                        Surface(color = accentSoft, shape = RoundedCornerShape(999.dp)) {
                            Text(
                                "强制更新",
                                fontSize = 11.sp,
                                color = accent,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                // 版本对比 + 安装包大小
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "当前版本 ${CloudClient.APP_VERSION_NAME}（${CloudClient.APP_VERSION_CODE}）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "最新版本 ${update.versionName}（${update.versionCode}）" +
                                if (update.fileSize > 0) " · ${update.fileSize / 1024 / 1024}MB" else "",
                            fontSize = 12.sp,
                            color = accent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // 更新内容
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("更新内容", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        update.changelog
                            .lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .ifEmpty { listOf("有新版本可用，建议更新。") }
                            .forEach { line ->
                                Row {
                                    Text("•", color = accent, fontSize = 13.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        line,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                    }
                }

                if (downloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = accent,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text(
                            "$percent%   $detail",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (needPermission) {
                    Surface(color = accentSoft, shape = RoundedCornerShape(14.dp)) {
                        Text(
                            "系统还没允许本应用安装新版本。点「去设置」打开「允许安装未知应用」，" +
                                "回到应用后再点一次即可，已下载的部分会接着下。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }

                errorText?.let { text ->
                    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { UpdateInstaller.openInBrowser(context, update.downloadUrl) }) {
                        Text("浏览器下载", fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (downloading) {
                        TextButton(onClick = {
                            cancelled.set(true)
                            job?.cancel()
                            downloading = false
                            onMessage("已取消更新下载（下次接着下）")
                        }) { Text("取消下载", fontSize = 13.sp) }
                    } else {
                        if (!update.force) {
                            TextButton(onClick = { close() }) { Text("稍后", fontSize = 13.sp) }
                        }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                if (needPermission) {
                                    (context as? ComponentActivity)?.let { UpdateInstaller.openInstallPermission(it) }
                                    needPermission = false
                                } else {
                                    startDownload()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                        ) {
                            Text(
                                when {
                                    needPermission -> "去设置"
                                    errorText != null -> "重试"
                                    else -> "立即更新"
                                },
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
