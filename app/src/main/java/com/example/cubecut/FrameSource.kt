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
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
//
// (4.0-0) The pictures are plain GrayPic (SolidDigits.kt) instead of OpenCV Mats, so the app needs no OpenCV. The grey values are the
// same as OpenCV gave: the same brightness formula and the same 16..235 stretch.
// ---------------------------------------------------------------------------------------

/** The brightness weights of OpenCV's RGB to grey, in 1/16384 (Y = 0.299 R + 0.587 G + 0.114 B), so the pictures are the same as before. */
private const val GRAY_R = 4899
private const val GRAY_G = 9617
private const val GRAY_B = 1868
private const val GRAY_SHIFT = 14

/** Pictures are compared at this size when the fast source is checked: small enough to be quick, big enough to tell turns apart. */
private const val CHECK_SIZE = 96

/** The fast source is used when its picture matches the slow one at least this well (1.0 = the same). */
private const val CHECK_MATCH = 0.85

/** A picture flatter than this (standard deviation of its grey values) can't tell the rotations apart. */
private const val CHECK_DETAIL = 10.0

/** Hands out the whole upright picture at a given time as grey, or null if there is none. */
interface GraySource {
    fun gray(t: Double): GrayPic?

    /** The real time of the picture [gray] gave last, or NaN if this source doesn't know it. */
    fun lastFrameSec(): Double = Double.NaN
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

    override fun gray(t: Double): GrayPic? {
        if (t < 0.0 || t * 1000.0 > durationMs) return null
        val frame = retriever.getFrameAtTime((t * 1_000_000.0).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        return try {
            bitmapToGray(frame)
        } finally {
            frame.recycle()
        }
    }

    override fun close() {
        retriever.release()
    }
}

/** [bmp] in grey, with OpenCV's formula (rounded the same way), so a picture reads the same as before 4.0. */
private fun bitmapToGray(bmp: Bitmap): GrayPic {
    val copy = if (bmp.config == Bitmap.Config.ARGB_8888) null else bmp.copy(Bitmap.Config.ARGB_8888, false)
    val src = copy ?: bmp
    try {
        val w = src.width
        val h = src.height
        val out = ByteArray(w * h)
        val row = IntArray(w)                       // one row at a time: a whole 1080p picture as ints would be 8 MB
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[y * w + x] = ((r * GRAY_R + g * GRAY_G + b * GRAY_B + (1 shl (GRAY_SHIFT - 1))) shr GRAY_SHIFT).toByte()
            }
        }
        return GrayPic(w, h, out)
    } finally {
        copy?.recycle()
    }
}

/** [src] turned clockwise by [deg] (0, 90, 180 or 270). 0 gives [src] itself (a GrayPic is never changed in place). */
private fun rotated(src: GrayPic, deg: Int): GrayPic {
    if (deg == 0) return src
    val w = src.w
    val h = src.h
    val d = src.d
    val turned = deg == 90 || deg == 270
    val ow = if (turned) h else w
    val oh = if (turned) w else h
    val out = ByteArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val (nx, ny) = when (deg) {
                90 -> Pair(h - 1 - y, x)
                180 -> Pair(w - 1 - x, h - 1 - y)
                else -> Pair(y, w - 1 - x)
            }
            out[ny * ow + nx] = d[y * w + x]
        }
    }
    return GrayPic(ow, oh, out)
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

    override fun gray(t: Double): GrayPic? {
        val raw = rawGray(t) ?: return null
        return rotated(raw, rotation)
    }

    override fun lastFrameSec(): Double = if (lastPts >= 0) lastPts / 1_000_000.0 else Double.NaN

    /** The picture at second [t] exactly as the decoder makes it (not turned). */
    fun rawGray(t: Double): GrayPic? {
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
    private fun decodeTo(targetUs: Long): GrayPic? {
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

    /** The brightness (Y) plane of output buffer [index] in grey, stretched to 0..255 if the video uses 16..235. */
    private fun toGray(c: MediaCodec, index: Int): GrayPic {
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

            val w = right - left
            val h = bottom - top
            val out = ByteArray(w * h)
            for (y in 0 until h) {
                val from = (top + y) * stride + left
                if (fullRange) {
                    System.arraycopy(scratch, from, out, y * w, w)
                } else {
                    for (x in 0 until w) out[y * w + x] = STRETCH[scratch[from + x].toInt() and 0xFF]
                }
            }
            return GrayPic(w, h, out)
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

        /**
         * 16..235 stretched to 0..255, rounded to the nearest, as OpenCV's convertTo did ((y - 16) * 255 / 219 never lands on a half,
         * so the rounding rule doesn't matter).
         */
        val STRETCH = ByteArray(256) { y -> ((y - 16) * 255.0 / 219.0).roundToInt().coerceIn(0, 255).toByte() }
    }
}

// ---------------------------------------------------------------------------------------
// Choosing the source
// ---------------------------------------------------------------------------------------

/** How much the grey values of [a] spread (their standard deviation). */
private fun stdDev(a: GrayPic): Double {
    var sum = 0.0
    var sq = 0.0
    for (b in a.d) {
        val v = (b.toInt() and 0xFF).toDouble()
        sum += v
        sq += v * v
    }
    val n = a.d.size.toDouble()
    if (n == 0.0) return 0.0
    val mean = sum / n
    return sqrt(maxOf(0.0, sq / n - mean * mean))
}

/** For each of [to] parts of a line of [from] pixels: the pixels it covers and how much of each (an average by area). */
private fun areaWeights(from: Int, to: Int): List<List<Pair<Int, Double>>> {
    val scale = from.toDouble() / to
    return (0 until to).map { i ->
        val a = i * scale
        val b = (i + 1) * scale
        val parts = ArrayList<Pair<Int, Double>>()
        var p = a.toInt()
        while (p < b && p < from) {
            val cover = minOf(b, p + 1.0) - maxOf(a, p.toDouble())
            if (cover > 1e-9) parts.add(Pair(p, cover / scale))
            p++
        }
        parts
    }
}

/** [a] made [CHECK_SIZE] x [CHECK_SIZE] by averaging the pixels each new pixel covers. */
private fun shrunkForCheck(a: GrayPic): DoubleArray {
    val xs = areaWeights(a.w, CHECK_SIZE)
    val ys = areaWeights(a.h, CHECK_SIZE)
    val out = DoubleArray(CHECK_SIZE * CHECK_SIZE)
    for (oy in 0 until CHECK_SIZE) {
        for (ox in 0 until CHECK_SIZE) {
            var v = 0.0
            for ((y, wy) in ys[oy]) {
                val row = y * a.w
                for ((x, wx) in xs[ox]) v += wy * wx * (a.d[row + x].toInt() and 0xFF)
            }
            out[oy * CHECK_SIZE + ox] = v
        }
    }
    return out
}

/** How alike two same-sized gray pictures are (1.0 = identical shape, ignoring brightness/contrast), or -1 if one of them is flat. */
private fun similarity(a: GrayPic, b: GrayPic): Double {
    val sa = shrunkForCheck(a)
    val sb = shrunkForCheck(b)
    val ma = sa.average()
    val mb = sb.average()
    var num = 0.0
    var da = 0.0
    var db = 0.0
    for (i in sa.indices) {
        val x = sa[i] - ma
        val y = sb[i] - mb
        num += x * y
        da += x * x
        db += y * y
    }
    val den = sqrt(da * db)
    return if (den <= 0.0) -1.0 else num / den
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
        if (raw == null) return false
        if (stdDev(ref) < CHECK_DETAIL) continue          // an almost flat picture can't tell the rotations apart
        var best = -1.0
        var bestDeg = 0
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val cand = rotated(raw, deg)
            if (cand.w != ref.w || cand.h != ref.h) continue
            val s = similarity(ref, cand)
            if (s > best) {
                best = s
                bestDeg = deg
            }
        }
        Log.d("CubeCut", "Fast frame source check: best match $best at rotation $bestDeg")
        if (best >= CHECK_MATCH) {
            fast.rotation = bestDeg
            return true
        }
        return false
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

    override fun gray(t: Double): GrayPic? {
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

    override fun lastFrameSec(): Double = fast?.lastFrameSec() ?: Double.NaN

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