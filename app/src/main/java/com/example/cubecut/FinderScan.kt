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
import kotlin.math.max
import kotlin.math.min

// (3.3-C) Finds the solves of a timer the finder found (TimerFinder.kt). The same idea as SolveAnalyzer.scan, with its constants: a time
// that stays is a finished solve, and the first frame that shows it is the cut. What is different, because this timer is read with its
// decimal point and while it runs:
//  - a text with fewer decimals than a stopped time is a RUNNING time, and is kept: it gives the moment the timer started;
//  - START TO STOP must be the time on the display, which catches misreads and tells where the stop was when it was hidden;
//  - the cut is searched frame by frame: phones blend two numbers into one picture, which misleads a search that halves the gap;
//  - a time that shows again without having run in between is the same hold (the timer was covered for a while).
// SolveAnalyzer.scan is left alone: timers set up by hand are scanned exactly as before.

/** A time is confirmed by the clock when start to stop differs from it by no more than this... */
private const val CLOCK_SLACK = 0.2

/** ...and at least this many running readings agree on the start. */
private const val CLOCK_READS = 2

/** When the first frame was hidden, the stop is taken from the clock, if this many running readings agree... */
private const val HIDDEN_READS = 4

/** ...and the time was first seen less than this late. */
private const val HIDDEN_MAX_SEC = 15.0

/** The first frame must show the time again within this many seconds: one odd reading isn't the stop. */
private const val FIRST_AGAIN_SEC = 0.12

/** Note on a solve whose running time was not seen, or doesn't fit. Kept in the list, to be checked. */
const val CLOCK_NOTE = "the running time doesn't confirm this time - check it"

/** Note on a cut that was worked out from the running time. */
const val HIDDEN_STOP_NOTE = "the timer was hidden when it stopped, the cut is worked out - check it"

private class TimeAt(val t: Double, val text: String) {
    val seconds: Double = shownSeconds(text)
}

private class Stop(val text: String, var sec: Double, val votes: Int, val reads: Int, val certain: Boolean) {
    val seconds: Double = shownSeconds(text)
    var confirmed: Boolean? = null      // null = no running reading to go by
    var agree = 0                       // how many running readings agree on the start
    var gap = 0.0                       // start to stop, minus the time
    var workedOut = false               // the cut comes from the clock
}

private fun decimalsOf(text: String): Int = text.length - 1 - text.indexOf('.')

private fun middle(v: List<Double>): Double {
    val s = v.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/**
 * [read] gives what the timer shows at a second ("12.69", "9.4" while running), or null. [frameSec] is the length of one frame.
 * [frameTime] gives the real time of the frame [read] looked at last, which can be half a frame off the second asked for.
 * [onProgress] gets (percent, 100). Throws [ScanCancelled] when [isCancelled] says so.
 */
fun scanFoundTimer(
    read: (Double) -> String?, durationSec: Double, runningDecimals: Int, stoppedDecimals: Int, frameSec: Double,
    frameTime: (Double) -> Double = { it }, onProgress: (Int, Int) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }
): ScanResult = FinderScan(read, durationSec, runningDecimals, stoppedDecimals, frameSec, frameTime, onProgress, isCancelled).run()

private class FinderScan(
    private val readAt: (Double) -> String?, private val duration: Double, private val runningDecimals: Int, private val stoppedDecimals: Int,
    private val frameSec: Double, private val frameTime: (Double) -> Double, private val onProgress: (Int, Int) -> Unit, private val isCancelled: () -> Boolean
) {
    private val stops = ArrayList<Stop>()
    private val running = ArrayList<TimeAt>()
    private val memo = HashMap<Long, String?>()
    private var total = 0
    private var readable = 0

    private fun read(t: Double): String? {
        if (isCancelled()) throw ScanCancelled()
        val key = Math.round(t * 1000)
        if (memo.containsKey(key)) return memo[key]
        return readAt(t).also { memo[key] = it }
    }

    /** A read that counts for how readable the video is. */
    private fun counted(t: Double): String? {
        val v = read(t)
        total++
        if (v != null) readable++
        return v
    }

    private fun isStopped(text: String) = decimalsOf(text) == stoppedDecimals
    private fun isZero(text: String) = shownSeconds(text) == 0.0

    fun run(): ScanResult {
        findStops()
        checkClock()
        val solves = sameHoldsMerged()
        workOutHiddenStops(solves)
        onProgress(100, 100)
        return ScanResult(solves.map { found(it) }.sortedBy { it.stopSec }, if (total > 0) readable.toDouble() / total else 0.0)
    }

    private fun found(s: Stop): FoundSolve {
        val leftover = !s.certain && s.sec < 1.0
        val note = when {
            leftover -> "${SolveAnalyzer.LEFTOVER_NOTE_START} when the video began - probably left over from an earlier solve"
            s.workedOut -> HIDDEN_STOP_NOTE
            s.confirmed != true -> CLOCK_NOTE
            s.votes < s.reads * 0.6 -> "time not steady (read on ${s.votes} of ${s.reads} frames) - check it"
            else -> ""
        }
        return FoundSolve(max(0.0, s.sec), s.text, note, !leftover)
    }

    /** Reads the display all along the video and collects the times that stay, and every running reading. */
    private fun findStops() {
        var t = 0.0
        var confirmed: String? = null                   // the time of the solve just found, while the display still shows it
        var recent = ArrayList<TimeAt>()
        var lastPercent = -1
        while (t <= duration) {
            val percent = if (duration > 0) (t / duration * 100).toInt().coerceIn(0, 100) else 0
            if (percent != lastPercent) { lastPercent = percent; onProgress(percent, 100) }
            val v = counted(t)
            if (v == null) { t += SolveAnalyzer.SCAN_STEP; continue }
            if (isZero(v)) { confirmed = null; recent.clear(); t += SolveAnalyzer.ZERO_STEP; continue }
            if (stoppedDecimals != runningDecimals && !isStopped(v)) {
                running.add(TimeAt(t, v))
                confirmed = null
                recent.clear()
                t += SolveAnalyzer.SCAN_STEP
                continue
            }
            if (v == confirmed) { t += SolveAnalyzer.HOLD_STEP; continue }
            confirmed = null
            recent.add(TimeAt(t, v))
            if (recent.size > SolveAnalyzer.RECENT_N) recent.removeAt(0)
            val held = recent.takeLastWhile { it.text == v && it.t >= t - SolveAnalyzer.AGREE_WINDOW_SEC }
            if (held.size < SolveAnalyzer.MIN_AGREE) {
                if (stoppedDecimals == runningDecimals) running.add(TimeAt(t, v))
                t += SolveAnalyzer.SCAN_STEP
                continue
            }
            // ---- a stopped timer: read on and vote on the exact digits ----
            val votes = ArrayList(held)
            var lt = t + SolveAnalyzer.SCAN_STEP
            while (lt <= min(duration, t + SolveAnalyzer.LOCK_SEC) + 1e-9) {
                val lv = counted(lt)
                if (lv != null && !isZero(lv) && isStopped(lv)) votes.add(TimeAt(lt, lv))
                lt += SolveAnalyzer.SCAN_STEP
            }
            val counts = votes.groupingBy { it.text }.eachCount()
            var time = if (counts[v] == counts.values.max()) v else counts.maxByOrNull { it.value }!!.key
            // the clock helps to choose: the time can't be far from start to now
            val before = running.filter { it.t > t - 12 && it.t < t }
            if (before.size >= 4) {
                val start = middle(before.map { it.t - it.seconds })
                val fits = counts.keys.filter { abs(votes[0].t - start - shownSeconds(it)) <= 0.75 }
                if (fits.isNotEmpty() && time !in fits) time = fits.maxByOrNull { counts[it]!! }!!
            }
            val first = votes.first { it.text == time }.t
            val (sec, certain) = firstFrame(time, first, max(0.0, first - SolveAnalyzer.BACK_MAX_SEC))
            stops.add(Stop(time, sec, counts[time]!!, votes.size, certain))
            confirmed = time
            recent = ArrayList()
            t += SolveAnalyzer.LOCK_SEC + SolveAnalyzer.SCAN_STEP
        }
    }

    /**
     * The first frame that shows [time], searched back from [from], not before [lowest]: half-second steps to the nearest picture that
     * shows something else, then every frame of that gap. True if something else was seen before it, so the start really is there.
     */
    private fun firstFrame(time: String, from: Double, lowest: Double): Pair<Double, Boolean> {
        var earliest = from
        var other = Double.NaN                  // the nearest earlier picture that shows something else
        var others = 0
        var t = from - SolveAnalyzer.COARSE_BACK_STEP
        while (t > lowest - 1e-6 + 1e-9) {
            val v = read(t)
            if (v == time) { earliest = t; others = 0; other = Double.NaN }
            else if (v != null) {
                others++
                if (others == 1) other = t
                if (others >= 2) break
            }
            t -= SolveAnalyzer.COARSE_BACK_STEP
        }
        val gapFrom = if (!other.isNaN()) other else max(lowest, earliest - 0.6)
        val frames = ArrayList<TimeAt>()
        val n = ((earliest - gapFrom) / frameSec).toInt() + 3
        for (k in -1..n) {
            val ft = gapFrom + k * frameSec
            if (ft < 0 || ft > earliest + 2 * frameSec) continue
            if (isCancelled()) throw ScanCancelled()
            readAt(ft)?.let { frames.add(TimeAt(frameTime(ft), it)) }
        }
        val shows = frames.filter { it.text == time }
        if (shows.isEmpty()) return Pair(earliest, !other.isNaN())
        val first = shows.firstOrNull { a -> shows.count { it.t >= a.t && it.t <= a.t + FIRST_AGAIN_SEC } >= 2 } ?: shows[0]
        return Pair(first.t, !other.isNaN() || frames.any { it.text != time && it.t < earliest })
    }

    /** Start to stop: the running readings before a stop give the start, and stop minus start must be the time. */
    private fun checkClock() {
        var previous = -1e9
        for (s in stops) {
            val starts = running.filter { it.t > max(previous, s.sec - s.seconds - 1) && it.t <= s.sec && it.seconds <= s.seconds + 0.15 }
                .map { it.t - it.seconds }
            if (starts.isNotEmpty()) {
                val rough = middle(starts)
                val near = starts.filter { abs(it - rough) < 0.2 }.ifEmpty { starts }
                s.agree = near.size
                s.gap = s.sec - middle(near) - s.seconds
                s.confirmed = abs(s.gap) <= CLOCK_SLACK && near.size >= CLOCK_READS
            }
            previous = s.sec
        }
    }

    /** Leaves out a time that shows again without having run in between: the timer was only hidden for a while, or misread. */
    private fun sameHoldsMerged(): List<Stop> {
        val out = ArrayList<Stop>()
        for (s in stops) {
            val p = out.lastOrNull()
            if (p != null) {
                val ran = running.count { it.t > p.sec && it.t < s.sec }
                val same = s.text == p.text
                val alike = s.text.length == p.text.length && s.text.indices.count { s.text[it] != p.text[it] } <= 1
                if (same && (ran < 2 || s.confirmed != true)) continue
                if (alike && ran < 2 && s.confirmed != true) continue
            }
            out.add(s)
        }
        return out
    }

    /** A time first seen too late for the clock: the stop itself was hidden. The clock says when it was. */
    private fun workOutHiddenStops(solves: List<Stop>) {
        val usual = solves.filter { it.confirmed == true }.map { it.gap }       // the display's own delay, the same for every solve
        val bias = if (usual.isEmpty()) 0.0 else middle(usual)
        for (s in solves) {
            if (s.confirmed == false && s.agree >= HIDDEN_READS && s.gap > CLOCK_SLACK && s.gap < HIDDEN_MAX_SEC) {
                s.sec -= s.gap - bias
                s.workedOut = true
            }
        }
    }
}
