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

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// (3.3-C) Reads times written in SOLID digits (csTimer, phone timer apps: every stroke joined) anywhere in a picture, without being told
// where they are, how big they are or which font it is. Seven-segment displays are not read here: their strokes are separate pieces.
//
// How: make the picture black-and-white by comparing every pixel with its surroundings, collect the joined blobs, chain digit-sized
// blobs that sit next to each other into rows, undo the slope of each row, and compare every blob with example digits.
// There is no Android and no OpenCV in this file, so the unit tests run exactly the code the phone runs.

/** A pixel counts as part of a digit when it is this much lighter than the average around it. Lower finds fainter digits, but also noise. */
private const val LIGHTER_BY = 18

/** Sizes of "around it", in pixels. A stroke must be thinner than this, so big digits need a big one and small print a small one. */
val SEARCH_BLOCKS = intArrayOf(31, 91, 181)

/** Every digit is compared at this size. Smaller is faster, but 6 and 8 start to look the same. */
private const val SHAPE_W = 24
private const val SHAPE_H = 28
private const val SHAPE_SIZE = SHAPE_W * SHAPE_H

/** The example digits are also tried leaning, because a row's slope is undone by shifting columns, which leaves turned digits leaning. */
private val LEANS = FloatArray(11) { (it - 5) * 0.1f }

/** How many digits of the rough look are compared properly. 6 and 8, or 1 and 7, can change places in the rough look. */
private const val LIKELY_DIGITS = 4

/** A blob less alike than this is no digit at all; the rest of its row needn't be compared. Below any likeness a caller asks for. */
private const val HOPELESS = 0.35f

/** Below this likeness (1 = identical) a blob is not called a digit, and its row is dropped. */
const val MIN_LIKENESS = 0.6f

/** Two neighbouring digits are at most this far apart, in digit heights... */
private const val MAX_GAP = 0.75

/** ...or this far, when a decimal point or a colon stands between them: in fixed-width fonts those are as wide as a digit. */
private const val MAX_GAP_WITH_MARK = 1.2

/** A row steeper than this (height per width) is not a row of digits. */
private const val MAX_SLOPE = 0.8

/** One time seen in a picture. [text] is as shown, e.g. "12.69" or "1:04.42"; [height] is the digits' height in pixels. */
class TimeRow(
    val text: String,
    val likeness: Float,        // of its least certain digit
    val height: Double,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val dark: Boolean,          // dark digits on a light background
    val block: Int,             // which of SEARCH_BLOCKS it was found with
    val shapes: List<FloatArray>
) {
    val seconds: Double = shownSeconds(text)
    val decimals: Int = text.length - 1 - text.indexOf('.')
}

/** A grey picture: one byte per pixel, row by row. */
class GrayPic(val w: Int, val h: Int, val d: ByteArray) {
    /** The part from ([x0], [y0]) up to, not including, ([x1], [y1]). */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): GrayPic {
        val cw = x1 - x0
        val out = ByteArray(cw * (y1 - y0))
        for (y in y0 until y1) System.arraycopy(d, y * w + x0, out, (y - y0) * cw, cw)
        return GrayPic(cw, y1 - y0, out)
    }

    /** [by] times smaller: every pixel is the average of [by] x [by]. Big digits don't need all their pixels to be read. */
    fun shrunk(by: Int): GrayPic {
        if (by <= 1) return this
        val nw = w / by
        val nh = h / by
        val out = ByteArray(nw * nh)
        val sums = IntArray(nw)
        val half = by * by / 2
        for (y in 0 until nh) {
            sums.fill(0)
            for (k in 0 until by) {
                var i = (y * by + k) * w
                for (x in 0 until nw) {
                    var t = 0
                    for (j in 0 until by) t += d[i++].toInt() and 255
                    sums[x] += t
                }
            }
            for (x in 0 until nw) out[y * nw + x] = ((sums[x] + half) / (by * by)).toByte()
        }
        return GrayPic(nw, nh, out)
    }
}

// ---------------------------------------------------------------------------------------
// black-and-white, and blobs
// ---------------------------------------------------------------------------------------

/** true where a pixel stands out from the average of the [block] x [block] pixels around it (outside the picture, the edge is repeated). */
fun standsOut(p: GrayPic, block: Int, dark: Boolean): BooleanArray {
    // plain loops over arrays, nothing clever: this runs over every pixel of every picture, and debug builds don't optimise calls away
    val w = p.w
    val h = p.h
    val r = block / 2
    val area = block * block
    val out = BooleanArray(w * h)
    val flip = if (dark) 255 else 0
    val d = IntArray(w * h)
    for (i in d.indices) d[i] = (p.d[i].toInt() and 255) xor flip
    // the sum of each column over the rows y-r..y+r, with the edge columns repeated r times on both sides
    val column = IntArray(w + 2 * r)
    for (x in 0 until w) {
        var s = 0
        for (k in -r..r) s += d[(if (k < 0) 0 else if (k >= h) h - 1 else k) * w + x]
        column[r + x] = s
    }
    val limit = 2 * area * LIGHTER_BY + area           // value - mean > LIGHTER_BY, with the mean rounded, without dividing
    for (y in 0 until h) {
        if (y > 0) {
            val leave = (if (y - 1 - r < 0) 0 else y - 1 - r) * w
            val enter = (if (y + r >= h) h - 1 else y + r) * w
            for (x in 0 until w) column[r + x] += d[enter + x] - d[leave + x]
        }
        val first = column[r]
        val last = column[r + w - 1]
        for (k in 0 until r) { column[k] = first; column[r + w + k] = last }
        var s = 0
        for (k in 0 until 2 * r + 1) s += column[k]
        val row = y * w
        for (x in 0 until w) {
            if (x > 0) s += column[x + 2 * r] - column[x - 1]
            // v - (2s + area) / (2 area) > 18  <=>  2 area (v - 18) >= 2s + area + 1 ... written so that no division is needed
            if (2 * area * d[row + x] - 2 * s > limit) out[row + x] = true
        }
    }
    return out
}

/** A joined blob: its box, its number of pixels, and the horizontal runs it is made of (start, end, y). */
class Blob(val first: Int) {
    var x0 = Int.MAX_VALUE
    var y0 = Int.MAX_VALUE
    var x1 = -1
    var y1 = -1
    var area = 0
    val w: Int get() = x1 - x0 + 1
    val h: Int get() = y1 - y0 + 1
}

/** The blobs of a black-and-white picture (pixels that touch, also at a corner, are joined). Works on runs, which is much faster than pixels. */
class Blobs(on: BooleanArray, w: Int, h: Int) {
    private var runs = IntArray(3 * 4096)       // start, end (included), y
    private var parent = IntArray(4096)
    private var count = 0
    private val nextOfBlob: IntArray
    val list: List<Blob>

    init {
        var prevFrom = 0
        var prevTo = 0
        for (y in 0 until h) {
            val from = count
            var q = prevFrom
            var x = 0
            val row = y * w
            while (x < w) {
                if (!on[row + x]) { x++; continue }
                val start = x
                while (x < w && on[row + x]) x++
                val k = add(start, x - 1, y)
                // join with every run of the row above that touches it (corners count)
                while (q < prevTo && runs[3 * q + 1] < start - 1) q++
                var t = q
                while (t < prevTo && runs[3 * t] <= x) { union(t, k); t++ }
            }
            prevFrom = from
            prevTo = count
        }
        val blobOf = arrayOfNulls<Blob>(count)
        nextOfBlob = IntArray(count) { -1 }
        val lastOf = IntArray(count) { -1 }
        val all = ArrayList<Blob>()
        for (k in 0 until count) {
            val root = find(k)
            val b = blobOf[root] ?: Blob(k).also { blobOf[root] = it; all.add(it) }
            if (lastOf[root] >= 0) nextOfBlob[lastOf[root]] = k
            lastOf[root] = k
            val s = runs[3 * k]
            val e = runs[3 * k + 1]
            val y = runs[3 * k + 2]
            b.x0 = min(b.x0, s); b.x1 = max(b.x1, e); b.y0 = min(b.y0, y); b.y1 = max(b.y1, y)
            b.area += e - s + 1
        }
        list = all
    }

    private fun add(start: Int, end: Int, y: Int): Int {
        if (count == parent.size) {
            parent = parent.copyOf(count * 2)
            runs = runs.copyOf(6 * count)
        }
        runs[3 * count] = start; runs[3 * count + 1] = end; runs[3 * count + 2] = y
        parent[count] = count
        return count++
    }

    private fun find(a: Int): Int {
        var r = a
        while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
        return r
    }

    private fun union(a: Int, b: Int) {
        val x = find(a)
        val y = find(b)
        if (x != y) { if (x < y) parent[y] = x else parent[x] = y }
    }

    /** The runs of [b], one after the other: start, end (included), y. */
    fun runsOf(b: Blob): IntArray {
        var n = 0
        var k = b.first
        while (k >= 0) { n++; k = nextOfBlob[k] }
        val out = IntArray(3 * n)
        n = 0
        k = b.first
        while (k >= 0) {
            for (i in 0..2) out[n++] = runs[3 * k + i]
            k = nextOfBlob[k]
        }
        return out
    }

    /** The blob alone, 255 on 0, with [pad] empty rows above and below. */
    fun picture(b: Blob, pad: Int): GrayPic {
        val out = ByteArray(b.w * (b.h + 2 * pad))
        var k = b.first
        while (k >= 0) {
            val row = (runs[3 * k + 2] - b.y0 + pad) * b.w
            for (x in runs[3 * k]..runs[3 * k + 1]) out[row + x - b.x0] = -1
            k = nextOfBlob[k]
        }
        return GrayPic(b.w, b.h + 2 * pad, out)
    }
}

// ---------------------------------------------------------------------------------------
// the shape of one digit
// ---------------------------------------------------------------------------------------

/** Shifts every column of [p] up or down so that a row with this [slope] becomes level. Pixels are 255 or 0, also afterwards. */
private fun levelled(p: GrayPic, slope: Double): GrayPic {
    val out = ByteArray(p.d.size)
    for (x in 0 until p.w) {
        val shift = slope * x - slope * p.w / 2.0
        for (y in 0 until p.h) {
            val sy = y + shift
            val y0 = Math.floor(sy).toInt()
            val f = sy - y0
            val a = if (y0 in 0 until p.h) p.d[y0 * p.w + x].toInt() and 255 else 0
            val b = if (y0 + 1 in 0 until p.h) p.d[(y0 + 1) * p.w + x].toInt() and 255 else 0
            if (a + (b - a) * f > 127.0) out[y * p.w + x] = -1
        }
    }
    return GrayPic(p.w, p.h, out)
}

private val BLUR = FloatArray(9) { val x = it - 4.0; Math.exp(-x * x / 2.0).toFloat() }.also { k ->
    val sum = k.sum()
    for (i in k.indices) k[i] /= sum
}

private fun mirrored(i: Int, n: Int): Int = if (i < 0) -i else if (i >= n) 2 * n - 2 - i else i

/**
 * The shape of a black-and-white digit, comparable whatever its size: cut to its edges, scaled to the same height, softened a little
 * (so one pixel more or less doesn't matter) and evened out. Two shapes are compared by multiplying them: 1 = identical. Null if empty.
 */
fun digitShape(p: GrayPic): FloatArray? {
    var x0 = p.w
    var x1 = -1
    var y0 = p.h
    var y1 = -1
    for (y in 0 until p.h) for (x in 0 until p.w) if (p.d[y * p.w + x].toInt() != 0) {
        if (x < x0) x0 = x
        if (x > x1) x1 = x
        if (y < y0) y0 = y
        if (y > y1) y1 = y
    }
    if (x1 < 0) return null
    val w = x1 - x0 + 1
    val h = y1 - y0 + 1
    val nw = (w * SHAPE_H.toDouble() / h).roundToInt().coerceIn(1, SHAPE_W)

    // area scaling: every target cell is the share of it that is covered
    val narrow = FloatArray(nw * h)                         // first the width...
    for (cx in 0 until nw) {
        val a = cx * w.toDouble() / nw
        val b = (cx + 1) * w.toDouble() / nw
        for (sx in a.toInt()..min(w - 1, b.toInt())) {
            val part = min(b, sx + 1.0) - max(a, sx.toDouble())
            if (part <= 0) continue
            for (y in 0 until h) if (p.d[(y + y0) * p.w + sx + x0].toInt() != 0) narrow[y * nw + cx] += (part / (b - a)).toFloat()
        }
    }
    val small = FloatArray(SHAPE_SIZE)                      // ...then the height, centred
    val left = (SHAPE_W - nw) / 2
    for (cy in 0 until SHAPE_H) {
        val a = cy * h.toDouble() / SHAPE_H
        val b = (cy + 1) * h.toDouble() / SHAPE_H
        for (sy in a.toInt()..min(h - 1, b.toInt())) {
            val part = (min(b, sy + 1.0) - max(a, sy.toDouble())) / (b - a)
            if (part <= 0) continue
            for (cx in 0 until nw) small[cy * SHAPE_W + left + cx] += (narrow[sy * nw + cx] * part).toFloat()
        }
    }

    val across = FloatArray(SHAPE_SIZE)
    for (y in 0 until SHAPE_H) for (x in 0 until SHAPE_W) {
        var s = 0f
        for (k in -4..4) s += BLUR[k + 4] * small[y * SHAPE_W + mirrored(x + k, SHAPE_W)]
        across[y * SHAPE_W + x] = s
    }
    val out = FloatArray(SHAPE_SIZE)
    for (y in 0 until SHAPE_H) for (x in 0 until SHAPE_W) {
        var s = 0f
        for (k in -4..4) s += BLUR[k + 4] * across[mirrored(y + k, SHAPE_H) * SHAPE_W + x]
        out[y * SHAPE_W + x] = s
    }
    val mean = out.sum() / SHAPE_SIZE
    var norm = 0.0
    for (i in out.indices) { out[i] -= mean; norm += out[i].toDouble() * out[i] }
    val n = sqrt(norm).toFloat()
    if (n <= 0f) return null
    for (i in out.indices) out[i] /= n
    return out
}

/** How alike two shapes are: 1 = identical. */
fun likeness(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** [shape] at half the width and height: good enough to see which digits it could be, and four times quicker to compare. */
private fun roughShape(shape: FloatArray): FloatArray {
    val w = SHAPE_W / 2
    val h = SHAPE_H / 2
    val out = FloatArray(w * h)
    var norm = 0f
    for (y in 0 until h) for (x in 0 until w) {
        val i = 2 * y * SHAPE_W + 2 * x
        val v = shape[i] + shape[i + 1] + shape[i + SHAPE_W] + shape[i + SHAPE_W + 1]
        out[y * w + x] = v
        norm += v * v
    }
    val n = sqrt(norm)
    if (n > 0f) for (i in out.indices) out[i] /= n
    return out
}

// ---------------------------------------------------------------------------------------
// the example digits
// ---------------------------------------------------------------------------------------

/** One style's example pictures of 0-9, [size] x [size] pixels each. */
class DigitStyle(val name: String, val size: Int, val digits: List<ByteArray>)

/** Reads digit_shapes.bin (made by tools/make_digit_shapes.py; the format is described there). */
fun readDigitStyles(input: InputStream): List<DigitStyle> {
    val d = DataInputStream(ByteArrayInputStream(GZIPInputStream(input).use { it.readBytes() }))
    val magic = ByteArray(4)
    d.readFully(magic)
    require(String(magic, Charsets.US_ASCII) == "CCDS" && d.readUnsignedByte() == 1) { "not a digit shapes file" }
    val styles = d.readUnsignedByte()
    val size = d.readUnsignedByte()
    return List(styles) {
        val name = ByteArray(d.readUnsignedByte())
        d.readFully(name)
        DigitStyle(String(name, Charsets.US_ASCII), size, List(10) { ByteArray(size * size).also { p -> d.readFully(p) } })
    }
}

/** What a row of shapes reads as: one digit per shape (-1 = none) and how alike each was. */
class RowReading(val digits: IntArray, val likeness: FloatArray)

/**
 * The example digits a shape is compared with: those of the fonts, or, once [learned], only the shapes seen in this video
 * (they fit better than any font and there are far fewer of them).
 */
class DigitShapes private constructor(
    private val byLean: Array<Array<Array<FloatArray>>>,        // lean, digit, examples
    private val learned: Array<Array<FloatArray>>?,             // digit, examples
    private val roughByLean: Array<Array<Array<FloatArray>>> =
        Array(byLean.size) { k -> Array(10) { d -> Array(byLean[k][d].size) { roughShape(byLean[k][d][it]) } } }
) {
    val isLearned: Boolean get() = learned != null

    /** The same, but reading only with the shapes of this video: at most [keep] of each digit, spread over the list. [shapes] goes by digit. */
    fun learned(shapes: List<List<FloatArray>>, keep: Int = 12): DigitShapes {
        require(shapes.size == 10 && shapes.all { it.isNotEmpty() }) { "all ten digits are needed" }
        return DigitShapes(byLean, Array(10) { d ->
            val s = shapes[d]
            val n = min(keep, s.size)
            Array(n) { s[if (n == 1) 0 else it * (s.size - 1) / (n - 1)] }
        }, roughByLean)
    }

    fun readRow(shapes: List<FloatArray>): RowReading {
        val own = learned
        if (own != null) return best(shapes) { shape, d -> own[d].maxOf { likeness(shape, it) } }
        // All digits of a row lean the same way: the lean that suits the whole row counts. Comparing with every example at full size is
        // slow, so a rough look (a quarter of the cells) picks the lean and the likely digits, and only those are compared properly.
        val n = shapes.size
        val rough = Array(n) { i -> roughShape(shapes[i]) }
        val table = Array(LEANS.size) { Array(n) { FloatArray(10) } }
        var lean = 0
        var leanSum = -1e9f
        for (k in LEANS.indices) {
            var sum = 0f
            for (i in 0 until n) {
                var best = -2f
                for (d in 0..9) {
                    var l = -2f
                    for (e in roughByLean[k][d]) { val x = likeness(rough[i], e); if (x > l) l = x }
                    table[k][i][d] = l
                    if (l > best) best = l
                }
                sum += best
            }
            if (sum > leanSum) { leanSum = sum; lean = k }
        }
        // (its two neighbours count too: digits far from the middle of a tilted screen lean a little differently)
        val from = max(0, lean - 1)
        val to = min(LEANS.size - 1, lean + 1)
        val digits = IntArray(n)
        val likes = FloatArray(n)
        for (i in 0 until n) {
            val roughly = FloatArray(10) { d -> var l = -2f; for (k in from..to) l = max(l, table[k][i][d]); l }
            val order = (0..9).sortedByDescending { roughly[it] }
            var b = -1
            var l = -2f
            for (c in 0 until LIKELY_DIGITS) {
                val d = order[c]
                for (k in from..to) for (e in byLean[k][d]) { val x = likeness(shapes[i], e); if (x > l) { l = x; b = d } }
            }
            digits[i] = b
            likes[i] = l
            if (l < HOPELESS) return RowReading(IntArray(n) { -1 }, FloatArray(n) { l })        // one blob that is no digit spoils the row
        }
        return RowReading(digits, likes)
    }

    private inline fun best(shapes: List<FloatArray>, score: (FloatArray, Int) -> Float): RowReading {
        val digits = IntArray(shapes.size)
        val likes = FloatArray(shapes.size)
        for (i in shapes.indices) {
            var l = -2f
            for (d in 0..9) {
                val s = score(shapes[i], d)
                if (s > l) { l = s; digits[i] = d }
            }
            likes[i] = l
        }
        return RowReading(digits, likes)
    }

    companion object {
        fun from(styles: List<DigitStyle>): DigitShapes = DigitShapes(
            Array(LEANS.size) { k ->
                Array(10) { d -> Array(styles.size) { s -> digitShape(leaning(styles[s].digits[d], styles[s].size, LEANS[k]))!! } }
            },
            null
        )

        /** A style's picture leaning by [lean], as black-and-white. */
        private fun leaning(p: ByteArray, size: Int, lean: Float): GrayPic {
            val out = ByteArray(size * size)
            for (y in 0 until size) {
                val shift = lean * (y - size / 2f)
                for (x in 0 until size) {
                    val sx = x - shift
                    val x0 = Math.floor(sx.toDouble()).toInt()
                    val f = sx - x0
                    val a = if (x0 in 0 until size) p[y * size + x0].toInt() and 255 else 0
                    val b = if (x0 + 1 in 0 until size) p[y * size + x0 + 1].toInt() and 255 else 0
                    if (a + (b - a) * f > 127f) out[y * size + x] = -1
                }
            }
            return GrayPic(size, size, out)
        }
    }
}

// ---------------------------------------------------------------------------------------
// rows of digits
// ---------------------------------------------------------------------------------------

private val TIME_TEXT = Regex("(\\d{1,3}|\\d{1,2}:[0-5]\\d)\\.\\d{1,3}")

/** "12.69" -> 12.69, "1:04.42" -> 64.42. Only for texts this reader gave (a zero is allowed, unlike in [parseTimeSeconds]). */
fun shownSeconds(text: String): Double {
    val colon = text.indexOf(':')
    return if (colon < 0) text.toDouble() else text.take(colon).toInt() * 60 + text.drop(colon + 1).toDouble()
}

/** The slope of the straight line that fits the points best. */
private fun slopeOf(x: DoubleArray, y: DoubleArray): Double {
    val mx = x.average()
    val my = y.average()
    var a = 0.0
    var b = 0.0
    for (i in x.indices) { a += (x[i] - mx) * (y[i] - my); b += (x[i] - mx) * (x[i] - mx) }
    return if (b > 0) a / b else 0.0
}

/**
 * Every time with a decimal point that can be read in [pic], with or without minutes. [darks]: look for light digits (false), dark ones
 * (true) or both. The same time is usually found with several [blocks] and is then listed several times; [pickRow] chooses.
 * Boxes and heights are in pixels of the whole frame: [pic] starts at ([offX], [offY]), and is looked at [shrink] times smaller.
 */
fun findTimeRows(
    pic: GrayPic, examples: DigitShapes, darks: List<Boolean>, blocks: IntArray = SEARCH_BLOCKS,
    minHeight: Double = 12.0, maxHeight: Double = 400.0, minLikeness: Float = MIN_LIKENESS, offX: Int = 0, offY: Int = 0,
    shrink: Int = 1
): List<TimeRow> {
    val out = ArrayList<TimeRow>()
    val small = pic.shrunk(shrink)
    val least = minHeight / shrink
    val most = maxHeight / shrink
    for (dark in darks) for (wanted in blocks) {
        val side = min(small.w, small.h)
        val block = if (wanted >= side) side / 2 * 2 - 1 else wanted
        if (block < 3) continue
        val blobs = Blobs(standsOut(small, block, dark), small.w, small.h)
        val digits = blobs.list.filter { b ->
            val r = b.w.toDouble() / b.h
            b.h >= least && b.h <= most && r > 0.12 && r < 1.4 && b.area > 0.1 * b.w * b.h
        }.sortedBy { it.x0 }
        if (digits.size < 2) continue
        val dots = blobs.list.filter { it.h >= 2 && it.h < 0.28 * most && it.w < 0.3 * most }.sortedBy { it.x0 }
        for (row in digitRows(digits, dots)) readRow(blobs, row, dots, examples, dark, block, minLikeness, offX, offY, shrink)?.let { out.add(it) }
    }
    return out
}

/** Chains digit-sized blobs, left to right: the next one is close, about as tall and about as high up. */
private fun digitRows(digits: List<Blob>, dots: List<Blob>): List<List<Blob>> {
    val next = IntArray(digits.size) { -1 }
    val hasPrevious = BooleanArray(digits.size)
    for (i in digits.indices) {
        val a = digits[i]
        for (j in i + 1 until digits.size) {
            val b = digits[j]
            if (b.x0 - a.x0 > a.w + MAX_GAP_WITH_MARK * 1.4 * a.h) break       // sorted by left edge: nothing further right is close enough
            val gap = b.x0 - (a.x0 + a.w)
            val tall = max(a.h, b.h)
            if (b.x0 > a.x0 && gap > -0.35 * a.h && gap < MAX_GAP_WITH_MARK * tall && b.h > 0.55 * a.h && b.h < 1.4 * a.h &&
                abs((b.y0 + b.h / 2.0) - (a.y0 + a.h / 2.0)) < (if (gap < MAX_GAP * tall) 0.6 else 0.75) * a.h &&
                (gap < MAX_GAP * tall || markBetween(a, b, dots) != Mark.NONE)
            ) {
                next[i] = j
                hasPrevious[j] = true
                break
            }
        }
    }
    val rows = ArrayList<List<Blob>>()
    for (i in digits.indices) {
        if (hasPrevious[i]) continue
        val row = ArrayList<Blob>()
        var k = i
        while (k >= 0) { row.add(digits[k]); k = next[k] }
        if (row.size in 2..8) rows.add(row)
    }
    return rows
}

/** The row as a time, or null: it must have exactly one decimal point, at most one colon before it, and every blob must look like a digit. */
private fun readRow(
    blobs: Blobs, row: List<Blob>, dots: List<Blob>, examples: DigitShapes, dark: Boolean, block: Int, minLikeness: Float, offX: Int, offY: Int,
    shrink: Int
): TimeRow? {
    var point = -1
    var colon = -1
    for (i in 0 until row.size - 1) {
        when (markBetween(row[i], row[i + 1], dots)) {
            Mark.POINT -> { if (point >= 0) return null; point = i + 1 }
            Mark.COLON -> { if (colon >= 0 || point >= 0) return null; colon = i + 1 }
            Mark.NONE -> {}
        }
    }
    if (point < 0) return null
    val middles = DoubleArray(row.size) { row[it].x0 + row[it].w / 2.0 }
    val slope = (slopeOf(middles, DoubleArray(row.size) { (row[it].y0 + row[it].h).toDouble() }) +
            slopeOf(middles, DoubleArray(row.size) { row[it].y0.toDouble() })) / 2
    if (abs(slope) > MAX_SLOPE) return null
    val shapes = ArrayList<FloatArray>(row.size)
    for (b in row) shapes.add(digitShape(levelled(blobs.picture(b, (abs(slope) * b.w).toInt() + 2), slope)) ?: return null)
    val read = examples.readRow(shapes)
    if (read.likeness.min() < minLikeness || read.digits.any { it < 0 }) return null
    val text = buildString {
        for (i in row.indices) {
            if (i == colon) append(':')
            if (i == point) append('.')
            append(read.digits[i])
        }
    }
    if (!TIME_TEXT.matches(text)) return null
    val heights = row.map { it.h }.sorted()
    val n = heights.size
    val height = if (n % 2 == 1) heights[n / 2].toDouble() else (heights[n / 2 - 1] + heights[n / 2]) / 2.0
    return TimeRow(
        text, read.likeness.min(), height * shrink,
        row.minOf { it.x0 } * shrink + offX, row.minOf { it.y0 } * shrink + offY,
        (row.maxOf { it.x1 } + 1) * shrink + offX, (row.maxOf { it.y1 } + 1) * shrink + offY,
        dark, block, shapes
    )
}

private enum class Mark { NONE, POINT, COLON }

/**
 * What stands between these two digits. A decimal point: a small blob near the line they stand on. A colon: two blobs of the same size
 * above each other (the lower one can be higher up than a point: some fonts put the colon halfway up).
 */
private fun markBetween(a: Blob, b: Blob, dots: List<Blob>): Mark {
    val height = (a.h + b.h) / 2.0
    val from = a.x0 + a.w - 2 - 0.1 * height
    val to = b.x0 + 2 + 0.1 * height
    val base = (a.y0 + a.h + b.y0 + b.h) / 2.0
    // the dots are sorted by their left edge: start at the first that can reach far enough
    var lo = 0
    var hi = dots.size
    while (lo < hi) { val mid = (lo + hi) / 2; if (dots[mid].x0 < from - 0.3 * height) lo = mid + 1 else hi = mid }
    val near = ArrayList<Blob>(4)
    while (lo < dots.size && dots[lo].x0 < to) {
        val d = dots[lo++]
        val cx = d.x0 + d.w / 2.0
        if (cx > from && cx < to && d.h > 0.04 * height && d.h < 0.28 * height && d.w > 0.04 * height && d.w < 0.3 * height) near.add(d)
    }
    // how far the middle of a dot is above the line, in digit heights
    fun up(d: Blob) = (base - d.y0 - d.h / 2.0) / height
    for (low in near) for (high in near) {
        val apart = up(high) - up(low)
        if (up(low) > -0.28 && up(low) < 0.45 && up(high) > 0.35 && up(high) < 0.95 && apart > 0.25 && apart < 0.8 &&
            abs((high.x0 + high.w / 2.0) - (low.x0 + low.w / 2.0)) < (0.08 + 0.5 * apart) * height &&
            min(low.w, high.w) >= 0.5 * max(low.w, high.w) && min(low.h, high.h) >= 0.5 * max(low.h, high.h)
        ) return Mark.COLON
    }
    return if (near.any { abs(up(it)) < 0.28 }) Mark.POINT else Mark.NONE
}

/** The reading most blocks agree on (a tie goes to the one that looks most like digits), or null when nothing was read. */
fun pickRow(rows: List<TimeRow>): TimeRow? {
    val byText = rows.groupBy { it.text }
    val best = byText.values.maxWithOrNull(compareBy({ it.size }, { g -> g.maxOf { it.likeness } })) ?: return null
    return best.maxByOrNull { it.likeness }
}
