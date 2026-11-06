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
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.IOException
import kotlin.math.min

// ---------------------------------------------------------------------------------------
// Where the pictures for Find solves come from.
//
// The scan asks for "the picture at second t" many times. Doing that with MediaMetadataRetriever (RetrieverSource) is slow:
// every single call seeks to a keyframe, sets up a decoder, decodes forward, and builds a Bitmap (measured: about 250 ms each).
//
// CodecSource keeps ONE decoder running instead. Asking for a time a little later than the last one just keeps decoding forward
// (frames that are not wanted are thrown away without being copied), and only a big jump or a step back seeks to a keyframe.
// The picture is taken straight from the decoder's brightness (Y) plane, so there is no Bitmap and no colour conversion.
//
// Safety net: openFrameSource() first checks that the fast path gives the same picture as the old one (and works out the
// rotation); if anything is off, or the decoder fails at any time during the scan, it quietly goes back to RetrieverSource.
// ---------------------------------------------------------------------------------------

/** Hands out the whole upright picture at a given time as a gray Mat (the caller releases it), or null if there is none. */
interface GraySource {
    fun gray(t: Double): Mat?
    fun close()
}

/** The original way: ask Android for one exact frame each time. Slow, but works everywhere. */
class RetrieverSource(context: Context, uri: Uri, private val durationMs: Long) : GraySource {
    private val retriever = MediaMetadataRetriever()

    init {
        try {
            retriever.setDataSource(context, uri)
        } catch (e: Exception) {
            retriever.release()
            throw e
        }
    }

    override fun gray(t: Double): Mat? {
        if (t < 0.0 || t * 1000.0 > durationMs) return null
        val frame = retriever.getFrameAtTime((t * 1_000_000.0).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        return try {
            TimerVision.bitmapToGray(frame)
        } finally {
            frame.recycle()
        }
    }

    override fun close() {
        retriever.release()
    }
}

/** Always a new Mat: [src] turned clockwise by [deg] (0, 90, 180 or 270). */
private fun rotatedCopy(src: Mat, deg: Int): Mat {
    if (deg == 0) return src.clone()
    val out = Mat()
    val code = when (deg) {
        90 -> Core.ROTATE_90_CLOCKWISE
        180 -> Core.ROTATE_180
        else -> Core.ROTATE_90_COUNTERCLOCKWISE
    }
    Core.rotate(src, out, code)
    return out
}

/** One running decoder that is moved along the video as little as possible. */
class CodecSource(context: Context, uri: Uri) : GraySource {
    private val extractor = MediaExtractor()      // feeds the decoder
    private val probe = MediaExtractor()          // only used to ask "where is the keyframe before this time?"
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    private var inputDone = false                 // the end of the video has been sent to the decoder
    private var posUs = -1L                       // how far the decoder has got (time of the last frame it handed out); -1 = must seek first
    private var lastPts = -1L
    private var frameDurUs = 33_333L              // length of one frame; shrinks to the real value as frames come out
    private var fullRange = false                 // true if the brightness already uses 0..255 (otherwise 16..235 is stretched)
    private var scratch = ByteArray(0)
    private var stage: Mat? = null

    /** Degrees clockwise that turn the decoded picture upright. Worked out by [openFrameSource]. */
    var rotation = 0

    init {
        try {
            extractor.setDataSource(context, uri, null)
            probe.setDataSource(context, uri, null)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i)
                    probe.selectTrack(i)
                    format = f
                    break
                }
            }
            if (format == null) throw IOException("no video track")
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)      // no surface: we get the pictures as plain memory
            c.start()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun gray(t: Double): Mat? {
        val raw = rawGray(t) ?: return null
        if (rotation == 0) return raw
        val upright = rotatedCopy(raw, rotation)
        raw.release()
        return upright
    }

    /** The picture at second [t] exactly as the decoder makes it (not turned). */
    fun rawGray(t: Double): Mat? {
        if (t < 0.0) return null
        val targetUs = (t * 1_000_000.0).toLong()
        moveTo(targetUs)
        return decodeTo(targetUs)
    }

    /**
     * Keep decoding forward, or jump? Jumping (seek to the keyframe before the target, flush the decoder) pays off when the
     * next keyframe is clearly ahead of where the decoder is, or when the target is behind it.
     */
    private fun moveTo(targetUs: Long) {
        val c = codec ?: throw IllegalStateException("closed")
        // the usual case: the next picture is just ahead of the decoder. Then no keyframe lookup is needed (a keyframe at or before
        // the target is never more than SEEK_PAYS_OFF_US ahead of the decoder), so skip the probe seek.
        if (posUs >= 0 && targetUs >= posUs - frameDurUs / 2 && targetUs - posUs <= SEEK_PAYS_OFF_US) return
        probe.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val keyUs = max0(probe.sampleTime)
        val mustSeek = posUs < 0 || targetUs < posUs - frameDurUs / 2 || keyUs - posUs > SEEK_PAYS_OFF_US
        if (mustSeek) {
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            c.flush()
            inputDone = false
            lastPts = -1L
            posUs = max0(extractor.sampleTime) - 1
        }
    }

    private fun max0(x: Long): Long = if (x < 0) 0L else x

    /** Decodes until the first frame at (or just before) [targetUs] comes out, and returns it as gray. Null at the end of the video. */
    private fun decodeTo(targetUs: Long): Mat? {
        val c = codec ?: throw IllegalStateException("closed")
        var idle = 0
        while (true) {
            // give the decoder as much input as it will take
            while (!inputDone) {
                val i = c.dequeueInputBuffer(0)
                if (i < 0) break
                val buf = c.getInputBuffer(i) ?: throw IOException("no decoder input buffer")
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    c.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            val o = c.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                idle = 0
                val hasFrame = info.size > 0
                val pts = info.presentationTimeUs
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (hasFrame) {
                    if (lastPts >= 0 && pts > lastPts) frameDurUs = min(frameDurUs, max(1_000L, pts - lastPts))
                    lastPts = pts
                    posUs = pts
                    if (pts >= targetUs - frameDurUs / 2) {
                        try {
                            return toGray(c, o)
                        } finally {
                            c.releaseOutputBuffer(o, false)
                        }
                    }
                }
                c.releaseOutputBuffer(o, false)
                if (end) {
                    posUs = -1L          // the decoder is finished: the next request has to seek
                    return null
                }
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                readOutputFormat(c.outputFormat)
            } else if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                idle++
                if (idle > 400) throw IOException("the decoder stopped delivering frames")
            }
        }
    }

    private fun max(a: Long, b: Long): Long = if (a > b) a else b

    private fun readOutputFormat(f: MediaFormat) {
        try {
            fullRange = f.containsKey(MediaFormat.KEY_COLOR_RANGE) && f.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        } catch (e: Exception) {
        }
    }

    /** The brightness (Y) plane of output buffer [index] as a gray Mat, stretched to 0..255 if the video uses 16..235. */
    private fun toGray(c: MediaCodec, index: Int): Mat {
        val image = c.getOutputImage(index) ?: throw IOException("the decoder gives no readable picture")
        try {
            val plane = image.planes[0]
            if (plane.pixelStride != 1) throw IOException("unexpected picture layout")
            var left = 0
            var top = 0
            var right = image.width
            var bottom = image.height
            val crop = image.cropRect
            if (crop != null && crop.width() > 0 && crop.height() > 0) {
                left = crop.left
                top = crop.top
                right = crop.right
                bottom = crop.bottom
            }
            val stride = plane.rowStride
            val need = stride * bottom
            if (scratch.size < need) scratch = ByteArray(need)
            val buf = plane.buffer
            buf.rewind()
            buf.get(scratch, 0, min(buf.remaining(), need))

            var m = stage
            if (m == null || m.rows() != bottom || m.cols() != stride) {
                m?.release()
                m = Mat(bottom, stride, CvType.CV_8UC1)
                stage = m
            }
            m.put(0, 0, scratch, 0, need)
            val sub = m.submat(top, bottom, left, right)
            val out = Mat()
            try {
                if (fullRange) sub.copyTo(out)
                else sub.convertTo(out, CvType.CV_8U, 255.0 / 219.0, -16.0 * 255.0 / 219.0)
            } finally {
                sub.release()
            }
            return out
        } finally {
            image.close()
        }
    }

    override fun close() {
        try {
            codec?.stop()
        } catch (e: Exception) {
        }
        try {
            codec?.release()
        } catch (e: Exception) {
        }
        codec = null
        stage?.release()
        stage = null
        try {
            extractor.release()
        } catch (e: Exception) {
        }
        try {
            probe.release()
        } catch (e: Exception) {
        }
    }

    private companion object {
        const val SEEK_PAYS_OFF_US = 300_000L    // a keyframe more than this far ahead of the decoder: jump instead of decoding through
    }
}

// ---------------------------------------------------------------------------------------
// Choosing the source
// ---------------------------------------------------------------------------------------

private fun stdDev(a: Mat): Double {
    val mean = MatOfDouble()
    val sd = MatOfDouble()
    try {
        Core.meanStdDev(a, mean, sd)
        return sd.get(0, 0)?.getOrNull(0) ?: 0.0
    } finally {
        mean.release()
        sd.release()
    }
}

/** How alike two same-sized gray pictures are (1.0 = identical shape, ignoring brightness/contrast). */
private fun similarity(a: Mat, b: Mat): Double {
    val sa = Mat()
    val sb = Mat()
    val r = Mat()
    try {
        Imgproc.resize(a, sa, Size(96.0, 96.0), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(b, sb, Size(96.0, 96.0), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.matchTemplate(sa, sb, r, Imgproc.TM_CCOEFF_NORMED)
        val v = r.get(0, 0)?.getOrNull(0) ?: return -1.0
        return if (v.isNaN()) -1.0 else v
    } finally {
        sa.release()
        sb.release()
        r.release()
    }
}

/**
 * Compares the fast picture with the old one at a moment where the picture has some detail, tries the four rotations,
 * and keeps the one that matches. Returns false if nothing matches well (then the fast path is not used).
 */
private fun calibrate(fast: CodecSource, slow: RetrieverSource, durationSec: Double): Boolean {
    for (frac in doubleArrayOf(0.5, 0.3, 0.7)) {
        val t = durationSec * frac
        val ref = slow.gray(t) ?: continue
        val raw = fast.rawGray(t)
        try {
            if (raw == null) return false
            if (stdDev(ref) < 10.0) continue          // an almost flat picture can't tell the rotations apart
            var best = -1.0
            var bestDeg = 0
            for (deg in intArrayOf(0, 90, 180, 270)) {
                val cand = rotatedCopy(raw, deg)
                try {
                    if (cand.cols() != ref.cols() || cand.rows() != ref.rows()) continue
                    val s = similarity(ref, cand)
                    if (s > best) {
                        best = s
                        bestDeg = deg
                    }
                } finally {
                    cand.release()
                }
            }
            Log.d("CubeCut", "Fast frame source check: best match $best at rotation $bestDeg")
            if (best >= 0.85) {
                fast.rotation = bestDeg
                return true
            }
            return false
        } finally {
            ref.release()
            raw?.release()
        }
    }
    return false
}

/** Fast source with the old one as a safety net: if the decoder ever fails, every later read uses the old way. */
private class AutoSource(context: Context, uri: Uri, private val durationMs: Long) : GraySource {
    private val slow = RetrieverSource(context, uri, durationMs)
    private var fast: CodecSource? = null

    init {
        var c: CodecSource? = null
        try {
            c = CodecSource(context, uri)
            if (calibrate(c, slow, durationMs / 1000.0)) {
                fast = c
                Log.d("CubeCut", "Frame source: MediaCodec (fast), rotation ${c.rotation}")
            } else {
                c.close()
                Log.d("CubeCut", "Frame source: MediaMetadataRetriever (the fast picture did not match)")
            }
        } catch (e: Exception) {
            try {
                c?.close()
            } catch (e2: Exception) {
            }
            fast = null
            Log.d("CubeCut", "Frame source: MediaMetadataRetriever (fast path failed: ${e.message})")
        }
    }

    override fun gray(t: Double): Mat? {
        if (t < 0.0 || t * 1000.0 > durationMs) return null
        val f = fast
        if (f != null) {
            try {
                return f.gray(t)
            } catch (e: Exception) {
                Log.d("CubeCut", "Frame source: fast path failed at ${t}s, back to MediaMetadataRetriever: ${e.message}")
                try {
                    f.close()
                } catch (e2: Exception) {
                }
                fast = null
            }
        }
        return slow.gray(t)
    }

    override fun close() {
        try {
            fast?.close()
        } catch (e: Exception) {
        }
        slow.close()
    }
}

/** The picture source for Find solves: the fast decoder if it checks out, otherwise the old way. */
fun openFrameSource(context: Context, uri: Uri, durationMs: Long): GraySource = AutoSource(context, uri, durationMs)