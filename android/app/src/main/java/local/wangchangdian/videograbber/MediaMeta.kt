package local.wangchangdian.videograbber

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载文件在卡片里的展示信息：缩略图 + 单个文件大小。
 *
 * 缩略图按需加载（只加载当前可见的卡片），结果进内存缓存；
 * 大小优先用下载时记录的值，老记录（没记）再按 URI 查一次并缓存。
 */
object MediaMeta {
    private const val THUMB_PX = 320

    private val thumbs = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }
    private val previews = object : LruCache<String, Bitmap>(8) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }
    private val sizes = ConcurrentHashMap<String, Long>()

    /** 卡片的缩略图只有这几类需要（其它类型保持原来的行样式） */
    fun hasThumb(kind: MediaKind): Boolean =
        kind == MediaKind.IMAGE || kind == MediaKind.VIDEO || kind == MediaKind.PDF

    /** 判断某个文件的类型：先看文件名，名字不可用（老记录）再退回 URI / MIME */
    fun kindFor(context: Context, uri: String, name: String): MediaKind {
        val byName = mediaKindOf(name)
        if (byName != MediaKind.OTHER) return byName
        val guessed = mediaKindOf(uri.substringBefore('?').substringAfterLast('/'))
        if (guessed != MediaKind.OTHER) return guessed
        val mime = runCatching { context.contentResolver.getType(Uri.parse(uri)) }.getOrNull()
        return when {
            mime == null -> MediaKind.OTHER
            mime.startsWith("image/") -> MediaKind.IMAGE
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime == "application/pdf" -> MediaKind.PDF
            else -> MediaKind.OTHER
        }
    }

    fun size(context: Context, uri: String): Long {
        if (uri.isBlank()) return 0L
        sizes[uri]?.let { return it }
        val value = runCatching {
            if (uri.startsWith("content://")) {
                context.contentResolver
                    .query(Uri.parse(uri), arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
                    ?: 0L
            } else {
                File(Uri.parse(uri).path ?: uri).length()
            }
        }.getOrDefault(0L)
        sizes[uri] = value
        return value
    }

    fun thumb(context: Context, uri: String, kind: MediaKind): Bitmap? {
        if (!hasThumb(kind)) return null
        val key = "$kind:$uri"
        thumbs.get(key)?.let { return it }
        val bitmap = runCatching { load(context, uri, kind, THUMB_PX) }.getOrNull() ?: return null
        thumbs.put(key, bitmap)
        return bitmap
    }

    /** 预览用的较大图（图片才需要） */
    fun preview(context: Context, uri: String, maxPx: Int = 2048): Bitmap? {
        val key = "$maxPx:$uri"
        previews.get(key)?.let { return it }
        val bitmap = runCatching { decodeImage(context, uri, maxPx) }.getOrNull() ?: return null
        previews.put(key, bitmap)
        return bitmap
    }

    private fun load(context: Context, uri: String, kind: MediaKind, maxPx: Int): Bitmap? = when (kind) {
        MediaKind.IMAGE -> decodeImage(context, uri, maxPx)
        MediaKind.VIDEO -> videoFrame(context, uri, maxPx)
        MediaKind.PDF -> pdfPage(context, uri, 0, maxPx)
        else -> null
    }

    private fun decodeImage(context: Context, uri: String, maxPx: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun videoFrame(context: Context, uri: String, maxPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            // 取 0.5 秒处的帧：第一帧常常是黑屏
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    500_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxPx,
                    maxPx,
                ) ?: retriever.frameAtTime
            } else {
                retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 渲染 PDF 的某一页（width <= maxPx） */
    fun pdfPage(context: Context, uri: String, index: Int, maxPx: Int): Bitmap? {
        val descriptor: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (index < 0 || index >= renderer.pageCount) return null
                renderer.openPage(index).use { page ->
                    val width = minOf(maxPx, page.width.coerceAtLeast(1))
                    val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    /** PDF 页数（界面里先取一次） */
    fun pdfPageCount(context: Context, uri: String): Int {
        val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return 0
        return descriptor.use { fd -> runCatching { PdfRenderer(fd).use { it.pageCount } }.getOrDefault(0) }
    }
}
