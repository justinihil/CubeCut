package com.example.cubecut

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
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
    val included: Boolean,                // true = this cut is exported as a clip
    val note: String,                     // why it needs a look, or "added by you"
    val includeScramble: Boolean = true   // true = clip runs from the end of the previous clip (scramble included); false = the solve only
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

class ExportResult(val done: Int, val failed: List<String>, val message: String)

object ClipExporter {
    private const val FOLDER = "CubeCut"
    private const val START_BUFFER = 4 * 1024 * 1024
    private const val MAX_BUFFER = 64 * 1024 * 1024

    // one buffer for all clips (clips are exported one after another); it only grows if a sample doesn't fit
    private var sampleBuffer: ByteBuffer = ByteBuffer.allocate(START_BUFFER)

    /** "1:02.345" -> "1m02.345", "" -> "unknown" (colons are not allowed in file names). */
    fun safeTime(timeText: String): String {
        val t = timeText.trim().replace(':', 'm')
        return if (t.isEmpty()) "unknown" else t
    }

    /** "Solve_03_11.263s.mp4", or "Solve_03_unknown.mp4" when the time is not known. */
    fun fileName(c: Clip): String {
        val t = safeTime(c.timeText)
        val tail = if (t == "unknown") t else t + "s"
        return "Solve_%02d_%s.mp4".format(c.number, tail)
    }

    /** Saves every clip to Movies/CubeCut (it shows up in the gallery). Runs one clip at a time. */
    fun export(context: Context, source: Uri, clips: List<Clip>, onProgress: (Int, Int) -> Unit): ExportResult {
        val failed = mutableListOf<String>()
        var done = 0
        for ((i, clip) in clips.withIndex()) {
            onProgress(i, clips.size)
            val error = exportOne(context, source, (clip.startSec * 1_000_000).toLong(), (clip.endSec * 1_000_000).toLong(), fileName(clip))
            if (error == null) done++ else failed.add("${fileName(clip)}: $error")
        }
        onProgress(clips.size, clips.size)
        val where = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Movies/$FOLDER" else "the app's Movies/$FOLDER folder"
        val message = if (failed.isEmpty()) "Exported $done clips to $where" else "Exported $done of ${clips.size} clips to $where. Problems: " + failed.joinToString("; ")
        return ExportResult(done, failed, message)
    }

    private class Output(val pfd: ParcelFileDescriptor?, val uri: Uri?, val path: String?)

    private fun openOutput(context: Context, name: String): Output {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            return Output(pfd, uri, null)
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), FOLDER)
        dir.mkdirs()
        return Output(null, null, File(dir, name).absolutePath)
    }

    /**
     * Copies the part of the video between [startUs] and [endUs] into a new file WITHOUT re-encoding (fast, no quality loss).
     * Because of that, a clip can start a little earlier than asked: it must begin on a "keyframe" (usually within a second).
     * Returns null on success, or a short error message.
     */
    private fun exportOne(context: Context, source: Uri, startUs: Long, endUs: Long, name: String): String? {
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
            val m = if (out.pfd != null) MediaMuxer(out.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else MediaMuxer(out.path!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            m.setOrientationHint(rotation)

            // copy the video track and the audio track
            val trackMap = HashMap<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackMap[i] = m.addTrack(format)
                }
            }
            if (trackMap.isEmpty()) return "no video or audio found"
            m.start()
            started = true

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var baseUs = -1L
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val timeUs = extractor.sampleTime
                if (timeUs < 0 || timeUs > endUs) break
                val outTrack = trackMap[track]
                if (outTrack != null) {
                    var size = -1
                    while (true) {
                        sampleBuffer.clear()
                        try {
                            size = extractor.readSampleData(sampleBuffer, 0)
                            break
                        } catch (e: IllegalArgumentException) {
                            // this sample (a big keyframe) is larger than the buffer: make it bigger and read it again
                            val bigger = sampleBuffer.capacity() * 2
                            if (bigger > MAX_BUFFER) throw IOException("a video frame is too large to copy")
                            sampleBuffer = ByteBuffer.allocate(bigger)
                        }
                    }
                    if (size < 0) break
                    val buffer = sampleBuffer
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