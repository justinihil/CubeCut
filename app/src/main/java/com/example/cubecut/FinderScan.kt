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

/** One solve found by a scan. */
class FoundSolve(
    val stopSec: Double,        // the FIRST frame on which the final time is showing = the moment the timer stopped
    val timeText: String,       // the same, written nicely, e.g. "12.412"
    val note: String,           // "" = looks fine, otherwise why it needs a look
    val include: Boolean        // false = listed but switched off (needs a look first)
)

/** Everything a scan learned. */
class ScanResult(
    val solves: List<FoundSolve>,
    val readableFraction: Double,           // 0..1: how many of the sampled frames had a readable timer
    val trace: ScanTrace = ScanTrace()      // (3.3-C) what was seen along the way, to tell where a solve may be missing
)

/** Thrown inside a scan when the user pressed Cancel. */
class ScanCancelled : Exception("cancelled")

/** Start of the note on a cut whose time was already showing when the video began. Such a cut is switched off and ignored like a deleted one. */
const val LEFTOVER_NOTE_START = "this time was already on the timer"

// (4.0-0) The scan's steps. They come from the scan of a timer set up by hand (SolveAnalyzer, removed in 4.0-0), which this scan was
// built on. Shorter steps find a stop sooner but read more pictures.
/** Seconds between reads while looking for a stop. */
private const val SCAN_STEP = 0.5
/** A wider step while the display shows 0.000: a stop is at least ~3 s away, and a running time is seen on the next read. */
private const val ZERO_STEP = 2.0
/** A wider step while the display still shows a time already found. */
private const val HOLD_STEP = 2.0
/** The same time on this many reads is a stopped timer... */
private const val MIN_AGREE = 2
/** ...looking at the last this many readable reads... */
private const val RECENT_N = 6
/** ...which must lie within this many seconds of each other. */
private const val AGREE_WINDOW_SEC = 6.0
/** After a stop is spotted, keep reading this long to vote on the exact digits. */
private const val LOCK_SEC = 3.0
/** The first step back when looking for the first frame. */
private const val COARSE_BACK_STEP = 0.5
/** Look back at most this far for the first frame. */
private const val BACK_MAX_SEC = 4.0

/** Digits as the display shows them, written as a time: "12412" with 3 decimals is "12.412", "102345" is "1:02.345". */
fun formatDigits(digits: String, decimals: Int): String {
    if (decimals <= 0 || digits.length <= decimals) return digits
    val frac = digits.takeLast(decimals)
    val rest = digits.dropLast(decimals)
    val sec = rest.takeLast(2)
    val mins = rest.dropLast(2)
    return if (mins.isNotEmpty()) "$mins:${sec.padStart(2, '0')}.$frac" else "${sec.toInt()}.$frac"
}

// (3.3-C) Finds the solves of a timer the finder found (TimerFinder.kt). The same idea as the old scan of a timer set up by hand: a time
// that stays is a finished solve, and the first frame that shows it is the cut. What is different, because this timer is read with its
// decimal point and while it runs:
//  - a text with fewer decimals than a stopped time is a RUNNING time, and is kept: it gives the moment the timer started;
//  - START TO STOP must be the time on the display, which catches misreads and tells where the stop was when it was hidden;
//  - the cut is searched frame by frame: phones blend two numbers into one picture, which misleads a search that halves the gap;
//  - a time that shows again without having run in between is the same hold (the timer was covered for a while).

/** A time is confirmed by the clock when start to stop differs from it by no more than this... */
private const val CLOCK_SLACK = 0.2

/** ...and at least this many running readings agree on the start. */
private const val CLOCK_READS = 2

/** When the first frame was hidden, the stop is taken from the clock, if this many running readings agree... */
private const val HIDDEN_READS = 4

/** ...and the time was first seen less than this late. */
private const val HIDDEN_MAX_SEC = 15.0

/** A time that seemed to stay, but then goes on rising with the clock in this many readings, was running (two blended frames read alike). */
private const val STILL_RUNNING_READS = 3

/** The same for a display whose last digits blur: so close to the clock. */
private const val BLURRED_RISE_SEC = 0.3

/**
 * (4.0) A stopped time can't show before start + time: the vote's clock doesn't choose one first seen more than this too early (the
 * start, from running times shown to the tenth, can be up to 0.1 s late). A QiYi's digits ghost while they change and read as 8s: two
 * steps of one solve once both read "12.898" while it ran (12.2 and 12.7), 0.7 s before 12.898 could have been the time.
 */
private const val TOO_EARLY_SEC = 0.3

/** It wasn't hidden when the timer was seen running, as the clock says, this short before the first frame. */
private const val SEEN_RUNNING_SEC = 0.5

/** The first frame must show the time again within this many seconds: one odd reading isn't the stop. */
private const val FIRST_AGAIN_SEC = 0.12

/** Note on a solve whose running time was not seen, or doesn't fit. Kept in the list, to be checked. */
const val CLOCK_NOTE = "the running time doesn't confirm this time - check it"

/** Note on a cut that was worked out from the running time. */
const val HIDDEN_STOP_NOTE = "the timer was hidden when it stopped, the cut is worked out - check it"

private class TimeAt(val t: Double, val text: String, val seconds: Double = shownSeconds(text))

private class Stop(val text: String, var sec: Double, val votes: Int, val reads: Int, val certain: Boolean) {
    val seconds: Double = shownSeconds(text)
    var confirmed: Boolean? = null      // null = no running reading to go by
    var agree = 0                       // how many running readings agree on the start
    var gap = 0.0                       // start to stop, minus the time
    var start = 0.0                     // when the timer started, by the running readings
    var workedOut = false               // the cut comes from the clock
}

private fun decimalsOf(text: String): Int = text.length - 1 - text.indexOf('.')

/** As long as each other, and at most one digit differs. */
private fun alike(a: String, b: String): Boolean = a.length == b.length && a.indices.count { a[it] != b[it] } <= 1

private fun middle(v: List<Double>): Double {
    val s = v.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/**
 * [read] gives what the timer shows at a second ("12.69", "9.4" while running), or null. [frameSec] is the length of one frame.
 * [frameTime] gives the real time of the frame [read] looked at last, which can be half a frame off the second asked for.
 * [tenths]: for a display whose last digits blur while it runs, the seconds it shows to the tenth, or null; its running times are known by
 * them, and a time that seems to stay needs another look.
 * [onProgress] gets (percent, 100). Throws [ScanCancelled] when [isCancelled] says so.
 * (4.0) [exact] is told true while the scan goes frame by frame (the cut, and whether the timer ran up to it) and false after: in
 * between, a half-second step may be a nearby frame (GraySource.setNear), and [frameTime] tells its real time.
 */
fun scanFoundTimer(
    read: (Double) -> String?, durationSec: Double, runningDecimals: Int, stoppedDecimals: Int, frameSec: Double,
    frameTime: (Double) -> Double = { it }, onProgress: (Int, Int) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false },
    tenths: ((Double) -> Double?)? = null, exact: (Boolean) -> Unit = {}
): ScanResult = FinderScan(read, durationSec, runningDecimals, stoppedDecimals, frameSec, frameTime, onProgress, isCancelled, tenths, exact).run()

private class FinderScan(
    private val readAt: (Double) -> String?, private val duration: Double, private val runningDecimals: Int, private val stoppedDecimals: Int,
    private val frameSec: Double, private val frameTime: (Double) -> Double, private val onProgress: (Int, Int) -> Unit, private val isCancelled: () -> Boolean,
    private val tenths: ((Double) -> Double?)?, private val exact: (Boolean) -> Unit
) {
    private val stops = ArrayList<Stop>()
    private val running = ArrayList<TimeAt>()
    private val memo = HashMap<Long, String?>()
    private val shownAt = HashMap<Long, Double>()       // (4.0) the real time of the frame each read looked at
    private val seen = ArrayList<SeenAt>()
    private var total = 0
    private var readable = 0

    private fun read(t: Double): String? {
        if (isCancelled()) throw ScanCancelled()
        val key = Math.round(t * 1000)
        if (memo.containsKey(key)) return memo[key]
        return readAt(t).also { memo[key] = it; shownAt[key] = frameTime(t) }
    }

    /**
     * (4.0) When the picture read for second [t] was really shown: a half-second step may have taken a frame or two away. The clock
     * (start = time of the picture - time on the display) goes by it.
     */
    private fun shownTime(t: Double): Double = shownAt[Math.round(t * 1000)]?.takeIf { abs(it - t) > 0.75 * frameSec } ?: t
    // (only when the step really landed on another frame: an exact step is the second asked for, as before 4.0, so that the rules
    // with a limit, such as a reset within 1.5 s, come out the same; a 4 ms difference once turned a "reset" hint into "ran")

    /** (4.0) [block] reads frame by frame: every picture exactly the frame asked for. */
    private inline fun <T> frameByFrame(block: () -> T): T {
        exact(true)
        try {
            return block()
        } finally {
            exact(false)
        }
    }

    /** A read that counts for how readable the video is. */
    private fun counted(t: Double): String? {
        val v = read(t)
        total++
        if (v != null) readable++
        seen.add(SeenAt(t, v, v != null))
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
        return ScanResult(
            solves.map { found(it) }.sortedBy { it.stopSec }, if (total > 0) readable.toDouble() / total else 0.0,
            ScanTrace(seen, running.map { RunningAt(it.t, it.seconds) }, duration)
        )
    }

    private fun found(s: Stop): FoundSolve {
        val leftover = !s.certain && s.sec < 1.0
        val note = when {
            leftover -> "${LEFTOVER_NOTE_START} when the video began - probably left over from an earlier solve"
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
            if (v == null) {
                tenths?.invoke(t)?.let {
                    running.add(TimeAt(shownTime(t), "", it))
                    seen[seen.size - 1] = SeenAt(t, null, true)
                }
                t += SCAN_STEP
                continue
            }
            if (isZero(v)) { confirmed = null; recent.clear(); t += ZERO_STEP; continue }
            if (stoppedDecimals != runningDecimals && !isStopped(v)) {
                running.add(TimeAt(shownTime(t), v))
                confirmed = null
                recent.clear()
                t += SCAN_STEP
                continue
            }
            if (v == confirmed) { t += HOLD_STEP; continue }
            confirmed = null
            recent.add(TimeAt(t, v))
            if (recent.size > RECENT_N) recent.removeAt(0)
            val held = recent.takeLastWhile { it.text == v && it.t >= t - AGREE_WINDOW_SEC }
            if (held.size < MIN_AGREE) {
                if (stoppedDecimals == runningDecimals) running.add(TimeAt(shownTime(t), v, tenths?.invoke(t) ?: shownSeconds(v)))
                t += SCAN_STEP
                continue
            }
            // ---- a stopped timer: read on and vote on the exact digits ----
            val votes = ArrayList(held)
            var lt = t + SCAN_STEP
            while (lt <= min(duration, t + LOCK_SEC) + 1e-9) {
                val lv = counted(lt)
                if (lv != null && !isZero(lv) && isStopped(lv)) votes.add(TimeAt(lt, lv))
                lt += SCAN_STEP
            }
            if (stoppedDecimals == runningDecimals && goesOnRising(held, votes)) {
                running.addAll(votes.filter { it.text != v }.map { TimeAt(shownTime(it.t), it.text, it.seconds) })
                recent.clear()
                t += SCAN_STEP
                continue
            }
            val counts = votes.groupingBy { it.text }.eachCount()
            var time = if (counts[v] == counts.values.max()) v else counts.maxByOrNull { it.value }!!.key
            // the clock helps to choose: the time can't be far from start to now
            val before = running.filter { it.t > t - 12 && it.t < t }
            if (before.size >= 4) {
                val start = middle(before.map { it.t - it.seconds })
                val fits = counts.keys.filter { abs(votes[0].t - start - shownSeconds(it)) <= 0.75 }
                    .filter { k -> shownTime(votes.first { it.text == k }.t) - start - shownSeconds(k) >= -TOO_EARLY_SEC }
                if (fits.isNotEmpty() && time !in fits) time = fits.maxByOrNull { counts[it]!! }!!
            }
            val first = votes.first { it.text == time }.t
            val (sec, certain) = firstFrame(time, first, max(0.0, first - BACK_MAX_SEC))
            stops.add(Stop(time, sec, counts[time]!!, votes.size, certain))
            confirmed = time
            recent = ArrayList()
            t += LOCK_SEC + SCAN_STEP
        }
    }

    /** Does the number rise with the clock after [held]? Only asked when a running time looks like a stopped one (as many decimals). */
    private fun goesOnRising(held: List<TimeAt>, votes: List<TimeAt>): Boolean {
        val from = if (tenths == null) held.take(1) else held               // (blurred digits: any of the readings may be the odd one)
        val slack = if (tenths == null) SEEN_RUNNING_SEC else BLURRED_RISE_SEC
        val rising = votes.filter { v -> v.text != held[0].text && from.any { h -> abs((v.t - v.seconds) - (h.t - h.seconds)) <= slack } }
        return rising.size >= STILL_RUNNING_READS && rising.size > votes.count { it.text == held[0].text }
    }

    /**
     * The first frame that shows [time], searched back from [from], not before [lowest]: half-second steps to the nearest picture that
     * shows something else, then every frame of that gap. True if something else was seen before it, so the start really is there.
     */
    private fun firstFrame(time: String, from: Double, lowest: Double): Pair<Double, Boolean> {
        var earliest = from
        var other = Double.NaN                  // the nearest earlier picture that shows something else
        var others = 0
        var t = from - COARSE_BACK_STEP
        while (t > lowest - 1e-6 + 1e-9) {
            val v = read(t)
            if (v == time) { earliest = t; others = 0; other = Double.NaN }
            else if (v != null && !(tenths != null && alike(v, time))) {        // (one digit misread on a display that blurs is no other time)
                others++
                if (others == 1) other = t
                if (others >= 2) break
            }
            t -= COARSE_BACK_STEP
        }
        // (4.0) from and to where those pictures really were: a step may have landed a frame or two off (GraySource.setNear)
        val gapFrom = if (!other.isNaN()) min(other, shownTime(other)) else max(lowest, earliest - 0.6)
        val last = max(earliest, shownTime(earliest))
        val frames = ArrayList<TimeAt>()
        val n = ((last - gapFrom) / frameSec).toInt() + 3
        frameByFrame {
            for (k in -1..n) {
                val ft = gapFrom + k * frameSec
                if (ft < 0 || ft > last + 2 * frameSec) continue
                if (isCancelled()) throw ScanCancelled()
                readAt(ft)?.let { frames.add(TimeAt(frameTime(ft), it)) }
            }
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
                s.start = middle(near)
                s.gap = s.sec - s.start - s.seconds
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
                if (same && (ran < 2 || s.confirmed != true)) continue
                if (alike(s.text, p.text) && ran < 2 && s.confirmed != true) continue
            }
            out.add(s)
        }
        return out
    }

    /** Was the timer seen running up to the first frame of [s]? */
    private fun seenRunningUntil(s: Stop): Boolean = frameByFrame { seenRunningUntilFrames(s) }

    private fun seenRunningUntilFrames(s: Stop): Boolean {
        val n = (SEEN_RUNNING_SEC / frameSec).toInt()
        for (k in 1..n) {
            val t = s.sec - k * frameSec
            if (tenths != null) {
                val v = (if (t >= 0) tenths.invoke(t) else null) ?: continue
                if (abs(v - s.seconds) > 0.1 && abs(t - v - s.start) <= SEEN_RUNNING_SEC) return true
                continue
            }
            val v = (if (t >= 0) readAt(t) else null) ?: continue
            if (v != s.text && abs(t - shownSeconds(v) - s.start) <= SEEN_RUNNING_SEC) return true
        }
        return false
    }

    /** A time first seen too late for the clock: the stop itself was hidden. The clock says when it was. */
    private fun workOutHiddenStops(solves: List<Stop>) {
        val usual = solves.filter { it.confirmed == true }.map { it.gap }       // the display's own delay, the same for every solve
        val bias = if (usual.isEmpty()) 0.0 else middle(usual)
        for (s in solves) {
            // (a timer with a smart cube corrects the time when it stops: then the clock is off, and the stop wasn't hidden)
            if (s.confirmed == false && s.agree >= HIDDEN_READS && s.gap > CLOCK_SLACK && s.gap < HIDDEN_MAX_SEC && !seenRunningUntil(s)) {
                s.sec -= s.gap - bias
                s.workedOut = true
            }
        }
    }
}
