/*
 * CubeCut - cuts a speedcubing session video into one clip per solve.
 * Copyright (C) 2026 DadCuber
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 of the License,
 * with the additional terms in NOTICE.md (GPL section 7): the author credit must stay in the app's
 * About window, and changed versions must be marked as changed.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program (the
 * LICENSE file). If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.cubecut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

// ---------------------------------------------------------------------------------------
// Gallery pictures. They are made the first time they are needed (a frame from the video, shrunk to 360 pixels high), kept as
// small JPEG files in the app's cache folder and in memory while the app runs. They are never needed for the data to be
// correct: if Android clears the cache, they are simply made again (as long as the video can still be opened).
// ---------------------------------------------------------------------------------------

object ThumbCache {
    private const val TARGET_HEIGHT = 360

    // the memory part holds about 24 MB of pictures
    private val memory = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024)
    }

    // at most two pictures are made at the same time, so scrolling a long gallery doesn't flood the phone
    private val gate = Semaphore(2)

    private fun dir(context: Context): File {
        val d = File(context.cacheDir, "thumbs")
        d.mkdirs()
        return d
    }

    /**
     * The picture for [key]. [uri] and [timeMs] say where to take it from if it doesn't exist yet
     * (a null [uri] = the video can't be opened, so only an already made picture can be returned).
     */
    suspend fun get(context: Context, key: String, uri: Uri?, timeMs: Long, height: Int = TARGET_HEIGHT): Bitmap? {
        val name = if (height == TARGET_HEIGHT) key else "${key}_h$height"      // bigger pictures (for the full-screen viewer) are kept apart
        val cached = memory.get(name)
        if (cached != null) return cached
        return withContext(Dispatchers.IO) {
            val file = File(dir(context), "$name.jpg")
            var bmp: Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            if (bmp == null && uri != null) {
                gate.withPermit {
                    bmp = grab(context, uri, timeMs, height)
                    val made = bmp
                    if (made != null) {
                        try {
                            FileOutputStream(file).use { out -> made.compress(Bitmap.CompressFormat.JPEG, 80, out) }
                        } catch (e: Exception) {
                            // not saved: it will just be made again next time
                        }
                    }
                }
            }
            val result = bmp
            if (result != null) memory.put(name, result)
            result
        }
    }

    /** Removes the pictures of one session (when it is forgotten). */
    fun forgetFootage(context: Context, footageId: Long) {
        try {
            dir(context).listFiles()?.forEach { f ->
                if (f.name.startsWith("c_${footageId}_") || f.name == "f_$footageId.jpg" || f.name.startsWith("f_${footageId}_h")) f.delete()
            }
            memory.evictAll()
        } catch (e: Exception) {
        }
    }

    private fun grab(context: Context, uri: Uri, timeMs: Long, targetHeight: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(max(0L, timeMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame == null) {
                null
            } else if (frame.height <= targetHeight) {
                frame
            } else {
                val w = max(1, frame.width * targetHeight / frame.height)
                val small = Bitmap.createScaledBitmap(frame, w, targetHeight, true)
                if (small !== frame) frame.recycle()
                small
            }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}

/**
 * A picture that fills its box and appears as soon as it has been made; dark grey (or [background]) until then. [contentScale] Crop fills
 * the box, Fit shows the whole picture; [height] is the picture's height in pixels (bigger for the full-screen viewer).
 */
@Composable
fun Thumb(
    key: String, uri: Uri?, timeMs: Long, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop, height: Int = 360, background: Color = Color(0xFF26262B)
) {
    val context = LocalContext.current
    val picture by produceState<ImageBitmap?>(initialValue = null, key, uri, height) {
        value = ThumbCache.get(context, key, uri, timeMs, height)?.asImageBitmap()
    }
    Box(modifier.background(background)) {
        val p = picture
        if (p != null) {
            Image(
                bitmap = p,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}