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

// (3.3-C) Finds the solves of a timer that shows no running time with decimals (HeldFinder.kt): it hides it, or shows whole seconds. Every
// number with decimals is a final time then, so it needs no long look: some TikTok clips show it for 0.7 s. What is missing is the running
// time that confirms it (FinderScan.kt). Instead:
//  - the time must have been AWAY for as long as it says: 12.97 can't come sooner than 12.97 s after the last time or 0.00;
//  - WHOLE SECONDS, when they are shown, must agree with start to stop. An inspection that counts down never does.
// A time that neither confirms stays in the list, to be checked.

private const val STEP = 0.5
private const val CLOSER = 0.25

/** While a time that was found is still there. */
private const val HOLD = 1.0

/** A final time is read again this long before and after it was first seen, and must be the same in this many of these pictures. */
private val AGAIN = doubleArrayOf(0.1, 0.2, 0.3, -0.1, -0.2, -0.3)
private const val AGAIN_WANTED = 3

/** Then it is read on for this long, to vote on the exact digits. */
private const val VOTE_SEC = 3.0

/** The first frame is searched back this far: a hand could be over the timer for a moment. */
private const val BACK_SEC = 2.0

/** The first frame must show the time again within this many seconds. */
private const val AGAIN_SEC = 0.12

/** The display's own delay: a time may come this much too soon. */
private const val SOON_SEC = 0.3

/** Whole seconds tell when this many were read between start and stop, and this share of them is right, give or take this much. */
private const val WHOLE_READS = 3
private const val WHOLE_SHARE = 0.6
private const val WHOLE_SLACK = 0.3

/** Note on a time that came sooner than it could have been timed. */
const val SOON_NOTE = "it came too soon after the time before it - check it"

private class HeldStop(val text: String, val sec: Double, val votes: Int, val reads: Int, val away: Double) {
    val seconds: Double = shownSeconds(text)
    var confirmed = false
    var wholeDisagrees = false
}

/** At most one digit differs, or the first digits are missing (a hand was over them). */
private fun couldBe(a: String, b: String): Boolean =
    if (a.length == b.length) a.indices.count { a[it] != b[it] } <= 1 else a.endsWith(b) || b.endsWith(a)

/**
 * [read] gives what the timer shows at a second. [frameSec], [frameTime], [onProgress] and [isCancelled]: as in [scanFoundTimer].
 */
fun scanHeldTimer(
    read: (Double) -> HeldShown?, durationSec: Double, frameSec: Double,
    frameTime: (Double) -> Double = { it }, onProgress: (Int, Int) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }
): ScanResult = HeldScan(read, durationSec, frameSec, frameTime, onProgress, isCancelled).run()

private class HeldScan(
    private val readAt: (Double) -> HeldShown?, private val duration: Double, private val frameSec: Double,
    private val frameTime: (Double) -> Double, private val onProgress: (Int, Int) -> Unit, private val isCancelled: () -> Boolean
) {
    private val memo = HashMap<Long, HeldShown>()
    private val stops = ArrayList<HeldStop>()
    private val whole = ArrayList<Pair<Double, Int>>()
    private val seen = ArrayList<SeenAt>()

    private fun read(t: Double): HeldShown {
        if (isCancelled()) throw ScanCancelled()
        return memo.getOrPut(Math.round(t * 1000)) { (if (t in 0.0..duration) readAt(t) else null) ?: NOTHING }
    }

    private fun isZero(text: String) = shownSeconds(text) == 0.0

    fun run(): ScanResult {
        findStops()
        val running = checkWhole()
        val solves = stops.filterIndexed { i, s -> i == 0 || s.confirmed || !couldBe(stops[i - 1].text, s.text) }
        onProgress(100, 100)
        val readable = seen.count { it.readable }
        return ScanResult(solves.map { found(it) }, if (seen.isEmpty()) 0.0 else readable.toDouble() / seen.size, ScanTrace(seen, running, duration))
    }

    private fun found(s: HeldStop): FoundSolve {
        val leftover = s.sec < 1.0
        val note = when {
            leftover -> "${LEFTOVER_NOTE_START} when the video began - probably left over from an earlier solve"
            s.wholeDisagrees -> CLOCK_NOTE
            !s.confirmed -> SOON_NOTE
            s.votes < s.reads * 0.6 -> "time not steady (read on ${s.votes} of ${s.reads} frames) - check it"
            else -> ""
        }
        return FoundSolve(max(0.0, s.sec), s.text, note, !leftover)
    }

    private fun findStops() {
        var t = 0.0
        var showing: String? = null             // the last time found, as long as no 0.00 came after it
        var zero: String? = null                // how the timer writes 0.00
        var lastShown = 0.0                     // when a 0.00 or that time was last seen: a solve can only start after that
        var lastPercent = -1
        while (t <= duration) {
            val percent = if (duration > 0) (t / duration * 100).toInt().coerceIn(0, 100) else 0
            if (percent != lastPercent) { lastPercent = percent; onProgress(percent, 100) }
            val now = read(t)
            val text = now.text
            seen.add(SeenAt(t, text, text != null || now.whole != null))
            if (text == null) {
                now.whole?.let { whole.add(Pair(t, it)) }
                t += STEP
                continue
            }
            if (isZero(text)) { showing = null; zero = text; lastShown = t; t += STEP; continue }
            val last = showing ?: zero
            if (last != null && t - lastShown < shownSeconds(text) - SOON_SEC && couldBe(last, text)) {
                if (text == last) lastShown = t
                t += HOLD
                continue
            }
            if (showing == text && !wholeAgrees(t, shownSeconds(text))) { lastShown = t; t += HOLD; continue }
            // ---- a new final time? ----
            val votes = ArrayList<Pair<Double, String>>()
            votes.add(Pair(t, text))
            for (d in AGAIN) read(t + d).text?.let { if (!isZero(it)) votes.add(Pair(t + d, it)) }
            if (votes.count { it.second == text } < AGAIN_WANTED) { t += CLOSER; continue }
            var lt = t + CLOSER
            while (lt <= min(duration, t + VOTE_SEC) + 1e-9) {
                val v = read(lt)
                if (v.whole != null || (v.text != null && isZero(v.text))) break
                v.text?.let { votes.add(Pair(lt, it)) }
                lt += CLOSER
            }
            val counts = votes.groupingBy { it.second }.eachCount()
            val longer = counts.filter { it.key != text && it.key.endsWith(text) && it.value >= 2 }.maxByOrNull { it.value }?.key
            val time = longer ?: if (counts[text] == counts.values.max()) text else counts.maxByOrNull { it.value }!!.key
            val shows = votes.filter { it.second == time }.map { it.first }
            val sec = firstFrame(time, shows.min())
            stops.add(HeldStop(time, sec, counts[time]!!, votes.size, sec - lastShown))
            showing = time
            lastShown = shows.max()
            t = max(t, shows.max()) + CLOSER
        }
    }

    /** The first frame that shows [time], searched back from [from]: to the nearest picture that shows something else, then every frame after it. */
    private fun firstFrame(time: String, from: Double): Double {
        var earliest = from
        var t = from - CLOSER
        while (t >= from - BACK_SEC && t >= 0) {
            val v = read(t)
            if (v.text == time) earliest = t
            else if (v.whole != null || v.text != null) break
            t -= CLOSER
        }
        val frames = ArrayList<Double>()
        for (k in 0..(CLOSER / frameSec).toInt() + 2) {
            val ft = earliest - CLOSER + (k - 1) * frameSec
            if (ft < 0 || ft > earliest + 2 * frameSec) continue
            if (isCancelled()) throw ScanCancelled()
            if (readAt(ft)?.text == time) frames.add(frameTime(ft))
        }
        if (frames.isEmpty()) return earliest
        return frames.firstOrNull { a -> frames.count { it > a && it <= a + AGAIN_SEC } >= 1 } ?: frames[0]
    }

    /** The whole seconds read between start and stop, and how many of them are right. */
    private fun wholeBetween(stop: Double, seconds: Double): Pair<Int, Int> {
        val start = stop - seconds
        val between = whole.filter { it.first > start + 0.3 && it.first < stop - 0.1 }
        val right = between.count { (t, n) -> n >= floor(t - start - WHOLE_SLACK) && n <= floor(t - start + WHOLE_SLACK) }
        return Pair(between.size, right)
    }

    private fun wholeAgrees(stop: Double, seconds: Double): Boolean = wholeBetween(stop, seconds).let { (n, right) -> n >= WHOLE_READS && right >= WHOLE_SHARE * n }

    /** Confirms the stops. Gives the running times the whole seconds tell: the moment each one changes, for the hints. */
    private fun checkWhole(): List<RunningAt> {
        val told = stops.map { wholeBetween(it.sec, it.seconds) }
        val shown = told.count { (n, right) -> n >= WHOLE_READS && right >= WHOLE_SHARE * n }
        val usesWhole = shown > 0 && shown >= told.size / 2        // (else they are an inspection, or something misread as a digit)
        for ((i, s) in stops.withIndex()) {
            val (n, right) = told[i]
            s.wholeDisagrees = usesWhole && n >= WHOLE_READS && right < WHOLE_SHARE * n
            s.confirmed = !s.wholeDisagrees && ((usesWhole && n >= WHOLE_READS) || s.away >= s.seconds - SOON_SEC)
        }
        if (!usesWhole) return emptyList()
        return whole.zipWithNext().filter { (a, b) -> b.second == a.second + 1 && abs(b.first - a.first - STEP) < 0.01 }
            .map { (a, b) -> RunningAt((a.first + b.first) / 2, b.second.toDouble()) }
    }

    private companion object {
        val NOTHING = HeldShown(null, null)
    }
}
