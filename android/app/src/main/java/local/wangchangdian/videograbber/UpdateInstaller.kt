package local.wangchangdian.videograbber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内更新：把新版本 APK 下到本机缓存目录，再交给系统安装器。
 *
 * 界面部分在 UpdateDialog.kt（Compose 卡片弹窗），这里只负责下载与安装本身。
 * 之前点「前往下载」是直接开浏览器（ACTION_VIEW），得跳到 Chrome 里再下一次；
 * 现在默认应用内下载 + 应用内安装，只有用户在系统里关掉了「安装未知应用」时才会引导去授权。
 *
 * 下载支持断点续传：取消或断网后留下的 `*.part` 下次接着下。
 */
object UpdateInstaller {

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 系统是否允许本应用安装 APK（Android 8+ 需要用户显式授权） */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳到本应用的「安装未知应用」授权页 */
    fun openInstallPermission(activity: ComponentActivity) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        }.onFailure {
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
        }
    }

    fun apkFile(context: Context, versionCode: Int): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        return File(dir, "unbounded-downloader-$versionCode.apk")
    }

    /** 调起系统安装界面（缓存目录里的文件要经 FileProvider 授权才能被安装器读取） */
    fun startInstall(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openInBrowser(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 流式下载 + 断点续传；返回下好的 APK 文件 */
    fun download(
        url: String,
        target: File,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val part = File(target.parentFile, target.name + ".part")
        var offset = if (part.exists()) part.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "UnboundedDownloader/${BuildConfig.VERSION_NAME} Android")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                val append = code == HttpURLConnection.HTTP_PARTIAL && offset > 0
                if (!append) offset = 0L
                val total = if (append) {
                    offset + connection.contentLengthLong.coerceAtLeast(0L)
                } else {
                    connection.contentLengthLong
                }
                connection.inputStream.use { input ->
                    RandomAccessFile(part, "rw").use { output ->
                        output.seek(offset)
                        val buffer = ByteArray(64 * 1024)
                        var done = offset
                        while (true) {
                            if (cancelled.get()) throw CancellationException("已取消")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
            } else {
                error("服务器返回 HTTP $code")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    /** 下载完成 / 复用缓存前的完整性校验：优先比 MD5，退而求其次比文件大小 */
    fun isPackageValid(file: File, info: CloudClient.UpdateInfo): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (info.fileMd5.isNotBlank()) {
            return md5Of(file).equals(info.fileMd5.trim(), ignoreCase = true)
        }
        return info.fileSize <= 0L || file.length() == info.fileSize
    }

    fun clearCached(file: File) {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, file.name + ".part").delete() }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
