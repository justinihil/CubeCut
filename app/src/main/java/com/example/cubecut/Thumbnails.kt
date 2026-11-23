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
    // the Edit window's picture zoomed onto the timer (timerFrame):
    // the frame is read this many pixels high before cutting out the timer: higher keeps the digits sharp when blown up, but
    // costs memory and time on each move of the cut (1080 = about 8 MB, read in a fraction of a second)
    private const val ZOOM_SOURCE_HEIGHT = 1080
    // room left around the timer on every side, as a share of the timer's width. The crop is fixed at the saved corners, so this
    // is also how far the camera may move before the timer leaves the picture; bigger = safer but smaller digits (owner: bigger)
    private const val ZOOM_MARGIN = 0.5
    // the crop is widened to about the shape of the Edit window's picture (full width x 150 dp), so it fills it without bars
    private const val ZOOM_ASPECT = 1.8
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

    /** One frame of the video, [height] pixels high, made fresh and not kept anywhere (the editor's Edit window uses it). */
    fun frame(context: Context, uri: Uri, timeMs: Long, height: Int): Bitmap? = grab(context, uri, timeMs, height)

    /**
     * Like [frame], but zoomed onto the timer: the box around [corners] (the 8 numbers saved at timer setup, in the pixels of the
     * full-size frame) plus [ZOOM_MARGIN] on every side, widened to [ZOOM_ASPECT]. Made fresh and not kept anywhere.
     * The crop doesn't follow the timer, so it assumes the camera didn't move after the setup. Null if the frame or the
     * video's size can't be read (the caller then shows the whole frame).
     */
    fun timerFrame(context: Context, uri: Uri, timeMs: Long, corners: DoubleArray): Bitmap? {
        if (corners.size != 8) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            var fullW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var fullH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (fullW <= 0 || fullH <= 0) return null
            val frame = retriever.getScaledFrameAtTime(
                max(0L, timeMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, ZOOM_SOURCE_HEIGHT * 4, ZOOM_SOURCE_HEIGHT
            ) ?: return null
            // the frame comes upright, but the file may store the size sideways (a portrait video): follow the frame's shape
            if ((frame.width > frame.height) != (fullW > fullH)) { val t = fullW; fullW = fullH; fullH = t }
            val sx = frame.width.toDouble() / fullW
            val sy = frame.height.toDouble() / fullH
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (i in 0 until 4) {
                val x = corners[2 * i] * sx
                val y = corners[2 * i + 1] * sy
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
            val tw = maxX - minX
            val th = maxY - minY
            if (tw < 2 || th < 2) { frame.recycle(); return null }
            val pad = maxOf(tw, th) * ZOOM_MARGIN
            var w = tw + 2 * pad
            var h = th + 2 * pad
            if (w < h * ZOOM_ASPECT) w = h * ZOOM_ASPECT else h = w / ZOOM_ASPECT
            w = minOf(w, frame.width.toDouble())
            h = minOf(h, frame.height.toDouble())
            // centred on the timer, then pushed back inside the frame (a timer near the edge stays near the edge)
            val left = ((minX + maxX) / 2 - w / 2).coerceIn(0.0, frame.width - w)
            val top = ((minY + maxY) / 2 - h / 2).coerceIn(0.0, frame.height - h)
            val cw = max(1, minOf(w.toInt(), frame.width - left.toInt()))
            val ch = max(1, minOf(h.toInt(), frame.height - top.toInt()))
            val out = Bitmap.createBitmap(frame, left.toInt(), top.toInt(), cw, ch)
            if (out !== frame) frame.recycle()
            out
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun grab(context: Context, uri: Uri, timeMs: Long, targetHeight: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val atUs = max(0L, timeMs) * 1000L
            // the phone shrinks the frame itself, so a full 4K picture (about 33 MB) is never handed to the app just to be shrunk
            // (the box is wide enough for any shape of video, so the height decides the size)
            val frame = retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST, targetHeight * 4, targetHeight)
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