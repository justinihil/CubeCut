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

// (3.3-C) Where a solve may be missing: the timer ran but no final time was found, or it couldn't be read for a long time. Only told when
// the scan has something to show; a long gap alone could be a break. Pure logic without a screen: the editor shows the hints today, and
// in 4.0 they come up after a scan in the background. They are saved with the video (SessionStore.saveHints).

/** What the timer showed at one moment of the scan: its [text] if it could be read in full, and whether anything of it could be read. */
class SeenAt(val t: Double, val text: String?, val readable: Boolean)

/** A running time, [seconds], seen at [t]. */
class RunningAt(val t: Double, val seconds: Double)

/** Everything a scan saw along the way (empty from a scan that doesn't keep it). */
class ScanTrace(val seen: List<SeenAt> = emptyList(), val running: List<RunningAt> = emptyList(), val durationSec: Double = 0.0)

/** Why a solve may be missing. [key] is how it is stored: never change it. */
enum class HintReason(val key: String) {
    RAN("ran"),                 // the timer ran, and no final time was found
    RESET("reset"),             // it ran, and then showed 0.000
    UNREADABLE("unreadable")    // it couldn't be read for a long time
}

/** A stretch of the video where a solve may be missing. [seekSec]: where to look. [dismissed]: the user said there is nothing. */
data class MissedHint(val fromSec: Double, val toSec: Double, val seekSec: Double, val reason: HintReason, val dismissed: Boolean = false) {
    /** One line for the user. */
    val text: String
        get() = when (reason) {
            HintReason.RAN -> "The timer ran, but no final time was found."
            HintReason.RESET -> "The timer ran and was reset."
            HintReason.UNREADABLE -> "The timer couldn't be read for ${Math.round(toSec - fromSec)} s."
        }
}

/** A run needs this many readings that agree on the start, over this long. Fewer could be misreads. */
private const val RUN_READINGS = 4
private const val RUN_SEC = 2.0
private const val RUN_AGREE_SEC = 0.3

/** A run belongs to a found solve when it lies in it, give or take this much. */
private const val BEFORE_SOLVE_SEC = 1.5
private const val AFTER_SOLVE_SEC = 1.0

/** 0.000 this soon after the last running reading: it was reset. Later, the final time was only not seen. */
private const val RESET_SEC = 1.5

/** A run up to the end of the video is no hint: the video just ended. */
private const val END_SEC = 2.5

/** An unreadable stretch counts from this length on, in seconds and in usual solves (stop to stop); without three solves to tell, this long. */
private const val UNREADABLE_SEC = 15.0
private const val UNREADABLE_CYCLES = 1.3
private const val UNREADABLE_UNKNOWN_SEC = 60.0

/** Less is enough when the time between the two solves around it is this much longer than usual. */
private const val LONG_GAP_CYCLES = 1.6
private const val UNREADABLE_IN_GAP_CYCLES = 0.5

/** Before the first and after the last time that was read, the camera was often just put up or taken down: then it takes more. */
private const val UNREADABLE_AT_ENDS_CYCLES = 2.0

private fun middleValue(v: List<Double>): Double {
    val s = v.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/** Where a solve may be missing, in time order. [solves]: what the scan found, as (stop, seconds). */
fun findMissedSolves(trace: ScanTrace, solves: List<Pair<Double, Double>>): List<MissedHint> {
    if (trace.seen.isEmpty()) return emptyList()
    val found = solves.sortedBy { it.first }
    val ran = runsWithoutASolve(trace, found)
    return (ran + unreadableStretches(trace, found).filter { u -> ran.none { it.fromSec < u.toSec && it.toSec > u.fromSec } }).sortedBy { it.fromSec }
}

/** The hints of a finished scan. */
fun hintsOf(scan: ScanResult): List<MissedHint> =
    findMissedSolves(scan.trace, scan.solves.filter { it.include && it.timeText.isNotEmpty() }.map { Pair(it.stopSec, shownSeconds(it.timeText)) })

private class SeenRun(first: RunningAt) {
    val at = arrayListOf(first)
    var start = first.t - first.seconds
    fun add(r: RunningAt) {
        at.add(r)
        start += (r.t - r.seconds - start) / at.size
    }
}

private fun runsWithoutASolve(trace: ScanTrace, solves: List<Pair<Double, Double>>): List<MissedHint> {
    val runs = ArrayList<SeenRun>()
    for (r in trace.running) {
        val run = runs.lastOrNull { abs(it.start - (r.t - r.seconds)) <= RUN_AGREE_SEC }
        if (run != null) run.add(r) else runs.add(SeenRun(r))
    }
    val out = ArrayList<MissedHint>()
    for (run in runs) {
        val first = run.at.first().t
        val last = run.at.last().t
        if (run.at.size < RUN_READINGS || last - first < RUN_SEC || last > trace.durationSec - END_SEC) continue
        if (solves.any { (stop, sec) -> last > stop - sec - BEFORE_SOLVE_SEC && last <= stop + AFTER_SOLVE_SEC }) continue
        val next = trace.seen.firstOrNull { it.t > last + 1e-6 && it.text != null }
        val reset = next != null && shownSeconds(next.text!!) == 0.0 && next.t - last <= RESET_SEC
        // up to the next thing that could be read: the final time must be before that
        val to = min(next?.t ?: trace.durationSec, solves.firstOrNull { it.first > last }?.let { it.first - it.second } ?: trace.durationSec)
        out.add(MissedHint(max(0.0, run.start), max(to, last + 0.5), last, if (reset) HintReason.RESET else HintReason.RAN))
    }
    return out
}

private fun unreadableStretches(trace: ScanTrace, solves: List<Pair<Double, Double>>): List<MissedHint> {
    val cycles = solves.zipWithNext { a, b -> b.first - a.first }
    val usual = if (cycles.size >= 2) middleValue(cycles) else Double.NaN
    val out = ArrayList<MissedHint>()
    val readable = trace.seen.filter { it.readable }
    val ends = listOf(0.0) + readable.map { it.t } + listOf(trace.durationSec)
    for (k in 0 until ends.size - 1) {
        val from = ends[k]
        // the next solve was seen: the time up to its start is what could hide another one
        val next = solves.firstOrNull { it.first > from + 1e-6 }
        val to = min(ends[k + 1], next?.let { it.first - it.second } ?: trace.durationSec)
        val length = to - from
        if (length < UNREADABLE_SEC) continue
        val before = readable.getOrNull(k - 1)
        val after = readable.getOrNull(k)
        // it still shows the same time: nothing was timed meanwhile
        if (before?.text != null && before.text == after?.text && shownSeconds(before.text) > 0) continue
        val atEnd = before == null || after == null
        val previous = solves.lastOrNull { it.first <= from + 1e-6 }
        val gap = if (previous != null && next != null) next.first - previous.first else 0.0
        val enough = when {
            usual.isNaN() -> length >= UNREADABLE_UNKNOWN_SEC * (if (atEnd) UNREADABLE_AT_ENDS_CYCLES else 1.0)
            atEnd -> length >= UNREADABLE_AT_ENDS_CYCLES * usual
            else -> length >= UNREADABLE_CYCLES * usual || (gap >= LONG_GAP_CYCLES * usual && length >= UNREADABLE_IN_GAP_CYCLES * usual)
        }
        if (enough) out.add(MissedHint(from, to, from, HintReason.UNREADABLE))
    }
    return out
}

/** The hints of a new scan, with those the user dismissed before kept dismissed. */
fun mergeHints(saved: List<MissedHint>, found: List<MissedHint>): List<MissedHint> {
    val dismissed = saved.filter { it.dismissed }
    return dismissed + found.filter { f -> dismissed.none { it.fromSec < f.toSec && it.toSec > f.fromSec } }
}

/** The hints to show: not dismissed, and no solve of the list (found again, or added by hand) is there. */
fun hintsToShow(hints: List<MissedHint>, cuts: List<CutItem>): List<MissedHint> = hints.filter { h ->
    !h.dismissed && cuts.none { c -> !isLeftover(c) && c.stopSec > h.fromSec && c.stopSec <= h.toSec + AFTER_SOLVE_SEC }
}.sortedBy { it.fromSec }
