package local.wangchangdian.videograbber

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 进程内共享状态（Activity 与 Service 同进程） */
object AppState {
    val download = MutableStateFlow<DownloadState?>(null)
    val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val theme = MutableStateFlow("orange")
    /** 画质偏好：0 = 最高画质；1080 / 720 表示不要超过这个高度 */
    val maxHeight = MutableStateFlow(0)
    /** 视频号是否优先 H.265（同分辨率体积约减半） */
    val wxchannelsH265 = MutableStateFlow(false)
    val saveTree = MutableStateFlow("")        // SAF 自定义保存目录（空 = 系统相册）
    val liveLog = MutableStateFlow<List<String>>(emptyList())   // 主页实时解析日志
    val ytDlpReady = MutableStateFlow(false)                    // 安卓版 yt-dlp 是否就绪
    val loginResult = MutableStateFlow<String?>(null)           // 登录窗口结果提示
    val loginStates = MutableStateFlow<Map<String, CookieStore.Status>>(emptyMap())  // 各平台登录态
    // 云控后台
    val cloudOnline = MutableStateFlow(false)
    val cloudMessage = MutableStateFlow("")
    val updateInfo = MutableStateFlow<CloudClient.UpdateInfo?>(null)
    val feedbackResult = MutableStateFlow<String?>(null)
    // B2 账号与云端 Cookie
    val accountLoggedIn = MutableStateFlow(false)
    val accountPhone = MutableStateFlow("")
    val accountNotice = MutableStateFlow<String?>(null)
    val cloudCookies = MutableStateFlow<Map<String, Map<String, Map<String, Any>>>>(emptyMap())

    private const val PREFS = "videograbber"
    private const val KEY_HISTORY = "history"
    private const val KEY_THEME = "theme"
    private const val KEY_MAX_HEIGHT = "max_height"
    private const val KEY_WXCHANNELS_H265 = "wxchannels_h265"
    private const val KEY_SAVE_TREE = "save_tree"
    private const val KEY_SAVE_TREE_NAME = "save_tree_name"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_ACCOUNT_TOKEN = "account_token"
    private const val KEY_ACCOUNT_PHONE = "account_phone"

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        history.value = loadHistory()
        theme.value = prefs.getString(KEY_THEME, "orange") ?: "orange"
        maxHeight.value = prefs.getInt(KEY_MAX_HEIGHT, 0)
        wxchannelsH265.value = prefs.getBoolean(KEY_WXCHANNELS_H265, false)
        saveTree.value = prefs.getString(KEY_SAVE_TREE, "") ?: ""
        initAccount()
    }

    /** 共享的偏好设置（引擎自动更新等场景也要读写） */
    fun preferences(): SharedPreferences = prefs

    fun accountToken(): String = prefs.getString(KEY_ACCOUNT_TOKEN, "") ?: ""

    fun setAccount(token: String, phone: String) {
        prefs.edit().putString(KEY_ACCOUNT_TOKEN, token).putString(KEY_ACCOUNT_PHONE, phone).apply()
        accountLoggedIn.value = token.isNotBlank()
        accountPhone.value = phone
    }

    fun initAccount() {
        accountLoggedIn.value = accountToken().isNotBlank()
        accountPhone.value = prefs.getString(KEY_ACCOUNT_PHONE, "") ?: ""
    }

    fun setSaveTree(uri: String, name: String) {
        saveTree.value = uri
        prefs.edit().putString(KEY_SAVE_TREE, uri).putString(KEY_SAVE_TREE_NAME, name).apply()
    }

    fun clearSaveTree() {
        saveTree.value = ""
        prefs.edit().remove(KEY_SAVE_TREE).remove(KEY_SAVE_TREE_NAME).apply()
    }

    fun saveTreeName(): String = prefs.getString(KEY_SAVE_TREE_NAME, "") ?: ""

    // ---------------------------------------------------------------- 云控

    fun serverUrl(): String =
        prefs.getString(KEY_SERVER_URL, CloudClient.DEFAULT_SERVER) ?: CloudClient.DEFAULT_SERVER

    fun deviceToken(): String = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""

    fun setDeviceToken(value: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()
    }

    fun deviceId(): String = prefs.getString(KEY_DEVICE_ID, "") ?: ""

    fun setDeviceId(value: String) {
        prefs.edit().putString(KEY_DEVICE_ID, value).apply()
    }

    fun setTheme(value: String) {
        theme.value = value
        prefs.edit().putString(KEY_THEME, value).apply()
    }

    fun setMaxHeight(value: Int) {
        maxHeight.value = value
        prefs.edit().putInt(KEY_MAX_HEIGHT, value).apply()
    }

    fun setWxChannelsH265(enabled: Boolean) {
        wxchannelsH265.value = enabled
        prefs.edit().putBoolean(KEY_WXCHANNELS_H265, enabled).apply()
    }

    /**
     * 清空下载记录；deleteFiles=true 时连文件一起删。
     * 返回实际删除的文件数（相册里的条目用 content:// 删除，自选目录用 File 删除）。
     */
    fun clearHistory(deleteFiles: Boolean = false): Int {
        var deleted = 0
        if (deleteFiles) {
            history.value.forEach { entry ->
                val targets = (entry.uris.ifEmpty { listOf(entry.uri) }).filter { it.isNotBlank() }
                targets.forEach { target ->
                    val ok = runCatching {
                        if (target.startsWith("content://")) {
                            appContext.contentResolver.delete(android.net.Uri.parse(target), null, null) > 0
                        } else {
                            java.io.File(target).delete()
                        }
                    }.getOrDefault(false)
                    if (ok) deleted += 1
                }
            }
        }
        history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
        return deleted
    }

    /** 供 LogStore 调用：把最新日志同步到主页展示 */
    fun pushLiveLog(line: String) {
        val next = (liveLog.value + line).takeLast(120)
        liveLog.value = next
    }

    fun clearLiveLog() {
        liveLog.value = emptyList()
    }

    /** 重新判断各平台登录态（进入/返回设置页、登录完成后调用） */
    fun refreshLoginStates() {
        loginStates.value = CookieStore.allStatuses(appContext)
    }

    fun addHistory(entry: HistoryEntry) {
        val list = listOf(entry) + history.value
        val trimmed = list.take(100)
        history.value = trimmed
        persistHistory(trimmed)
    }

    /** 删除单条下载记录（连同其全部文件，不弹确认）；返回实际删除的文件数 */
    fun deleteHistoryItem(index: Int): Int {
        val list = history.value.toMutableList()
        val entry = list.getOrNull(index) ?: return 0
        var deleted = 0
        (entry.uris.ifEmpty { listOf(entry.uri) }).filter { it.isNotBlank() }.forEach { target ->
            val ok = runCatching {
                if (target.startsWith("content://")) {
                    appContext.contentResolver.delete(android.net.Uri.parse(target), null, null) > 0
                } else {
                    java.io.File(target).delete()
                }
            }.getOrDefault(false)
            if (ok) deleted += 1
        }
        list.removeAt(index)
        history.value = list
        persistHistory(list)
        return deleted
    }

    /** 删除记录里的单个文件（从记录里移除；全部删光时整条记录一并删除，不弹确认） */
    fun deleteHistoryFile(index: Int, uri: String) {
        val list = history.value.toMutableList()
        val entry = list.getOrNull(index) ?: return
        runCatching {
            if (uri.startsWith("content://")) {
                appContext.contentResolver.delete(android.net.Uri.parse(uri), null, null)
            } else {
                java.io.File(uri).delete()
            }
        }
        val uris = (entry.uris.ifEmpty { listOf(entry.uri) }).toMutableList()
        val names = entry.fileNames.toMutableList()
        val sizes = entry.sizes.toMutableList()
        val removeIndex = uris.indexOf(uri)
        if (removeIndex >= 0) {
            uris.removeAt(removeIndex)
            if (removeIndex < names.size) names.removeAt(removeIndex)
            if (removeIndex < sizes.size) sizes.removeAt(removeIndex)
        }
        if (uris.isEmpty()) {
            list.removeAt(index)
        } else {
            list[index] = entry.copy(uri = uris.first(), uris = uris, fileNames = names, sizes = sizes)
        }
        history.value = list
        persistHistory(list)
    }

    /** 某个文件所在的记录下标；找不到返回 -1（「刚下载完」的卡片用它定位记录） */
    fun historyIndexOfFile(uri: String): Int =
        history.value.indexOfFirst { (it.uris.ifEmpty { listOf(it.uri) }).contains(uri) }

    /**
     * 删除「刚下载完」卡片对应的记录（连同文件）并收起卡片。
     * 卡片里的文件与历史记录是同一次下载产生的，删掉记录即等于删掉这次下载。
     */
    fun deleteCurrentDownload() {
        val state = download.value ?: return
        val primary = state.fileUris.firstOrNull() ?: state.uri
        val index = historyIndexOfFile(primary)
        if (index >= 0) deleteHistoryItem(index)
        download.value = null
    }

    /** 删除「刚下载完」卡片里的单个文件：同步更新卡片与对应记录；文件全删完时收起卡片 */
    fun deleteCurrentDownloadFile(uri: String) {
        val state = download.value ?: return
        val index = historyIndexOfFile(uri)
        if (index >= 0) deleteHistoryFile(index, uri)
        val keepUris = (state.fileUris.ifEmpty { listOf(state.uri) }).filter { it != uri }
        if (keepUris.isEmpty()) {
            download.value = null
            return
        }
        val dropIndex = (state.fileUris.ifEmpty { listOf(state.uri) }).indexOf(uri)
        download.value = state.copy(
            uri = keepUris.first(),
            fileUris = keepUris,
            fileNames = state.fileNames.filterIndexed { i, _ -> i != dropIndex },
            fileSizes = state.fileSizes.filterIndexed { i, _ -> i != dropIndex },
        )
    }

    /** 下载记录持久化（addHistory / 删除记录共用同一份 JSON 结构） */
    private fun persistHistory(list: List<HistoryEntry>) {
        val array = JSONArray()
        list.forEach { item ->
            array.put(JSONObject().apply {
                put("platform", item.platform.key)
                put("title", item.title)
                put("author", item.author)
                put("filePath", item.filePath)
                put("sizeText", item.sizeText)
                put("quality", item.quality)
                put("finishedAt", item.finishedAt)
                put("uri", item.uri)
                put("uris", org.json.JSONArray(item.uris))
                put("fileNames", org.json.JSONArray(item.fileNames))
                put("sizes", org.json.JSONArray(item.sizes))
            })
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun loadHistory(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                HistoryEntry(
                    platform = Platform.fromKey(item.optString("platform")),
                    title = item.optString("title"),
                    author = item.optString("author"),
                    filePath = item.optString("filePath"),
                    sizeText = item.optString("sizeText"),
                    quality = item.optString("quality"),
                    finishedAt = item.optString("finishedAt"),
                    uri = item.optString("uri"),
                    uris = item.optJSONArray("uris")?.let { array ->
                        (0 until array.length()).map { array.optString(it) }
                    } ?: emptyList(),
                    fileNames = item.optJSONArray("fileNames")?.let { array ->
                        (0 until array.length()).map { array.optString(it) }
                    } ?: emptyList(),
                    sizes = item.optJSONArray("sizes")?.let { array ->
                        (0 until array.length()).map { array.optLong(it) }
                    } ?: emptyList(),
                )
            }
        }.getOrDefault(emptyList())
    }
}
