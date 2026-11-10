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
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------
// What the app remembers about your timer
// ---------------------------------------------------------------------------------------

/** How the digits on this timer look once the display has been straightened. Learned from the number you type. */
class DigitLayout(
    val invert: Boolean,        // true if the digits are lighter than the background (LED timers)
    val shear: Double,          // how much the digits lean (italic) - undone before reading
    val bandTop: Int,           // rows of the straightened picture that hold the digits
    val bandBottom: Int,
    val digitW: Int,            // width of one wide digit, in pixels of the straightened picture
    val offsets: IntArray,      // right edge of each digit position, measured from the last digit
    val canvasW: Int,           // size of the straightened picture (fixed when the timer was set up)
    val canvasH: Int,
    val decimals: Int,          // how many digits come after the decimal point
    val readerId: String = "segment",   // which DisplayReader learned this layout (see DisplayReaders.kt)
    val readerData: String = ""         // free space for a future reader's own settings (e.g. a model name)
)

/** Everything saved by "Set up timer". */
class TimerSetup(
    val corners: DoubleArray,   // 8 numbers: the display's corners TL, TR, BR, BL (x,y each) in the setup frame
    val cropX: Int,             // where the saved picture of the timer was cut from, in frame pixels
    val cropY: Int,
    val layout: DigitLayout,
    val fixedPosition: Boolean = false,  // true = trust the saved corners (plain screens the shape finder can't lock onto)
    val readCorners: DoubleArray = corners  // the box the digits are read from (snug around the digits); the rest is for tracking
)

class SetupResult(val setup: TimerSetup?, val reference: Bitmap?, val message: String)

class ReadResult(val digits: String?, val message: String, val debug: Bitmap?)

/** A black-and-white picture stored as plain bytes so it is quick to look at pixel by pixel. */
class Bin(val w: Int, val h: Int, val d: ByteArray) {
    fun on(x: Int, y: Int): Boolean = d[y * w + x].toInt() != 0
}

// ---------------------------------------------------------------------------------------
// The image-processing toolbox
// ---------------------------------------------------------------------------------------
object TimerVision {
    private const val CANVAS_W = 380
    private const val MAX_SLOTS = 8        // the most digit positions a layout is stretched to (covers 1:02.345 and longer)
    private val SHEARS = doubleArrayOf(-0.05, 0.0, 0.08, 0.15, 0.22, 0.30, 0.38)

    // which segments (a top, b upper right, c lower right, d bottom, e lower left, f upper left, g middle) make each digit
    private val SEGMENTS = mapOf(
        "abcdef" to '0', "bc" to '1', "abdeg" to '2', "abcdg" to '3', "bcfg" to '4',
        "acdfg" to '5', "acdefg" to '6', "abc" to '7', "abcdefg" to '8', "abcdfg" to '9',
        "abcf" to '7', "cdefg" to '6'
    )

    fun bitmapToGray(bmp: Bitmap): Mat {
        val copy = if (bmp.config == Bitmap.Config.ARGB_8888) null else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val src = copy ?: bmp
        val rgba = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return gray
        } finally {
            rgba.release()
            copy?.recycle()
        }
    }

    fun dist(q: DoubleArray, a: Int, b: Int): Double {
        val dx = q[2 * a] - q[2 * b]
        val dy = q[2 * a + 1] - q[2 * b + 1]
        return sqrt(dx * dx + dy * dy)
    }

    /** Size of the straightened display picture, from the shape of the four corners. */
    fun canvasSize(q: DoubleArray): Pair<Int, Int> {
        val w = (dist(q, 0, 1) + dist(q, 3, 2)) / 2.0
        val h = (dist(q, 0, 3) + dist(q, 1, 2)) / 2.0
        val hh = (CANVAS_W * h / w).roundToInt().coerceIn(80, 260)
        return Pair(CANVAS_W, hh)
    }

    /**
     * Cuts the display out of the frame, straightens it, evens out the lighting, undoes the lean of the digits
     * and turns it into pure black-and-white (digits = white).
     */
    fun prep(gray: Mat, q: DoubleArray, shear: Double, invert: Boolean, w: Int, h: Int): Bin {
        val mats = ArrayList<Mat>()      // everything created below is released at the end, even if something fails
        try {
            val src = MatOfPoint2f(Point(q[0], q[1]), Point(q[2], q[3]), Point(q[4], q[5]), Point(q[6], q[7]))
            val dst = MatOfPoint2f(
                Point(0.0, 0.0), Point(w.toDouble(), 0.0),
                Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble())
            )
            mats.add(src); mats.add(dst)
            val m = Imgproc.getPerspectiveTransform(src, dst)
            mats.add(m)
            val warped = Mat()
            mats.add(warped)
            Imgproc.warpPerspective(gray, warped, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC)
            if (invert) Core.bitwise_not(warped, warped)

            // stretch the contrast so dark / dim footage looks like bright footage
            val px = ByteArray(w * h)
            warped.get(0, 0, px)
            val hist = IntArray(256)
            for (b in px) hist[b.toInt() and 0xFF]++
            val total = w * h
            var acc = 0
            var lo = 0
            var hi = 255
            var gotLo = false
            for (v in 0..255) {
                acc += hist[v]
                if (!gotLo && acc >= 0.01 * total) {
                    lo = v
                    gotLo = true
                }
                if (acc >= 0.99 * total) {
                    hi = v
                    break
                }
            }
            val alpha = 255.0 / max((hi - lo).toDouble(), 20.0)
            val stretched = Mat()
            mats.add(stretched)
            warped.convertTo(stretched, CvType.CV_8U, alpha, -lo * alpha)

            val cy = h * 0.37
            val aff = Mat(2, 3, CvType.CV_64F)
            mats.add(aff)
            aff.put(0, 0, 1.0, shear, -shear * cy, 0.0, 1.0, 0.0)
            val sheared = Mat()
            mats.add(sheared)
            Imgproc.warpAffine(stretched, sheared, aff, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)

            val blurred = Mat()
            mats.add(blurred)
            Imgproc.GaussianBlur(sheared, blurred, Size(0.0, 0.0), 1.0)
            val th = Mat()
            mats.add(th)
            Imgproc.adaptiveThreshold(blurred, th, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 41, 12.0)

            val out = ByteArray(w * h)
            th.get(0, 0, out)
            return Bin(w, h, out)
        } finally {
            for (mat in mats) mat.release()
        }
    }

    /** Finds runs of columns that contain digit strokes. Returns [startX, endX] pairs, left to right. */
    fun groups(b: Bin, y0: Int, y1: Int): List<IntArray> {
        val minCol = max(4, (0.115 * (y1 - y0 + 1)).roundToInt())
        val xMin = (0.105 * b.w).toInt()
        val xMax = (0.918 * b.w).toInt()
        // For every column: how many pixels are lit, and how tall the lit part is (first lit row to last lit row).
        val count = IntArray(b.w)
        val extent = IntArray(b.w)
        for (x in xMin until xMax) {
            var c = 0
            var lo = -1
            var hi = -1
            for (y in y0..y1) {
                if (b.on(x, y)) {
                    c++
                    if (lo < 0) lo = y
                    hi = y
                }
            }
            count[x] = c
            extent[x] = if (lo < 0) 0 else hi - lo + 1
        }
        var tallest = 0
        for (x in xMin until xMax) if (count[x] >= minCol && extent[x] > tallest) tallest = extent[x]
        // A column belongs to a digit only if its lit part is tall. Things that are only short - the decimal point, a
        // colon, an icon or small text next to or above the digits - are left out, so they can't join a digit's group
        // or become a "digit" of their own.
        val cols = BooleanArray(b.w)
        for (x in xMin until xMax) cols[x] = count[x] >= minCol && extent[x] >= 0.35 * tallest
        val res = mutableListOf<IntArray>()
        var start = -1
        var last = -1
        for (x in 0 until b.w) {
            if (cols[x]) {
                if (start < 0) start = x
                last = x
            } else if (start >= 0 && x - last > 2) {
                res.add(intArrayOf(start, last))
                start = -1
            }
        }
        if (start >= 0) res.add(intArrayOf(start, last))
        return res.filter { it[1] - it[0] >= 8 }
    }

    /** First and last row (inside y0..y1) that has any lit pixel in the columns of group [g]; last < 0 if none. */
    private fun inkRows(b: Bin, g: IntArray, y0: Int, y1: Int): IntArray {
        var lo = Int.MAX_VALUE
        var hi = -1
        for (y in y0..y1) {
            for (x in g[0]..g[1]) {
                if (b.on(x, y)) {
                    if (y < lo) lo = y
                    if (y > hi) hi = y
                    break
                }
            }
        }
        return intArrayOf(lo, hi)
    }

    /**
     * The row band found by autoBand can be taller than the digits (an icon or text just above them gets included).
     * This shrinks it to the rows the digit shapes really cover, so each digit's segments are measured in the right place.
     */
    fun refineBand(b: Bin, band: IntArray, gs: List<IntArray>): IntArray {
        var lo = Int.MAX_VALUE
        var hi = -1
        for (g in gs) {
            val r = inkRows(b, g, band[0], band[1])
            if (r[1] >= 0) {
                lo = min(lo, r[0])
                hi = max(hi, r[1])
            }
        }
        if (hi < 0) return band
        return intArrayOf(max(0, lo - 3), min(b.h - 1, hi + 3))
    }

    /** Finds which rows of the straightened display hold the big digits (ignores the display's frame line). */
    fun autoBand(b: Bin): IntArray? {
        val x0 = (0.105 * b.w).toInt()
        val x1 = (0.918 * b.w).toInt()
        val rows = DoubleArray(b.h)
        for (y in 0 until b.h) {
            var c = 0
            for (x in x0 until x1) if (b.on(x, y)) c++
            rows[y] = c.toDouble()
        }
        val limit = 0.55 * (x1 - x0)
        for (y in rows.indices) if (rows[y] > limit) rows[y] = 0.0
        val mx = rows.maxOrNull() ?: 0.0
        if (mx <= 0.0) return null
        var bestS = -1
        var bestE = -1
        var s = -1
        var e = -1
        var miss = 0
        for (y in 0 until b.h) {
            if (rows[y] >= 0.12 * mx) {
                if (s < 0) s = y
                e = y
                miss = 0
            } else if (s >= 0) {
                miss++
                if (miss > 6) {
                    if (bestS < 0 || e - s > bestE - bestS) {
                        bestS = s
                        bestE = e
                    }
                    s = -1
                    miss = 0
                }
            }
        }
        if (s >= 0 && (bestS < 0 || e - s > bestE - bestS)) {
            bestS = s
            bestE = e
        }
        if (bestS < 0) return null
        return intArrayOf(max(0, bestS - 3), min(b.h - 1, bestE + 3))
    }

    private fun frac(b: Bin, x0: Int, y0: Int, w: Int, h: Int, xa: Double, xb: Double, ya: Double, yb: Double): Double {
        val xs = (xa * w).toInt()
        val xe = max((xb * w).toInt(), xs + 1)
        val ys = (ya * h).toInt()
        val ye = max((yb * h).toInt(), ys + 1)
        var on = 0
        var tot = 0
        for (yy in ys until min(ye, h)) {
            for (xx in xs until min(xe, w)) {
                tot++
                if (b.on(x0 + xx, y0 + yy)) on++
            }
        }
        return if (tot == 0) 0.0 else on.toDouble() / tot
    }

    /** How "filled" each of the seven segments is, in the order a b c d e f g. */
    private fun segScores(b: Bin, x0: Int, x1: Int, y0: Int, y1: Int): DoubleArray {
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        return doubleArrayOf(
            frac(b, x0, y0, w, h, 0.2, 0.8, 0.0, 0.17),    // a
            frac(b, x0, y0, w, h, 0.7, 1.0, 0.14, 0.43),   // b
            frac(b, x0, y0, w, h, 0.7, 1.0, 0.57, 0.86),   // c
            frac(b, x0, y0, w, h, 0.2, 0.8, 0.83, 1.0),    // d
            frac(b, x0, y0, w, h, 0.0, 0.3, 0.57, 0.86),   // e
            frac(b, x0, y0, w, h, 0.0, 0.3, 0.14, 0.43),   // f
            frac(b, x0, y0, w, h, 0.2, 0.8, 0.38, 0.62)    // g
        )
    }

    private val PATTERNS: List<Pair<Char, IntArray>> = listOf(
        '0' to intArrayOf(1, 1, 1, 1, 1, 1, 0), '1' to intArrayOf(0, 1, 1, 0, 0, 0, 0),
        '2' to intArrayOf(1, 1, 0, 1, 1, 0, 1), '3' to intArrayOf(1, 1, 1, 1, 0, 0, 1),
        '4' to intArrayOf(0, 1, 1, 0, 0, 1, 1), '5' to intArrayOf(1, 0, 1, 1, 0, 1, 1),
        '6' to intArrayOf(1, 0, 1, 1, 1, 1, 1), '7' to intArrayOf(1, 1, 1, 0, 0, 0, 0),
        '7' to intArrayOf(1, 1, 1, 0, 0, 1, 0), '8' to intArrayOf(1, 1, 1, 1, 1, 1, 1),
        '9' to intArrayOf(1, 1, 1, 1, 0, 1, 1), '6' to intArrayOf(0, 0, 1, 1, 1, 1, 1),
        '9' to intArrayOf(1, 1, 1, 0, 0, 1, 1)
    )

    /** Fraction of lit pixels in the box x in [xs, xe), y in [ys, ye). */
    private fun meanOn(b: Bin, xs: Int, xe: Int, ys: Int, ye: Int): Double {
        var on = 0
        var tot = 0
        for (yy in max(0, ys) until min(ye, b.h)) {
            for (xx in max(0, xs) until min(xe, b.w)) {
                tot++
                if (b.on(xx, yy)) on++
            }
        }
        return if (tot == 0) 0.0 else on.toDouble() / tot
    }

    /**
     * Works out which digit sits in this column range. Returns the digit ('?' if unclear) and how sure we are.
     * [full] is the width of a whole digit (0 = unknown). [soft] also accepts a clearly-closest pattern (used while learning).
     */
    fun classify(b: Bin, x0: Int, x1: Int, y0: Int, y1: Int, narrow: Double, soft: Boolean = false, full: Int = 0): Pair<Char, Double> {
        if (x1 - x0 + 1 < narrow) {
            // a narrow digit is a 1 or a 7: the 7 has a thin bar across the top, left of its vertical stroke
            val ye = y0 + max((0.16 * (y1 - y0)).toInt(), 4)
            val bar = meanOn(b, max(0, x0 - 20), max(0, x0 - 2), y0, ye)
            if (bar >= 0.40 && max(0, x0 - 2) > max(0, x0 - 20)) return Pair('7', 1.0)
            if (full > 0) {
                // a lone right-hand stroke is a 1 - unless the 4's upper-left stroke and middle bar are there as well
                val h = y1 - y0 + 1
                val xs = max(0, x1 - full + 1)
                val xe = max(xs, x0 - 2)
                if (xe - xs >= 6) {
                    val f = meanOn(b, xs, min(xe, xs + (0.3 * full).toInt()), y0 + (0.14 * h).toInt(), y0 + (0.43 * h).toInt())
                    val g = meanOn(b, xs, xe, y0 + (0.38 * h).toInt(), y0 + (0.62 * h).toInt())
                    if (f >= 0.25 && g >= 0.20) return Pair('4', 0.1)
                }
            }
            return Pair('1', 1.0)
        }
        val sc = segScores(b, x0, x1, y0, y1)
        val mx = sc.maxOrNull() ?: 0.0
        val thr = max(0.18, 0.4 * mx)
        val letters = "abcdefg"
        val key = StringBuilder()
        for (i in 0 until 7) if (sc[i] > thr) key.append(letters[i])
        val exact = SEGMENTS[key.toString()]
        if (exact != null) {
            var margin = 1.0
            for (v in sc) margin = min(margin, abs(v - thr))
            return Pair(exact, margin)
        }
        if (soft && mx >= 0.15) {
            var d1 = Double.MAX_VALUE
            var d2 = Double.MAX_VALUE
            var best = '?'
            for ((digit, pat) in PATTERNS) {
                var d = 0.0
                for (i in 0 until 7) d += abs(sc[i] / mx - pat[i])
                if (d < d1) {
                    d2 = d1
                    d1 = d
                    best = digit
                } else if (d < d2) {
                    d2 = d
                }
            }
            if (d1 <= 1.2 && d2 - d1 >= 0.5) return Pair(best, 0.02)
        }
        return Pair('?', 0.0)
    }

    /** All ways to cut [m] groups into [n] consecutive clusters. Each result is n+1 boundaries. */
    private fun combos(m: Int, n: Int): List<IntArray> {
        val out = mutableListOf<IntArray>()
        val cur = IntArray(n + 1)
        cur[0] = 0
        cur[n] = m
        fun rec(idx: Int, from: Int) {
            if (idx == n) {
                out.add(cur.copyOf())
                return
            }
            for (c in from..(m - (n - idx))) {
                cur[idx] = c
                rec(idx + 1, c + 1)
            }
        }
        rec(1, 1)
        return out
    }

    /** Real timers have evenly spaced digits (one wider gap at the decimal point) that are narrower than their spacing. */
    private fun plausibleLayout(cl: Array<IntArray>, dw: Int, n: Int, decimals: Int, colonAt: Int): Boolean {
        val r = IntArray(n) { cl[it][1] }
        val sp = IntArray(n - 1) { r[it + 1] - r[it] }
        val j = if (decimals in 1 until n) n - decimals - 1 else -1      // the gap that holds the decimal point
        val normal = sp.filterIndexed { i, _ -> i != j && i != colonAt }.sorted()
        if (normal.isEmpty()) return true
        val m = if (normal.size % 2 == 1) normal[normal.size / 2].toDouble()
        else (normal[normal.size / 2 - 1] + normal[normal.size / 2]) / 2.0
        if (m <= 0.0) return false
        if (normal.any { abs(it - m) > 0.22 * m }) return false
        if (j in 0 until n - 1 && !(sp[j] >= 0.95 * m && sp[j] <= 1.9 * m)) return false
        // the gap that holds the minutes colon (1:02.34) - same idea, a little more room
        if (colonAt in 0 until n - 1 && !(sp[colonAt] >= 0.95 * m && sp[colonAt] <= 2.4 * m)) return false
        if (dw > 1.0 * m) return false
        return true
    }

    /** The usual distance between neighbouring digits (the decimal-point and colon gaps are left out). */
    private fun normalPitch(cl: Array<IntArray>, n: Int, decimals: Int, colonAt: Int): Int {
        val r = IntArray(n) { cl[it][1] }
        val j = if (decimals in 1 until n) n - decimals - 1 else -1
        val normal = IntArray(n - 1) { r[it + 1] - r[it] }.filterIndexed { i, _ -> i != j && i != colonAt }.sorted()
        if (normal.isEmpty()) return 0
        return normal[normal.size / 2]
    }

    /**
     * The learned layout only knows the digit positions of the number you typed. Real timers show shorter and longer
     * numbers (9.87 / 12.34 / 1:02.34), so the positions are stretched to [MAX_SLOTS] using the digit spacing.
     * Slot 0 is the last digit. The decimal point adds a little extra room after slot decimals-1; if the timer has a
     * minutes colon (between slot decimals+1 and decimals+2) it is assumed to need the same extra room.
     */
    private fun extendOffsets(measured: IntArray, pitch: Int, decimals: Int, hasColon: Boolean): IntArray {
        if (measured.size >= MAX_SLOTS || pitch <= 0) return measured
        val out = IntArray(MAX_SLOTS)
        for (i in measured.indices) out[i] = measured[i]
        val extra = if (decimals in 1 until measured.size) max(0, measured[decimals] - measured[decimals - 1] - pitch) else 0
        for (i in measured.size until MAX_SLOTS) {
            var step = pitch
            if (hasColon && i == decimals + 2) step += extra
            out[i] = out[i - 1] + step
        }
        return out
    }

    /**
     * LEARNING: given a frame, the display's corners and the number the display is showing (what you typed),
     * tries different settings until reading the picture gives exactly that number. Remembers the settings.
     */
    fun learn(gray: Mat, q: DoubleArray, typed: String, allowUnverified: Boolean = false): DigitLayout? {
        val digits = typed.filter { it.isDigit() }
        val n = digits.length
        if (n < 3) return null
        val decimals = if (typed.contains('.')) typed.substringAfter('.').count { it.isDigit() } else 0
        val (cw, ch) = canvasSize(q)
        // 3 or more digits before the decimal point means minutes are shown, so a colon sits between the minutes and the seconds
        val colonOptions = if (n - decimals >= 3) intArrayOf(n - decimals - 3, -1) else intArrayOf(-1)
        var best: DigitLayout? = null
        var bestScore = -1.0
        // used only when allowUnverified: the best-fitting layout that doesn't reproduce every typed digit
        var loose: DigitLayout? = null
        var looseMatches = 0
        for (invert in booleanArrayOf(false, true)) {
            // rank the possible leans by how sharply the vertical strokes line up, then only try the best two
            val ranked = mutableListOf<Pair<Double, Double>>()
            for (k in SHEARS) {
                val th = prep(gray, q, k, invert, cw, ch)
                val band = autoBand(th) ?: continue
                var sharp = 0.0
                for (x in (0.105 * cw).toInt() until (0.918 * cw).toInt()) {
                    var c = 0
                    for (y in band[0]..band[1]) if (th.on(x, y)) c++
                    sharp += c.toDouble() * c
                }
                ranked.add(Pair(sharp, k))
            }
            ranked.sortByDescending { it.first }
            for ((_, k) in ranked.take(2)) {
                val th = prep(gray, q, k, invert, cw, ch)
                val band0 = autoBand(th) ?: continue
                val gs0 = groups(th, band0[0], band0[1])
                if (gs0.isEmpty()) continue
                val band = refineBand(th, band0, gs0)
                val gs = groups(th, band[0], band[1])
                if (gs.size < n || gs.size > 13) continue
                for (cuts in combos(gs.size, n)) {
                    val cl = Array(n) { i -> intArrayOf(gs[cuts[i]][0], gs[cuts[i + 1] - 1][1]) }
                    val dw = cl.maxOf { it[1] - it[0] + 1 }
                    if (dw > 0.16 * cw) continue
                    var colonAt = -2
                    for (c in colonOptions) {
                        if (plausibleLayout(cl, dw, n, decimals, c)) {
                            colonAt = c
                            break
                        }
                    }
                    if (colonAt == -2) continue
                    val narrow = 0.5 * max(dw, 36)
                    var matches = 0
                    var marg = 1.0
                    for (i in 0 until n) {
                        val (d, mg) = classify(th, cl[i][0], cl[i][1], band[0], band[1], narrow, true, max(dw, 36))
                        if (d == digits[i]) {
                            matches++
                            marg = min(marg, mg)
                        } else if (!allowUnverified) {
                            break
                        }
                    }
                    val ok = matches == n
                    if ((ok && marg > bestScore) || (allowUnverified && !ok && matches > looseMatches)) {
                        val r = cl[n - 1][1]
                        val measured = IntArray(n) { i -> r - cl[n - 1 - i][1] }
                        val offsets = extendOffsets(measured, normalPitch(cl, n, decimals, colonAt), decimals, colonAt >= 0)
                        val layout = DigitLayout(
                            invert, k, band[0], band[1], max(dw, 36),
                            offsets, cw, ch, decimals, "segment", ""
                        )
                        if (ok) {
                            bestScore = marg
                            best = layout
                        } else {
                            looseMatches = matches
                            loose = layout
                        }
                    }
                }
            }
        }
        return best ?: (if (allowUnverified) loose else null)
    }

    /** Reads the number from a straightened black-and-white display picture, or null if it is unclear. */
    fun readBin(th: Bin, l: DigitLayout): String? {
        val gs = groups(th, l.bandTop, l.bandBottom)
        if (gs.isEmpty()) return null
        val anchor = gs.maxOf { it[1] }
        val dw = l.digitW.toDouble()
        val lo = IntArray(l.offsets.size) { Int.MAX_VALUE }
        val hi = IntArray(l.offsets.size) { -1 }
        for (g in gs) {
            val c = (g[0] + g[1]) / 2.0
            var bi = -1
            var bd = Double.MAX_VALUE
            for (i in l.offsets.indices) {
                val d = abs(c - (anchor - l.offsets[i] - dw / 2))
                if (d < bd) {
                    bd = d
                    bi = i
                }
            }
            if (bi >= 0 && bd <= 0.9 * dw) {
                lo[bi] = min(lo[bi], g[0])
                hi[bi] = max(hi[bi], g[1])
            }
        }
        if (hi[0] < 0) return null
        var top = 0
        for (i in hi.indices) if (hi[i] >= 0) top = i
        // a digit position with no clear strokes inside the number: look again there with a lower bar
        val low = max(3, (0.06 * (l.bandBottom - l.bandTop + 1)).roundToInt())
        for (i in top downTo 0) {
            if (hi[i] >= 0) continue
            val r = anchor - l.offsets[i]
            val xs = max(0, (r - dw).toInt())
            val xe = min(th.w, r + 4)
            var cMin = Int.MAX_VALUE
            var cMax = -1
            var count = 0
            for (x in xs until xe) {
                var c = 0
                for (y in l.bandTop..l.bandBottom) if (th.on(x, y)) c++
                if (c >= low) {
                    count++
                    cMin = min(cMin, x)
                    cMax = max(cMax, x)
                }
            }
            if (count >= 5) {
                lo[i] = cMin
                hi[i] = cMax
            }
        }
        val sb = StringBuilder()
        for (i in top downTo 0) {
            if (hi[i] < 0) return null            // a gap inside the number: something is hiding a digit
            val (d, _) = classify(th, lo[i], hi[i], l.bandTop, l.bandBottom, 0.5 * dw, false, l.digitW)
            if (d == '?') return null
            sb.append(d)
        }
        // at least one digit before the decimal point (so 9.87 counts), and never fewer than 3 digits
        return if (sb.length >= max(3, l.decimals + 1)) sb.toString() else null
    }

    fun binToBitmap(b: Bin): Bitmap {
        val pixels = IntArray(b.w * b.h) { i -> if (b.d[i].toInt() != 0) -1 else -16777216 }
        val bmp = Bitmap.createBitmap(b.w, b.h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, b.w, 0, 0, b.w, b.h)
        return bmp
    }

    /** 11108 with 3 decimals -> "11.108";  102345 -> "1:02.345" */
    fun formatDigits(digits: String, decimals: Int): String {
        if (decimals <= 0 || digits.length <= decimals) return digits
        val frac = digits.takeLast(decimals)
        val rest = digits.dropLast(decimals)
        val sec = rest.takeLast(2)
        val mins = rest.dropLast(2)
        return if (mins.isNotEmpty()) "$mins:${sec.padStart(2, '0')}.$frac" else "${sec.toInt()}.$frac"
    }

    private fun quadArea(p: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return abs(s) / 2.0
    }

    /**
     * TIGHTENING: once the digits have been learned, finds where they really sit and returns a snug box around them
     * (4 corners in frame pixels: TL, TR, BR, BL) - or null if that can't be done safely.
     *
     * - The box leaves room for the digit positions a longer time would use (up to minutes), but never reaches past the
     *   part of the picture the reader looks at anyway, so no new noise is let in.
     * - The side margins are sized so the digits sit inside the reader's own side margins (10.5% left / 8.2% right).
     * - The picture was un-leaned after straightening, so the corners are leaned back before going to the frame.
     * The caller learns again on this box and keeps it only if it still reproduces the typed number.
     */
    fun tightenQuad(gray: Mat, q: DoubleArray, l: DigitLayout, typedDigits: Int, frameW: Int, frameH: Int): DoubleArray? {
        if (l.offsets.isEmpty() || typedDigits < 1) return null
        val th = prep(gray, q, l.shear, l.invert, l.canvasW, l.canvasH)
        val gs = groups(th, l.bandTop, l.bandBottom)
        if (gs.isEmpty()) return null
        val right = gs.maxOf { it[1] }.toDouble()
        val cw = l.canvasW.toDouble()
        val ch = l.canvasH.toDouble()

        // how many digit positions to leave room for
        val want = max(max(typedDigits, l.decimals + 4), 6)
        var slots = min(want, l.offsets.size)
        while (slots > typedDigits && right - l.offsets[slots - 1] - l.digitW < 0.105 * cw) slots--
        val left = min(right - l.offsets[slots - 1] - l.digitW, gs.minOf { it[0] }.toDouble())
        val contentW = right - left
        if (contentW < 20.0) return null

        val wNew = contentW / 0.78
        val xl = left - 0.12 * wNew
        val xr = right + 0.10 * wNew
        val hd = (l.bandBottom - l.bandTop + 1).toDouble()
        val yt = l.bandTop - 0.18 * hd
        val yb = l.bandBottom + 0.18 * hd
        val cy = l.canvasH * 0.37
        val s = l.shear

        val box = MatOfPoint2f(
            Point(xl - s * (yt - cy), yt), Point(xr - s * (yt - cy), yt),
            Point(xr - s * (yb - cy), yb), Point(xl - s * (yb - cy), yb)
        )
        val canvas = MatOfPoint2f(Point(0.0, 0.0), Point(cw, 0.0), Point(cw, ch), Point(0.0, ch))
        val frameQuad = MatOfPoint2f(Point(q[0], q[1]), Point(q[2], q[3]), Point(q[4], q[5]), Point(q[6], q[7]))
        val m = Imgproc.getPerspectiveTransform(canvas, frameQuad)    // straightened picture -> frame
        val out = MatOfPoint2f()
        Core.perspectiveTransform(box, out, m)
        val a = out.toArray()
        for (mat in listOf(box, canvas, frameQuad, m, out)) mat.release()
        if (a.size != 4) return null

        val res = DoubleArray(8)
        for (i in 0 until 4) {
            res[2 * i] = a[i].x.coerceIn(0.0, frameW - 1.0)
            res[2 * i + 1] = a[i].y.coerceIn(0.0, frameH - 1.0)
        }
        val ratio = quadArea(res) / max(quadArea(q), 1e-6)
        if (ratio < 0.05 || ratio > 6.0) return null
        return res
    }

    /**
     * SET UP: takes the frame, the four corners you marked, and the number you typed. Saves a picture of the
     * timer (for finding it again later) and learns how its digits look.
     */
    fun createSetup(frame: Bitmap, corners: DoubleArray, typed: String): SetupResult {
        val gray = bitmapToGray(frame)
        try {
            val dw = dist(corners, 0, 1)
            val cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4.0
            val cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4.0
            val x0 = max(0, (cx - 2.6 * dw).toInt())
            val x1 = min(frame.width, (cx + 2.6 * dw).toInt())
            val y0 = max(0, (cy - 1.3 * dw).toInt())
            val y1 = min(frame.height, (cy + 1.3 * dw).toInt())
            if (x1 - x0 < 50 || y1 - y0 < 50) return SetupResult(null, null, "The marked corners look wrong - please try again.")
            val ref = Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0)

            val finder = ShapeFinder(ref, x0, y0, corners)
            // A plain screen (PC / phone timer) can have too little detail around it to find by shape.
            // Then the timer is read at the saved position, so the camera must stay still.
            val fixed = !finder.ok
            val q = try {
                if (fixed) {
                    corners
                } else {
                    finder.locate(gray)
                        ?: return SetupResult(null, null, "Couldn't recognise the timer's shape in this frame. Try a sharper frame.")
                }
            } finally {
                finder.release()      // only needed to find the display once; the TimerEngine builds its own
            }
            // try each reader in order (see DisplayReaders.kt); the first one that reproduces your number wins
            var layout: DigitLayout? = null
            for (reader in DisplayReaders.all) {
                if (!reader.available) continue
                layout = reader.learn(gray, q, typed)
                if (layout != null) break
            }
            // Nothing reproduced your number exactly. Save the closest layout anyway, with a warning, instead of stopping.
            var unverified = false
            if (layout == null) {
                for (reader in DisplayReaders.all) {
                    if (!reader.available) continue
                    layout = reader.learnUnverified(gray, q, typed)
                    if (layout != null) {
                        unverified = true
                        break
                    }
                }
            }
            if (layout == null) {
                return SetupResult(
                    null, null,
                    "Couldn't find digit shapes in this box that fit \"$typed\" (wrong number of digits, or the box " +
                            "holds other things). Check the number and that the four dots sit on the display's corners."
                )
            }
            val learned: DigitLayout = layout

            // Try a snug box around the digits. It is kept only if learning on it still reproduces your number.
            var finalLayout = learned
            var readCorners = corners
            var readQ = q
            var tightened = false
            val tight = if (unverified) null else tightenQuad(gray, q, learned, typed.count { it.isDigit() }, frame.width, frame.height)
            if (tight != null) {
                val again = DisplayReaders.byId(learned.readerId).learn(gray, tight, typed)
                if (again != null) {
                    finalLayout = again
                    readCorners = tight
                    readQ = tight
                    tightened = true
                }
            }

            val setup = TimerSetup(corners, x0, y0, finalLayout, fixed, readCorners)
            val th = prep(gray, readQ, finalLayout.shear, finalLayout.invert, finalLayout.canvasW, finalLayout.canvasH)
            val check = DisplayReaders.byId(finalLayout.readerId).read(th, finalLayout)
            val shown = if (check != null) formatDigits(check, finalLayout.decimals) else "(nothing readable)"
            val note = (if (fixed) " (fixed-camera mode: keep the camera still)" else "") +
                    (if (tightened) " (box tightened)" else "")
            val message = if (unverified) {
                "Timer saved WITH A WARNING: the digits did not match \"$typed\", so readings may be wrong. " +
                        "Test read: $shown. Use \"Read timer here\" on a few frames to check it, or set it up again.$note"
            } else {
                "Timer saved$note. Test read: $shown"
            }
            return SetupResult(setup, ref, message)
        } finally {
            gray.release()
        }
    }
}

// ---------------------------------------------------------------------------------------
// Finding the timer anywhere in a frame by its shape (no colours, so lighting changes don't matter)
// ---------------------------------------------------------------------------------------
/**
 * [corners] is the whole display as you marked it (hidden from the matcher, because the digits keep changing).
 * [readCorners] is the box locate() hands back - usually the same, or a snug box around the digits.
 */
class ShapeFinder(refBitmap: Bitmap, cropX: Int, cropY: Int, corners: DoubleArray, readCorners: DoubleArray = corners) {
    private val scale = 0.5
    private val minMatches = 14
    private val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
    private val orb = ORB.create(2500, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 8)
    private val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
    private val refDes = Mat()
    private val refKp: Array<KeyPoint>
    private val refQuad = DoubleArray(8)
    private val refRead = DoubleArray(8)
    val ok: Boolean

    init {
        val gray = TimerVision.bitmapToGray(refBitmap)
        val small = prepForMatching(gray)
        val mask = Mat(small.rows(), small.cols(), CvType.CV_8UC1, Scalar(255.0))
        for (i in 0 until 8) refQuad[i] = (corners[i] - (if (i % 2 == 0) cropX else cropY)) * scale
        for (i in 0 until 8) refRead[i] = (readCorners[i] - (if (i % 2 == 0) cropX else cropY)) * scale
        val pts = Array(4) { i -> Point(refQuad[2 * i], refQuad[2 * i + 1]) }
        // ignore the display itself: its digits keep changing
        Imgproc.fillConvexPoly(mask, MatOfPoint(*pts), Scalar(0.0))
        val kp = MatOfKeyPoint()
        orb.detectAndCompute(small, mask, kp, refDes)
        refKp = kp.toArray()
        ok = refKp.size >= 30 && !refDes.empty()
        gray.release()
        small.release()
        mask.release()
    }

    /** Frees the native memory this finder holds. Call when the finder is no longer needed (it can't be used afterwards). */
    fun release() {
        refDes.release()
    }

    private fun prepForMatching(gray: Mat): Mat {
        val small = Mat()
        Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val out = Mat()
        clahe.apply(small, out)           // evens out brightness and contrast
        small.release()
        return out
    }

    private fun area(p: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return abs(s) / 2.0
    }

    private fun isConvex(p: DoubleArray): Boolean {
        var pos = 0
        var neg = 0
        for (i in 0 until 4) {
            val a = i
            val b = (i + 1) % 4
            val c = (i + 2) % 4
            val cross = (p[2 * b] - p[2 * a]) * (p[2 * c + 1] - p[2 * b + 1]) -
                    (p[2 * b + 1] - p[2 * a + 1]) * (p[2 * c] - p[2 * b])
            if (cross > 0) pos++ else if (cross < 0) neg++
        }
        return pos == 0 || neg == 0
    }

    /** Returns the display's four corners (TL, TR, BR, BL as 8 numbers, in frame pixels) or null if not found. */
    fun locate(gray: Mat): DoubleArray? {
        if (!ok) return null
        val small = prepForMatching(gray)
        val noMask = Mat()
        val kp = MatOfKeyPoint()
        val des = Mat()
        val knn = ArrayList<MatOfDMatch>()
        val srcMat = MatOfPoint2f()
        val dstMat = MatOfPoint2f()
        val inliers = Mat()
        val quadIn = MatOfPoint2f(
            Point(refRead[0], refRead[1]), Point(refRead[2], refRead[3]),
            Point(refRead[4], refRead[5]), Point(refRead[6], refRead[7])
        )
        val quadOut = MatOfPoint2f()
        var hMat: Mat? = null
        try {
            orb.detectAndCompute(small, noMask, kp, des)
            val frameKp = kp.toArray()
            if (des.empty() || frameKp.size < minMatches) return null

            matcher.knnMatch(refDes, des, knn, 2)
            val src = ArrayList<Point>()
            val dst = ArrayList<Point>()
            for (pair in knn) {
                val m = pair.toArray()
                if (m.size == 2 && m[0].distance < 0.75f * m[1].distance) {
                    src.add(refKp[m[0].queryIdx].pt)
                    dst.add(frameKp[m[0].trainIdx].pt)
                }
            }
            if (src.size < minMatches) return null

            srcMat.fromList(src)
            dstMat.fromList(dst)
            val h = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, 3.0, inliers)
            hMat = h
            if (h.empty() || Core.countNonZero(inliers) < minMatches) return null

            Core.perspectiveTransform(quadIn, quadOut, h)
            val pts = quadOut.toArray()
            if (pts.size != 4) return null
            val p = DoubleArray(8) { i -> if (i % 2 == 0) pts[i / 2].x else pts[i / 2].y }
            val ratio = area(p) / max(area(refRead), 1e-6)
            if (!isConvex(p) || ratio < 0.5 || ratio > 2.0) return null   // a nonsense match
            return DoubleArray(8) { i -> p[i] / scale }
        } finally {
            for (mat in listOf(small, noMask, kp, des, srcMat, dstMat, inliers, quadIn, quadOut)) mat.release()
            for (k in knn) k.release()
            hMat?.release()
        }
    }
}

// ---------------------------------------------------------------------------------------
// Ties it together: find the timer in a frame, then read it
// ---------------------------------------------------------------------------------------
class TimerEngine(val setup: TimerSetup, referenceBitmap: Bitmap) {
    // in fixed-camera mode there is no shape finder: the saved corners are used as they are
    private val finder: ShapeFinder? =
        if (setup.fixedPosition) null else ShapeFinder(referenceBitmap, setup.cropX, setup.cropY, setup.corners, setup.readCorners)
    private val reader: DisplayReader = DisplayReaders.byId(setup.layout.readerId)

    /** How many digits come after the decimal point on this timer. */
    val decimals: Int get() = setup.layout.decimals

    /** Frees the native memory of the shape finder. Call when this timer setup is replaced; don't use the engine afterwards. */
    fun release() {
        finder?.release()
    }

    private val usable: Boolean get() = finder == null || finder.ok

    private fun locate(gray: Mat): DoubleArray? = if (finder == null) setup.readCorners else finder.locate(gray)

    // ---- "last position first": the slow shape finder (ORB) only runs when it is really needed ----
    // While the camera stays put, the display is in the same place as on the previous frame, so we try reading with
    // that position first. Only if that read comes back empty (hand in the way, camera moved...) do we run the shape finder.
    // To stay safe against slow drift, the shape finder also re-checks the position every REFIND_EVERY quick reads.
    private val REFIND_EVERY = 20
    private var lastQuad: DoubleArray? = null
    private var quickReadsSinceFind = 0

    /** Start of a new scan: don't trust the position remembered from before. */
    fun forgetPosition() {
        lastQuad = null
        quickReadsSinceFind = 0
    }

    /**
     * Quick version of [readFrame]: just the digits (or null), no picture. [gray] is the whole upright frame as a gray picture
     * (the caller releases it). This is what Find solves uses.
     */
    fun readDigitsGray(gray: Mat): String? {
        if (!usable) return null
        val l = setup.layout

        // 1) the position from the previous frame (not in fixed-camera mode: there locate() is already free)
        val cached = lastQuad
        if (finder != null && cached != null && quickReadsSinceFind < REFIND_EVERY) {
            val digits = reader.read(TimerVision.prep(gray, cached, l.shear, l.invert, l.canvasW, l.canvasH), l)
            if (digits != null) {
                quickReadsSinceFind++
                return digits
            }
        }

        // 2) the normal way: find the display by its shape
        val q = locate(gray)
        if (q == null) {
            lastQuad = null
            return null
        }
        lastQuad = q
        quickReadsSinceFind = 0
        return reader.read(TimerVision.prep(gray, q, l.shear, l.invert, l.canvasW, l.canvasH), l)
    }

    fun readFrame(frame: Bitmap): ReadResult {
        if (!usable) return ReadResult(null, "The saved timer picture is unusable - please set up the timer again.", null)
        val gray = TimerVision.bitmapToGray(frame)
        try {
            val q = locate(gray)
                ?: return ReadResult(null, "Couldn't find the timer in this frame (hand in the way, or the camera moved a lot?)", null)
            val l = setup.layout
            val th = TimerVision.prep(gray, q, l.shear, l.invert, l.canvasW, l.canvasH)
            val digits = reader.read(th, l)
            val picture = TimerVision.binToBitmap(th)
            val mode = if (setup.fixedPosition) " (fixed-camera mode)" else ""
            return if (digits != null) {
                ReadResult(digits, "Timer reads: ${TimerVision.formatDigits(digits, l.decimals)}$mode", picture)
            } else {
                ReadResult(null, "Found the timer, but the digits weren't clear (glare? a finger over the display?)$mode", picture)
            }
        } finally {
            gray.release()
        }
    }
}

// ---------------------------------------------------------------------------------------
// Leftovers from versions that saved the timer setup on the phone
// ---------------------------------------------------------------------------------------
object TimerStore {
    private const val JSON_FILE = "timer_setup.json"
    private const val PNG_FILE = "timer_reference.png"

    /**
     * Deletes the single timer setup that version 1.x saved for the whole app (since 3.0.0 the setup is saved per video, in
     * SessionStore). Can go once nobody runs 1.x any more.
     */
    fun clear(context: Context) {
        try {
            File(context.filesDir, JSON_FILE).delete()
            File(context.filesDir, PNG_FILE).delete()
        } catch (e: Exception) {
            // nothing to clean up
        }
    }
}