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

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * One cut on the timeline: the moment the timer stopped (the first video frame that shows the final time),
 * the time it showed, and what to export for it.
 */
data class CutItem(
    val stopSec: Double,                  // the first frame that shows the final time, in seconds from the start of the video
    val timeText: String,                 // what the timer showed, e.g. "11.263" ("" = unknown)
    val included: Boolean,                // true = a real solve; false only for a cut left over from before the video began (see isLeftover)
    val note: String,                     // why it needs a look, or "added by you"
    val includeScramble: Boolean = true,  // true = clip runs from the end of the previous clip (scramble included); false = the solve only
    val userNote: String = "",            // your own note about this solve, written in the Gallery (the social media buttons add the platform's name to it)
    val edited: Boolean = false,          // true once you changed this cut yourself (its scramble, time, position, note or check) or added it: Find solves then leaves it alone
    val reviewed: Boolean = false         // you marked its "check" as looked at: its scan note and timing warnings stop flagging it (a solve with no clip stays flagged)
)

/** One video file that will be exported. */
class Clip(
    val number: Int,            // 1, 2, 3... in order
    val startSec: Double,
    val endSec: Double,
    val timeText: String,
    val cutIndex: Int,          // which CutItem it came from (index in the original list)
    val withScramble: Boolean,  // true = starts where the previous clip ended
    val solveStartSec: Double,  // where the solve part starts (the time before the stop, minus the "before" buffer); equals startSec when the scramble is left out
    val noTime: Boolean         // "solve only" was wanted but the time is unknown, so the full clip is used instead
)

/** "12.112" -> 12.112, "1:02.345" -> 62.345, "" or nonsense -> null. */
fun parseTimeSeconds(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val seconds: Double? = if (t.contains(':')) {
        val parts = t.split(':')
        val m = if (parts.size == 2) parts[0].toIntOrNull() else null
        val s = if (parts.size == 2) parts[1].toDoubleOrNull() else null
        if (m != null && s != null) m * 60.0 + s else null
    } else {
        t.toDoubleOrNull()
    }
    return seconds?.takeIf { it > 0.0 && it < 3600.0 }
}

/**
 * Turns the cuts into clips.
 *
 * Every clip ends [afterPad] seconds after the first frame that shows the time (the stop).
 *  - With the scramble (includeScramble = true): it starts where the PREVIOUS CUT ended (the first one at 0:00), whether or not
 *    that previous cut is switched on. So a solve you left out is never dragged into the next clip; its scramble is not
 *    included either. The start is never later than the solve start minus [beforePad], so the solve is always fully inside.
 *  - Without the scramble: it starts (time on the timer + [beforePad]) seconds before the stop = just the solve with a buffer.
 *    If the time is unknown this can't be worked out, so the full clip is used and Clip.noTime says so.
 *
 * A switched-off cut is still a real solve, so it still marks where the next scramble begins. To say "this was not a solve"
 * the user deletes the cut. A cut that the scan switched off because its time was already on the timer when the video began
 * (left over from before) is treated like a deleted one, as long as it stays switched off.
 */
fun computeClips(items: List<CutItem>, beforePad: Double, afterPad: Double, durationSec: Double): List<Clip> {
    val order = items.indices.sortedBy { items[it].stopSec }
    val clips = mutableListOf<Clip>()
    var previousEnd = 0.0          // where the previous cut (exported or not) ends
    var number = 0
    for (i in order) {
        val item = items[i]
        if (!item.included && item.note.startsWith(SolveAnalyzer.LEFTOVER_NOTE_START)) continue   // same as deleted
        val end = if (durationSec > 0) min(item.stopSec + afterPad, durationSec) else item.stopSec + afterPad
        if (end - previousEnd < 1.0) continue          // two cuts too close together: skip the second one
        if (!item.included) {
            previousEnd = end                          // not exported, but the next scramble still starts after it
            continue
        }
        val solveSec = parseTimeSeconds(item.timeText)
        val soloStart: Double? = if (solveSec != null) max(0.0, item.stopSec - solveSec - beforePad) else null
        val start: Double
        val noTime: Boolean
        if (item.includeScramble) {
            start = if (soloStart != null) min(previousEnd, soloStart) else previousEnd
            noTime = false
        } else if (soloStart != null) {
            start = soloStart
            noTime = false
        } else {
            start = previousEnd
            noTime = true
        }
        number++
        clips.add(Clip(number, start, end, item.timeText, i, item.includeScramble || noTime, soloStart ?: start, noTime))
        previousEnd = end
    }
    return clips
}

/** One exported file: the clip it came from, where the file is, and where in that file the final time first shows. */
class ExportedFile(val clip: Clip, val uri: String, val fileName: String, val startSec: Double)

class ExportResult(val done: Int, val failed: List<String>, val message: String, val files: List<ExportedFile> = emptyList())

object ClipExporter {
    private const val FOLDER = "CubeCut"
    private const val START_BUFFER = 4 * 1024 * 1024
    private const val MAX_BUFFER = 64 * 1024 * 1024
    private const val AUDIO_SLACK_US = 25_000L   // one audio frame is about 21 ms; keep the one that starts just before the keyframe

    /**
     * The memory one sample is copied through. Each export() call has its own (shared by its clips, which are exported one after
     * another), so two exports running at the same time (the player's and a batch) can never write into each other's buffer.
     * It starts at 4 MB and only grows if a sample doesn't fit.
     */
    private class SampleBuffer {
        var bytes: ByteBuffer = ByteBuffer.allocate(START_BUFFER)
    }

    /** "1:02.345" -> "1m02.345", "" -> "unknown" (colons are not allowed in file names). */
    fun safeTime(timeText: String): String {
        val t = timeText.trim().replace(':', 'm')
        return if (t.isEmpty()) "unknown" else t
    }

    /** A session title made safe for a file name: no characters Android or PCs refuse, at most 40 characters. */
    fun safeTitle(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().take(40).trim()

    /**
     * "Solve_03_11.263s.mp4", or "Solve_03_unknown.mp4" when the time is not known.
     * With a [prefix] (the session's title): "Sunday practice_Solve_03_11.263s.mp4", numbered like the Gallery ("Solve 3 of 20"),
     * so clips from different videos never get the same name.
     */
    fun fileName(c: Clip, prefix: String = ""): String {
        val t = safeTime(c.timeText)
        val tail = if (t == "unknown") t else t + "s"
        val p = safeTitle(prefix)
        return if (p.isEmpty()) "Solve_%02d_%s.mp4".format(c.number, tail) else "%s_Solve_%02d_%s.mp4".format(p, c.cutIndex + 1, tail)
    }

    /** Saves every clip to Movies/CubeCut (it shows up in the gallery). Runs one clip at a time. [prefix]: see fileName. */
    fun export(context: Context, source: Uri, clips: List<Clip>, prefix: String = "", onProgress: (Int, Int) -> Unit): ExportResult {
        val failed = mutableListOf<String>()
        val files = mutableListOf<ExportedFile>()
        var done = 0
        val buffer = SampleBuffer()
        for ((i, clip) in clips.withIndex()) {
            onProgress(i, clips.size)
            val made = Made()
            val error = exportOne(context, source, (clip.startSec * 1_000_000).toLong(), (clip.endSec * 1_000_000).toLong(), fileName(clip, prefix), made, buffer)
            if (error == null) {
                done++
                val uri = made.uri
                if (uri != null) files.add(ExportedFile(clip, uri, fileName(clip, prefix), if (made.startUs >= 0) made.startUs / 1_000_000.0 else clip.startSec))
            } else {
                failed.add("${fileName(clip, prefix)}: $error")
            }
        }
        onProgress(clips.size, clips.size)
        val where = "Movies/$FOLDER"
        val message = if (failed.isEmpty()) "Exported $done clips to $where" else "Exported $done of ${clips.size} clips to $where. Problems: " + failed.joinToString("; ")
        return ExportResult(done, failed, message, files)
    }

    /** The new file in the phone's gallery (Movies/CubeCut): opened for writing, and its address. */
    private class Output(val pfd: ParcelFileDescriptor, val uri: Uri)

    /** What one finished export reports back: where the new file is and where its first picture comes from in the source video. */
    private class Made(var uri: String? = null, var startUs: Long = -1L)

    // (CubeCut needs Android 11 or newer, so the file always goes to the shared Movies/CubeCut folder, never an app-only one)
    private fun openOutput(context: Context, name: String): Output {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("couldn't create the file in the gallery")
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("couldn't open the new file")
        return Output(pfd, uri)
    }

    /**
     * Copies the part of the video between [startUs] and [endUs] into a new file WITHOUT re-encoding (fast, no quality loss).
     * Because of that, a clip can start a little earlier than asked: it must begin on a "keyframe" (usually within a second).
     * Returns null on success, or a short error message.
     */
    private fun exportOne(context: Context, source: Uri, startUs: Long, endUs: Long, name: String, made: Made, sample: SampleBuffer): String? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var output: Output? = null
        var started = false
        var success = false
        try {
            extractor.setDataSource(context, source, null)

            // keep the picture the right way up
            var rotation = 0
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source)
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }

            val out = openOutput(context, name)
            output = out
            val m = MediaMuxer(out.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            m.setOrientationHint(rotation)

            // copy the video track and the audio track
            val trackMap = HashMap<Int, Int>()
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackMap[i] = m.addTrack(format)
                    if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                }
            }
            if (trackMap.isEmpty()) return "no video or audio found"
            m.start()
            started = true

            // The picture can only start on a keyframe, which may be up to a second BEFORE the time asked for,
            // while the audio starts right at the time asked for. Left like that, the audio would begin late in the
            // clip's own timeline (editors such as TikTok's then show it out of sync). So: find the keyframe the
            // picture really starts on, then seek again to exactly that moment so the audio starts there too.
            var baseUs = -1L
            if (videoTrack >= 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    val t = extractor.sampleTrackIndex
                    if (t < 0) break
                    if (t == videoTrack) {
                        baseUs = extractor.sampleTime
                        break
                    }
                    extractor.advance()
                }
                if (baseUs >= 0) extractor.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            if (baseUs < 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)   // no video track: plain seek
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val timeUs = extractor.sampleTime
                if (timeUs < 0 || timeUs > endUs) break
                val outTrack = trackMap[track]
                if (outTrack != null && baseUs >= 0 && track != videoTrack && timeUs < baseUs - AUDIO_SLACK_US) {
                    extractor.advance()      // audio from before the picture starts: leave it out
                    continue
                }
                if (outTrack != null) {
                    var size = -1
                    while (true) {
                        sample.bytes.clear()
                        try {
                            size = extractor.readSampleData(sample.bytes, 0)
                            break
                        } catch (e: IllegalArgumentException) {
                            // this sample (a big keyframe) is larger than the buffer: make it bigger and read it again
                            val bigger = sample.bytes.capacity() * 2
                            if (bigger > MAX_BUFFER) throw IOException("a video frame is too large to copy")
                            sample.bytes = ByteBuffer.allocate(bigger)
                        }
                    }
                    if (size < 0) break
                    val buffer = sample.bytes
                    if (baseUs < 0) baseUs = timeUs
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, max(0L, timeUs - baseUs), flags)
                    m.writeSampleData(outTrack, buffer, info)
                }
                extractor.advance()
            }
            m.stop()
            started = false
            success = true
            made.uri = out.uri.toString()
            made.startUs = if (baseUs >= 0) baseUs else startUs
            return null
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            try {
                if (started) muxer?.stop()
            } catch (e: Exception) {
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
            }
            extractor.release()
            try {
                output?.pfd?.close()
            } catch (e: Exception) {
            }
            val uri = output?.uri
            if (uri != null) {
                if (success) {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, values, null, null)
                } else {
                    context.contentResolver.delete(uri, null, null)       // don't leave a broken file behind
                }
            }
        }
    }
}