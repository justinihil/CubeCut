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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// (3.3-C) The GRID of a seven-segment number: where each digit's place is. A display's digits stand at fixed distances, so once the grid
// is known every place can be compared with the ten digits, also when a stroke is faint, blurred or covered. Pure logic, no OpenCV.

/** The room around a digit's place, at the size digits are compared ([SEG_W] x [SEG_H]). */
private const val ROOM = 4
private const val PLACE_W = SEG_W + 2 * ROOM
private const val PLACE_H = SEG_H + 2 * ROOM
private const val PLACE = PLACE_W * PLACE_H

/** A place's digit is sure from this likeness on (1 = identical)... */
private const val SURE = 0.55f

/** ...and a place is empty when it has less contrast than this share of the others. */
private const val EMPTY = 0.4f

/** Two digits that fit a place this nearly alike are a close call: then the place is also moved by this many pixels, to all sides. */
private const val CLOSE_CALL = 0.06f
private const val NUDGE = 2

/** How many digits a display can have: two before the minutes' colon would be 7, but those aren't read yet. */
private const val MOST_DIGITS = 6

/** Right edges of a first row may be off a grid by at most this, in digit heights. */
private const val OFF_GRID = 0.09

/** Looking for the best place, far ones count a little less: per squared pixel of the level picture. Less so when it was pushed away. */
private const val FAR = 0.0006f
private const val FAR_PUSHED = 0.00002f

/** A reach longer than this is looked at at half size first. */
private const val ROUGH_FROM = 4

/** How far a pushed timer is looked for, in pixels of the level picture: 1.5 digit heights. Further away it is looked for at half size. */
private const val PUSHED = 60

private val PLACE_SHAPES: List<FloatArray> by lazy {
    SEGMENT_DIGITS.map { (_, s) ->
        val digit = drawnDigit(s)
        val a = FloatArray(PLACE)
        for (y in 0 until SEG_H) for (x in 0 until SEG_W) a[(y + ROOM) * PLACE_W + x + ROOM] = digit[y * SEG_W + x]
        softened(a, PLACE_W, PLACE_H, 1.6).also { evenOut(it) }
    }
}

/** The same at half size, for a quick look around. */
private val SMALL_SHAPES: List<FloatArray> by lazy {
    PLACE_SHAPES.map { s ->
        val w = PLACE_W / 2
        FloatArray(PLACE / 4) { val i = (it / w) * 2 * PLACE_W + (it % w) * 2; s[i] + s[i + 1] + s[i + PLACE_W] + s[i + PLACE_W + 1] }.also { evenOut(it) }
    }
}

/** What one place of the grid shows. */
class PlaceRead(val digit: Int, val likeness: Float, val contrast: Float)

/**
 * The grid. The frame is turned by [turn], then made level by [slope] and [lean]; there, the last digit ends at [right], digits are between
 * [top] and [bottom] and [digitWidth] wide, and are [pitch] apart, plus [gap] where the decimal point is ([decimals] digits from the end).
 */
data class SegmentGrid(
    val turn: Flat, val slope: Double, val lean: Double,
    val right: Double, val top: Double, val bottom: Double,
    val digitWidth: Double, val pitch: Double, val gap: Double,
    val decimals: Int, val dark: Boolean
) {
    val height: Double get() = bottom - top
    val toLevel: Flat by lazy { Flat.level(slope, lean).after(turn) }
    private val back: Flat by lazy { toLevel.inverse() }

    /** The middle of the last few digits, in the frame. */
    val middleX: Double get() = back.x(right - pitch * 1.5, (top + bottom) / 2)
    val middleY: Double get() = back.y(right - pitch * 1.5, (top + bottom) / 2)

    /** Where the last [digits] digits are in the frame, as a box. */
    fun box(digits: Int): Area {
        val l = rightOf(digits - 1) - digitWidth
        val xs = listOf(back.x(l, top), back.x(right, top), back.x(l, bottom), back.x(right, bottom))
        val ys = listOf(back.y(l, top), back.y(right, top), back.y(l, bottom), back.y(right, bottom))
        return Area(floor(xs.min()).toInt(), floor(ys.min()).toInt(), xs.max().toInt() + 1, ys.max().toInt() + 1)
    }

    /** Are the last digits cut off by the edge of a [w] x [h] picture? */
    fun isCutOff(w: Int, h: Int): Boolean = box(decimals + 1).let { it.left < 0 || it.top < 0 || it.right > w || it.bottom > h }

    /** The same grid ([dx], [dy]) further in the frame. */
    fun moved(dx: Double, dy: Double): SegmentGrid {
        val x = toLevel.x(dx, dy) - toLevel.x(0.0, 0.0)
        val y = toLevel.y(dx, dy) - toLevel.y(0.0, 0.0)
        return copy(right = right + x, top = top + y, bottom = bottom + y)
    }

    private fun rightOf(i: Int): Double = right - pitch * i - if (i >= decimals) gap else 0.0

    /** The level picture of the last digits at the size digits are compared ([by] times smaller), with [room] more around it, and where each place begins. */
    private inner class Level(g: GrayPic, digits: Int, room: Int, by: Int = 1) {
        private val sx = SEG_W / by / digitWidth
        private val sy = SEG_H / by / height
        private val edge = ROOM / by + room
        private val left = rightOf(digits - 1) - digitWidth
        val w = ((right - left) * sx).toInt() + 2 * edge + 1
        val h = PLACE_H / by + 2 * room
        val d: FloatArray
        val starts = DoubleArray(digits) { (rightOf(it) - digitWidth - left) * sx }

        init {
            val v = FloatArray(w * h)
            for (y in 0 until h) {
                val ly = (y - edge) / sy + top
                for (x in 0 until w) {
                    val lx = (x - edge) / sx + left
                    v[y * w + x] = g.between(back.x(lx, ly), back.y(lx, ly))
                }
            }
            // the difference to the surroundings, so that a shadow across the display doesn't matter
            val around = boxMean(v, w, h, 10 / by)
            for (i in v.indices) v[i] = max(-8f, if (dark) around[i] - v[i] else v[i] - around[i])
            d = softened(v, w, h, 0.8 / by)
        }
    }

    /** What the last [digits] places show, the last one first. */
    fun read(g: GrayPic, digits: Int = MOST_DIGITS): List<PlaceRead> {
        val level = Level(g, digits, NUDGE)
        val p = FloatArray(PLACE)
        val likes = FloatArray(10)
        return List(digits) { i ->
            /** Fills [likes] for the place moved by ([dx], [dy]), and gives its contrast. */
            fun look(dx: Int, dy: Int): Float {
                val start = level.starts[i] + NUDGE + dx
                val from = floor(start).toInt()
                val f = (start - from).toFloat()
                for (y in 0 until PLACE_H) {
                    val row = (y + NUDGE + dy) * level.w
                    for (x in 0 until PLACE_W) {
                        val k = row + min(level.w - 1, from + x)
                        val k2 = row + min(level.w - 1, from + x + 1)
                        p[y * PLACE_W + x] = level.d[k] * (1 - f) + level.d[k2] * f
                    }
                }
                var mean = 0f
                for (v in p) mean += v
                mean /= PLACE
                var sum = 0f
                for (v in p) sum += (v - mean) * (v - mean)
                val some = evenOut(p)
                likes.fill(-1f)
                if (some) for (s in PLACE_SHAPES.indices) {
                    val d = SEGMENT_DIGITS[s].first
                    likes[d] = max(likes[d], likeness(p, PLACE_SHAPES[s]))
                }
                return sqrt(sum / PLACE)
            }

            val contrast = look(0, 0)
            var digit = likes.indices.maxByOrNull { likes[it] }!!
            var best = likes[digit]
            val second = likes.indices.filter { it != digit }.maxOf { likes[it] }
            // two digits fit nearly as well (7 and 1, 6 and 5): the one that fits better with the place moved a little is it
            if (best >= SURE && best - second < CLOSE_CALL) {
                for (dy in -NUDGE..NUDGE step NUDGE) for (dx in -NUDGE..NUDGE step NUDGE) if (dx != 0 || dy != 0) {
                    look(dx, dy)
                    for (d in likes.indices) if (likes[d] > best) { best = likes[d]; digit = d }
                }
            }
            PlaceRead(digit, best, contrast)
        }
    }

    /** The grid moved, by at most [reach] pixels of the level picture, to where its digits fit best; and how well they fit there. */
    fun located(g: GrayPic, reach: Int, digits: Int = decimals + 1): Pair<SegmentGrid, Float> =
        if (reach <= ROUGH_FROM) searched(g, reach, digits, 1, FAR)
        else searched(g, (reach + 1) / 2, digits, 2, 4 * FAR).first.searched(g, 2, digits, 1, FAR)      // (roughly first: four times fewer places, four times smaller)

    /** The same for a timer that was pushed away: a quick look further around, then a proper one there. */
    fun foundAgain(g: GrayPic): Pair<SegmentGrid, Float> = searched(g, PUSHED / 2, decimals + 1, 2, 4 * FAR_PUSHED).first.searched(g, 4, decimals + 1, 1, FAR)

    private fun searched(g: GrayPic, reach: Int, digits: Int, by: Int, far: Float): Pair<SegmentGrid, Float> {
        val level = Level(g, digits, reach, by)
        val shapes = if (by == 1) PLACE_SHAPES else SMALL_SHAPES
        val pw = PLACE_W / by
        val ph = PLACE_H / by
        val n = 2 * reach + 1
        val sum = FloatArray(n * n)
        val p = FloatArray(pw * ph)
        // the likeness of the best digit at every place of the level picture, worked out when it is first needed
        val columns = level.w - pw + 1
        val best = FloatArray(n * columns) { Float.NaN }
        fun at(x: Int, y: Int): Float {
            val c = if (x < 0) 0 else if (x >= columns) columns - 1 else x
            val known = best[y * columns + c]
            if (!known.isNaN()) return known
            for (j in 0 until ph) System.arraycopy(level.d, (y + j) * level.w + c, p, j * pw, pw)
            var l = -1f
            if (evenOut(p)) for (s in shapes) { val k = likeness(p, s); if (k > l) l = k }
            best[y * columns + c] = l
            return l
        }
        for (start in level.starts) {
            val from = floor(start).toInt()
            val f = (start - from).toFloat()
            for (y in 0 until n) for (x in 0 until n) sum[y * n + x] += at(from + x, y) * (1 - f) + at(from + x + 1, y) * f
        }
        var at = 0
        var most = -1e9f
        for (y in 0 until n) for (x in 0 until n) {
            val s = sum[y * n + x] / digits - far * ((y - reach) * (y - reach) + (x - reach) * (x - reach))
            if (s > most) { most = s; at = y * n + x }
        }
        val dx = (at % n - reach) * by * digitWidth / SEG_W
        val dy = (at / n - reach) * by * height / SEG_H
        return Pair(copy(right = right + dx, top = top + dy, bottom = bottom + dy), sum[at] / digits)
    }

    /** This grid's shape where [place] is: the middle of the last digit stays. */
    fun putAt(place: SegmentGrid): SegmentGrid {
        val x = place.back.x(place.right - place.digitWidth / 2, (place.top + place.bottom) / 2)
        val y = place.back.y(place.right - place.digitWidth / 2, (place.top + place.bottom) / 2)
        val h = height
        val middle = toLevel.y(x, y)
        return copy(right = toLevel.x(x, y) + digitWidth / 2, top = middle - h / 2, bottom = middle + h / 2)
    }

    /** One shape for the whole video: made to fit many pictures, each with the place where the timer is in it. */
    fun refinedOver(pics: List<Pair<GrayPic, SegmentGrid>>, isCancelled: () -> Boolean): Pair<SegmentGrid, Double> {
        // (where a digit is sure before the last ones, it counts too: from there the grid can be told best)
        val digits = pics.map { (pic, place) ->
            val read = place.read(pic, decimals + 2)
            val first = read[decimals + 1]
            if (first.likeness >= SURE && first.contrast >= EMPTY * typicalContrast(read, decimals)) decimals + 2 else decimals + 1
        }
        fun quality(shape: SegmentGrid): Double {
            if (isCancelled()) throw ScanCancelled()
            val q = DoubleArray(pics.size)
            IntStream.range(0, pics.size).parallel().forEach { i ->
                val (pic, place) = pics[i]
                q[i] = shape.putAt(place).located(pic, 2, digits[i]).first.read(pic, digits[i]).sumOf { it.likeness.toDouble() } / digits[i]
            }
            return q.average()
        }
        var now = this
        var q = quality(now)
        val px = height / 38
        val changes: List<(SegmentGrid, Double) -> SegmentGrid> = listOf(
            { g, s -> g.copy(slope = g.slope + 0.015 * s) }, { g, s -> g.copy(lean = g.lean + 0.04 * s) },
            { g, s -> g.copy(digitWidth = g.digitWidth + 0.8 * px * s) }, { g, s -> g.copy(pitch = g.pitch + 0.5 * px * s) },
            { g, s -> g.copy(gap = g.gap + 0.8 * px * s) }, { g, s -> g.copy(bottom = g.bottom + px * s) }
        )
        for (round in 0..2) for (change in changes) for (sign in listOf(1.0, -1.0)) {
            while (true) {
                val t = change(now, sign / (1 shl round))
                if (t.gap < 0 || t.digitWidth >= t.pitch || t.height < 4) break
                val better = quality(t)
                if (better <= q + 2e-4) break
                now = t
                q = better
            }
        }
        return Pair(now, q)
    }

    /** How well the digits fit in these pictures. */
    private fun quality(pics: List<GrayPic>): Double = pics.sumOf { g -> read(g, decimals + 1).sumOf { it.likeness.toDouble() } / (decimals + 1) } / pics.size

    /** The same grid with a changed slope or lean, still around the same point of the frame (else it would swing away). */
    private fun swung(slope: Double, lean: Double): SegmentGrid {
        val x = right - pitch * 2
        val y = (top + bottom) / 2
        val t = copy(slope = slope, lean = lean)
        val fx = back.x(x, y)
        val fy = back.y(x, y)
        val dx = t.toLevel.x(fx, fy) - x
        val dy = t.toLevel.y(fx, fy) - y
        return t.copy(right = right + dx, top = top + dy, bottom = bottom + dy)
    }

    /** The grid made to fit [pics] better, one number at a time, in smaller and smaller steps. */
    fun refined(pics: List<GrayPic>, rounds: Int = 3): Pair<SegmentGrid, Double> {
        var now = this
        var q = quality(pics)
        val px = height / 38
        val changes: List<(SegmentGrid, Double) -> SegmentGrid> = listOf(
            { g, s -> g.swung(g.slope + 0.02 * s, g.lean) }, { g, s -> g.swung(g.slope, g.lean + 0.05 * s) },
            { g, s -> g.copy(right = g.right + 1.5 * px * s) }, { g, s -> g.copy(top = g.top + 1.5 * px * s) },
            { g, s -> g.copy(bottom = g.bottom + 1.5 * px * s) }, { g, s -> g.copy(digitWidth = g.digitWidth + 1.2 * px * s) },
            { g, s -> g.copy(pitch = g.pitch + 0.8 * px * s) }, { g, s -> g.copy(gap = g.gap + 1.2 * px * s) }
        )
        for (round in 0 until rounds) for (change in changes) for (sign in listOf(1.0, -1.0)) {
            while (true) {
                val t = change(now, sign / (1 shl round))
                if (t.gap < 0 || t.digitWidth >= t.pitch || t.height < 4) break
                val better = t.quality(pics)
                if (better <= q + 1e-4) break
                now = t
                q = better
            }
        }
        return Pair(now, q)
    }

    companion object {
        /** The grid that goes through a first row, or null if its digits aren't evenly spaced. */
        fun through(row: SegmentRow): SegmentGrid? {
            val n = row.rights.size
            if (n < 4) return null
            var best: SegmentGrid? = null
            var bestOff = OFF_GRID * row.height
            for (decimals in 1..3) {
                if (n - decimals < 1 || n - decimals > 3) continue
                // the right edge of the i-th digit from the end = right - i pitch - (gap if i >= decimals)
                val a = Array(3) { DoubleArray(4) }
                for (k in 0 until n) {
                    val i = n - 1 - k
                    val c = doubleArrayOf(1.0, -i.toDouble(), if (i >= decimals) -1.0 else 0.0, row.rights[k])
                    for (r in 0..2) for (m in 0..3) a[r][m] += c[r] * c[m]
                }
                val (right, pitch, gap) = solved(a) ?: continue
                if (pitch < 0.45 * row.height || pitch > 1.7 * row.height || gap < -0.06 * row.height || gap >= 0.5 * pitch || row.digitWidth >= pitch) continue
                val off = (0 until n).maxOf { k -> val i = n - 1 - k; abs(right - i * pitch - (if (i >= decimals) gap else 0.0) - row.rights[k]) }
                if (off >= bestOff) continue
                bestOff = off
                best = SegmentGrid(row.turn, row.slope, row.lean, right, row.top, row.bottom, row.digitWidth, pitch, max(0.0, gap), decimals, row.dark)
            }
            return best
        }

        /** Three equations with three unknowns (each row: the factors and the right side), or null if they can't be solved. */
        private fun solved(a: Array<DoubleArray>): Triple<Double, Double, Double>? {
            for (c in 0..2) {
                val p = (c..2).maxByOrNull { abs(a[it][c]) }!!
                if (abs(a[p][c]) < 1e-9) return null
                val t = a[c]; a[c] = a[p]; a[p] = t
                for (r in 0..2) if (r != c) {
                    val f = a[r][c] / a[c][c]
                    for (m in c..3) a[r][m] -= f * a[c][m]
                }
            }
            return Triple(a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2])
        }
    }
}

/** The time the places show, without the last digits, which blur while the timer runs: seconds and tenths, or null. */
fun tenthsOf(places: List<PlaceRead>, decimals: Int): Double? {
    val typical = typicalContrast(places, decimals)
    val tenth = places[decimals - 1]
    val unit = places[decimals]
    if (tenth.likeness < 0.5f || unit.likeness < 0.5f || tenth.contrast < EMPTY * typical || unit.contrast < EMPTY * typical) return null
    var v = unit.digit + tenth.digit / 10.0
    var worth = 10.0
    for (k in decimals + 1 until places.size) {
        if (places[k].contrast < 0.45f * typical || places[k].likeness < 0.45f) break
        v += places[k].digit * worth
        worth *= 10
    }
    return v
}

/** The whole time the places show, with '?' for unsure digits. */
fun segmentText(places: List<PlaceRead>, decimals: Int): String {
    val typical = typicalContrast(places, decimals)
    var n = places.size
    while (n > decimals + 1 && (places[n - 1].likeness < 0.45f || places[n - 1].contrast < EMPTY * typical)) n--
    val digits = places.take(n).reversed().map { if (it.likeness >= SURE && it.contrast >= EMPTY * typical) '0' + it.digit else '?' }.joinToString("")
    return digits.dropLast(decimals) + "." + digits.takeLast(decimals)
}

private fun typicalContrast(places: List<PlaceRead>, decimals: Int): Float {
    val c = places.take(decimals + 1).map { it.contrast }.sorted()
    return if (c.size % 2 == 1) c[c.size / 2] else (c[c.size / 2 - 1] + c[c.size / 2]) / 2
}

/** The value at a point between pixels; outside, the edge is repeated. */
private fun GrayPic.between(x: Double, y: Double): Float {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val u = (x - ix).toFloat()
    val v = (y - iy).toFloat()
    val x0 = if (ix < 0) 0 else if (ix > w - 1) w - 1 else ix
    val x1 = if (ix + 1 < 0) 0 else if (ix + 1 > w - 1) w - 1 else ix + 1
    val y0 = (if (iy < 0) 0 else if (iy > h - 1) h - 1 else iy) * w
    val y1 = (if (iy + 1 < 0) 0 else if (iy + 1 > h - 1) h - 1 else iy + 1) * w
    val a = (d[y0 + x0].toInt() and 255) * (1 - u) + (d[y0 + x1].toInt() and 255) * u
    val b = (d[y1 + x0].toInt() and 255) * (1 - u) + (d[y1 + x1].toInt() and 255) * u
    return a * (1 - v) + b * v
}

/** The average of the (2 [r] + 1) x (2 [r] + 1) values around each; outside, the edge is mirrored. */
private fun boxMean(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    fun at(i: Int, n: Int): Int { var j = i; while (j < 0 || j >= n) j = if (j < 0) -j else 2 * n - 2 - j; return j }
    val across = FloatArray(w * h)
    val column = IntArray(w + 2 * r) { at(it - r, w) }
    for (y in 0 until h) {
        val row = y * w
        var s = 0f
        for (k in 0..2 * r) s += v[row + column[k]]
        across[row] = s
        for (x in 1 until w) { s += v[row + column[x + 2 * r]] - v[row + column[x - 1]]; across[row + x] = s }
    }
    val out = FloatArray(w * h)
    val sums = FloatArray(w)
    for (k in -r..r) { val row = at(k, h) * w; for (x in 0 until w) sums[x] += across[row + x] }
    val area = ((2 * r + 1) * (2 * r + 1)).toFloat()
    for (y in 0 until h) {
        if (y > 0) {
            val enter = at(y + r, h) * w
            val leave = at(y - 1 - r, h) * w
            for (x in 0 until w) sums[x] += across[enter + x] - across[leave + x]
        }
        for (x in 0 until w) out[y * w + x] = sums[x] / area
    }
    return out
}
