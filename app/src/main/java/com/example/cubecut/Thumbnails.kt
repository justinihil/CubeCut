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
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
// The folder is kept under about 100 MB: when it grows past that, the pictures that were used longest ago are removed.
// ---------------------------------------------------------------------------------------

object ThumbCache {
    private const val TARGET_HEIGHT = 360
    private const val DISK_LIMIT = 100L * 1024 * 1024        // above this the folder is trimmed...
    private const val DISK_TRIM_TO = 80L * 1024 * 1024       // ...down to this, so it isn't trimmed again after every new picture
    private const val TRIM_EVERY = 25                        // the folder is measured after this many new pictures (and once per app start)
    private var madeSinceTrim = TRIM_EVERY                   // starts "due", so the first new picture after a start measures it

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

    /** The picture for [key] if it is already in memory, without waiting (so a picture that is ready is drawn in the very first frame). */
    fun peek(key: String, height: Int = TARGET_HEIGHT): Bitmap? = memory.get(nameOf(key, height))

    private fun nameOf(key: String, height: Int): String =
        if (height == TARGET_HEIGHT) key else "${key}_h$height"      // bigger pictures (for the full-screen viewer) are kept apart

    /**
     * The picture for [key]. [uri] and [timeMs] say where to take it from if it doesn't exist yet
     * (a null [uri] = the video can't be opened, so only an already made picture can be returned).
     */
    suspend fun get(context: Context, key: String, uri: Uri?, timeMs: Long, height: Int = TARGET_HEIGHT): Bitmap? {
        val name = nameOf(key, height)
        val cached = memory.get(name)
        if (cached != null) return cached
        return withContext(Dispatchers.IO) {
            val folder = dir(context)
            val file = File(folder, "$name.jpg")
            var bmp: Bitmap? = null
            if (file.exists()) {
                bmp = BitmapFactory.decodeFile(file.path)
                if (bmp != null) file.setLastModified(System.currentTimeMillis())   // "used just now", so trimming keeps it
            }
            if (bmp == null && uri != null) {
                gate.withPermit {
                    bmp = grab(context, uri, timeMs, height)
                    val made = bmp
                    if (made != null) {
                        save(made, folder, file)
                        trimIfDue(folder)
                    }
                }
            }
            val result = bmp
            if (result != null) memory.put(name, result)
            result
        }
    }

    /**
     * Writes the picture to a temporary file first and only then gives it its real name, so a half-written picture (the app
     * closed while saving, or the phone ran out of space) is never mistaken for a finished one.
     */
    private fun save(picture: Bitmap, folder: File, file: File) {
        val temp = File(folder, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { out -> picture.compress(Bitmap.CompressFormat.JPEG, 80, out) }
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()          // not saved: it will just be made again next time
        }
    }

    /** Every [TRIM_EVERY] new pictures: if the folder is over [DISK_LIMIT], remove the pictures used longest ago. */
    private fun trimIfDue(folder: File) {
        synchronized(this) {
            madeSinceTrim++
            if (madeSinceTrim < TRIM_EVERY) return
            madeSinceTrim = 0
        }
        try {
            val files = folder.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= DISK_LIMIT) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= DISK_TRIM_TO) break
                val size = f.length()
                if (f.delete()) total -= size
            }
        } catch (e: Exception) {
            // trimming is housekeeping: if it fails, the pictures still work
        }
    }

    /**
     * Removes the pictures of one session (when it is forgotten): its video's picture (f_), its clips' grid pictures (c_) and
     * viewer pictures (v_), and the pictures taken from its exported files (x_ and xv_, named by [exportIds]).
     */
    fun forgetFootage(context: Context, footageId: Long, exportIds: Collection<Long> = emptyList()) {
        try {
            val exportNames = exportIds.flatMap { id -> listOf("x_$id", "xv_$id") }.toSet()
            dir(context).listFiles()?.forEach { f ->
                val n = f.name
                val base = n.substringBefore(".jpg").substringBefore("_h")       // the name without "_h900" and ".jpg"
                if (n.startsWith("c_${footageId}_") || n.startsWith("v_${footageId}_") ||
                    n == "f_$footageId.jpg" || n.startsWith("f_${footageId}_h") || base in exportNames
                ) f.delete()
            }
            memory.evictAll()
        } catch (e: Exception) {
        }
    }

    private fun grab(context: Context, uri: Uri, timeMs: Long, targetHeight: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val atUs = max(0L, timeMs) * 1000L
            // Android 8.1 and newer: the phone shrinks the frame itself, so a full 4K picture (about 33 MB) is never handed to the
            // app just to be shrunk. (The box is wide enough for any shape of video, so the height decides the size.)
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST, targetHeight * 4, targetHeight)
            } else {
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
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
    contentScale: ContentScale = ContentScale.Crop, height: Int = 360, background: Color = Color(0xFF26262B),
    placeholderKey: String? = null
) {
    val context = LocalContext.current
    // a picture already in memory is used straight away; only a missing one is waited for
    val ready = remember(key, height) { ThumbCache.peek(key, height)?.asImageBitmap() }
    val picture by produceState(initialValue = ready, key, uri, height) {
        value = ready            // (also when the key changes: never keep showing the previous picture)
        if (value == null) value = ThumbCache.get(context, key, uri, timeMs, height)?.asImageBitmap()
    }
    // a picture that is already made (e.g. the grid's small one) is shown while the wanted one is still being made, so there is no empty frame
    val placeholderReady = remember(placeholderKey) { placeholderKey?.let { ThumbCache.peek(it)?.asImageBitmap() } }
    val placeholder by produceState(initialValue = placeholderReady, placeholderKey) {
        value = placeholderReady
        if (value == null && placeholderKey != null) value = ThumbCache.get(context, placeholderKey, null, 0L)?.asImageBitmap()
    }
    Box(modifier.background(background)) {
        val p = picture ?: placeholder
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