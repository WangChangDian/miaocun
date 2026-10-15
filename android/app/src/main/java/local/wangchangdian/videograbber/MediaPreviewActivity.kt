package local.wangchangdian.videograbber

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 下载内容的放大预览（点卡片里的缩略图进来）：
 * - 图片：左右滑动切换、双指缩放 / 双击放大；同一条记录里的图片放在一起
 * - 视频：进来即播放（用系统播放能力，不再弹「选择应用」）
 * - 音频：音频图标点开后即播放，带进度条
 * - PDF：逐页渲染，直接翻看
 *
 * 顶栏保留「打开 / 分享」：需要交给其它应用处理时仍然走系统选择器。
 */
class MediaPreviewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_NAMES = "names"
        const val EXTRA_SIZES = "sizes"
        const val EXTRA_INDEX = "index"

        fun intent(
            context: Context,
            uris: List<String>,
            names: List<String>,
            sizes: List<Long>,
            index: Int,
        ): Intent = Intent(context, MediaPreviewActivity::class.java)
            .putExtra(EXTRA_URIS, uris.toTypedArray())
            .putExtra(EXTRA_NAMES, names.toTypedArray())
            .putExtra(EXTRA_SIZES, sizes.toLongArray())
            .putExtra(EXTRA_INDEX, index)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uris = intent.getStringArrayExtra(EXTRA_URIS)?.toList() ?: emptyList()
        val names = intent.getStringArrayExtra(EXTRA_NAMES)?.toList() ?: emptyList()
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES)?.toList() ?: emptyList()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        if (uris.isEmpty()) {
            finish()
            return
        }
        val startKind = mediaKindOf(names.getOrNull(startIndex).orEmpty())
        if (startKind == MediaKind.OTHER) {
            // 没有预览能力：交回系统处理（保持原来的行为）
            openWithSystem(uris[startIndex])
            finish()
            return
        }

        setContent {
            VideoGrabberTheme(AppState.theme.value) {
                PreviewScreen(
                    uris = uris,
                    names = names,
                    sizes = sizes,
                    startIndex = startIndex,
                    kind = startKind,
                    onClose = { finish() },
                )
            }
        }
    }

    private fun openWithSystem(uri: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uri), contentResolver.getType(Uri.parse(uri)) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(
    uris: List<String>,
    names: List<String>,
    sizes: List<Long>,
    startIndex: Int,
    kind: MediaKind,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // 图片放进同一个翻页器里；视频 / 音频 / PDF 只预览点中的那一个
    val images = remember(uris, names) {
        uris.indices.filter { mediaKindOf(names.getOrNull(it).orEmpty()) == MediaKind.IMAGE }
    }
    val imageIndex = images.indexOf(startIndex).coerceAtLeast(0)
    var current by remember { mutableStateOf(startIndex) }
    val name = names.getOrNull(current).orEmpty().ifBlank { "文件 ${current + 1}" }
    val sizeText = sizes.getOrNull(current)?.takeIf { it > 0 }?.let { formatBytes(it) }.orEmpty()

    Scaffold(
        containerColor = Color(0xFF101014),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, fontSize = 14.sp, maxLines = 1, color = Color.White)
                        if (sizeText.isNotBlank() || images.size > 1) {
                            Text(
                                listOf(sizeText, if (images.size > 1) "${imageIndex + 1}/${images.size}" else "")
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                fontSize = 11.sp,
                                color = Color(0xFFB9B9C0),
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("关闭", color = Color.White) }
                },
                actions = {
                    TextButton(onClick = {
                        onShare(context, listOf(uris[current]))
                    }) { Text("分享", color = Color.White) }
                    TextButton(onClick = {
                        onOpenExternal(context, uris[current])
                    }) { Text("打开", color = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (kind) {
                MediaKind.IMAGE -> if (images.isNotEmpty()) {
                    val pagerState = rememberPagerState(initialPage = imageIndex) { images.size }
                    LaunchedEffect(pagerState.currentPage) {
                        current = images.getOrElse(pagerState.currentPage) { startIndex }
                    }
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        ZoomableImage(uris[images[page]])
                    }
                }
                MediaKind.VIDEO -> VideoPlayer(uris[current])
                MediaKind.AUDIO -> AudioPlayer(uri = uris[current], title = name, sizeText = sizeText)
                MediaKind.PDF -> PdfViewer(uris[current])
                else -> Unit
            }
        }
    }
}

/** 可双指缩放 / 双击放大的图片页 */
@Composable
private fun ZoomableImage(uri: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { MediaMeta.preview(context, uri) }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else androidx.compose.ui.geometry.Offset.Zero
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(transformState),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            Text("正在加载…", color = Color(0xFFB9B9C0), fontSize = 13.sp)
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }
    }
}

/** 视频：进来即播放（VideoView + 系统控制条） */
@Composable
private fun VideoPlayer(uri: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            // 铺满屏幕：竖屏视频也能尽量大（VideoView 会按比例居中，不会变形）
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setVideoURI(Uri.parse(uri))
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnPreparedListener { it.isLooping = false; start() }
                    requestFocus()
                }
            },
        )
    }
}

/** 音频：图标 + 进度条 + 播放/暂停（进来即播放） */
@Composable
private fun AudioPlayer(uri: String, title: String, sizeText: String) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }

    DisposableEffect(uri) {
        val created = MediaPlayer().apply {
            runCatching {
                setDataSource(context, Uri.parse(uri))
                prepare()
                start()
            }
        }
        player = created
        playing = runCatching { created.isPlaying }.getOrDefault(false)
        duration = runCatching { created.duration }.getOrDefault(0)
        created.setOnCompletionListener { playing = false; position = 0 }
        onDispose {
            runCatching { created.stop() }
            runCatching { created.release() }
            player = null
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { position = runCatching { it.currentPosition }.getOrDefault(position) }
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = Color(0xFF26262C),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.size(120.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("♪", fontSize = 56.sp, color = Color(0xFFE9A05B))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, color = Color.White, fontSize = 14.sp, maxLines = 2)
        if (sizeText.isNotBlank()) {
            Text(sizeText, color = Color(0xFFB9B9C0), fontSize = 11.sp)
        }
        Spacer(Modifier.height(20.dp))
        Slider(
            value = position.toFloat(),
            onValueChange = { value ->
                position = value.toInt()
                runCatching { player?.seekTo(position) }
            },
            valueRange = 0f..(duration.coerceAtLeast(1)).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(timeText(position), color = Color(0xFFB9B9C0), fontSize = 11.sp)
            Text(timeText(duration), color = Color(0xFFB9B9C0), fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = {
            val target = player ?: return@TextButton
            if (playing) {
                runCatching { target.pause() }
                playing = false
            } else {
                runCatching { target.start() }
                playing = true
            }
        }) {
            Text(if (playing) "暂停" else "播放", color = Color.White, fontSize = 16.sp)
        }
    }
}

/** PDF：逐页渲染，直接翻看 */
@Composable
private fun PdfViewer(uri: String) {
    val context = LocalContext.current
    val pageCount by produceState(0, uri) {
        value = withContext(Dispatchers.IO) { MediaMeta.pdfPageCount(context, uri) }
    }
    if (pageCount <= 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("无法预览这个 PDF（可以点右上角「打开」用其它应用查看）", color = Color(0xFFB9B9C0), fontSize = 13.sp)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pageCount) { index ->
            val bitmap by produceState<Bitmap?>(null, uri, index) {
                value = withContext(Dispatchers.IO) { MediaMeta.pdfPage(context, uri, index, 1400) }
            }
            val page = bitmap
            if (page != null) {
                Image(
                    bitmap = page.asImageBitmap(),
                    contentDescription = "第 ${index + 1} 页",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.White),
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun timeText(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private fun onShare(context: Context, uris: List<String>) {
    if (uris.isEmpty()) return
    runCatching {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(Uri.parse(uris[0])) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uris[0]))
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.map { Uri.parse(it) }))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享"))
    }
}

private fun onOpenExternal(context: Context, uri: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), context.contentResolver.getType(Uri.parse(uri)) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }
}
