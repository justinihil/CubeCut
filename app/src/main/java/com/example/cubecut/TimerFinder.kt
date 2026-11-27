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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

// (3.3-C) Finds the timer in a video by itself: no corners, no typed number. Many things in a picture look like a number (averages, a
// session list, a window edge); only the timer RISES WITH THE CLOCK and then freezes. Pure logic: it only needs a picture at a given
// second, so the PC tests run it on the reference videos (ReferenceVideoTest).
//
//  1. SEARCH: look at places spread over the video for a number that rises by 0.5 every half second.
//  2. WATCH: follow a few of those runs to their end, to see where the number freezes and with how many decimals.
//  3. LEARN: look at the same runs again at 12 pictures a second. The tenths go through 0-9, and the clock says which one it is, so
//     the digits of THIS timer are learned without trusting any font.
//
// Where it was measured: docs/roadmap.md 3.3-C.

/** A run is this many pictures, this far apart, all rising with the clock. Fewer, and a blinking number could pass by chance. */
private const val RUN_PICTURES = 4
private const val RUN_STEP = 0.5

/** The search stops after this many runs of the same number; more would only cost time. */
private const val RUNS_WANTED = 3

/** ...and gives up after looking at this many places. A video without a readable timer pays for all of them. */
private const val MAX_PLACES = 160

/** The second search, at full size, is slower and only helps with tiny digits: it looks at fewer places. */
private const val MAX_PLACES_FULL_SIZE = 64

/** After this many places (and then this many at full size) without any running number, another kind of timer is tried before going on. */
private const val EARLY_PLACES = 40
private const val EARLY_PLACES_FULL_SIZE = 16

/** Two numbers run at the same moment: the smaller one could be the timer too when it is at least this tall compared with the bigger one... */
private const val RIVAL_HEIGHT = 0.6

/** ...and was seen running next to it at this many places. Then CubeCut asks instead of guessing. */
private const val RIVAL_PLACES = 2

/** Extra runs to learn from when the first ones don't show all ten digits. */
private const val MORE_RUNS = 8
private const val MORE_PLACES = 24

/** Learned digits are only used when every one of 0-9 was seen this often. Learning from a set without a 3 read every 3 as an 8. */
private const val MIN_SHAPES = 4

/** Pictures per second, and the longest stretch of a run, for learning. */
private const val LEARN_RATE = 12
private const val LEARN_SEC = 8.0

/** Follow a run at most this long to see it freeze. */
private const val FOLLOW_SEC = 90.0

/** The timer's area is its numbers plus this much around them, in digit heights: a stopped time is wider and may jump up. */
private const val MARGIN_ACROSS = 2.0
private const val MARGIN_DOWN = 1.2

/** Whole seconds are about as tall as the digits of a final time, or taller. Smaller ones are something else. */
private const val WHOLE_HEIGHT = 0.7

/** To see a run stop, look this far around it instead, in digit heights: some timers show the stopped time somewhere else for a moment. */
private const val WATCH_ACROSS = 5.0
private const val WATCH_DOWN = 2.5

/** A number this close to the edge of the timer's area, in digit heights, could go on outside it: it isn't read at all. */
private const val EDGE = 0.8

/** The whole picture is searched this many times smaller first; only if that finds nothing, at full size. Digits under 24 px need that. */
private const val SEARCH_SHRINK = 2

/** Digits are read at about this height: enough for 6 and 8 to differ, and four times fewer pixels than a 94 px digit has. */
private const val READ_HEIGHT = 45.0

/** Gives the picture closest to a second of the video, or null if there is none. */
fun interface PicAt {
    fun at(t: Double): GrayPic?

    /**
     * (4.0) Only [area] of the picture at second [t] (kept inside the picture), or null. The same pixels as cut from [at]; a source
     * that can make just that part (the video decoder) does, which is much quicker than the whole picture.
     */
    fun area(t: Double, area: Area): AreaPic? = at(t)?.let { AreaPic.of(it, area) }
}

/** A box in frame pixels ([right] and [bottom] are just outside). */
data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** (4.0) The part [area] of a [frameW] x [frameH] picture, as [pic]. [area] lies inside the picture. */
class AreaPic(val pic: GrayPic, val area: Area, val frameW: Int, val frameH: Int) {
    companion object {
        /** [area] of [frame], kept inside it, as before 4.0 (right and bottom cut to the picture). */
        fun of(frame: GrayPic, area: Area): AreaPic {
            val a = Area(area.left, area.top, min(frame.w, area.right), min(frame.h, area.bottom))
            return AreaPic(frame.crop(a.left, a.top, a.right, a.bottom), a, frame.w, frame.h)
        }
    }
}

/** A timer the finder is sure about: where it is, how big its digits are, and what they look like. */
class FoundTimer(
    val area: Area,                     // where it is read, with room around it
    val numbers: Area,                  // just the numbers themselves
    val digitHeight: Double,
    val dark: Boolean,                  // dark digits on a light background
    val runningDecimals: Int,
    val stoppedDecimals: Int,
    val examples: DigitShapes,          // learned from this video when possible, else the fonts
    val held: Boolean = false           // found by its final times (HeldFinder.kt): it shows no running time with decimals
) {
    /** What the timer shows in a whole frame, or null. */
    fun read(frame: GrayPic): TimeRow? = read(AreaPic.of(frame, area))

    /** (4.0) The same from just its area ([PicAt.area] with [area]). */
    fun read(p: AreaPic): TimeRow? = readArea(p, area, digitHeight, dark, examples)

    /** The same for a [held] timer: a final time, or whole seconds. */
    fun readHeld(frame: GrayPic): HeldShown = readHeld(AreaPic.of(frame, area))

    /** (4.0) The same from just its area. */
    fun readHeld(p: AreaPic): HeldShown {
        val rows = areaRows(p, digitHeight, dark, examples, MIN_LIKENESS, whole = true, shrink = max(1, (digitHeight / READ_HEIGHT).roundToInt())).filter { isInside(it, area, digitHeight, p.frameW) }
        val time = pickRow(rows.filter { '.' in it.text && it.decimals == stoppedDecimals })
        if (time != null) return HeldShown(time.text, null)
        val whole = pickRow(rows.filter { '.' !in it.text && it.height > WHOLE_HEIGHT * digitHeight })
        return HeldShown(null, whole?.let { shownSeconds(it.text).toInt() })
    }
}

/** What a timer without running decimals shows: a final time ([text]), [whole] seconds, or neither. */
class HeldShown(val text: String?, val whole: Int?)

/** One number that could be the timer: where it is in the picture at second [t]. */
class TimerChoice(val t: Double, val box: Area)

/** What the finder has to say. [timer] null = not found, or not sure: then [choices] are the numbers to choose from. [notes] explains, for the debug report. */
class FinderResult(
    val timer: FoundTimer?,
    val notes: List<String>,
    val places: Int,                    // how many places were looked at
    val frozen: List<String>,           // the stopped times it saw while watching
    val wholeFrames: Int,               // how many whole pictures were searched
    val areaReads: Int,                 // how many times only the timer's area was read
    val choices: List<TimerChoice> = emptyList()
)

private class Seen(val t: Double, val row: TimeRow)
private typealias Run = List<Seen>

private fun isInside(r: TimeRow, area: Area, height: Double, frameW: Int): Boolean =
    (area.left <= 0 || r.left - area.left >= EDGE * height) && (area.right >= frameW || area.right - r.right >= EDGE * height)

private fun readArea(frame: GrayPic, area: Area, height: Double, dark: Boolean, examples: DigitShapes): TimeRow? =
    readArea(AreaPic.of(frame, area), area, height, dark, examples)

/** [area] as asked for (its right edge may lie outside the picture, which the edge rule needs), [p] as cut from the picture. */
private fun readArea(p: AreaPic, area: Area, height: Double, dark: Boolean, examples: DigitShapes): TimeRow? =
    pickRow(areaRows(p, height, dark, examples, MIN_LIKENESS).filter { isInside(it, area, height, p.frameW) })

/** A timer's area: its numbers with room around them, inside a [w] x [h] frame. */
fun roomAround(rows: List<TimeRow>, height: Double, w: Int, h: Int): Area = roomAround(rows, height, MARGIN_ACROSS, MARGIN_DOWN, w, h)

private fun roomAround(rows: List<TimeRow>, height: Double, across: Double, down: Double, w: Int, h: Int) = Area(
    max(0, (rows.minOf { it.left } - across * height).toInt()), max(0, (rows.minOf { it.top } - down * height).toInt()),
    min(w, (rows.maxOf { it.right } + across * height).toInt()), min(h, (rows.maxOf { it.bottom } + down * height).toInt())
)

private fun areaRows(
    frame: GrayPic, area: Area, height: Double, dark: Boolean, examples: DigitShapes, minLikeness: Float, whole: Boolean = false,
    shrink: Int = max(1, (height / READ_HEIGHT).toInt())
): List<TimeRow> = areaRows(AreaPic.of(frame, area), height, dark, examples, minLikeness, whole, shrink)

private fun areaRows(
    p: AreaPic, height: Double, dark: Boolean, examples: DigitShapes, minLikeness: Float, whole: Boolean = false,
    shrink: Int = max(1, (height / READ_HEIGHT).toInt())
): List<TimeRow> =
    findTimeRows(
        p.pic, examples, listOf(dark),
        minHeight = 0.55 * height, maxHeight = 1.6 * height, minLikeness = minLikeness, offX = p.area.left, offY = p.area.top,
        shrink = shrink, whole = whole
    )

private fun median(v: List<Double>): Double {
    val s = v.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
}

/** The most common value (the first seen wins a tie), or null. */
private fun <T> mostCommon(v: List<T>): T? = v.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

/** [tenths] of a second as a timer shows them: 594 = "59.4", 604 = "1:00.4". */
private fun tenthsShown(tenths: Int): String {
    val s = tenths / 10
    return (if (s < 60) "$s" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}") + ".${tenths % 10}"
}

/** Is [b] the number [a], [dt] seconds later, still running? */
private fun rises(a: TimeRow, b: TimeRow, dt: Double): Boolean {
    val lastPlace = Math.pow(10.0, -a.decimals.toDouble())
    val longer = if (a.text.length != b.text.length) a.height else 0.0          // 9.9 -> 10.0 makes the number wider
    return a.dark == b.dark && a.decimals == b.decimals &&
            abs(b.seconds - a.seconds - dt) <= 0.051 + lastPlace &&
            b.height / a.height > 0.8 && b.height / a.height < 1.25 &&
            abs(a.right - b.right) < 1.2 * a.height + longer &&
            abs((a.top + a.bottom) / 2.0 - (b.top + b.bottom) / 2.0) < 0.6 * a.height
}

/**
 * Looks for the timer of a video of [durationSec] seconds. [near]: the number the user chose, only numbers there count (null = any).
 * [otherKind] is asked once, early, when no number was seen running yet: it looks for another kind of timer, and true ends this search.
 * [onProgress] gets 0..100. Throws [ScanCancelled] when [isCancelled] says so.
 */
fun findTimer(
    pic: PicAt, durationSec: Double, fonts: DigitShapes, near: Area? = null, onProgress: (Int) -> Unit = {}, isCancelled: () -> Boolean = { false },
    otherKind: (() -> Boolean)? = null
): FinderResult = TimerSearch(pic, durationSec, fonts, near, onProgress, isCancelled, otherKind).run()

private class TimerSearch(
    private val pic: PicAt, private val duration: Double, private val fonts: DigitShapes, private val near: Area?,
    private val onProgress: (Int) -> Unit, private val isCancelled: () -> Boolean, private val otherKind: (() -> Boolean)?
) {
    private val notes = ArrayList<String>()
    private val frozen = ArrayList<String>()
    private var places = 0
    private var wholeFrames = 0
    private var areaReads = 0
    private var choices: List<TimerChoice> = emptyList()

    private var area = Area(0, 0, 1, 1)
    private var height = 0.0
    private var dark = false
    private var frameW = 0
    private var frameH = 0
    private var searchShrink = SEARCH_SHRINK
    private val sightings = ArrayList<Sighting>()       // every number seen, for a timer that doesn't show its running time

    private fun frame(t: Double): GrayPic? {
        if (isCancelled()) throw ScanCancelled()
        val p = pic.at(t) ?: return null
        frameW = p.w
        frameH = p.h
        return p
    }

    private fun result(timer: FoundTimer?) = FinderResult(timer, notes, places, frozen, wholeFrames, areaReads, if (timer == null) choices else emptyList())

    fun run(): FinderResult {
        val half = Search(SEARCH_SHRINK, MAX_PLACES)
        val full = Search(1, MAX_PLACES_FULL_SIZE)
        var hits: List<Run> = emptyList()
        if (otherKind != null) {
            hits = search(half, EARLY_PLACES)
            if (half.runs.isEmpty()) hits = search(full, EARLY_PLACES_FULL_SIZE)
            if (half.runs.isEmpty() && full.runs.isEmpty()) {
                heldTimer()?.let { return result(it) }
                if (otherKind.invoke()) return result(null)
            }
        }
        if (hits.isEmpty() && choices.isEmpty()) hits = search(half)
        if (hits.isEmpty() && choices.isEmpty()) hits = search(full)
        if (hits.isEmpty()) return result(if (choices.isEmpty()) heldTimer() else null)
        val rows = hits.flatMap { h -> h.map { it.row } }
        dark = mostCommon(rows.map { it.dark })!!
        height = median(rows.map { it.height })
        setArea(rows)
        return result(learn(hits))
    }

    /** A timer without running decimals, by its final times. Not when the user chose a number: that one was seen running. */
    private fun heldTimer(): FoundTimer? = if (near != null) null else findHeldTimer(sightings, { frame(it) }, fonts, notes)

    // ---- 1. search ----

    /** The numbers that rise with the clock from second [t] on. */
    private fun runsAt(t: Double): List<Run> {
        val seen = ArrayList<List<TimeRow>>()
        for (k in 0 until RUN_PICTURES) {
            val p = frame(t + k * RUN_STEP) ?: return emptyList()
            wholeFrames++
            val rows = findTimeRows(p, fonts, listOf(false, true), shrink = searchShrink)
            if (rows.isEmpty()) return emptyList()
            sightings.addAll(sightingsOf(t + k * RUN_STEP, rows))
            seen.add(rows)
        }
        val out = ArrayList<Run>()
        for (first in seen[0]) {
            val run = arrayListOf(Seen(t, first))
            for (k in 1 until RUN_PICTURES) {
                val next = seen[k].filter { it.block == first.block && rises(run.last().row, it, RUN_STEP) && rises(first, it, k * RUN_STEP) }
                    .maxByOrNull { it.likeness } ?: break
                run.add(Seen(t + k * RUN_STEP, next))
            }
            if (run.size == RUN_PICTURES) out.add(run)
        }
        return out
    }

    private fun worst(run: Run): Float = run.minOf { it.row.likeness }

    /** A search through the video that can stop and go on: the pictures' size, the places (spread over the video, halving the gaps), and what it saw. */
    private inner class Search(val shrink: Int, most: Int) {
        val order: List<Double>
        var next = 0
        var places = 0
        val runs = ArrayList<Run>()

        init {
            val all = ArrayList<Double>()
            var q = 2
            while (all.size < most) {
                for (i in 0 until q) all.add(round(duration * (2 * i + 1) / (2 * q) * 2) / 2)
                q *= 2
            }
            order = all.take(most).distinct()
        }
    }

    /**
     * Goes on with [s] until enough runs of the same number are seen, or [upTo] places were looked at. The biggest running number is the
     * timer.
     */
    private fun search(s: Search, upTo: Int = Int.MAX_VALUE): List<Run> {
        searchShrink = s.shrink
        val hits = s.runs
        if (s.shrink == 1) notes.add("At full size:")
        while (s.next < min(upTo, s.order.size)) {
            val t = s.order[s.next++]
            if (t + (RUN_PICTURES - 1) * RUN_STEP + 0.5 > duration) continue
            places = ++s.places
            onProgress(min(45, places))
            hits.addAll(runsAt(t).filter { isNear(it) })
            if (hits.size < RUNS_WANTED) continue
            val biggest = hits.maxOf { it[0].row.height }
            val best = LinkedHashMap<Double, Run>()             // one per place
            for (h in hits) if (h[0].row.height > 0.8 * biggest) {
                val b = best[h[0].t]
                if (b == null || worst(h) > worst(b)) best[h[0].t] = h
            }
            if (best.size >= RUNS_WANTED) {
                choices = rivals(hits)
                if (choices.isNotEmpty()) {
                    notes.add("Search: $places places, two numbers run at the same time: which one is the timer?")
                    return emptyList()
                }
                val others = hits.filter { it[0].row.height <= 0.8 * biggest }
                    .map { "${it[0].row.text} (${it[0].row.height.toInt()} px)" }.distinct()
                notes.add("Search: $places places, ${best.size} runs of the timer" + if (others.isEmpty()) "" else "; smaller running numbers: $others")
                return best.values.toList()
            }
        }
        notes.add("Search: ${s.places} places, ${hits.size} runs: no timer. " + hits.take(5).map { h -> h.map { it.row.text } })
        return emptyList()
    }

    private fun boxOf(r: TimeRow) = Area(r.left, r.top, r.right, r.bottom)

    /** Is it the number the user chose (or was none chosen)? */
    private fun isNear(run: Run): Boolean = near == null || touches(boxOf(run[0].row), near, run[0].row.height / 2)

    /** Do the boxes overlap, or nearly (within [room])? */
    private fun touches(a: Area, b: Area, room: Double): Boolean =
        a.left - room < b.right && a.right + room > b.left && a.top - room < b.bottom && a.bottom + room > b.top

    /** The two numbers to choose from, when a second one of about the same size ran next to the biggest at several places. */
    private fun rivals(hits: List<Run>): List<TimerChoice> {
        if (near != null) return emptyList()
        var found: List<TimerChoice> = emptyList()
        var count = 0
        for ((t, runs) in hits.groupBy { it[0].t }) {
            val big = runs.maxByOrNull { it[0].row.height }!![0].row
            val other = runs.map { it[0].row }
                .filter { it.height >= RIVAL_HEIGHT * big.height && !touches(boxOf(it), boxOf(big), big.height / 2) }
                .maxByOrNull { it.height } ?: continue
            count++
            found = listOf(TimerChoice(t, boxOf(big)), TimerChoice(t, boxOf(other)))
        }
        return if (count >= RIVAL_PLACES) found else emptyList()
    }

    private fun setArea(rows: List<TimeRow>) {
        area = around(rows, MARGIN_ACROSS, MARGIN_DOWN)
    }

    private fun around(rows: List<TimeRow>, across: Double, down: Double) = roomAround(rows, height, across, down, frameW, frameH)

    /** What [run]'s number shows at second [t], also when it isn't quite at its place any more. */
    private fun readAround(run: Run, t: Double, examples: DigitShapes): TimeRow? {
        val p = frame(t) ?: return null
        areaReads++
        return readArea(p, around(run.map { it.row }, WATCH_ACROSS, WATCH_DOWN), height, dark, examples)
    }

    /** More runs of the same number, at other places, one at a time. */
    private fun moreRuns(have: List<Run>): Sequence<Run> = sequence {
        val done = have.map { it[0].t }.toMutableList()
        for (i in 0 until MORE_PLACES) {
            val t = round(duration * (i + 0.37) / MORE_PLACES * 2) / 2
            if (t + (RUN_PICTURES - 1) * RUN_STEP + 0.5 > duration || done.any { abs(t - it) < 15 }) continue
            val runs = runsAt(t).filter { isNear(it) }
            val tallest = runs.maxOfOrNull { it[0].row.height } ?: continue
            if (abs(tallest / have[0][0].row.height - 1) >= 0.3) continue
            done.add(t)
            yield(runs.filter { it[0].row.height > 0.8 * tallest }.maxByOrNull { worst(it) }!!)
        }
    }

    // ---- 2. watch ----

    private fun startOf(run: Run): Double = median(run.map { it.t - it.row.seconds })

    /** Follows [run] to where the number freezes. Gives everything read while running, and the frozen reads (empty = none seen). */
    private fun watch(run: Run): Pair<List<Seen>, List<Seen>> {
        val start = startOf(run)
        val last = run.last().t
        val running = ArrayList(run)
        var held = ArrayList<Seen>()
        var miss = 0
        var t = last + RUN_STEP
        while (t < duration && miss < 4 && held.size < 3 && t - last < FOLLOW_SEC) {
            val r = readAround(run, t, fonts)
            when {
                r == null -> miss++
                r.decimals == run[0].row.decimals && abs(t - start - r.seconds) < 0.16 -> { running.add(Seen(t, r)); miss = 0; held = ArrayList() }
                held.isNotEmpty() && held.last().row.text == r.text -> held.add(Seen(t, r))
                r.seconds <= t - start + 0.2 -> { held = arrayListOf(Seen(t, r)); miss++ }
                else -> miss++
            }
            t += RUN_STEP
        }
        return Pair(running, if (held.size >= 3) held else emptyList())
    }

    /** The digits are known now: follow the runs to their end again, for timers whose first watch lost them. */
    private fun watchAgain(hits: List<Run>, learned: DigitShapes): List<List<Seen>> {
        val out = ArrayList<List<Seen>>()
        val runs = (hits.asSequence() + moreRuns(hits)).iterator()
        while (out.size < RUNS_WANTED && runs.hasNext()) {          // (hasNext may have to search: only ask when one is needed)
            val run = runs.next()
            val start = startOf(run)
            val last = run.last()
            var held = ArrayList<Seen>()
            var t = last.t + RUN_STEP
            while (t < min(duration, last.t + 60) && held.size < 3) {
                val r = readAround(run, t, learned)
                if (r != null) {
                    if (abs(t - start - r.seconds) < 0.2 && r.decimals == run[0].row.decimals) held = ArrayList()
                    else if (r.seconds >= last.row.seconds) { if (held.isNotEmpty() && held.last().row.text != r.text) held = ArrayList(); held.add(Seen(t, r)) }
                }
                t += RUN_STEP
            }
            if (held.size == 3) out.add(held)
        }
        return out
    }

    // ---- 3. learn ----

    private fun enough(shapes: List<List<FloatArray>>) = shapes.all { it.size >= MIN_SHAPES }

    private fun learn(hits: List<Run>): FoundTimer? {
        val shapes = List(10) { ArrayList<FloatArray>() }
        val rows = ArrayList<TimeRow>()
        val stopped = ArrayList<Int>()
        var used = 0
        val runs = (hits.asSequence() + moreRuns(hits)).iterator()
        while (used < hits.size || (used < hits.size + MORE_RUNS && !enough(shapes))) {
            if (!runs.hasNext()) break                              // (may have to search: only ask when one is needed)
            val run = runs.next()
            used++
            onProgress(min(99, 45 + 50 * used / (hits.size + 1)))
            val (running, held) = watch(run)
            rows.addAll(running.map { it.row })
            if (held.isNotEmpty()) {
                rows.addAll(held.map { it.row })
                stopped.add(held[0].row.decimals)
                frozen.add(held[0].row.text)
            }
            learnFrom(run, running, shapes)
        }
        val all = shapes.sumOf { it.size }
        if (rows.isNotEmpty()) setArea(rows)
        val clean = if (enough(shapes)) withoutOddOnes(shapes) else null
        val learned = clean?.let { fonts.learned(it) }
        notes.add(
            "Learned: " + (if (learned != null) "yes" else "NO, not all digits were seen") + ", from $used runs, " +
                    "${clean?.sumOf { it.size } ?: 0} of $all shapes, per digit " + (clean ?: shapes).map { it.size }
        )
        if (stopped.isEmpty() && learned != null) {
            for (held in watchAgain(hits, learned)) {
                rows.addAll(held.map { it.row })
                stopped.add(held[0].row.decimals)
                frozen.add(held[0].row.text)
            }
            setArea(rows)
        }
        val stoppedDecimals = mostCommon(stopped)
        if (stoppedDecimals == null) {
            notes.add("A number runs, but it was never seen to stop")
            return null
        }
        val numbers = Area(rows.minOf { it.left }, rows.minOf { it.top }, rows.maxOf { it.right }, rows.maxOf { it.bottom })
        return FoundTimer(area, numbers, height, dark, mostCommon(hits.map { it[0].row.decimals })!!, stoppedDecimals, learned ?: fonts)
    }

    /** One run again at [LEARN_RATE] pictures a second: adds its digits' shapes to [into], named by the clock. */
    private fun learnFrom(run: Run, running: List<Seen>, into: List<MutableList<FloatArray>>) {
        val start = startOf(run)
        val decimals = run[0].row.decimals
        val seen = ArrayList<Seen>()
        val from = running.first().t
        val to = min(running.last().t + 2.0, from + LEARN_SEC)
        for (k in 0..((to - from) * LEARN_RATE).toInt()) {
            val t = from + k.toDouble() / LEARN_RATE
            val p = frame(t) ?: continue
            areaReads++
            for (r in areaRows(p, area, height, dark, fonts, 0.4f)) if (r.decimals == decimals) seen.add(Seen(t, r))
        }
        if (seen.count { abs(it.t - it.row.seconds - start) < 0.16 } < 10) return

        // The exact start. First its phase: the one that makes the tenths' shapes most alike within each label (nothing has to be read
        // right for that). Then which whole tenth: the shift that agrees best with the readings, and clearly better than the next best.
        val n = seen.size
        val tenth = seen.map { it.row.shapes[it.row.text.substringBefore('.').count { c -> c.isDigit() }] }
        val phase = (0 until 20).map { it * 0.005 }.maxByOrNull { x ->
            val sums = Array(10) { FloatArray(tenth[0].size) }
            val counts = IntArray(10)
            for (i in 0 until n) {
                val d = floor((seen[i].t - x) * 10).toInt().mod(10)
                counts[d]++
                for (j in tenth[i].indices) sums[d][j] += tenth[i][j]
            }
            (0..9).sumOf { d -> if (counts[d] > 1) sqrt(sums[d].sumOf { (it * it).toDouble() }) else 0.0 }
        }!!
        val base = phase + 0.1 * round((start - phase) / 0.1)
        val shown = seen.map { floor(it.row.seconds * 10 + 1e-6).toInt() }
        val agree = (-2..2).associateWith { k -> (0 until n).count { floor((seen[it].t - base - 0.1 * k) * 10 + 1e-6).toInt() == shown[it] }.toDouble() / n }
        val best = agree.entries.sortedByDescending { it.value }
        val sure = best[0].value >= 0.3 && best[0].value > 1.5 * best[1].value
        notes.add("Run at ${videoPlace(from)}: the clock agrees with ${(best[0].value * 100).toInt()}% of $n readings" + if (sure) "" else ", NOT used")
        if (!sure) return
        val zero = base + 0.1 * best[0].key
        for (s in seen) {
            val tenths = (s.t - zero) * 10
            val part = tenths - floor(tenths)
            if (tenths < 0 || part <= 0.3 || part >= 0.8) continue              // near a change two numbers are blended
            val whole = floor(tenths).toInt()
            val want = tenthsShown(whole)
            val have = s.row.text.take(s.row.text.indexOf('.') + 2)
            if (have.length != want.length) continue
            if (have.dropLast(2) != want.dropLast(2) && have.indices.count { have[it] != want[it] } > 1) continue
            want.filter { it.isDigit() }.forEachIndexed { i, c -> into[c - '0'].add(s.row.shapes[i]) }
        }
    }

    /** Leaves out every shape that looks more like another digit's typical shape: it was named at the wrong moment. */
    private fun withoutOddOnes(shapes: List<List<FloatArray>>): List<List<FloatArray>> {
        var now = shapes
        repeat(2) {
            val typical = now.map { s ->
                val size = s[0].size
                val m = FloatArray(size)
                val column = FloatArray(s.size)
                for (j in 0 until size) {
                    for (i in s.indices) column[i] = s[i][j]
                    column.sort()
                    m[j] = if (s.size % 2 == 1) column[s.size / 2] else (column[s.size / 2 - 1] + column[s.size / 2]) / 2
                }
                val norm = sqrt(m.sumOf { (it * it).toDouble() }).toFloat()
                for (j in m.indices) m[j] /= norm
                m
            }
            val kept = now.mapIndexed { d, s -> s.filter { x -> typical.indices.maxByOrNull { likeness(x, typical[it]) } == d } }
            if (!enough(kept)) return now
            now = kept
        }
        return now
    }
}
