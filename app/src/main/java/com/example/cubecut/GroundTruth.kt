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
import java.util.Locale
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

/**
 * A timer setup as it was tried: the picture ([frameMs]), the four corners (8 numbers, in that picture's pixels) and the typed number.
 * It is kept whether or not the setup worked, so a later reader is tried on exactly the same display.
 */
data class SetupAttempt(val frameMs: Long, val corners: List<Double>, val typed: String)

/**
 * Everything one ground-truth file says. [video] is the file name, used to warn when a file is compared with another video.
 * [setup] is null if no timer setup was ever tried on the video.
 * [timesOnly] is the other kind of answer key: just the times in order (csTimer's export, see [parseTimesList]), with no places in the
 * video. [solves] is empty then, and it is never written to JSON.
 */
data class GroundTruth(
    val video: String,
    val timer: String,
    val durationSec: Double,
    val solves: List<ExpectedSolve>,
    val note: String = "",
    val setup: SetupAttempt? = null,
    val timesOnly: List<String> = emptyList()
)

/** An expected solve and the found solve matched to it. [offsetSec] is found minus expected (negative = the cut is early). */
class ScanMatch(val expected: ExpectedSolve, val found: FoundSolve) {
    val offsetSec: Double get() = found.stopSec - expected.stopSec
    /** An expected time of "" (unknown) is not checked. */
    val timeOk: Boolean get() = expected.time.isEmpty() || sameTime(expected.time, found.timeText)
}

/** The same time, however it is written: a hand-typed "12.5" and a read "12.50" are equal. */
fun sameTime(a: String, b: String): Boolean {
    val x = parseTimeSeconds(a)
    val y = parseTimeSeconds(b)
    return if (x != null && y != null) abs(x - y) < 1e-6 else a == b
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

// ---- an answer key with only the times, in order: it needs no work by hand, and nothing CubeCut found went into it ----

/**
 * The times of a list, oldest first, as the display showed them when the timer stopped. Understands csTimer's CSV export, or plain text
 * with one time per line. A +2 is taken off again and "DNF(12.25)" gives 12.25; a DNF with no time gives "" (any time fits).
 * Throws IllegalArgumentException naming the first line it can't read, so no solve is dropped silently.
 */
fun parseTimesList(text: String): List<String> {
    val rows = csvRows(text.removePrefix("\uFEFF"))
    if (rows.isEmpty()) throw IllegalArgumentException("the file is empty")
    val timeAt = rows[0].indexOf("Time")
    // P.1 is the time without its penalty, but only the first part of a solve timed in several phases (then there is a P.2)
    val rawAt = if ("P.2" in rows[0]) -1 else rows[0].indexOf("P.1")
    val body = if (timeAt >= 0) rows.drop(1) else rows
    val times = body.map { r ->
        val cell = if (timeAt >= 0) r.getOrElse(timeAt) { "" } else r[0]
        val raw = if (timeAt >= 0 && rawAt >= 0) r.getOrElse(rawAt) { "" } else ""
        if (parseTimeSeconds(raw) != null) raw.trim()
        else timeAsShown(cell) ?: throw IllegalArgumentException("can't read the time \"${cell.take(30)}\"")
    }
    if (times.isEmpty()) throw IllegalArgumentException("no times in it")
    return times
}

private fun timeAsShown(cell: String): String? {
    val c = cell.trim()
    if (c == "DNF") return ""
    if (c.startsWith("DNF(") && c.endsWith(")")) return c.substring(4, c.length - 1).takeIf { parseTimeSeconds(it) != null }
    if (c.endsWith("+")) {
        val sec = parseTimeSeconds(c.dropLast(1)) ?: return null
        return if (sec > 2.0) "%.3f".format(Locale.US, sec - 2.0) else null
    }
    return c.takeIf { parseTimeSeconds(it) != null }
}

/** Rows of a ';' file; a quoted field may hold ';', line breaks and "" (csTimer quotes comments that way). Empty lines are left out. */
private fun csvRows(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    fun endRow() {
        row.add(field.toString())
        field.setLength(0)
        if (row.any { it.isNotBlank() }) rows.add(row)
        row = ArrayList()
    }
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        when {
            quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
            quoted && ch == '"' -> quoted = false
            quoted -> field.append(ch)
            ch == '"' && field.isEmpty() -> quoted = true
            ch == ';' -> { row.add(field.toString()); field.setLength(0) }
            ch == '\n' -> endRow()
            ch != '\r' -> field.append(ch)
        }
        i++
    }
    endRow()
    return rows
}

/** How a scan did against a list of times: [wrong] pairs an expected time with the solve in its place, [missed] is (number in the list, time). */
class TimesComparison(
    val right: Int,
    val wrong: List<Pair<String, FoundSolve>>,
    val missed: List<Pair<Int, String>>,
    val falseSolves: List<FoundSolve>,
    val leftovers: List<FoundSolve>
) {
    val expectedCount: Int get() = right + wrong.size + missed.size
}

/**
 * Lines the [found] solves up with the [expected] times in order, with the fewest differences. There are no places to go by, so a missed
 * solve right next to a false one can't be told apart from a wrong time: it counts as a wrong time.
 */
fun compareTimes(expected: List<String>, found: List<FoundSolve>): TimesComparison {
    val leftovers = found.filter { !it.include && it.note.startsWith(SolveAnalyzer.LEFTOVER_NOTE_START) }
    val real = found.filter { it !in leftovers }.sortedBy { it.stopSec }
    val n = expected.size
    val m = real.size
    fun fits(e: Int, f: Int) = expected[e].isEmpty() || sameTime(expected[e], real[f].timeText)
    // an unknown time fits anything, but a little worse than a real match, so it doesn't take another solve's place
    fun pairCost(e: Int, f: Int) = if (!fits(e, f)) 10 else if (expected[e].isEmpty()) 1 else 0
    val cost = Array(n + 1) { IntArray(m + 1) }
    for (e in 0..n) for (f in 0..m) {
        var best = if (e == 0 && f == 0) 0 else Int.MAX_VALUE
        if (e > 0) best = minOf(best, cost[e - 1][f] + 10)
        if (f > 0) best = minOf(best, cost[e][f - 1] + 10)
        if (e > 0 && f > 0) best = minOf(best, cost[e - 1][f - 1] + pairCost(e - 1, f - 1))
        cost[e][f] = best
    }
    var right = 0
    val wrong = ArrayList<Pair<String, FoundSolve>>()
    val missed = ArrayList<Pair<Int, String>>()
    val extra = ArrayList<FoundSolve>()
    var e = n
    var f = m
    while (e > 0 || f > 0) {
        if (e > 0 && f > 0 && cost[e][f] == cost[e - 1][f - 1] + pairCost(e - 1, f - 1)) {
            e--
            f--
            if (fits(e, f)) right++ else wrong.add(0, Pair(expected[e], real[f]))
        } else if (e > 0 && cost[e][f] == cost[e - 1][f] + 10) {
            e--
            missed.add(0, Pair(e + 1, expected[e]))
        } else {
            f--
            extra.add(0, real[f])
        }
    }
    return TimesComparison(right, wrong, missed, extra, leftovers)
}

// ---- grading the reader by itself: the answer key says what the display shows right after each stop ----

/** Where after a stop the display is read, in seconds: the time is still held there, because the scan itself needs about 1.5 s of hold. */
private val READ_AFTER_STOP = doubleArrayOf(0.1, 0.3, 0.5, 0.7, 0.9, 1.1)

/** What the reader made of one solve's held time: how many frames it read right, wrong ([wrong] = what it read) and not at all. */
class ReadGrade(val expected: ExpectedSolve, val right: Int, val wrong: List<String>, val unreadable: Int)

/**
 * Reads the display a few times after every expected stop and compares it with the expected time, so a failed scan can be told
 * apart from a failed reader. A solve with no known time is skipped. A zero means the display was already reset: the rest of that solve
 * is left out instead of being counted as wrong.
 */
fun gradeReading(expected: List<ExpectedSolve>, decimals: Int, durationSec: Double, read: (Double) -> String?): List<ReadGrade> =
    expected.filter { it.time.isNotEmpty() }.map { e ->
        var right = 0
        var unreadable = 0
        val wrong = ArrayList<String>()
        for (after in READ_AFTER_STOP) {
            val t = e.stopSec + after
            if (t > durationSec) break
            val digits = read(t)
            if (digits == null) {
                unreadable++
                continue
            }
            if (digits.all { it == '0' }) break
            val text = TimerVision.formatDigits(digits, decimals)
            if (sameTime(text, e.time)) right++ else wrong.add(text)
        }
        ReadGrade(e, right, wrong, unreadable)
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
 * The scan report as plain text, short enough to paste into a chat or into docs/algorithms.md § 8. Its three stages depend on each
 * other: finding the timer ([timerOk], [timer] = what was said about it), reading ([reading]), scan ([c]).
 * [seconds] is how long the scan took, [readable] the share of sampled frames the reader could read (0..1), [app] the app version.
 * Without a timer nothing can be read or found: that is a result too (0 found), so the report says it instead of not running.
 */
fun scanReportText(
    truth: GroundTruth, videoName: String, timerOk: Boolean, timer: String, reading: List<ReadGrade>,
    c: ScanComparison, seconds: Double, readable: Double, app: String
): String {
    val lines = reportStart(truth, videoName, timerOk, timer)
    if (!timerOk) return noTimerReport(lines, c.expectedCount, app)
    val right = reading.sumOf { it.right }
    val wrong = reading.sumOf { it.wrong.size }
    val unreadable = reading.sumOf { it.unreadable }
    lines.add("2. Reading: $right right, $wrong wrong, $unreadable unreadable of ${right + wrong + unreadable} held frames")
    for (g in reading.filter { it.wrong.isNotEmpty() }) {
        val seen = g.wrong.groupingBy { it }.eachCount().entries.joinToString(", ") { (t, n) -> if (n == 1) t else "$t (${n}x)" }
        lines.add("Misread: ${videoPlace(g.expected.stopSec)} ${g.expected.time} as $seen")
    }
    for (g in reading.filter { it.right == 0 && it.wrong.isEmpty() && it.unreadable > 0 }) {
        lines.add("Never read: ${videoPlace(g.expected.stopSec)} ${g.expected.time}")
    }
    lines.add("3. Scan:")
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

private fun reportStart(truth: GroundTruth, videoName: String, timerOk: Boolean, timer: String): ArrayList<String> {
    val lines = ArrayList<String>()
    lines.add("Scan report: $videoName" + (if (truth.timer.isNotEmpty()) " (${truth.timer})" else ""))
    if (truth.video.isNotEmpty() && truth.video != videoName) lines.add("WARNING: the expected solves are for ${truth.video}")
    lines.add("1. Timer: " + (if (timerOk) "" else "NOT FOUND. ") + timer)
    return lines
}

private fun noTimerReport(lines: MutableList<String>, expected: Int, app: String): String {
    lines.add("2. Reading: not tested")
    lines.add("3. Scan: not tested. Expected $expected, found 0, missed $expected")
    lines.add("CubeCut $app")
    return lines.joinToString("\n")
}

/** The same report for an answer key with only times: the reading and the cuts can't be graded, because no place in the video is known. */
fun timesReportText(
    truth: GroundTruth, videoName: String, timerOk: Boolean, timer: String, c: TimesComparison, seconds: Double, readable: Double, app: String
): String {
    val lines = reportStart(truth, videoName, timerOk, timer)
    if (!timerOk) return noTimerReport(lines, c.expectedCount, app)
    lines.add("2. Reading: not graded (list of times only)")
    lines.add("3. Scan against the list of times (cuts not graded):")
    lines.add(
        "Expected ${c.expectedCount}, found ${c.right + c.wrong.size}, missed ${c.missed.size}, " +
                "false ${c.falseSolves.size}, wrong time ${c.wrong.size}"
    )
    lines.add("Readable ${Math.round(readable * 100)}%, took ${"%.1f".format(seconds)} s, CubeCut $app")
    if (c.leftovers.isNotEmpty()) lines.add("Left-over at the start (switched off): ${c.leftovers.size}")
    for ((number, time) in c.missed) lines.add("Missed: no. $number, ${time.ifEmpty { "DNF" }}")
    for (f in c.falseSolves) lines.add("False: ${videoPlace(f.stopSec)} ${f.timeText}")
    for ((time, f) in c.wrong) lines.add("Wrong time: ${videoPlace(f.stopSec)} expected $time, read ${f.timeText}")
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
        .put("setup", t.setup?.let { setupToJson(it) })       // null leaves it out
        .toString(2)
}

private fun setupToJson(s: SetupAttempt): JSONObject {
    val corners = JSONArray()
    for (v in s.corners) corners.put(Math.round(v * 10) / 10.0)
    return JSONObject().put("frameMs", s.frameMs).put("typed", s.typed).put("corners", corners)
}

private fun setupFromJson(o: JSONObject): SetupAttempt? {
    val arr = o.optJSONArray("corners") ?: return null
    val corners = (0 until arr.length()).map { arr.optDouble(it, Double.NaN) }
    val typed = o.optString("typed", "")
    return if (corners.size == 8 && corners.none { it.isNaN() } && typed.isNotEmpty()) SetupAttempt(o.optLong("frameMs", 0L), corners, typed) else null
}

/** One setup attempt as text, for the debug build's memory of a refused setup (see ScanReport.kt). */
fun setupAttemptToJson(s: SetupAttempt): String = setupToJson(s).toString()

/** Null if [text] isn't a setup attempt. */
fun parseSetupAttempt(text: String): SetupAttempt? = try { setupFromJson(JSONObject(text)) } catch (e: Exception) { null }

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
    return GroundTruth(o.optString("video", ""), o.optString("timer", ""), o.optDouble("durationSec", 0.0), solves.sortedBy { it.stopSec }, o.optString("note", ""), o.optJSONObject("setup")?.let { setupFromJson(it) })
}
