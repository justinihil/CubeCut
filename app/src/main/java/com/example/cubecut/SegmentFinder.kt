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

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// (3.3-C) Finds a seven-segment timer in a video by itself: rows of digits (SegmentRows.kt), a grid through each (SegmentGrid.kt), and the
// first grid whose seconds and tenths RISE WITH THE CLOCK in several pictures is the timer. Nothing is learned: the ten digits look the
// same on every such display. Pure logic, like TimerFinder.kt.

/** Places looked at before giving up, and again in softened pictures (a strongly noisy video needs that, and it only helps there). */
private const val SEGMENT_PLACES = 120
private const val SEGMENT_PLACES_SOFT = 40
private const val SOFT = 1.3

/** A run is this many pictures, this far apart, all rising with the clock. */
private const val SEGMENT_PICTURES = 6
private const val SEGMENT_STEP = 0.5

/** How far a tenth may be off the clock. */
private const val SEGMENT_OFF = 0.11

/** First rows worth checking: their likeness, and how many per place. */
private const val ROW_WORTH = 0.72
private const val ROWS_CHECKED = 24

/** Digits have to fit at least this well where a run is read. */
private const val FITS = 0.55f

/** A seven-segment timer: its grid as it was at second [t], what it showed there, and how well its digits fit (1 = perfectly). */
class FoundSegments(val grid: SegmentGrid, val t: Double, val shown: List<Double>, val fit: Double)

/**
 * [found] null = there is none. [held]: the final times read on the way, with their grid, for [findHeldSegments]. The rest is for the debug
 * report: places looked at, rows checked, and seconds spent on rows and on checking.
 */
class SegmentResult(
    val found: FoundSegments?, val places: Int, val checked: Int, val rowsSec: Double, val checkSec: Double, val fitSec: Double,
    val held: List<Pair<Sighting, SegmentGrid>> = emptyList()
)

/** A final time stands still: this long before or after it was seen, it reads the same (as in HeldFinder.kt). */
private const val HELD_STILL_SEC = 0.15

/** A final time has at least this many digits, all of them sure. */
private const val HELD_SEGMENT_DIGITS = 3

/**
 * The time [grid] shows in [g] if every digit of it is sure, as a number seen there, with the grid where it was read; else null. For a
 * seven-segment timer that shows only its final times (csTimer's "digital" fonts with "solve" while it runs).
 */
fun heldSegmentRow(grid: SegmentGrid, g: GrayPic): Pair<TimeRow, SegmentGrid>? {
    val (here, fit) = grid.located(g, 3)
    if (fit < FITS) return null
    val places = here.read(g)
    val text = segmentText(places, here.decimals)
    val digits = text.count { it.isDigit() }
    if ('?' in text || digits < HELD_SEGMENT_DIGITS) return null
    val box = here.box(digits)
    return Pair(TimeRow(text, places.take(digits).minOf { it.likeness }, here.height, box.left, box.top, box.right, box.bottom, here.dark, 0, emptyList()), here)
}

/**
 * A seven-segment timer that shows no running time, found by its final times among those [findSegments] read on its way ([held]), as
 * [findHeldTimer] does for solid digits: its grid, or null. [pic] is asked for a few pictures more. [notes] gets a line for the report.
 */
fun findHeldSegments(held: List<Pair<Sighting, SegmentGrid>>, pic: PicAt, notes: MutableList<String> = ArrayList()): SegmentGrid? {
    val gridOf = java.util.IdentityHashMap<TimeRow, SegmentGrid>()
    for ((s, g) in held) gridOf[s.row] = g
    val (_, still) = heldPlace(held.map { it.first }, { s ->
        listOf(-HELD_STILL_SEC, HELD_STILL_SEC).any { d -> pic.at(s.t + d)?.let { heldSegmentRow(gridOf.getValue(s.row), it) }?.first?.text == s.row.text }
    }, notes) ?: return null
    return gridOf.getValue(still[0].row)
}

/** Seconds spread over the video, halving the gaps. */
private fun spreadPlaces(duration: Double, most: Int): List<Double> {
    val out = ArrayList<Double>()
    var q = 2
    while (out.size < most) {
        for (i in 0 until q) out.add((duration * (2 * i + 1) / (2 * q) * 2).roundToInt() / 2.0)
        q *= 2
    }
    return out.take(most).distinct()
}

/** Looks for a seven-segment timer. [onProgress] gets 0..100. Throws [ScanCancelled] when [isCancelled] says so. */
fun findSegments(pic: PicAt, durationSec: Double, onProgress: (Int) -> Unit = {}, isCancelled: () -> Boolean = { false }): SegmentResult {
    var places = 0
    var checked = 0
    var rowsNanos = 0L
    var checkNanos = 0L
    fun frame(t: Double): GrayPic? {
        if (isCancelled()) throw ScanCancelled()
        return if (t > durationSec) null else pic.at(t)
    }

    /** The pictures of a run that begins with [first] at second [t], if all of them can be read and rise with the clock; and the times they show. */
    fun rising(grid: SegmentGrid, t: Double, first: GrayPic, kept: MutableMap<Int, GrayPic>): Pair<List<GrayPic>, List<Double>>? {
        val shown = ArrayList<Double>()
        for (k in 0 until SEGMENT_PICTURES) {
            val g = if (k == 0) first else kept[k] ?: frame(t + k * SEGMENT_STEP)?.also { kept[k] = it } ?: return null
            val (here, fits) = grid.located(g, 3)
            if (fits < FITS) return null
            shown.add(tenthsOf(here.read(g, grid.decimals + 1), grid.decimals) ?: return null)
            // (seconds without their tens: a faint first digit may be missed)
            if (abs((shown[k] - shown[0] - k * SEGMENT_STEP + 5).mod(10.0) - 5) > SEGMENT_OFF) return null
        }
        return Pair(listOf(first) + (1 until SEGMENT_PICTURES).map { kept.getValue(it) }, shown)
    }

    val held = ArrayList<Pair<Sighting, SegmentGrid>>()
    val sharp = spreadPlaces(durationSec, SEGMENT_PLACES)
    val all = sharp.map { Pair(it, false) } + sharp.take(SEGMENT_PLACES_SOFT).map { Pair(it, true) }
    for ((t, soft) in all) {
        places++
        onProgress(min(99, 100 * places / all.size))
        if (t + (SEGMENT_PICTURES - 1) * SEGMENT_STEP > durationSec) continue
        val first = frame(t) ?: continue
        val rowsStart = System.nanoTime()
        val rows = segmentRows(if (soft) softPic(first) else first).filter { it.score >= ROW_WORTH }
        val checkStart = System.nanoTime()
        rowsNanos += checkStart - rowsStart
        val kept = HashMap<Int, GrayPic>()
        var tried = 0
        for (row in rows) {
            if (tried >= ROWS_CHECKED) break
            val grid = SegmentGrid.through(row)?.widenedIn(first) ?: continue
            tried++
            checked++
            heldSegmentRow(grid, first)?.let { (row, here) -> held.add(Pair(Sighting(t, row), here)) }
            val (pics, shown) = rising(grid, t, first, kept) ?: continue
            val fitStart = System.nanoTime()
            checkNanos += fitStart - checkStart
            val (better, fit) = grid.located(first, 4).first.refined(pics)
            return SegmentResult(FoundSegments(better, t, shown, fit), places, checked, rowsNanos / 1e9, checkNanos / 1e9, (System.nanoTime() - fitStart) / 1e9)
        }
        checkNanos += System.nanoTime() - checkStart
    }
    return SegmentResult(null, places, checked, rowsNanos / 1e9, checkNanos / 1e9, 0.0, held)
}

private fun softPic(p: GrayPic): GrayPic {
    val a = softened(FloatArray(p.d.size) { (p.d[it].toInt() and 255).toFloat() }, p.w, p.h, SOFT)
    return GrayPic(p.w, p.h, ByteArray(a.size) { a[it].roundToInt().toByte() })
}
