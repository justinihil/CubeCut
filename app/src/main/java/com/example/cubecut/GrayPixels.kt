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

import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------
// (4.0) Turning the decoder's brightness plane into the upright grey picture the readers see. Pure, so it is tested on the PC.
//
// A phone video is mostly stored on its side (a rotation tag of 90), so every picture has to be turned. Before 4.0 the whole
// picture was stretched (16..235 to 0..255) into one array and then turned into a second one, a pixel at a time. Now both happen
// in one pass, in tiles (so the turned writes stay in the processor's cache), and only for the part that is wanted: a scan that
// reads a known timer asks for the timer's area alone, a few percent of the picture. Each pixel gets exactly the value it got
// before, so what the readers see doesn't change.
// ---------------------------------------------------------------------------------------

/** Pixels are turned in tiles of this size, so both the reads and the writes stay close together in memory. */
private const val TILE = 64

/**
 * A part of the decoder's picture as it was decoded (not turned): raw pixel (x, y) is `d[(y - y0) * stride + (x - x0)]`.
 * [w] and [h] are the size of the whole raw picture.
 */
class RawPlane(val d: ByteArray, val stride: Int, val x0: Int, val y0: Int, val w: Int, val h: Int)

/** The size of the upright picture of a [w] x [h] raw one turned clockwise by [deg]. */
fun uprightSize(w: Int, h: Int, deg: Int): Pair<Int, Int> = if (deg == 90 || deg == 270) Pair(h, w) else Pair(w, h)

/**
 * Where upright pixel (X, Y) lies in the raw picture, as x = ax + X * xX + Y * xY and y = ay + X * yX + Y * yY. Turning clockwise by
 * [deg] takes raw (x, y) to (h-1-y, x) for 90, (w-1-x, h-1-y) for 180 and (y, w-1-x) for 270, as before 4.0.
 */
private class Turn(deg: Int, w: Int, h: Int) {
    val ax: Int; val xX: Int; val xY: Int
    val ay: Int; val yX: Int; val yY: Int
    init {
        when (deg) {
            90 -> { ax = 0; xX = 0; xY = 1; ay = h - 1; yX = -1; yY = 0 }
            180 -> { ax = w - 1; xX = -1; xY = 0; ay = h - 1; yX = 0; yY = -1 }
            270 -> { ax = w - 1; xX = 0; xY = -1; ay = 0; yX = 1; yY = 0 }
            else -> { ax = 0; xX = 1; xY = 0; ay = 0; yX = 0; yY = 1 }
        }
    }
    fun x(X: Int, Y: Int) = ax + X * xX + Y * xY
    fun y(X: Int, Y: Int) = ay + X * yX + Y * yY
}

/** [a] kept inside a [w] x [h] picture (left and top at least 0, right and bottom at most the size), or null if nothing is left. */
fun clampedArea(a: Area, w: Int, h: Int): Area? {
    val c = Area(max(0, a.left), max(0, a.top), min(w, a.right), min(h, a.bottom))
    return if (c.right > c.left && c.bottom > c.top) c else null
}

/**
 * The raw pixels that upright [area] needs, for a [w] x [h] raw picture turned by [deg]: the box around the four corners
 * ([Area.right] and [Area.bottom] just outside). [area] must lie inside the upright picture.
 */
fun rawAreaFor(area: Area, w: Int, h: Int, deg: Int): Area {
    val t = Turn(deg, w, h)
    val xs = intArrayOf(t.x(area.left, area.top), t.x(area.right - 1, area.top), t.x(area.left, area.bottom - 1), t.x(area.right - 1, area.bottom - 1))
    val ys = intArrayOf(t.y(area.left, area.top), t.y(area.right - 1, area.top), t.y(area.left, area.bottom - 1), t.y(area.right - 1, area.bottom - 1))
    return Area(xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
}

/**
 * Upright [area] of the picture in [raw], turned clockwise by [deg], each value passed through [map] (the 16..235 stretch) unless it
 * is null. [area] is in upright pixels and must lie inside the upright picture, and [raw] must hold the raw pixels it needs ([rawAreaFor]).
 */
fun uprightPart(raw: RawPlane, deg: Int, map: ByteArray?, area: Area): GrayPic {
    val t = Turn(deg, raw.w, raw.h)
    val ow = area.right - area.left
    val oh = area.bottom - area.top
    val out = ByteArray(ow * oh)
    val s = raw.stride
    val d = raw.d
    val dX = t.yX * s + t.xX                // one upright pixel to the right, in the raw part
    val dY = t.yY * s + t.xY                // one upright row down
    var ty = 0
    while (ty < oh) {
        val yEnd = min(oh, ty + TILE)
        var tx = 0
        while (tx < ow) {
            val xEnd = min(ow, tx + TILE)
            for (oy in ty until yEnd) {
                val X = area.left + tx
                val Y = area.top + oy
                var i = (t.y(X, Y) - raw.y0) * s + (t.x(X, Y) - raw.x0)
                var o = oy * ow + tx
                if (map == null) {
                    for (ox in tx until xEnd) { out[o++] = d[i]; i += dX }
                } else {
                    for (ox in tx until xEnd) { out[o++] = map[d[i].toInt() and 0xFF]; i += dX }
                }
            }
            tx = xEnd
        }
        ty = yEnd
    }
    return GrayPic(ow, oh, out)
}

/** The whole picture in [raw] (which must hold all of it), upright and through [map]. */
fun uprightWhole(raw: RawPlane, deg: Int, map: ByteArray?): GrayPic {
    val (w, h) = uprightSize(raw.w, raw.h, deg)
    return uprightPart(raw, deg, map, Area(0, 0, w, h))
}

/** [src] turned clockwise by [deg] (0, 90, 180 or 270). 0 gives [src] itself (a GrayPic is never changed in place). */
fun rotated(src: GrayPic, deg: Int): GrayPic = if (deg == 0) src else uprightWhole(RawPlane(src.d, src.w, 0, 0, src.w, src.h), deg, null)

// ---------------------------------------------------------------------------------------
// (4.0) Frames nothing else needs. Phones store most video in layers (HEVC "temporal sub-layers"): the top layer's frames that are
// marked "non-reference" are never used to decode another frame. The scan wants one picture now and then, so such a frame that
// isn't the one asked for can be left out of the decoder entirely; every frame that is handed out decodes exactly as before.
// ---------------------------------------------------------------------------------------

/** The kind of video, as far as leaving frames out goes. */
enum class FrameCodec { HEVC, AVC, OTHER }

/** How many bytes at the start of a sample are looked at to tell whether it can be left out: its first NAL headers are there. */
const val SAMPLE_HEAD = 512

/**
 * The highest temporal layer an HEVC video uses (sps_max_sub_layers_minus1 of its SPS), from the codec's setup bytes ([csd], with
 * start codes), or -1 if it can't be told: then no frame is left out.
 */
fun hevcTopLayer(csd: ByteArray): Int {
    var top = -1
    for (start in nalHeaders(csd, csd.size)) {
        if (start + 2 >= csd.size) continue
        val type = (csd[start].toInt() shr 1) and 0x3F
        if (type == 33) top = max(top, (csd[start + 2].toInt() shr 1) and 7)     // SPS: 4 bits id, then 3 bits max sub-layers - 1
    }
    return top
}

/**
 * Can the decoder do without this sample? [head] holds the first [size] bytes of one frame (up to [SAMPLE_HEAD]), with start codes as
 * MediaExtractor gives them, or with 4-byte lengths. Its first picture NAL decides. HEVC: sub-layer non-reference (an even NAL type
 * below 16) on [topLayer], the layer nothing is decoded from. AVC: nal_ref_idc 0. Anything that can't be read clearly is needed.
 */
fun isDroppable(head: ByteArray, size: Int, codec: FrameCodec, topLayer: Int): Boolean {
    if (codec == FrameCodec.OTHER || size < 5) return false
    if (codec == FrameCodec.HEVC && topLayer < 0) return false
    for (s in nalHeaders(head, size)) {
        if (codec == FrameCodec.HEVC) {
            if (s + 1 >= size) return false
            val type = (head[s].toInt() shr 1) and 0x3F
            if (type >= 32) continue                                   // parameter sets, SEI, delimiters: look further
            val layer = (head[s + 1].toInt() and 7) - 1
            return type < 16 && type % 2 == 0 && layer == topLayer
        } else {
            val b = head[s].toInt()
            val type = b and 0x1F
            if (type !in 1..5) continue
            return (b shr 5) and 3 == 0 && type != 5
        }
    }
    return false
}

/** The temporal layer of an HEVC frame (its first picture NAL), from the first [size] bytes, or -1 if it can't be told. */
fun hevcLayerOf(head: ByteArray, size: Int): Int {
    for (s in nalHeaders(head, size)) {
        if (s + 1 >= size) return -1
        val type = (head[s].toInt() shr 1) and 0x3F
        if (type >= 32) continue
        return (head[s + 1].toInt() and 7) - 1
    }
    return -1
}

/** Is this HEVC frame an IDR picture (NAL type 19 or 20), after which no frame refers to anything before it? */
fun isHevcIdr(head: ByteArray, size: Int): Boolean {
    for (s in nalHeaders(head, size)) {
        if (s >= size) return false
        val type = (head[s].toInt() shr 1) and 0x3F
        if (type >= 32) continue
        return type == 19 || type == 20
    }
    return false
}

/**
 * (4.0) For reads that may take a nearby frame (the scan's half-second steps): the lowest temporal layer whose frames, with those of
 * the layers below, are never more than [maxGapUs] apart in [samples] ((time, layer), some seconds of the video). A frame of that layer
 * is decoded from frames of that layer and below only, so the frames above can be left out. Gives (layer, how far a read may be from the
 * time asked for: half the largest gap), or (-1, 0) when no layer below [topLayer] is close enough, or a layer can't be told.
 */
fun nearLayerFor(samples: List<Pair<Long, Int>>, topLayer: Int, maxGapUs: Long): Pair<Int, Long> {
    if (samples.size < 16 || samples.any { it.second < 0 }) return Pair(-1, 0L)
    for (layer in 0 until topLayer) {
        val times = samples.filter { it.second <= layer }.map { it.first }.sorted()
        if (times.size < 2) continue
        val gap = times.zipWithNext().maxOf { (a, b) -> b - a }
        if (gap <= maxGapUs) return Pair(layer, gap / 2 + 1_000L)
    }
    return Pair(-1, 0L)
}

/**
 * Where each NAL unit's header starts in the first [size] bytes of [d]: after a 00 00 01 start code (00 00 00 01 too), or, when [d]
 * doesn't begin with one, after each 4-byte length. Lazy, so a caller that stops at the first picture NAL reads no further.
 */
private fun nalHeaders(d: ByteArray, size: Int): Sequence<Int> = sequence {
    val annexB = size >= 3 && d[0].toInt() == 0 && d[1].toInt() == 0 && (d[2].toInt() == 1 || (size >= 4 && d[2].toInt() == 0 && d[3].toInt() == 1))
    if (annexB) {
        var i = 0
        while (i + 2 < size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                yield(i + 3)
                i += 3
            } else i++
        }
    } else {
        var i = 0
        while (i + 4 < size) {
            val n = ((d[i].toInt() and 0xFF) shl 24) or ((d[i + 1].toInt() and 0xFF) shl 16) or ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            if (n <= 0) return@sequence
            yield(i + 4)
            i += 4 + n
        }
    }
}
