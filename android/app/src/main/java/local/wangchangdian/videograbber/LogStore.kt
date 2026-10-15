package local.wangchangdian.videograbber

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 轻量日志：内存环形缓冲 + 追加写入应用私有目录，便于在设置页排查问题 */
object LogStore {
    private const val TAG = "VideoGrabber"
    private const val MAX_LINES = 800
    private const val MAX_FILE_BYTES = 1024 * 1024

    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun init(context: Context) {
        if (logFile != null) return
        val file = File(context.filesDir, "app.log")
        logFile = file
        runCatching {
            if (file.exists() && file.length() < MAX_FILE_BYTES) {
                file.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
            } else {
                file.writeText("")
            }
        }
    }

    fun log(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        // 同时打到 logcat，便于连电脑时用 adb logcat 排查（设置页的日志框不好翻）
        Log.i(TAG, line)
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        // 先同步到主页实时日志（避免下面的 return 影响）
        AppState.pushLiveLog(line)
        val file = logFile ?: return
        runCatching {
            if (file.length() > MAX_FILE_BYTES) file.writeText("")
            file.appendText(line + "\n")
        }
    }

    fun snapshot(maxLines: Int = 300): String = synchronized(lock) {
        lines.toList().takeLast(maxLines).joinToString("\n")
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        runCatching { logFile?.writeText("") }
    }
}
