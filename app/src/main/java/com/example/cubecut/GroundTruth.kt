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

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

// (3.3-A) The ground-truth harness: what a reference video SHOULD give, and how a scan compares with it.
// A ground-truth file is a small JSON file per reference video (the video itself is never committed). It is written from the
// editor's list after you have fixed it by hand (debug builds only, see ScanReport.kt), kept in app/src/test/resources/groundtruth/,
// and read back by the debug scan report and by the unit tests. The format is in docs/algorithms.md § 8.

/** Matching a found solve to an expected one: the nearest within this many seconds is the same solve (a wrong cut, not a miss). */
private const val MATCH_SEC = 2.0

/** A cut within this many seconds of the expected one is on the right frame (one 60 fps frame plus rounding). */
const val CUT_FRAME_SEC = 0.02

/** A cut further than this from the expected one is clearly wrong for a clip (the solve-only clip would start visibly off). */
const val CUT_CLOSE_SEC = 0.1

/** The version of the file format; a reader refuses newer files instead of guessing. */
const val GROUND_TRUTH_FORMAT = 1

/** The timer kinds the owner films; written into each file so results can be grouped per timer. */
val TIMER_KINDS = listOf("csTimer (PC)", "Phone app", "Stackmat", "GAN", "QiYi", "Other")

/** One solve a reference video should give: the first frame showing the final time, and that time as the timer showed it. */
data class ExpectedSolve(val stopSec: Double, val time: String)

/** Everything one ground-truth file says. [video] is the file name, used to warn when a file is compared with another video. */
data class GroundTruth(
    val video: String,
    val timer: String,
    val durationSec: Double,
    val solves: List<ExpectedSolve>,
    val note: String = ""
)

/** An expected solve and the found solve matched to it. [offsetSec] is found minus expected (negative = the cut is early). */
class ScanMatch(val expected: ExpectedSolve, val found: FoundSolve) {
    val offsetSec: Double get() = found.stopSec - expected.stopSec
    /** An expected time of "" (unknown) is not checked. */
    val timeOk: Boolean get() = expected.time.isEmpty() || expected.time == found.timeText
}

/**
 * How one scan did against the expected solves. [leftovers] are found times that were already on the display when the video
 * began; the scan switches them off itself, so they are neither solves nor false solves.
 */
class ScanComparison(
    val matches: List<ScanMatch>,
    val missed: List<ExpectedSolve>,
    val falseSolves: List<FoundSolve>,
    val leftovers: List<FoundSolve>
) {
    val expectedCount: Int get() = matches.size + missed.size
    val wrongTime: List<ScanMatch> get() = matches.filter { !it.timeOk }
    val onFrame: Int get() = matches.count { abs(it.offsetSec) <= CUT_FRAME_SEC }
    val close: Int get() = matches.count { abs(it.offsetSec) <= CUT_CLOSE_SEC }
    val worstOffsetSec: Double get() = matches.maxOfOrNull { abs(it.offsetSec) } ?: 0.0
}

/**
 * Compares a scan's [found] solves with the [expected] ones. Each pair within [MATCH_SEC] is a candidate; the closest pairs are
 * taken first, each solve used once, so two solves close together can't both grab the same match.
 */
fun compareScan(expected: List<ExpectedSolve>, found: List<FoundSolve>): ScanComparison {
    val leftovers = found.filter { !it.include && it.note.startsWith(SolveAnalyzer.LEFTOVER_NOTE_START) }
    val real = found.filter { it !in leftovers }
    val pairs = ArrayList<Triple<Int, Int, Double>>()
    for (e in expected.indices) for (f in real.indices) {
        val d = abs(real[f].stopSec - expected[e].stopSec)
        if (d <= MATCH_SEC) pairs.add(Triple(e, f, d))
    }
    pairs.sortBy { it.third }
    val usedE = HashSet<Int>()
    val usedF = HashSet<Int>()
    val matches = ArrayList<ScanMatch>()
    for ((e, f, _) in pairs) {
        if (e in usedE || f in usedF) continue
        usedE.add(e)
        usedF.add(f)
        matches.add(ScanMatch(expected[e], real[f]))
    }
    return ScanComparison(
        matches.sortedBy { it.expected.stopSec },
        expected.indices.filter { it !in usedE }.map { expected[it] },
        real.indices.filter { it !in usedF }.map { real[it] },
        leftovers
    )
}

/** The expected solves of an editor list you have checked by hand: every cut except the left-overs the scan switches off. */
fun expectedFromCuts(cuts: List<CutItem>): List<ExpectedSolve> =
    cuts.filter { !isLeftover(it) }.map { ExpectedSolve(it.stopSec, it.timeText) }.sortedBy { it.stopSec }

/** "1:23.45" for a place in the video (minutes:seconds, two decimals): precise enough to find the frame, short enough to read. */
fun videoPlace(sec: Double): String {
    val cs = Math.round(sec * 100).coerceAtLeast(0)
    return "%d:%02d.%02d".format(cs / 6000, (cs / 100) % 60, cs % 100)
}

/**
 * The scan report as plain text, short enough to paste into a chat or into docs/algorithms.md § 8.
 * [seconds] is how long the scan took, [readable] the share of sampled frames the reader could read (0..1), [app] the app version.
 */
fun scanReportText(truth: GroundTruth, videoName: String, c: ScanComparison, seconds: Double, readable: Double, app: String): String {
    val lines = ArrayList<String>()
    lines.add("Scan report: $videoName" + (if (truth.timer.isNotEmpty()) " (${truth.timer})" else ""))
    if (truth.video.isNotEmpty() && truth.video != videoName) lines.add("WARNING: the expected solves are for ${truth.video}")
    lines.add(
        "Expected ${c.expectedCount}, found ${c.matches.size}, missed ${c.missed.size}, " +
                "false ${c.falseSolves.size}, wrong time ${c.wrongTime.size}"
    )
    if (c.matches.isNotEmpty()) {
        lines.add(
            "Cuts: ${c.onFrame} of ${c.matches.size} on the frame, ${c.close} within ${CUT_CLOSE_SEC} s, " +
                    "worst ${"%.3f".format(c.worstOffsetSec)} s"
        )
    }
    lines.add("Readable ${Math.round(readable * 100)}%, took ${"%.1f".format(seconds)} s, CubeCut $app")
    if (c.leftovers.isNotEmpty()) lines.add("Left-over at the start (switched off): ${c.leftovers.size}")
    for (m in c.missed) lines.add("Missed: ${videoPlace(m.stopSec)} ${m.time}")
    for (f in c.falseSolves) lines.add("False: ${videoPlace(f.stopSec)} ${f.timeText}")
    for (m in c.wrongTime) lines.add("Wrong time: ${videoPlace(m.expected.stopSec)} expected ${m.expected.time}, read ${m.found.timeText}")
    for (m in c.matches.filter { abs(it.offsetSec) > CUT_FRAME_SEC }) {
        lines.add("Cut off: ${videoPlace(m.expected.stopSec)} ${m.expected.time} by ${"%+.3f".format(m.offsetSec)} s")
    }
    return lines.joinToString("\n")
}

/** The file's text (pretty-printed, so a diff in git shows which solve changed). */
fun groundTruthToJson(t: GroundTruth): String {
    val solves = JSONArray()
    for (s in t.solves) solves.put(JSONObject().put("stopSec", Math.round(s.stopSec * 1000) / 1000.0).put("time", s.time))
    return JSONObject()
        .put("format", GROUND_TRUTH_FORMAT)
        .put("video", t.video)
        .put("timer", t.timer)
        .put("durationSec", Math.round(t.durationSec * 1000) / 1000.0)
        .put("note", t.note)
        .put("solves", solves)
        .toString(2)
}

/** Reads a ground-truth file. Throws IllegalArgumentException with a short reason if it isn't one (or is from a newer CubeCut). */
fun parseGroundTruth(text: String): GroundTruth {
    val o = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("not a JSON file") }
    val format = o.optInt("format", -1)
    if (format < 1) throw IllegalArgumentException("not a ground-truth file")
    if (format > GROUND_TRUTH_FORMAT) throw IllegalArgumentException("made by a newer CubeCut (format $format)")
    val arr = o.optJSONArray("solves") ?: throw IllegalArgumentException("no solves in it")
    val solves = (0 until arr.length()).map {
        val s = arr.getJSONObject(it)
        ExpectedSolve(s.getDouble("stopSec"), s.optString("time", ""))
    }
    return GroundTruth(o.optString("video", ""), o.optString("timer", ""), o.optDouble("durationSec", 0.0), solves.sortedBy { it.stopSec }, o.optString("note", ""))
}
