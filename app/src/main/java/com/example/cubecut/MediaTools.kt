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
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max

// How many pictures make up the film strip.
const val THUMB_COUNT = 12

// ---------------------------------------------------------------------------------------
// Helpers that read the video file (all of these run in the background, not on the screen thread).
// Since the cuts now come from reading the timer, the audio is no longer analysed at all.
// ---------------------------------------------------------------------------------------

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (ms % 1000) / 100
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}

fun readDurationMs(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        retriever.release()
    }
}

/** Grabs [count] evenly spaced pictures from the video, shrunk to a small size. */
fun buildThumbnails(context: Context, uri: Uri, durationMs: Long, count: Int, onProgress: (Float) -> Unit = {}): List<ImageBitmap> {
    val retriever = MediaMetadataRetriever()
    val result = mutableListOf<ImageBitmap>()
    try {
        retriever.setDataSource(context, uri)
        for (i in 0 until count) {
            onProgress(i.toFloat() / count)
            val timeUs = ((i + 0.5) / count * durationMs * 1000).toLong()
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: continue
            val targetHeight = 160
            val targetWidth = max(1, frame.width * targetHeight / frame.height)
            val small = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
            if (small !== frame) frame.recycle()
            result.add(small.asImageBitmap())
        }
    } finally {
        retriever.release()
    }
    return result
}

/** Grabs one exact picture from the video at [ms]. */
fun grabFrame(context: Context, uri: Uri, ms: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
    } finally {
        retriever.release()
    }
}