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

    /** (4.0) Only [area] of it (kept inside the picture): the same pixels, made much quicker when the source can (CodecSource). */
    fun grayArea(t: Double, area: Area): AreaPic? = gray(t)?.let { AreaPic.of(it, area) }

    /** The real time of the picture [gray] gave last, or NaN if this source doesn't know it. */
    fun lastFrameSec(): Double = Double.NaN

    /** (4.0) Where the time for pictures went, for the debug Scan report; null if this source doesn't count. */
    fun stats(): FrameStats? = null

    /**
     * (4.0) [on]: from now on a picture may be a frame up to about a frame or two from the time asked for, if that is quicker
     * (CodecSource: the video's lower layers only). For the scan's half-second steps; its frame-by-frame search switches it off.
     * [lastFrameSec] always tells the real time.
     */
    fun setNear(on: Boolean) {}

    /** (4.0) Which source this is, and why (debug Scan report). */
    fun note(): String = ""

    /** (4.0) [on]: make the picture the scan will most likely ask for next while it reads this one (PrefetchSource). */
    fun setAhead(on: Boolean) {}
    fun close()
}

/** (4.0) Where the time for getting pictures goes (debug Scan report). [since] tells the part after an earlier [copy]. */
class FrameStats {
    var decodeNanos = 0L        // waiting for the decoder, and moving it
    var greyNanos = 0L          // turning its pictures into upright grey
    var decoded = 0             // frames that came out of the decoder
    var dropped = 0             // frames left out because nothing needs them (isDroppable)
    var jumps = 0               // seeks to a keyframe

    fun copy(): FrameStats = FrameStats().also {
        it.decodeNanos = decodeNanos; it.greyNanos = greyNanos; it.decoded = decoded; it.dropped = dropped; it.jumps = jumps
    }

    /** "decoder 41.2 s (38102 frames, 25014 left out, 512 jumps), grey 3.1 s" for the part after [before]. */
    fun since(before: FrameStats): String =
        "decoder ${"%.1f".format((decodeNanos - before.decodeNanos) / 1e9)} s (${decoded - before.decoded} frames, " +
                "${dropped - before.dropped} left out, ${jumps - before.jumps} jumps), grey ${"%.1f".format((greyNanos - before.greyNanos) / 1e9)} s"
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

/**
 * One running decoder that is moved along the video as little as possible. [stats]: where its counts go (a decoder started again after a
 * failure adds to the counts of the one before, so the report's parts stay right).
 */
class CodecSource(context: Context, uri: Uri, val stats: FrameStats = FrameStats()) : GraySource {
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
    private var kind = FrameCodec.OTHER           // (4.0) how to tell a frame nothing needs (isDroppable)
    private var topLayer = -1                     // (4.0) HEVC: the highest temporal layer, whose non-reference frames nothing needs
    private val head = ByteArray(SAMPLE_HEAD)
    private val recentIn = java.util.TreeSet<Long>()          // (4.0) the last input times, to know one frame's length (see noteInput)
    private val recentOrder = ArrayDeque<Long>()
    private var format: MediaFormat? = null
    private var near = false                      // (4.0) setNear: a read may take the nearest frame of the lower layers
    private var nearLayer = -1                    // ...the highest layer decoded then (-1 = no such layers, near reads are exact)
    private var nearSlackUs = 0L                  // ...and how far such a frame may be from the time asked for
    private var nearChecked = false
    private var holes = false                     // frames other frames need were left out since the last IDR: an exact read must jump

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
            this.format = format
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (LEAVE_OUT_FRAMES) {
                kind = when (mime) {
                    MediaFormat.MIMETYPE_VIDEO_HEVC -> FrameCodec.HEVC
                    MediaFormat.MIMETYPE_VIDEO_AVC -> FrameCodec.AVC
                    else -> FrameCodec.OTHER
                }
                if (kind == FrameCodec.HEVC) topLayer = try {
                    format.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }?.let { hevcTopLayer(it) } ?: -1
                } catch (e: Exception) { -1 }
            }
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)      // no surface: we get the pictures as plain memory
            c.start()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun gray(t: Double): GrayPic? = picture(t) { c, o -> planePart(c, o, rotation, null).pic }

    override fun grayArea(t: Double, area: Area): AreaPic? = picture(t) { c, o -> planePart(c, o, rotation, area) }

    override fun lastFrameSec(): Double = if (lastPts >= 0) lastPts / 1_000_000.0 else Double.NaN

    override fun stats(): FrameStats = stats

    override fun setNear(on: Boolean) {
        if (!STEPS_NEAR) return
        if (on && !nearChecked) findNearLayer()
        near = on
    }

    /** "turned 90°; steps read layers 0-1 of 0-3 (frames up to 33 ms from the time)", for the report. */
    fun describe(): String = "turned ${rotation}°" + when {
        !nearChecked -> ""
        nearLayer < 0 -> "; steps read every frame"
        else -> "; steps read layers 0-$nearLayer of 0-$topLayer (up to ${nearSlackUs / 1000} ms from the time)"
    }

    private fun nearOn() = near && nearLayer >= 0

    /** (4.0) Which layers the half-second steps need (nearLayerFor), from a few seconds in the middle of the video. */
    private fun findNearLayer() {
        nearChecked = true
        if (kind != FrameCodec.HEVC || topLayer <= 0) return
        try {
            val f = format ?: return
            val durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            val maxIn = if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = java.nio.ByteBuffer.allocate(if (maxIn > 0) maxIn else 8 shl 20)
            probe.seekTo(durUs / 2, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val samples = ArrayList<Pair<Long, Int>>()
            val h = ByteArray(SAMPLE_HEAD)
            while (samples.size < NEAR_SAMPLES) {
                buf.clear()
                val size = probe.readSampleData(buf, 0)
                if (size < 0) break
                val n = min(size, SAMPLE_HEAD)
                for (k in 0 until n) h[k] = buf.get(k)
                samples.add(Pair(probe.sampleTime, hevcLayerOf(h, n)))
                probe.advance()
            }
            val (layer, slack) = nearLayerFor(samples, topLayer, NEAR_GAP_US)
            nearLayer = layer
            nearSlackUs = slack
        } catch (e: Exception) {
            nearLayer = -1
        }
        Log.d("CubeCut", "Frame source: ${describe()}")
    }

    /** The picture at second [t] exactly as the decoder makes it (not turned). */
    fun rawGray(t: Double): GrayPic? = picture(t) { c, o -> planePart(c, o, 0, null).pic }

    /** The decoder moved to second [t], and its picture there made by [make]; the time is counted in [stats]. */
    private fun <T> picture(t: Double, make: (MediaCodec, Int) -> T): T? {
        if (t < 0.0) return null
        val start = System.nanoTime()
        val greyBefore = stats.greyNanos
        try {
            val targetUs = (t * 1_000_000.0).toLong()
            moveTo(targetUs)
            return decodeTo(targetUs, make)
        } finally {
            stats.decodeNanos += System.nanoTime() - start - (stats.greyNanos - greyBefore)
        }
    }

    /**
     * Keep decoding forward, or jump? Jumping (seek to the keyframe before the target, flush the decoder) pays off when the
     * next keyframe is clearly ahead of where the decoder is, or when the target is behind it.
     */
    private fun moveTo(targetUs: Long) {
        val c = codec ?: throw IllegalStateException("closed")
        // the usual case: the next picture is just ahead of the decoder. Then no keyframe lookup is needed (a keyframe at or before
        // the target is never more than SEEK_PAYS_OFF_US ahead of the decoder), so skip the probe seek.
        // (4.0) an exact read after frames other frames need were left out: decode again from a keyframe, with every layer
        val refill = holes && !nearOn()
        if (!refill && posUs >= 0 && targetUs >= posUs - frameDurUs / 2 && targetUs - posUs <= SEEK_PAYS_OFF_US) return
        probe.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val keyUs = max0(probe.sampleTime)
        val mustSeek = refill || posUs < 0 || targetUs < posUs - frameDurUs / 2 || keyUs - posUs > SEEK_PAYS_OFF_US
        if (mustSeek) {
            stats.jumps++
            holes = false
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            c.flush()
            inputDone = false
            lastPts = -1L
            posUs = max0(extractor.sampleTime) - 1
        }
    }

    private fun max0(x: Long): Long = if (x < 0) 0L else x

    /**
     * Decodes until the first frame at (or just before) [targetUs] comes out, and returns what [make] makes of it. Null at the end
     * of the video. (4.0) A frame before the target that no other frame is decoded from is left out (isDroppable): it could never
     * be handed out for this target, and nothing else needs it, so every frame that is handed out is the same as before.
     */
    private fun <T> decodeTo(targetUs: Long, make: (MediaCodec, Int) -> T): T? {
        val c = codec ?: throw IllegalStateException("closed")
        var idle = 0
        while (true) {
            // give the decoder as much input as it will take
            while (!inputDone) {
                val i = c.dequeueInputBuffer(0)
                if (i < 0) break
                val buf = c.getInputBuffer(i) ?: throw IOException("no decoder input buffer")
                while (true) {
                    buf.clear()
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        break
                    }
                    val sampleUs = extractor.sampleTime
                    noteInput(sampleUs)
                    if (nearOn()) {
                        // (4.0) a step of the scan: only the lower layers are decoded; the frame handed out is the nearest of those
                        val n = min(size, SAMPLE_HEAD)
                        for (k in 0 until n) head[k] = buf.get(k)
                        if (isHevcIdr(head, n)) holes = false
                        val layer = hevcLayerOf(head, n)
                        if (layer > nearLayer) {
                            if (!isDroppable(head, n, kind, topLayer)) holes = true
                            stats.dropped++
                            extractor.advance()
                            continue
                        }
                    } else if (kind != FrameCodec.OTHER && sampleUs < targetUs - frameDurUs / 2) {
                        val n = min(size, SAMPLE_HEAD)
                        for (k in 0 until n) head[k] = buf.get(k)
                        if (isDroppable(head, n, kind, topLayer)) {
                            stats.dropped++
                            extractor.advance()
                            continue                // the same input buffer takes the next sample
                        }
                    }
                    c.queueInputBuffer(i, 0, size, sampleUs, 0)
                    extractor.advance()
                    break
                }
            }

            val o = c.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                idle = 0
                val hasFrame = info.size > 0
                val pts = info.presentationTimeUs
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (hasFrame) {
                    stats.decoded++
                    if (lastPts >= 0 && pts > lastPts) frameDurUs = min(frameDurUs, max(1_000L, pts - lastPts))
                    lastPts = pts
                    posUs = pts
                    if (pts >= targetUs - (if (nearOn()) nearSlackUs else frameDurUs / 2)) {
                        try {
                            return make(c, o)
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

    /**
     * (4.0) One frame's length, from the times of the frames sent to the decoder (each against its neighbours in time). Since frames
     * are left out, the frames that come out are no longer next to each other, so their times alone could make it look twice as long.
     */
    private fun noteInput(us: Long) {
        if (!recentIn.add(us)) return
        recentOrder.addLast(us)
        recentIn.lower(us)?.let { frameDurUs = min(frameDurUs, max(1_000L, us - it)) }
        recentIn.higher(us)?.let { frameDurUs = min(frameDurUs, max(1_000L, it - us)) }
        if (recentOrder.size > 32) recentIn.remove(recentOrder.removeFirst())
    }

    /**
     * The brightness (Y) plane of output buffer [index], turned clockwise by [deg] and stretched to 0..255 if the video uses 16..235:
     * the whole picture, or (4.0) only upright [wanted] (kept inside the picture), in one pass (uprightPart, GrayPixels.kt). Only the
     * rows and columns needed are copied out of the decoder.
     */
    private fun planePart(c: MediaCodec, index: Int, deg: Int, wanted: Area?): AreaPic {
        val start = System.nanoTime()
        try {
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
                val w = right - left
                val h = bottom - top
                val (uw, uh) = uprightSize(w, h, deg)
                val area = if (wanted == null) Area(0, 0, uw, uh) else Area(wanted.left, wanted.top, min(uw, wanted.right), min(uh, wanted.bottom))
                if (area.left < 0 || area.top < 0 || area.right <= area.left || area.bottom <= area.top) {
                    // an area partly outside the picture: cut from the whole picture, as before 4.0 (AreaPic.of)
                    return AreaPic.of(planePart(c, index, deg, null).pic, wanted!!)
                }
                val raw = rawAreaFor(area, w, h, deg)
                val rw = raw.right - raw.left
                val rh = raw.bottom - raw.top
                if (scratch.size < rw * rh) scratch = ByteArray(rw * rh)
                val stride = plane.rowStride
                val buf = plane.buffer
                for (y in 0 until rh) {
                    buf.position((top + raw.top + y) * stride + left + raw.left)
                    buf.get(scratch, y * rw, rw)
                }
                val pic = uprightPart(RawPlane(scratch, rw, raw.left, raw.top, w, h), deg, if (fullRange) null else STRETCH, area)
                return AreaPic(pic, area, uw, uh)
            } finally {
                image.close()
            }
        } finally {
            stats.greyNanos += System.nanoTime() - start
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
         * (4.0) Leave out frames nothing needs (isDroppable): about a third of a Samsung phone's HEVC frames, so the decoder has a third
         * less to do. Every picture handed out stays the same. False switches it off (for comparing on a phone).
         */
        const val LEAVE_OUT_FRAMES = true

        /**
         * (4.0) A half-second step of the scan may take the nearest frame of the video's lower layers, when those come at least this
         * often (Samsung, 60 fps: every 4th frame, 67 ms; 30 fps: every 2nd): at most a frame or two from the time asked for, and a
         * half or more of the frames need no decoding. Larger would save more, but the steps would land further off.
         */
        const val NEAR_GAP_US = 70_000L

        /** ...worked out from this many frames in the middle of the video. */
        const val NEAR_SAMPLES = 240

        /**
         * (4.0) Steps may take the nearest frame of the lower layers. Off at first (2026-11-27): with every step 2 frames late QiYi 01
         * got one false solve, with every step 2 frames early one flowtimer cut moved 2 frames. Both fixed in FinderScan (TOO_EARLY_SEC;
         * the cut search from the pictures' real times); then every reference video was as good as before or better. False switches it off.
         */
        const val STEPS_NEAR = true

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
 * and keeps the one that matches. Returns null when one matches, else why not (then the fast path is not used).
 * (4.0) A moment that doesn't match (the two sources can give neighbouring frames, which differ when hands move fast) no longer
 * decides alone: the next of the three moments is tried.
 */
private fun calibrate(fast: CodecSource, slow: RetrieverSource, durationSec: Double): String? {
    val tried = ArrayList<String>()
    for (frac in doubleArrayOf(0.5, 0.3, 0.7)) {
        val t = durationSec * frac
        val ref = slow.gray(t) ?: continue
        val raw = fast.rawGray(t) ?: return "no fast picture at ${videoPlace(t)}"
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
            return null
        }
        tried.add("${"%.2f".format(best)} at ${videoPlace(t)}")
    }
    return if (tried.isEmpty()) "no picture with enough detail to compare" else "best match " + tried.joinToString(", ")
}

/**
 * (4.0) How often the fast decoder is started again in one video after it failed, before the old way takes over. A phone can take its
 * decoder away for a moment (owner's phone, 2026-11-28: it failed 45 s into a 21 min video, with no message, and the old way then took
 * 1057 s instead of about 100).
 */
private const val DECODER_RESTARTS = 2

/** (4.0) An exception in words for the report: its kind, its message if it has one, and what MediaCodec adds. */
private fun errorText(e: Exception): String {
    val message = e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
    val codec = (e as? MediaCodec.CodecException)?.let { " (${it.diagnosticInfo}; transient ${it.isTransient}, recoverable ${it.isRecoverable})" } ?: ""
    return e.javaClass.simpleName + message + codec
}

/**
 * Fast source with the old one as a safety net. (4.0) If the decoder fails, a new one is started (at most [DECODER_RESTARTS] times); only
 * if that fails too does every later read use the old way.
 */
private class AutoSource(private val context: Context, private val uri: Uri, private val durationMs: Long) : GraySource {
    private val slow = RetrieverSource(context, uri, durationMs)
    private var fast: CodecSource? = null
    private var counted: FrameStats? = null       // the fast source's counters (kept after a fall back, for the report)
    private var why = ""                          // (4.0) why the slow source is used, for the report
    private var slowFrom = -1.0                   // ...from which second of the video on (-1 = from the start, or not at all)
    private var nearOn = false                    // (4.0) the last setNear, for a decoder started again
    private val restarted = ArrayList<String>()   // (4.0) where and why the decoder was started again, for the report

    init {
        var c: CodecSource? = null
        try {
            c = CodecSource(context, uri)
            val check = calibrate(c, slow, durationMs / 1000.0)
            if (check == null) {
                fast = c
                counted = c.stats
                Log.d("CubeCut", "Frame source: MediaCodec (fast), rotation ${c.rotation}")
            } else {
                c.close()
                why = "the fast picture did not match the slow one ($check)"
                Log.d("CubeCut", "Frame source: MediaMetadataRetriever (the fast picture did not match)")
            }
        } catch (e: Exception) {
            try {
                c?.close()
            } catch (e2: Exception) {
            }
            fast = null
            why = "the decoder failed to start: ${errorText(e)}"
            Log.d("CubeCut", "Frame source: MediaMetadataRetriever (fast path failed: ${e.message})")
        }
    }

    override fun gray(t: Double): GrayPic? {
        if (t < 0.0 || t * 1000.0 > durationMs) return null
        var f = fast
        while (f != null) {
            try {
                return f.gray(t)
            } catch (e: Exception) {
                f = recover(f, t, e)
            }
        }
        return slow.gray(t)
    }

    /**
     * The decoder [f] failed when asked for second [t]: it is closed, and a new one started in its place (the same turn, the same counts),
     * at most [DECODER_RESTARTS] times. Returns the new one, or null when the old way takes over from here.
     */
    private fun recover(f: CodecSource, t: Double, e: Exception): CodecSource? {
        val what = errorText(e)
        Log.d("CubeCut", "Frame source: fast path failed at ${t}s: $what")
        try {
            f.close()
        } catch (e2: Exception) {
        }
        fast = null
        var failed = what
        if (restarted.size < DECODER_RESTARTS) {
            restarted.add("${videoPlace(t)} ($what)")
            try {
                val c = CodecSource(context, uri, f.stats)
                c.rotation = f.rotation
                c.setNear(nearOn)
                fast = c
                return c
            } catch (e2: Exception) {
                failed = "$what, then it failed to start again: ${errorText(e2)}"
            }
        }
        slowFrom = t
        why = "the decoder failed: $failed"
        return null
    }

    override fun setNear(on: Boolean) {
        nearOn = on
        fast?.setNear(on)
    }

    override fun note(): String {
        val f = fast
        val again = if (restarted.isEmpty()) "" else "; the decoder was started again at " + restarted.joinToString(", ")
        return when {
            f != null -> "fast (MediaCodec), " + f.describe() + again
            slowFrom >= 0 -> "SLOW (MediaMetadataRetriever) from ${videoPlace(slowFrom)} on: $why$again"
            else -> "SLOW (MediaMetadataRetriever): $why"
        }
    }

    override fun grayArea(t: Double, area: Area): AreaPic? {
        if (t < 0.0 || t * 1000.0 > durationMs) return null
        var f = fast
        while (f != null) {
            try {
                return f.grayArea(t, area)
            } catch (e: Exception) {
                f = recover(f, t, e)
            }
        }
        return slow.gray(t)?.let { AreaPic.of(it, area) }
    }

    override fun lastFrameSec(): Double = fast?.lastFrameSec() ?: Double.NaN

    override fun stats(): FrameStats? = counted

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