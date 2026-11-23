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
import java.net.HttpURLConnection
import java.net.URL

private val sharedBitmapCache = LruCache<String, Bitmap>(48)

/**
 * Loads and caches HTTP posters/thumbnails off the main thread in an in-memory LRU cache.
 */
@Composable
fun rememberRemoteBitmap(url: String?): Bitmap? {
    var bitmap by remember(url) { mutableStateOf(url?.let { sharedBitmapCache.get(it) }) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        sharedBitmapCache.get(url)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                }
                try {
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
        if (fetched != null) {
            sharedBitmapCache.put(url, fetched)
            bitmap = fetched
        }
    }
    return bitmap
}
