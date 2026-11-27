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

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// (3.3-C) Reads a seven-segment timer all along the video. A timer on a mat slides and jumps when it is hit, so its place is followed:
// first a rough track (a picture every few seconds, outwards from where it was found), from which ONE grid is made that fits the whole
// video; then every picture that is read looks around the last known place. A place only counts where the digits fit well: a grid half
// a digit too low reads 0.000 as 4.444. Pure logic, like SegmentFinder.kt.

/** The rough track has a picture this often. */
private const val TRACK_STEP = 10.0

/** The digits fit this well at a place that counts as the timer's... */
private const val PLACE_FITS = 0.7f

/** ...this well in pictures good enough to refine the grid with... */
private const val GOOD_FIT = 0.8f
private const val REFINE_PICTURES = 24
private const val REFINE_LEAST = 8

/** ...and at least this well to be read at all. */
private const val READ_FITS = 0.66f

/** Below this, it is looked for further away: it could have been pushed. */
private const val LOOK_FURTHER = 0.76f

/** A further place must be this much better. */
private const val BETTER = 0.08f

/** Looked for far away at most this often, in seconds of the video, and after four tries in vain only every fourth time. */
private const val FAR_STEP = 0.45

/** What the display shows in one picture: the whole [text], if every digit is sure, and the seconds to the tenth, if those two are. */
class SegmentShown(val text: String?, val tenths: Double?)

class SegmentTracker private constructor(private val pic: PicAt, private val duration: Double, private val isCancelled: () -> Boolean) {
    private val places = TreeMap<Double, SegmentGrid>()
    private var last: Pair<Double, SegmentShown>? = null        // (only the last: a scan asks for the real time of the frame it just read)
    private var lastFar = -1e9
    private var inVain = 0

    /** For the report: how many pictures the grid was refined with, how well it fits them, and how far the timer moved (pixels). */
    var refinedWith = 0
        private set
    var fit = 0.0
        private set
    var reads = 0
        private set
    var farLooks = 0
        private set

    /** (4.0) How often a look reached past the part of the picture it was given, so the whole picture was read (debug report). */
    var wholeAgain = 0
        private set
    val decimals: Int get() = grid.decimals
    val grid: SegmentGrid get() = places.values.first()

    /** How far the timer moved across and down, as far as it was followed. */
    fun moved(): Pair<Double, Double> {
        val x = places.values.map { it.middleX }
        val y = places.values.map { it.middleY }
        return Pair(x.max() - x.min(), y.max() - y.min())
    }

    /** The box around its numbers, wherever they were, in frame pixels. */
    fun numbers(): Area {
        val boxes = places.values.map { it.box(it.decimals + 2) }
        return Area(max(0, boxes.minOf { it.left }), max(0, boxes.minOf { it.top }), boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    private fun frame(t: Double): GrayPic? {
        if (isCancelled()) throw ScanCancelled()
        return pic.at(t)
    }

    /** What the display shows at second [t]. */
    fun shown(t: Double): SegmentShown {
        last?.let { if (it.first == t) return it.second }
        return readAt(t).also { last = Pair(t, it) }
    }

    private fun readAt(t: Double): SegmentShown {
        val before = places.floorEntry(t)?.value
        val after = places.ceilingEntry(t)?.value
        val near = if (before != null) listOfNotNull(before, after) else listOf(after!!)
        // (4.0) only the part of the picture the looks below can reach (much quicker to make); if they reached further after all,
        // the whole picture is read, so the result is the same either way
        val reach = near.map { it.lookArea() }
        val part = pic.area(t, Area(max(0, reach.minOf { it.left }), max(0, reach.minOf { it.top }), reach.maxOf { it.right }, reach.maxOf { it.bottom }))
        if (isCancelled()) throw ScanCancelled()
        val window = part?.let { GrayPic(it.pic.w, it.pic.h, it.pic.d, it.area.left, it.area.top, it.frameW, it.frameH) }
        var look = window?.let { looked(t, it, near) }
        if (look == null || window?.outside == true) {
            if (window != null) wholeAgain++
            val g = frame(t) ?: return NOTHING
            look = looked(t, g, near)
        }
        reads++
        if (look.triedFar) lastFar = t
        if (look.lookedFar) farLooks++
        if (look.triedFar) inVain++
        if (look.counts) { places[t] = look.place; inVain = 0 }
        return look.shown
    }

    /** What one look at picture [g] found, before anything is remembered (a look in a window that was too small is thrown away). */
    private class Look(val place: SegmentGrid, val counts: Boolean, val shown: SegmentShown, val triedFar: Boolean, val lookedFar: Boolean)

    private fun looked(t: Double, g: GrayPic, near: List<SegmentGrid>): Look {
        var (place, fits) = near[0].located(g, 3)
        if (fits < LOOK_FURTHER) for (p in near.distinct()) {
            val (p2, f2) = p.located(g, 10)
            if (f2 > fits + BETTER) { place = p2; fits = f2 }
        }
        var triedFar = false
        var lookedFar = false
        if (fits < PLACE_FITS && abs(t - lastFar) >= FAR_STEP) {
            triedFar = true
            if (inVain < 4 || inVain % 4 == 0) {
                lookedFar = true
                for (p in near.distinct()) {
                    val (p2, f2) = p.foundAgain(g)
                    if (f2 > fits + BETTER) { place = p2; fits = f2 }
                }
            }
        }
        val cutOff = place.isCutOff(g.fw, g.fh)
        val counts = fits >= PLACE_FITS && !cutOff
        if (fits < READ_FITS || cutOff) return Look(place, counts, NOTHING, triedFar, lookedFar)
        val read = place.read(g)
        val text = segmentText(read, place.decimals)
        return Look(place, counts, SegmentShown(if ('?' in text) null else text, tenthsOf(read, place.decimals)), triedFar, lookedFar)
    }

    /** The rough track, and the one grid that fits all of it. */
    private fun track(found: FoundSegments, onProgress: (Int) -> Unit) {
        val good = TreeMap<Double, Pair<GrayPic, SegmentGrid>>()
        val steps = duration / TRACK_STEP
        var done = 0
        for (way in listOf(1, -1)) {
            var near = found.grid
            var t = if (way == 1) found.t else found.t - TRACK_STEP
            while (t in 0.0..duration) {
                val g = frame(t)
                if (g != null) {
                    var (place, fits) = near.located(g, 4)
                    if (fits < LOOK_FURTHER) {
                        val (p2, f2) = near.foundAgain(g)
                        if (f2 > fits + 0.03f) { place = p2; fits = f2 }
                    }
                    // lost: covered, or pushed further than that
                    if (fits < PLACE_FITS) {
                        farLooks++
                        val (p3, f3) = near.foundFarAway(g)
                        if (f3 >= GOOD_FIT) { place = p3; fits = f3 }
                    }
                    if (fits >= PLACE_FITS && !place.isCutOff(g.w, g.h)) { near = place; places[t] = place }
                    if (fits >= GOOD_FIT) good[t] = cut(g, place)
                }
                onProgress(min(99, (100 * ++done / steps).toInt()))
                t += way * TRACK_STEP
            }
        }
        if (places.isEmpty()) places[found.t] = found.grid
        if (good.size < REFINE_LEAST) return
        val all = good.values.toList()
        val n = min(REFINE_PICTURES, all.size)
        val (shape, q) = found.grid.refinedOver(List(n) { all[((2 * it + 1) * all.size) / (2 * n)] }, isCancelled)
        for (e in places.entries) e.setValue(shape.putAt(e.value))
        refinedWith = n
        fit = q
    }

    /** The part of a picture around the timer, and the grid in it. */
    private fun cut(g: GrayPic, place: SegmentGrid): Pair<GrayPic, SegmentGrid> {
        val b = place.box(place.decimals + 3)
        val room = (1.5 * place.height).toInt()
        val x = max(0, b.left - room)
        val y = max(0, b.top - room)
        return Pair(g.crop(x, y, min(g.w, b.right + room), min(g.h, b.bottom + room)), place.moved(-x.toDouble(), -y.toDouble()))
    }

    companion object {
        private val NOTHING = SegmentShown(null, null)

        /** Follows [found] through the video. Throws [ScanCancelled] when [isCancelled] says so. */
        fun follow(
            found: FoundSegments, pic: PicAt, durationSec: Double, onProgress: (Int) -> Unit = {}, isCancelled: () -> Boolean = { false }
        ): SegmentTracker = SegmentTracker(pic, durationSec, isCancelled).also { it.track(found, onProgress) }
    }
}

/** The solves of a seven-segment timer that was followed. */
fun scanSegments(
    timer: SegmentTracker, durationSec: Double, frameSec: Double, frameTime: (Double) -> Double = { it },
    onProgress: (Int, Int) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }, exact: (Boolean) -> Unit = {}
): ScanResult {
    val read = { t: Double -> timer.shown(t).text }
    return scanFoundTimer(read, durationSec, timer.decimals, timer.decimals, frameSec, frameTime, onProgress, isCancelled, { timer.shown(it).tenths }, exact)
}
