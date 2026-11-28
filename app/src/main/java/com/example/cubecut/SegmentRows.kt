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

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// (3.3-C) Finds rows of SEVEN-SEGMENT digits (QiYi and other timers with a display) anywhere in a picture. Their strokes are separate
// pieces, so SolidDigits.kt can't see them. This first look only has to find SOME rows well enough to lay a grid over them
// (SegmentGrid.kt); the real reading is done there.
//
// How: black-and-white by comparing every pixel with its surroundings, close the gaps between the strokes, collect the blobs, chain
// digit-sized blobs into rows, and compare every blob with drawn seven-segment digits, for every lean a row could have.
// No Android and no OpenCV here: the unit tests run exactly the code the phone runs.
// Where the numbers come from: docs/roadmap.md 3.3-C (seven-segment prototype).

/** Sizes of "around it", in pixels, for small to big digits. */
private val BLOCKS = intArrayOf(9, 15, 31, 61)

/**
 * The gaps between strokes are closed with a circle of this share of the block: small ones first, so that digits don't join each other.
 * The smallest is for screen fonts with thick strokes close together (csTimer's "digital 4"): with 0.2, its digits already join each
 * other or the text beside them at the block that suits their size. It costs a third more time for rows.
 */
private val CLOSINGS = doubleArrayOf(0.1, 0.2, 0.33, 0.47)

/** Digits smaller or bigger than this, in pixels, are not looked for. */
private const val MIN_DIGIT = 14
private const val MAX_DIGIT = 220

/** A row steeper than this (height per width), after turning the picture, is not a row of digits. */
private const val MAX_ROW_SLOPE = 0.8

/**
 * A digit in two blobs above each other (see `stacked`): each is this share of their height together, at most this far apart. Tried
 * alongside the blobs themselves; without it a 1 whose two strokes don't touch is left out of a row, and with it the grid.
 */
private const val PART_MIN = 0.25
private const val PART_MAX = 0.7
private const val PART_GAP = 0.25

/**
 * ...and the two are bars (no wider than this share of their height: a 1) or halves of the same width right above each other (an 8),
 * each filling at least this share of its box. Pairing any two blobs made seven times as many rows to read, nearly all of them noise.
 */
private const val PART_WIDE = 0.7
private const val PART_FULL = 0.35

/** A first row needs this many digits, and at most this many blobs next to each other are looked at. */
private const val ROW_DIGITS = 3
private const val ROW_BLOBS = 14
private const val ROW_PART = 8

/** Leans that are tried: how far the top of a digit is to the side of its foot, per height. */
private val ROW_LEANS = DoubleArray(23) { -0.77 + 0.07 * it }

/**
 * (4.0-S2) The leans are first tried this far apart (and the last one), then the ones between around the best: 13 tries instead of 23.
 * Reading the rows of a busy picture (a patterned mat) took most of the time of a video without a timer, most of it in these tries.
 */
private const val LEAN_STEP = 3

/** Below this likeness a blob of a first row isn't counted as a digit; and below this mean the whole row isn't one. */
private const val ROW_DIGIT = 0.55f
private const val ROW_MEAN = 0.5

/** A display can be turned in the picture: rows are looked for along these directions too (degrees). Each covers about 25 degrees more. */
private val ROW_TURNS = doubleArrayOf(0.0, -30.0, 30.0)

/** Every digit is drawn and compared at this size. */
const val SEG_W = 24
const val SEG_H = 40

/**
 * The digits as seven segments (a top, b upper right, c lower right, d bottom, e lower left, f upper left, g middle), with the other ways
 * some displays draw 6, 7 and 9.
 */
val SEGMENT_DIGITS: List<Pair<Int, String>> = listOf(
    0 to "abcdef", 1 to "bc", 2 to "abdeg", 3 to "abcdg", 4 to "bcfg", 5 to "acdfg", 6 to "acdefg", 6 to "cdefg",
    7 to "abc", 7 to "abcf", 8 to "abcdefg", 9 to "abcdfg", 9 to "abcfg"
)

/** A flat mapping: (x, y) -> (a x + b y + c, d x + e y + f). */
class Flat(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun x(x: Double, y: Double) = a * x + b * y + c
    fun y(x: Double, y: Double) = d * x + e * y + f

    /** First [first], then this. */
    fun after(first: Flat) = Flat(
        a * first.a + b * first.d, a * first.b + b * first.e, a * first.c + b * first.f + c,
        d * first.a + e * first.d, d * first.b + e * first.e, d * first.c + e * first.f + f
    )

    fun inverse(): Flat {
        val det = a * e - b * d
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Flat(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }

    companion object {
        /** Makes lines with this [slope] level and digits with this [lean] upright: y' = y - slope x, x' = x + lean y'. */
        fun level(slope: Double, lean: Double) = Flat(1 - lean * slope, lean, 0.0, -slope, 1.0, 0.0)

        fun turn(degrees: Double): Flat {
            val r = Math.toRadians(degrees)
            return Flat(Math.cos(r), Math.sin(r), 0.0, -Math.sin(r), Math.cos(r), 0.0)
        }
    }
}

/** A first row of seven-segment digits. Its numbers are in the level picture that [turn], then [slope] and [lean], make of the frame. */
class SegmentRow(
    val text: String,               // '?' where a blob was no clear digit
    val score: Double,              // mean likeness
    val turn: Flat, val slope: Double, val lean: Double,
    val height: Double, val digitWidth: Double, val top: Double, val bottom: Double,
    val rights: DoubleArray,        // the digits' right edges, left to right
    val dark: Boolean
)

// ---------------------------------------------------------------------------------------
// black-and-white pictures, 64 pixels at a time
// ---------------------------------------------------------------------------------------

/** A black-and-white picture, one bit per pixel. */
class Bits(val w: Int, val h: Int) {
    val stride = (w + 63) / 64
    val d = LongArray(stride * h)
    private val lastMask: Long = if (w % 64 == 0) -1L else (1L shl (w % 64)) - 1

    fun set(x: Int, y: Int) {
        val i = y * stride + (x shr 6)
        d[i] = d[i] or (1L shl (x and 63))
    }

    /** Every pixel within reach of a set one: up to [halfWidths] to the side, one entry per row above and below. */
    private fun grown(halfWidths: IntArray): Bits {
        val out = Bits(w, h)
        val r = halfWidths.size / 2
        val widest = halfWidths.max()
        val by = Array(widest + 1) { LongArray(stride) }        // the row, grown sideways by 0, 1, 2...
        for (y in 0 until h) {
            var any = 0L
            val row = y * stride
            for (i in 0 until stride) { by[0][i] = d[row + i]; any = any or d[row + i] }
            if (any == 0L) continue
            for (n in 1..widest) {
                val a = by[n - 1]
                val b = by[n]
                for (i in 0 until stride) {
                    val left = if (i > 0) a[i - 1] ushr 63 else 0L
                    val right = if (i < stride - 1) a[i + 1] shl 63 else 0L
                    b[i] = a[i] or (a[i] shl 1) or left or (a[i] ushr 1) or right
                }
                b[stride - 1] = b[stride - 1] and lastMask
            }
            for (k in halfWidths.indices) {
                val ty = y + k - r
                if (ty < 0 || ty >= h) continue
                val g = by[halfWidths[k]]
                val to = ty * stride
                for (i in 0 until stride) out.d[to + i] = out.d[to + i] or g[i]
            }
        }
        return out
    }

    private fun flip() {
        for (i in d.indices) d[i] = d[i].inv()
        for (y in 0 until h) { val i = y * stride + stride - 1; d[i] = d[i] and lastMask }
    }

    /** Gaps narrower than a circle [k] pixels wide (an odd number) are filled; nothing else grows. */
    fun closed(k: Int): Bits {
        val r = k / 2
        val halfWidths = IntArray(k) { val dy = it - r; (r * sqrt((r * r - dy * dy) / (r * r).toDouble())).roundToInt() }
        val grown = grown(halfWidths)
        grown.flip()
        val closed = grown.grown(halfWidths)
        closed.flip()
        return closed
    }

    fun toBooleans(): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (i in 0 until stride) {
            var v = d[y * stride + i]
            while (v != 0L) {
                out[y * w + i * 64 + java.lang.Long.numberOfTrailingZeros(v)] = true
                v = v and (v - 1)
            }
        }
        return out
    }
}

/**
 * Where a pixel differs from the average of the [block] x [block] pixels around it: darker, and lighter. How much is needed goes with the
 * brightness (a tenth of it, 2 to 6): a faint display in a dark room must count, the noise of a bright wall must not.
 */
fun darkerAndLighter(p: GrayPic, block: Int): Pair<Bits, Bits> {
    val w = p.w
    val h = p.h
    val r = block / 2
    val area = block * block
    val dark = Bits(w, h)
    val light = Bits(w, h)
    val v = IntArray(w * h)
    for (i in v.indices) v[i] = p.d[i].toInt() and 255
    val column = IntArray(w + 2 * r)
    for (x in 0 until w) {
        var s = 0
        for (k in -r..r) s += v[(if (k < 0) 0 else if (k >= h) h - 1 else k) * w + x]
        column[r + x] = s
    }
    for (y in 0 until h) {
        if (y > 0) {
            val leave = (if (y - 1 - r < 0) 0 else y - 1 - r) * w
            val enter = (if (y + r >= h) h - 1 else y + r) * w
            for (x in 0 until w) column[r + x] += v[enter + x] - v[leave + x]
        }
        val first = column[r]
        val last = column[r + w - 1]
        for (k in 0 until r) { column[k] = first; column[r + w + k] = last }
        var s = 0
        for (k in 0 until 2 * r + 1) s += column[k]
        val row = y * w
        for (x in 0 until w) {
            if (x > 0) s += column[x + 2 * r] - column[x - 1]
            val mean = (s + area / 2) / area
            val limit = if (mean < 20) 20 else if (mean > 60) 60 else mean
            val darker = 10 * (mean - v[row + x])
            if (darker > limit) dark.set(x, y) else if (-darker > limit) light.set(x, y)
        }
    }
    return Pair(dark, light)
}

// ---------------------------------------------------------------------------------------
// drawn digits
// ---------------------------------------------------------------------------------------

/** [a] ([w] x [h]) softened by a Gaussian; outside, the picture is mirrored. */
fun softened(a: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
    val r = max(1, (4 * sigma).roundToInt())
    val k = FloatArray(2 * r + 1) { val x = it - r; Math.exp(-x * x / (2 * sigma * sigma)).toFloat() }
    val sum = k.sum()
    for (i in k.indices) k[i] /= sum
    val column = IntArray(w + 2 * r) { mirror(it - r, w) }
    val line = IntArray(h + 2 * r) { mirror(it - r, h) }
    val across = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        var s = 0f
        for (i in k.indices) s += k[i] * a[y * w + column[x + i]]
        across[y * w + x] = s
    }
    val out = FloatArray(w * h)
    for (y in 0 until h) for (i in k.indices) {
        val from = line[y + i] * w
        val f = k[i]
        for (x in 0 until w) out[y * w + x] += f * across[from + x]
    }
    return out
}

private fun mirror(i: Int, n: Int): Int {
    if (n == 1) return 0
    var j = i
    while (j < 0 || j >= n) j = if (j < 0) -j else 2 * n - 2 - j
    return j
}

/** Evens [a] out so that two of them are compared by multiplying: 1 = identical. False if there is nothing in it. */
fun evenOut(a: FloatArray): Boolean {
    var mean = 0f
    for (x in a) mean += x
    mean /= a.size
    var n = 0f
    for (i in a.indices) { a[i] -= mean; n += a[i] * a[i] }
    if (n <= 1e-12f) return false
    n = 1f / sqrt(n)
    for (i in a.indices) a[i] *= n
    return true
}

/** A digit drawn with [segments], [SEG_W] x [SEG_H], 0 to 1, not softened. */
fun drawnDigit(segments: String): FloatArray {
    val s = 8                                   // drawn this many times bigger first
    val w = SEG_W * s
    val h = SEG_H * s
    val t = 5 * s                               // a stroke's thickness
    val g = s                                   // half the gap between two strokes
    val m = h / 2
    val shapes = mapOf(
        'a' to intArrayOf(g, 0, w - g, 0, w - t, t, t, t), 'd' to intArrayOf(g, h, w - g, h, w - t, h - t, t, h - t),
        'f' to intArrayOf(0, g, t, t, t, m - t / 2, 0, m - g), 'b' to intArrayOf(w, g, w - t, t, w - t, m - t / 2, w, m - g),
        'e' to intArrayOf(0, h - g, t, h - t, t, m + t / 2, 0, m + g), 'c' to intArrayOf(w, h - g, w - t, h - t, w - t, m + t / 2, w, m + g),
        'g' to intArrayOf(2 * g, m, t, m - t / 2, w - t, m - t / 2, w - 2 * g, m, w - t, m + t / 2, t, m + t / 2)
    )
    fun inside(q: IntArray, x: Int, y: Int): Boolean {          // (all of them bulge outwards)
        var plus = false
        var minus = false
        val n = q.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            val side = (q[2 * j] - q[2 * i]) * (y - q[2 * i + 1]) - (q[2 * j + 1] - q[2 * i + 1]) * (x - q[2 * i])
            if (side > 0) plus = true
            if (side < 0) minus = true
        }
        return !(plus && minus)
    }
    val out = FloatArray(SEG_W * SEG_H)
    val drawn = segments.map { shapes.getValue(it) }
    for (y in 0 until h) for (x in 0 until w) if (drawn.any { inside(it, x, y) }) out[(y / s) * SEG_W + x / s] += 1f / (s * s)
    return out
}

private fun halved(a: FloatArray): FloatArray {
    val w = SEG_W / 2
    val out = FloatArray(w * SEG_H / 2)
    for (y in 0 until SEG_H / 2) for (x in 0 until w) {
        val i = 2 * y * SEG_W + 2 * x
        out[y * w + x] = a[i] + a[i + 1] + a[i + SEG_W] + a[i + SEG_W + 1]
    }
    return out
}

private val ROW_SHAPES: List<FloatArray> by lazy { SEGMENT_DIGITS.map { (_, s) -> softened(drawnDigit(s), SEG_W, SEG_H, 1.2).also { evenOut(it) } } }
private val ROUGH_SHAPES: List<FloatArray> by lazy { ROW_SHAPES.map { s -> halved(s).also { evenOut(it) } } }

/**
 * (4.0-S2) The shapes cell by cell: all of them for cell 0, then for cell 1, and so on, so that [likenesses] compares a place with every
 * shape in one pass. Reading the rows of a busy picture spent a quarter of its time on these comparisons, one shape after another.
 */
private val ROW_BY_CELL: FloatArray by lazy { byCell(ROW_SHAPES) }
private val ROUGH_BY_CELL: FloatArray by lazy { byCell(ROUGH_SHAPES) }

private fun byCell(shapes: List<FloatArray>): FloatArray {
    val n = shapes.size
    val cells = shapes[0].size
    return FloatArray(cells * n) { val cell = it / n; shapes[it % n][cell] }
}

/**
 * (4.0-S2) [likeness] of [c] with every shape of [byCell] (made by [byCell]), into [into]: each sum is added up cell by cell in the
 * same order as [likeness], so the numbers are exactly the same; the shapes' sums no longer wait for each other.
 */
private fun likenesses(c: FloatArray, byCell: FloatArray, into: FloatArray) {
    val n = into.size
    java.util.Arrays.fill(into, 0f)
    var k = 0
    for (i in c.indices) {
        val v = c[i]
        for (s in 0 until n) { into[s] += v * byCell[k]; k++ }
    }
}

/**
 * (4.0-S2) [softened] for one size and [sigma], with its tables made once: the same sums in the same order, so the same numbers. Every
 * digit place is softened, at two sizes only, and making the tables again each time was a large part of reading a row.
 */
private class Softener(private val w: Int, private val h: Int, sigma: Double) {
    private val k: FloatArray
    private val column: IntArray
    private val line: IntArray

    init {
        val r = max(1, (4 * sigma).roundToInt())
        k = FloatArray(2 * r + 1) { val x = it - r; Math.exp(-x * x / (2 * sigma * sigma)).toFloat() }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        column = IntArray(w + 2 * r) { mirror(it - r, w) }
        line = IntArray(h + 2 * r) { mirror(it - r, h) }
    }

    fun apply(a: FloatArray): FloatArray {
        val across = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in k.indices) s += k[i] * a[y * w + column[x + i]]
            across[y * w + x] = s
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (i in k.indices) {
            val from = line[y + i] * w
            val f = k[i]
            for (x in 0 until w) out[y * w + x] += f * across[from + x]
        }
        return out
    }
}

private val ROUGH_SOFT = Softener(SEG_W / 2, SEG_H / 2, 0.6)
private val FINE_SOFT = Softener(SEG_W, SEG_H, 1.2)

// ---------------------------------------------------------------------------------------
// rows
// ---------------------------------------------------------------------------------------

/** A digit-sized blob, or two above each other ([parts]), with its box in the turned picture. */
private class Piece(val parts: List<Blob>, val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val w = x1 - x0
    val h = y1 - y0
    val cx = (x0 + x1) / 2
    val cy = (y0 + y1) / 2
    val area = parts.sumOf { it.area }
}

/** The seven-segment rows of [pic], best first. The work is shared between the processor's cores. */
fun segmentRows(pic: GrayPic): List<SegmentRow> {
    val masks = arrayOfNulls<Pair<Bits, Bits>>(BLOCKS.size)
    IntStream.range(0, BLOCKS.size).parallel().forEach { masks[it] = darkerAndLighter(pic, BLOCKS[it]) }
    class Job(val block: Int, val mask: Bits, val dark: Boolean, val k: Int)
    val jobs = ArrayList<Job>()
    for ((i, block) in BLOCKS.withIndex()) for (k in CLOSINGS.map { max(3, (block * it).toInt() or 1) }.distinct()) {
        jobs.add(Job(block, masks[i]!!.first, true, k))
        jobs.add(Job(block, masks[i]!!.second, false, k))
    }
    val found = arrayOfNulls<List<SegmentRow>>(jobs.size)
    IntStream.range(0, jobs.size).parallel().forEach { i ->
        val j = jobs[i]
        found[i] = rowsOf(Blobs(j.mask.closed(j.k).toBooleans(), pic.w, pic.h), j.block, j.dark)
    }
    return found.flatMap { it!! }.sortedByDescending { it.score }
}

private fun rowsOf(blobs: Blobs, block: Int, dark: Boolean): List<SegmentRow> {
    val least = max(MIN_DIGIT.toDouble(), block * 1.1)
    val most = min(MAX_DIGIT.toDouble(), block * 5.5)
    // (in the turned picture a blob is at most this much bigger or smaller; a part of a digit is smaller)
    val whole = blobs.list.filter { b -> max(b.w, b.h) * 1.42 >= least && max(b.w, b.h) <= 1.42 * 1.6 * most }
    val near = whole + partnered(blobs.list, least, most)
    val out = ArrayList<SegmentRow>()
    for (degrees in ROW_TURNS) {
        val turn = Flat.turn(degrees)
        val boxes = near.map { b -> if (degrees == 0.0) Piece(listOf(b), b.x0.toDouble(), b.y0.toDouble(), b.x1 + 1.0, b.y1 + 1.0) else turned(blobs, listOf(b), turn) }
        val pieces = ArrayList<Piece>()
        for (p in boxes + stacked(boxes, least, most)) {
            if (p.h >= least && p.h <= most && p.w <= 1.6 * p.h && p.w >= 0.07 * p.h && p.area >= 0.06 * p.w * p.h) pieces.add(p)
        }
        if (pieces.size >= ROW_DIGITS) chain(blobs, pieces.sortedBy { it.cx }, turn, dark, out)
    }
    return out
}

/**
 * The blobs too small to be a digit that have a blob close enough to be the rest of one (see [stacked]), judged by their boxes before
 * turning. Turning every small blob would cost most of the time for rows, and nearly all of them are alone.
 */
private fun partnered(list: List<Blob>, least: Double, most: Double): List<Blob> {
    val parts = list.filter { b -> max(b.w, b.h) * 1.42 >= PART_MIN * least && max(b.w, b.h) <= 1.42 * PART_MAX * most }.sortedBy { it.x0 }
    val small = HashSet<Blob>()
    for ((i, a) in parts.withIndex()) for (j in i + 1 until parts.size) {
        val b = parts[j]
        val span = max(max(a.x1, b.x1) - min(a.x0, b.x0), max(a.y1, b.y1) - min(a.y0, b.y0)) + 1
        if (b.x0 - a.x1 > PART_GAP * 1.42 * span) { if (b.x0 - a.x1 > PART_GAP * 1.42 * 1.42 * most) break else continue }
        if (span * 1.42 < least || span > 1.42 * most) continue
        if (max(b.y0 - a.y1, a.y0 - b.y1) > PART_GAP * 1.42 * span) continue
        if (max(a.w, a.h) * 1.42 < least) small.add(a)
        if (max(b.w, b.h) * 1.42 < least) small.add(b)
    }
    return small.toList()
}

/**
 * Two blobs above each other that together are a digit: a 1 whose two strokes don't touch (csTimer's "digital" fonts, a drawn
 * display), or a digit such as an 8 in two halves of the same width. Each is [PART_MIN] to [PART_MAX] of their height together, and they overlap across. The blobs stay
 * pieces on their own too.
 */
private fun stacked(boxes: List<Piece>, least: Double, most: Double): List<Piece> {
    val out = ArrayList<Piece>()
    val byX = boxes.filter { it.h >= PART_MIN * least && it.h <= PART_MAX * most && it.area >= PART_FULL * it.w * it.h }.sortedBy { it.x0 }
    for ((i, a) in byX.withIndex()) for (j in i + 1 until byX.size) {
        val b = byX[j]
        if (b.x0 > a.x1) break
        val overlap = min(a.x1, b.x1) - max(a.x0, b.x0)
        if (overlap < 0.5 * min(a.w, b.w)) continue
        val bars = a.w <= PART_WIDE * a.h && b.w <= PART_WIDE * b.h
        val halves = overlap >= 0.8 * max(a.w, b.w) && min(a.w, b.w) >= 0.75 * max(a.w, b.w)
        if (!bars && !halves) continue
        val (upper, lower) = if (a.cy < b.cy) Pair(a, b) else Pair(b, a)
        val y0 = upper.y0
        val y1 = lower.y1
        val h = y1 - y0
        if (h < least || h > most || lower.y0 - upper.y1 > PART_GAP * h || lower.y0 < upper.y1 - 0.1 * h) continue
        if (upper.h < PART_MIN * h || upper.h > PART_MAX * h || lower.h < PART_MIN * h || lower.h > PART_MAX * h) continue
        out.add(Piece(upper.parts + lower.parts, min(a.x0, b.x0), y0, max(a.x1, b.x1), y1))
    }
    return out
}

private fun turned(blobs: Blobs, parts: List<Blob>, m: Flat): Piece {
    val box = DoubleArray(4)
    boxIn(runsOf(blobs, parts), m, box)
    return Piece(parts, box[0], box[1], box[2], box[3])
}

private fun runsOf(blobs: Blobs, parts: List<Blob>): IntArray =
    if (parts.size == 1) blobs.runsOf(parts[0]) else parts.map { blobs.runsOf(it) }.reduce { x, y -> x + y }

/** The box (left, top, right, bottom) around [runs] (start, end, y...) after the mapping [m]. */
private fun boxIn(runs: IntArray, m: Flat, into: DoubleArray) {
    var x0 = 1e9
    var x1 = -1e9
    var y0 = 1e9
    var y1 = -1e9
    var i = 0
    while (i < runs.size) {
        val y = runs[i + 2].toDouble()
        for (k in 0..1) {
            val x = runs[i + k].toDouble()
            val tx = m.x(x, y)
            val ty = m.y(x, y)
            if (tx < x0) x0 = tx
            if (tx > x1) x1 = tx
            if (ty < y0) y0 = ty
            if (ty > y1) y1 = ty
        }
        i += 3
    }
    into[0] = x0; into[1] = y0; into[2] = x1 + 1; into[3] = y1 + 1
}

/** Chains digit-sized blobs, left to right, and reads what comes of it. */
private fun chain(blobs: Blobs, pieces: List<Piece>, turn: Flat, dark: Boolean, out: MutableList<SegmentRow>) {
    val n = pieces.size
    val next = IntArray(n) { -1 }
    val hasPrevious = BooleanArray(n)
    for (i in 0 until n) {
        val a = pieces[i]
        var best = 1e9
        for (j in i + 1 until n) {
            val b = pieces[j]
            val dx = b.cx - a.cx
            val h = (a.h + b.h) / 2
            // (sorted by the middle; a much taller one further right can't be in the same row as this one)
            if (dx > 2.2 * h) { if (dx > 2.2 * 1.225 * a.h) break else continue }
            if (dx < 0.25 * h || max(a.h, b.h) / min(a.h, b.h) > 1.45 || abs(b.cy - a.cy) > 0.75 * dx) continue
            val gap = b.x0 - a.x1
            if (gap > h || gap < -0.45 * h) continue
            val cost = dx + 2 * abs(b.cy - a.cy)
            if (cost < best) { best = cost; next[i] = j }
        }
    }
    for (j in next) if (j >= 0) hasPrevious[j] = true
    for (i in 0 until n) {
        if (hasPrevious[i]) continue
        val row = ArrayList<Piece>()
        var k = i
        while (k >= 0) { row.add(pieces[k]); k = next[k] }
        if (row.size < ROW_DIGITS || row.size > ROW_BLOBS) continue
        for (a in 0 until max(1, row.size - ROW_PART + 1)) {
            readSegmentRow(blobs, row.subList(a, min(row.size, a + ROW_PART)), turn, dark)?.let { out.add(it) }
        }
    }
}

/** One piece of a row (one blob, or two above each other), cut out. */
private class Cut(blobs: Blobs, parts: List<Blob>) {
    private val bx0 = parts.minOf { it.x0 }
    private val by0 = parts.minOf { it.y0 }
    private val w = parts.maxOf { it.x1 } - bx0 + 1
    private val h = parts.maxOf { it.y1 } - by0 + 1
    // (4.0-S2) 1 on the blob and 0 elsewhere, with a border of 0 all round: read without checking the edges, the same values as before
    private val pw = w + 2
    private val on = FloatArray(pw * (h + 2))
    val runs: IntArray = runsOf(blobs, parts)

    init {
        for (i in runs.indices step 3) for (x in runs[i]..runs[i + 1]) on[(runs[i + 2] - by0 + 1) * pw + x - bx0 + 1] = 1f
    }

    /** The part of the level picture between these edges as one digit's place. A quarter of the cells ([rough]) is enough to choose a lean. */
    fun place(back: Flat, left: Double, right: Double, top: Double, bottom: Double, rough: Boolean): FloatArray {
        val cw = if (rough) SEG_W / 2 else SEG_W
        val ch = if (rough) SEG_H / 2 else SEG_H
        val out = FloatArray(cw * ch)
        for (cy in 0 until ch) {
            val ly = top + (cy + 0.5) * (bottom - top) / ch - 0.5
            for (cx in 0 until cw) {
                val lx = left + (cx + 0.5) * (right - left) / cw - 0.5
                val fx = back.x(lx, ly) - bx0
                val fy = back.y(lx, ly) - by0
                if (fx <= -1 || fy <= -1 || fx >= w || fy >= h) continue
                val ix = Math.floor(fx).toInt()
                val iy = Math.floor(fy).toInt()
                val u = (fx - ix).toFloat()
                val v = (fy - iy).toFloat()
                val k = (iy + 1) * pw + ix + 1
                out[cy * cw + cx] = (on[k] * (1 - u) + on[k + 1] * u) * (1 - v) + (on[k + pw] * (1 - u) + on[k + pw + 1] * u) * v
            }
        }
        return if (rough) ROUGH_SOFT.apply(out) else FINE_SOFT.apply(out)
    }
}

private fun middleOf(v: List<Double>): Double {
    val s = v.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

private fun readSegmentRow(blobs: Blobs, row: List<Piece>, turn: Flat, dark: Boolean): SegmentRow? {
    val n = row.size
    val mx = row.sumOf { it.cx } / n
    val my = row.sumOf { it.cy } / n
    val across = row.sumOf { (it.cx - mx) * (it.cx - mx) }
    val slope = if (across > 0) row.sumOf { (it.cx - mx) * (it.cy - my) } / across else 0.0
    if (abs(slope) > MAX_ROW_SLOPE) return null
    val cuts = row.map { Cut(blobs, it.parts) }
    val boxes = Array(n) { DoubleArray(4) }
    val digits = IntArray(n)
    val likes = FloatArray(n)
    val sums = FloatArray(SEGMENT_DIGITS.size)

    class Level(val lean: Double, val score: Double, val height: Double, val digitWidth: Double, val top: Double, val bottom: Double)

    fun at(lean: Double, rough: Boolean): Level {
        val m = Flat.level(slope, lean).after(turn)
        val back = m.inverse()
        for (i in 0 until n) boxIn(cuts[i].runs, m, boxes[i])
        val height = middleOf(boxes.map { it[3] - it[1] })
        val wide = boxes.map { it[2] - it[0] }.filter { it > 0.35 * height && it < 1.3 * height }
        val digitWidth = if (wide.isEmpty()) 0.55 * height else middleOf(wide)
        val top = middleOf(boxes.map { it[1] })
        val bottom = middleOf(boxes.map { it[3] })
        var sum = 0.0
        for (i in 0 until n) {
            val b = boxes[i]
            digits[i] = -1
            likes[i] = 0f
            if (b[2] - b[0] > 1.3 * digitWidth) continue
            // (a 1 is only the right edge of its place)
            val c = cuts[i].place(back, b[2] - max(digitWidth, b[2] - b[0]), b[2], top, bottom, rough)
            if (!evenOut(c)) continue
            likenesses(c, if (rough) ROUGH_BY_CELL else ROW_BY_CELL, sums)
            var best = -2f
            for (s in sums.indices) { val x = sums[s]; if (x > best) { best = x; digits[i] = SEGMENT_DIGITS[s].first } }
            likes[i] = best
            sum += best
        }
        return Level(lean, sum / n, height, digitWidth, top, bottom)
    }

    val roughScore = DoubleArray(ROW_LEANS.size) { Double.NaN }
    fun roughAt(i: Int): Double { if (roughScore[i].isNaN()) roughScore[i] = at(ROW_LEANS[i], true).score; return roughScore[i] }
    val start = ((0 until ROW_LEANS.size step LEAN_STEP) + (ROW_LEANS.size - 1)).distinct().maxByOrNull { roughAt(it) }!!
    val rough = (max(0, start - LEAN_STEP + 1)..min(ROW_LEANS.size - 1, start + LEAN_STEP - 1)).maxByOrNull { roughAt(it) }!!
    val close = (max(0, rough - 1)..min(ROW_LEANS.size - 1, rough + 1)).map { at(ROW_LEANS[it], false) }
    val best = close.maxByOrNull { it.score }!!
    if (best.score < ROW_MEAN) return null
    at(best.lean, false)                                // (fills the boxes, digits and likenesses again)
    val all = String(CharArray(n) { if (digits[it] >= 0 && likes[it] >= ROW_DIGIT) '0' + digits[it] else '?' })
    // (a blob at either end that is no digit, such as the last digit grown into the text beside it, would put the grid out)
    val first = all.indexOfFirst { it != '?' }
    val last = all.indexOfLast { it != '?' }
    if (first < 0) return null
    val text = all.substring(first, last + 1)
    if (text.count { it != '?' } < ROW_DIGITS) return null
    return SegmentRow(
        text, best.score, turn, slope, best.lean, best.height, best.digitWidth, best.top, best.bottom,
        (first..last).map { boxes[it][2] }.sorted().toDoubleArray(), dark
    )
}
