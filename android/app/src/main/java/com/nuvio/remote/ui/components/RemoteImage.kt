package com.nuvio.remote.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val IMAGE_CACHE_MAX_KB = 8 * 1024 // 8 MiB bounded cache
private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024 // 2 MiB download cap
private const val DEFAULT_MAX_DIM_PX = 512
private const val CONNECT_TIMEOUT_MS = 3500
private const val READ_TIMEOUT_MS = 3500

private val sharedBitmapCache = object : LruCache<String, Bitmap>(IMAGE_CACHE_MAX_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
}

/**
 * Loads, downsamples, and caches HTTP/HTTPS posters and thumbnails off the main thread
 * in a single memory-bounded LRU cache.
 */
@Composable
fun rememberRemoteBitmap(url: String?, maxDimensionPx: Int = DEFAULT_MAX_DIM_PX): Bitmap? {
    val cacheKey = remember(url, maxDimensionPx) {
        if (url.isNullOrBlank()) null else "$maxDimensionPx:$url"
    }
    var bitmap by remember(cacheKey) { mutableStateOf(cacheKey?.let { sharedBitmapCache.get(it) }) }
    LaunchedEffect(cacheKey) {
        val key = cacheKey
        if (key == null || url.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        sharedBitmapCache.get(key)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val fetched = withContext(Dispatchers.IO) {
            decodeBoundedRemoteBitmap(url, maxDimensionPx)
        }
        if (fetched != null) {
            sharedBitmapCache.put(key, fetched)
            bitmap = fetched
        }
    }
    return bitmap
}

private fun decodeBoundedRemoteBitmap(url: String, maxDimensionPx: Int): Bitmap? = runCatching {
    val parsed = URL(url)
    if (parsed.protocol != "https" && parsed.protocol != "http") return@runCatching null
    val conn = (parsed.openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
    }
    try {
        val rawBytes = readBoundedImageBytes(conn.inputStream) ?: return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val safeMax = maxDimensionPx.coerceAtLeast(64)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > safeMax || bounds.outHeight / sampleSize > safeMax) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
    } finally {
        conn.disconnect()
    }
}.getOrNull()

private fun readBoundedImageBytes(stream: java.io.InputStream): ByteArray? = stream.use { input ->
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        total += n
        if (total > MAX_IMAGE_BYTES) return null
        out.write(buf, 0, n)
    }
    if (total == 0) null else out.toByteArray()
}
