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

// ---------------------------------------------------------------------------------------
// Running Find solves again on a video that already has saved cuts.
//
// The new scan does not replace the saved list. Each solve it finds is matched to a saved cut when their SOLVE parts
// (timer start to stop, worked out from the time shown) overlap; the scramble and inspection are not used for matching,
// because a missed solve can sit inside the next solve's scramble.
//  - A saved cut you changed (scramble, time, position, note, marked as checked, added by hand) or exported stays exactly as it is.
//  - A saved cut you never touched takes the new scan's values.
//  - A new solve that matches no saved cut is added.
//  - A saved cut the new scan did not find again is kept; an untouched one gets a note so it can be checked.
// ---------------------------------------------------------------------------------------

/** The note given to an untouched cut that the latest scan did not find again. */
const val NOT_FOUND_AGAIN_NOTE = "not found again by the last Find solves - check it"

private const val SLACK_SEC = 0.3                  // both times known: the solve parts may be this far apart and still match
private const val SLACK_UNKNOWN_SEC = 1.5          // a time unknown: only the stop moments can be compared, so allow more

/**
 * The shortest time from the end of one solve to the end of the next that a person can manage: reset the timer, scramble and solve.
 * The fastest WCA scrambles (2x2, Pyraminx, Skewb) take a very fast person about 3-4 s, plus about 1 s for the timer and 1-2 s for a
 * world-class solve. Less than this is almost always a misread of the time still on the display (e.g. 11.517 read as 1151 = 1.151).
 */
private const val MIN_SOLVE_CYCLE_SEC = 6.0

/** What a re-scan did to the saved cuts. */
class RescanResult(val cuts: List<CutItem>, val added: Int, val updated: Int, val kept: Int, val notFound: Int) {
    /** "2 new, 1 updated, 12 kept as you left them, 1 not found again (check them)" */
    fun summary(): String {
        val parts = ArrayList<String>()
        if (added > 0) parts.add("$added new")
        if (updated > 0) parts.add("$updated updated")
        if (kept > 0) parts.add("$kept kept as you left them")
        if (notFound > 0) parts.add("$notFound not found again (check them)")
        return if (parts.isEmpty()) "nothing changed" else parts.joinToString(", ")
    }
}

/** Where the solve part of a cut runs: from (stop - time on the timer) to the stop. Just the stop moment if the time is unknown. */
private fun solveSpan(c: CutItem): Pair<Double, Double> {
    val t = parseTimeSeconds(c.timeText)
    return if (t != null) Pair(c.stopSec - t, c.stopSec) else Pair(c.stopSec, c.stopSec)
}

/** True if the two cuts are the same solve: their solve parts overlap. */
private fun sameSolve(a: CutItem, b: CutItem): Boolean {
    val (aStart, aEnd) = solveSpan(a)
    val (bStart, bEnd) = solveSpan(b)
    val slack = if (parseTimeSeconds(a.timeText) == null || parseTimeSeconds(b.timeText) == null) SLACK_UNKNOWN_SEC else SLACK_SEC
    return aStart <= bEnd + slack && bStart <= aEnd + slack
}

/**
 * Combines the [saved] cuts with the solves a new scan [found]. [exportedStops] are the stop times of every clip exported from this
 * video (an exported cut is kept as it is, so its link to the exported file is never lost).
 */
fun mergeRescan(saved: List<CutItem>, found: List<CutItem>, exportedStops: List<Double>): RescanResult {
    fun keepAsIs(c: CutItem) = c.edited || exportedStops.any { Math.abs(it - c.stopSec) < SAME_SOLVE_SEC }

    val matched = BooleanArray(saved.size)
    val out = ArrayList<CutItem>()
    var added = 0
    var updated = 0
    for (n in found.sortedBy { it.stopSec }) {
        val same = saved.indices.filter { sameSolve(saved[it], n) }
        if (same.isEmpty()) {
            out.add(n)                                  // a solve the saved list doesn't have
            added++
            continue
        }
        // the closest saved cut not matched yet; if they are all matched already, this is a second find of the same solve: ignore it
        val best = same.filter { !matched[it] }.minByOrNull { Math.abs(saved[it].stopSec - n.stopSec) } ?: continue
        matched[best] = true
        val old = saved[best]
        if (keepAsIs(old)) {
            out.add(old)
        } else {
            out.add(n)
            val changed = Math.abs(n.stopSec - old.stopSec) > 0.0005 || n.timeText != old.timeText ||
                    n.included != old.included || n.note != old.note
            if (changed) updated++
        }
    }
    var notFound = 0
    for (i in saved.indices) {
        if (matched[i]) continue
        val old = saved[i]
        if (keepAsIs(old)) {
            out.add(old)
            continue
        }
        // untouched and not found again: keep it (it may still be a real solve), but say so
        val note = when {
            old.note.startsWith(SolveAnalyzer.LEFTOVER_NOTE_START) || old.note.contains(NOT_FOUND_AGAIN_NOTE) -> old.note
            old.note.isEmpty() -> NOT_FOUND_AGAIN_NOTE
            else -> "${old.note}; $NOT_FOUND_AGAIN_NOTE"
        }
        out.add(old.copy(note = note))
        notFound++
    }
    val kept = saved.count { keepAsIs(it) }
    return RescanResult(out.sortedBy { it.stopSec }, added, updated, kept, notFound)
}

// ---------------------------------------------------------------------------------------
// Checks on a session's cuts. The editor is only for finding the real solves; choosing what to export happens in the Gallery.
// ---------------------------------------------------------------------------------------

/** A cut the scan switched off because its time was already on the timer when the video began (computeClips treats it as deleted). */
fun isLeftover(c: CutItem): Boolean = !c.included && c.note.startsWith(SolveAnalyzer.LEFTOVER_NOTE_START)

/**
 * Every cut is a solve that can be exported; the only cuts that stay switched off are the leftovers above. Cuts switched off by hand in an
 * older version, or by the scan for another reason, become normal solves (a scan note on them still flags them for a check).
 */
fun normalizeIncluded(c: CutItem): CutItem = if (c.included || isLeftover(c)) c else c.copy(included = true)

/** What is wrong with some cuts of a session: a short warning per cut index, and the cuts that get no clip at all. */
class CutChecks(val warnings: Map<Int, String>, val noClip: Set<Int>)

/**
 * Looks for impossible timing between neighbouring solves, and flags BOTH solves of each pair, because either one can be the wrong one.
 * [clips] must be computeClips(cuts, ...) for the same [cuts].
 *  - too close: computeClips makes no clip for a solve that ends less than a second after the one before it;
 *  - overlap: a solve that started before the previous one stopped (worked out from the times shown) can't be right;
 *  - too fast: a solve that ended less than MIN_SOLVE_CYCLE_SEC after the previous one (no time to scramble and solve).
 */
fun checkCuts(cuts: List<CutItem>, clips: List<Clip>): CutChecks {
    val found = HashMap<Int, MutableList<String>>()
    // a cut you marked as checked is not flagged again, except when it has no clip at all (that can't be waved away)
    fun flag(i: Int, text: String, evenIfChecked: Boolean = false) {
        if (cuts[i].reviewed && !evenIfChecked) return
        val list = found.getOrPut(i) { ArrayList() }
        if (text !in list) list.add(text)
    }
    val withClip = clips.map { it.cutIndex }.toSet()
    val noClip = cuts.indices.filter { !isLeftover(cuts[it]) && it !in withClip }.toSet()
    var prev = -1                                       // index of the previous real solve (in time order)
    for (i in cuts.indices.sortedBy { cuts[it].stopSec }) {
        val c = cuts[i]
        if (isLeftover(c)) continue
        if (prev >= 0) {
            val p = cuts[prev]
            var paired = false                          // this pair already has a reason, so the "too fast" one isn't added as well
            if (i in noClip) {
                flag(i, "too close to the solve before it, so it has no clip - delete one of them or move it", true)
                flag(prev, "too close to the solve after it - one of the two is probably not a real solve")
                paired = true
            }
            val t = parseTimeSeconds(c.timeText)
            if (t != null && c.stopSec - t < p.stopSec - SLACK_SEC) {
                flag(i, "it would have started before the solve before it stopped - check this time or that one")
                flag(prev, "the solve after it would have started before this one stopped - check this time or that one")
                paired = true
            }
            val cycle = c.stopSec - p.stopSec
            if (!paired && cycle < MIN_SOLVE_CYCLE_SEC) {
                val s = "%.1f".format(cycle)
                flag(i, "ended only $s s after the solve before it - too fast to scramble and solve, so one of the two is probably a misread")
                flag(prev, "the solve after it ended only $s s after this one - one of the two is probably a misread")
            }
        } else if (i in noClip) {
            flag(i, "too close to the start of the video, so it has no clip", true)
        }
        prev = i
    }
    return CutChecks(found.mapValues { it.value.joinToString("; ") }, noClip)
}
// ---------------------------------------------------------------------------------------
// Checks for a cut added by hand ("Add a missed solve at the playhead", solveSpan above gives where each solve runs). The user puts the playhead on the first frame of the final time and types the
// solve time; the solve then runs from (playhead - time) to the playhead. Two solves can't overlap, so:
//  - a playhead inside an existing solve can't take a new cut (that would be the same solve again, or one of the two isn't real);
//  - a typed time that makes the new solve start before an earlier solve stopped is refused.
// The same 0.3 s slack as checkCuts is used, so a cut added here is never flagged by checkCuts for overlapping, and nothing checkCuts
// accepts is refused here.
// ---------------------------------------------------------------------------------------

/**
 * The index of the solve the playhead at [atSec] sits inside (its solve part, or within 0.3 s after its stop, which would be the same
 * stop again), or -1 if the spot is free. Left-over cuts are ignored, like everywhere else.
 */
fun solveAtPlayhead(cuts: List<CutItem>, atSec: Double): Int {
    var best = -1
    for (i in cuts.indices) {
        val c = cuts[i]
        if (isLeftover(c)) continue
        val (start, stop) = solveSpan(c)
        val inside = if (stop > start) atSec > start + SLACK_SEC && atSec < stop + SLACK_SEC else Math.abs(atSec - stop) < SLACK_SEC
        if (inside && (best < 0 || Math.abs(cuts[best].stopSec - atSec) > Math.abs(c.stopSec - atSec))) best = i
    }
    return best
}

/**
 * The index of an existing solve that a new solve of [timeSec] seconds, stopping at [stopSec], would overlap, or -1 if there is none.
 * When several would overlap, the one that stops closest to [stopSec] is returned (normally the solve just before it).
 */
fun solveOverlappedBy(cuts: List<CutItem>, stopSec: Double, timeSec: Double): Int {
    val newStart = stopSec - timeSec
    var best = -1
    for (i in cuts.indices) {
        val c = cuts[i]
        if (isLeftover(c)) continue
        val (start, stop) = solveSpan(c)
        val overlaps = if (stop > start) newStart < stop - SLACK_SEC && start < stopSec - SLACK_SEC
        else stop > newStart + SLACK_SEC && stop < stopSec + SLACK_SEC
        if (overlaps && (best < 0 || Math.abs(cuts[best].stopSec - stopSec) > Math.abs(c.stopSec - stopSec))) best = i
    }
    return best
}