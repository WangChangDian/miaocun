package local.wangchangdian.videograbber

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {
    companion object {
        const val CHANNEL_ID = "download"
    }

    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        LogStore.init(this)
        LogStore.log("App", "启动（Android ${Build.VERSION.RELEASE}）")
        initYtDlp()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "显示视频下载进度" }
            manager.createNotificationChannel(channel)
        }
    }

    /** 首次启动解压内置 Python / yt-dlp（约 20-30 秒，仅一次） */
    private fun initYtDlp() {
        Thread {
            runCatching {
                com.yausername.youtubedl_android.YoutubeDL.getInstance().init(this)
                runCatching { com.yausername.ffmpeg.FFmpeg.getInstance().init(this) }
                AppState.ytDlpReady.value = true
                LogStore.log("引擎", "yt-dlp 初始化完成（含 QuickJS / ffmpeg）")
                updateYtDlpIfNeeded()
            }.onFailure { error ->
                AppState.ytDlpReady.value = false
                LogStore.log("引擎", "yt-dlp 初始化失败: ${error.message}")
            }
        }.start()
    }

    /**
     * 内置的 yt-dlp 是打包时的版本（较旧），B 站 4K / 新站点往往要新版 extractor 才能拿到。
     * 每个 App 版本只自动更新一次，下载约 30MB，失败不影响其它功能（设置页也有手动按钮）。
     */
    private fun updateYtDlpIfNeeded() {
        val prefs = AppState.preferences()
        val updatedFor = prefs.getString("ytdlp_updated_for", "").orEmpty()
        if (updatedFor == BuildConfig.VERSION_NAME) return
        runCatching {
            LogStore.log("引擎", "内置 yt-dlp 较旧，正在自动更新到最新版…")
            com.yausername.youtubedl_android.YoutubeDL.getInstance().updateYoutubeDL(this)
            prefs.edit().putString("ytdlp_updated_for", BuildConfig.VERSION_NAME).apply()
            LogStore.log("引擎", "yt-dlp 已更新到最新版")
        }.onFailure { error ->
            LogStore.log("引擎", "yt-dlp 自动更新失败：${error.message}（可在设置里手动重试）")
        }
    }
}
