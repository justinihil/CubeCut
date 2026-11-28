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

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * (3.3-C) The app's own finder and scan on the whole reference videos, on the PC that has them (docs/algorithms.md § 8). It gets the video and
 * its length, nothing else. Slow (ffmpeg starts once per picture), so it only runs when asked: set CUBECUT_REF_VIDEOS=1, or to a part of
 * the video names ("4x4"). Else skipped. A video takes part when it has a .places.txt beside it.
 */
class ReferenceVideoTest {

    private val dir = File(System.getenv("CUBECUT_REF") ?: "D:/CubeCutRef")

    /**
     * Shown by the timer, but left out of the keys: a test tap (csTimer 03), a solve deleted on the screen (other timer 01), and a time from
     * before the video that the camera only sees after 4 s (csTimer "digital 4"; it is flagged).
     */
    private val LEFT_OUT = setOf("0.34", "7.480", "13.262")

    /** The fonts, without the ones that look like csTimer's, or like flowtimer's (the other timer). */
    private fun fonts(video: File): DigitShapes =
        if ("othertimer" in video.name || "flowtimer" in video.name) DrawnTime.without("robotomono", "jetbrainsmono", "roboto") else DrawnTime.without("arimo")

    /** What the scan saw goes to a file, for MissedSolvesTest. */
    private fun writeTrace(video: File, scan: ScanResult) {
        // (MissedSolvesTest reads the traces of the real steps: a run with its steps moved writes elsewhere)
        val folder = if ((System.getenv("CUBECUT_REF_NEAR")?.toIntOrNull() ?: 0) != 0) "traces-near" else "traces"
        File(dir, folder).mkdirs()
        File(dir, "$folder/" + video.name.replace(".mp4", ".trace.txt")).printWriter().use { w ->
            w.println("D ${scan.trace.durationSec}")
            for (x in scan.trace.seen) w.println("S ${x.t} ${x.text} ${x.readable}")
            for (x in scan.trace.running) w.println("R ${x.t} ${x.seconds}")
            for (x in scan.solves) w.println("F ${x.stopSec} ${x.timeText} ${x.include}")
        }
    }

    private fun places(video: File) = File(dir, video.name.replace(".mp4", ".places.txt"))

    private fun videos(): List<File> {
        val wanted = System.getenv("CUBECUT_REF_VIDEOS").orEmpty()
        assumeTrue("set CUBECUT_REF_VIDEOS=1 to run", wanted.isNotEmpty())
        val videos = dir.listFiles { f -> f.name.startsWith("ref_") && f.name.endsWith(".mp4") && places(f).exists() }.orEmpty()
            .filter { wanted == "1" || wanted in it.name }.sortedBy { it.name }
        assumeTrue("no reference videos on this PC", videos.isNotEmpty())
        return videos
    }

    @Test
    fun findsLearnsAndScansWithoutBeingToldAnything() {
        val problems = ArrayList<String>()
        for (v in videos()) {
            val video = FfmpegVideo(v)
            val started = System.nanoTime()
            val r = findTimer({ video.at(it) }, video.duration, fonts(v), otherKind = { false })
            val finding = (System.nanoTime() - started - video.nanos) / 1e9
            println("FINDER ${v.name}: ${if (r.timer != null) "FOUND" else "NOT FOUND"}; ${r.places} places, ${r.wholeFrames} whole frames, ${r.areaReads} area reads; ${"%.1f".format(finding)} s without getting pictures")
            for (n in r.notes) println("   $n")
            val timer = r.timer
            if (timer == null) { problems.add("${v.name}: not found"); continue }
            println("   ${timer.area}, digits ${timer.digitHeight.toInt()} px, decimals ${timer.runningDecimals} running, ${timer.stoppedDecimals} stopped, learned ${timer.examples.isLearned}, froze at ${r.frozen}")
            video.nanos = 0
            video.stepsNear = true
            val scanning = System.nanoTime()
            var reads = 0
            val scan = if (timer.held) scanHeldTimer(
                { t -> reads++; video.at(t)?.let { timer.readHeld(it) } }, video.duration, video.frameSec, { t -> video.frameTime(t) }
            ) else scanFoundTimer(
                { t -> reads++; video.at(t)?.let { timer.read(it)?.text } },
                video.duration, timer.runningDecimals, timer.stoppedDecimals, video.frameSec, { t -> video.frameTime(t) },
                exact = { video.exact = it }
            )
            val seconds = (System.nanoTime() - scanning - video.nanos) / 1e9
            writeTrace(v, scan)
            val key = places(v).readLines().filter { !it.startsWith("#") && it.isNotBlank() }
                .map { l -> l.split(" ").let { ExpectedSolve(it[1].toDouble(), it[0]) } }
            val c = compareScan(key, scan.solves.filter { it.timeText !in LEFT_OUT })
            println(
                "SCAN ${v.name}: expected ${c.expectedCount}, found ${c.matches.size}, missed ${c.missed.size}, false ${c.falseSolves.size}, wrong time ${c.wrongTime.size}; " +
                        "${c.onFrame} on the frame, ${c.close} within 0.1 s, worst ${"%.3f".format(c.worstOffsetSec)} s; " +
                        "readable ${(scan.readableFraction * 100).toInt()}%; $reads reads, ${"%.1f".format(seconds)} s without getting pictures; steps ${video.shift} frames off"
            )
            for (f in scan.solves.filter { it.note.isNotEmpty() }) println("   flagged: ${videoPlace(f.stopSec)} ${f.timeText}: ${f.note}")
            for (h in hintsOf(scan)) println("   hint: ${videoPlace(h.fromSec)} - ${videoPlace(h.toSec)} ${h.text}")
            for (f in c.falseSolves) println("   false: ${videoPlace(f.stopSec)} ${f.timeText}")
            for (f in scan.solves.filter { it.timeText in LEFT_OUT }) println("   left out: ${videoPlace(f.stopSec)} ${f.timeText}")
            for (m in c.missed) println("   missed: ${videoPlace(m.stopSec)} ${m.time}")
            for (m in c.wrongTime) println("   wrong: ${m.expected.time} as ${m.found.timeText}")
            for (m in c.matches.filter { abs(it.offsetSec) > CUT_FRAME_SEC }) println("   cut off: ${m.expected.time} by ${"%+.3f".format(m.offsetSec)} s")
            if (c.missed.isNotEmpty() || c.falseSolves.isNotEmpty() || c.wrongTime.isNotEmpty() || c.close < c.matches.size) problems.add(v.name)
            video.close()
        }
        assertTrue("$problems", problems.isEmpty())
    }

    /**
     * csTimer's videos as if it didn't show its running time, or only whole seconds: the reader's running times are thrown away, or cut off.
     * (What csTimer writes instead, "solve", is not in the picture.) The videos with a seven-segment timer must have no such timer.
     */
    @Test
    fun findsTimersByTheirFinalTimes() {
        val problems = ArrayList<String>()
        val wanted = System.getenv("CUBECUT_REF_VIDEOS").orEmpty()
        assumeTrue("set CUBECUT_REF_VIDEOS=1 to run", wanted.isNotEmpty())
        val all = dir.listFiles { f -> f.name.endsWith(".mp4") && ("cstimer_old" in f.name || "qiyi" in f.name) && (wanted == "1" || wanted in f.name) }
        for (v in all.orEmpty().sortedBy { it.name }) {
            val video = FfmpegVideo(v)
            val qiyi = "qiyi" in v.name
            // the places of the search: spread over the video, halving the gaps; half size first, then full size
            val seen = ArrayList<Sighting>()
            for (shrink in listOf(2, 1)) for (i in 0 until (if (qiyi) 96 else if (shrink == 2) 40 else 16)) {
                val n = if (qiyi) 96 else if (shrink == 2) 40 else 16
                for (k in 0 until 4) {
                    val t = Math.round(video.duration * (2 * i + 1) / (2 * n) * 2) / 2.0 + 0.5 * k
                    val rows = findTimeRows(video.at(t) ?: break, fonts(v), listOf(false, true), shrink = shrink)
                    if (rows.isEmpty()) break
                    seen.addAll(sightingsOf(t, rows.filter { qiyi || it.decimals == 2 }))
                }
            }
            val notes = ArrayList<String>()
            val timer = findHeldTimer(seen, { video.at(it) }, fonts(v), notes)
            println("HELD ${v.name}: ${if (timer == null) "none" else "${timer.numbers}, digits ${timer.digitHeight.toInt()} px"} $notes")
            if ((timer == null) != qiyi) problems.add("${v.name}: $notes")
            val looked = HashMap<Double, Pair<String?, Int?>?>()         // (getting a picture is slow, and both ways ask for mostly the same)
            if (timer != null && !qiyi) for (wholeSeconds in listOf(false, true)) {
                val scan = scanHeldTimer(
                    { t ->
                        looked.getOrPut(t) {
                            video.at(t)?.let { p ->
                                val text = timer.readHeld(p).text
                                Pair(text, if (text == null) timer.read(p)?.takeIf { it.decimals == 1 }?.seconds?.toInt() else null)
                            }
                        }?.let { (text, running) -> HeldShown(text, if (wholeSeconds) running else null) }
                    }, video.duration, video.frameSec, { t -> video.frameTime(t) }
                )
                val key = places(v).readLines().filter { !it.startsWith("#") && it.isNotBlank() }.map { l -> l.split(" ").let { ExpectedSolve(it[1].toDouble(), it[0]) } }
                val c = compareScan(key, scan.solves.filter { it.include && it.timeText !in LEFT_OUT })
                println(
                    "HELD ${v.name} ${if (wholeSeconds) "whole seconds" else "hidden"}: expected ${c.expectedCount}, found ${c.matches.size}, false ${c.falseSolves.map { it.timeText }}, " +
                            "wrong time ${c.wrongTime.map { "${it.expected.time} as ${it.found.timeText}" }}; missed ${c.missed.map { it.time }}; ${c.onFrame} on the frame, worst ${"%.3f".format(c.worstOffsetSec)} s; " +
                            "flagged ${scan.solves.filter { it.note.isNotEmpty() }.map { it.timeText + " " + it.note }}; hints ${hintsOf(scan).map { videoPlace(it.fromSec) + " " + it.text }}"
                )
                if (c.matches.size < c.expectedCount || c.falseSolves.isNotEmpty() || c.wrongTime.isNotEmpty()) problems.add(v.name)
            }
            video.close()
        }
        assertTrue("$problems", problems.isEmpty())
    }

    /**
     * Seven-segment timers that show only their final times (csTimer's "digital 4" with "solve" while it runs), found as the app does when
     * nothing else was, and scanned against the keys. The other videos must give none; csTimer's "digital 1" may (the solid reader takes it first).
     * The small real QiYi on the desk of the 4x4 and old csTimer videos (digits under 30 px) may be found too: it shows the same solves, and in
     * the app csTimer's running time is seen first, so this is never asked there.
     */
    @Test
    fun findsSevenSegmentTimersByTheirFinalTimes() {
        val problems = ArrayList<String>()
        for (v in videos()) {
            val video = FfmpegVideo(v)
            val r = findSegments({ video.at(it) }, video.duration)
            val notes = ArrayList<String>()
            val grid = if (r.found == null) findHeldSegments(r.held, { video.at(it) }, notes) else null
            println(
                "SEGHELD ${v.name}: ${if (r.found != null) "RUNNING found" else if (grid == null) "none" else "FOUND, digits ${grid.height.toInt()} px, ${grid.decimals} decimals, ${grid.box(grid.decimals + 2)}"}; " +
                        "${r.held.size} final times read on the way; $notes; ${"%.1f".format(r.rowsSec)} s rows, ${"%.1f".format(r.checkSec)} s checks"
            )
            val wanted = "digital4" in v.name
            val deskQiYi = ("4x4" in v.name || "cstimer_old" in v.name) && ((r.found?.grid ?: grid)?.height ?: 99.0) < 30
            if ((grid != null || r.found != null) != wanted && "digital1" !in v.name && !deskQiYi) problems.add("${v.name}: ${if (wanted) "not found" else "found"}")
            if (grid != null && !deskQiYi) {
                val scan = scanHeldTimer({ t -> video.at(t)?.let { HeldShown(heldSegmentRow(grid, it)?.first?.text, null) } }, video.duration, video.frameSec, { t -> video.frameTime(t) })
                writeTrace(v, scan)
                val key = places(v).readLines().filter { !it.startsWith("#") && it.isNotBlank() }.map { l -> l.split(" ").let { ExpectedSolve(it[1].toDouble(), it[0]) } }
                val c = compareScan(key, scan.solves.filter { it.include && it.timeText !in LEFT_OUT })
                println(
                    "SEGHELD SCAN ${v.name}: expected ${c.expectedCount}, found ${c.matches.size}, false ${c.falseSolves.map { videoPlace(it.stopSec) + " " + it.timeText }}, " +
                            "wrong time ${c.wrongTime.map { "${it.expected.time} as ${it.found.timeText}" }}; missed ${c.missed.map { it.time }}; ${c.onFrame} on the frame, worst ${"%.3f".format(c.worstOffsetSec)} s; " +
                            "flagged ${scan.solves.filter { it.note.isNotEmpty() }.map { it.timeText + " " + it.note }}; left out ${scan.solves.filter { !it.include }.map { it.timeText }}"
                )
                for (m in c.matches.filter { abs(it.offsetSec) > CUT_FRAME_SEC }) println("   cut off: ${m.expected.time} by ${"%+.3f".format(m.offsetSec)} s")
                if (c.matches.size < c.expectedCount || c.falseSolves.isNotEmpty() || c.wrongTime.isNotEmpty() || c.close < c.matches.size) problems.add(v.name)
            }
            video.close()
        }
        assertTrue("$problems", problems.isEmpty())
    }

    /** The seven-segment finder: the QiYi videos have such a timer. The 4x4 videos have a small real one in view, which may be found too; all others must give nothing. */
    @Test
    fun findsOnlyTheSevenSegmentTimers() {
        val wanted = System.getenv("CUBECUT_REF_VIDEOS").orEmpty()
        assumeTrue("set CUBECUT_REF_VIDEOS=1 to run", wanted.isNotEmpty())
        val problems = ArrayList<String>()
        for (v in dir.listFiles { f -> f.name.startsWith("ref_") && f.name.endsWith(".mp4") && (wanted == "1" || wanted in f.name) }.orEmpty().sortedBy { it.name }) {
            val video = FfmpegVideo(v)
            val r = findSegments({ video.at(it) }, video.duration)
            val f = r.found
            println(
                "SEGMENTS ${v.name}: ${if (f == null) "none" else "at ${f.t} s, ${f.grid.box(f.grid.decimals + 2)}, digits ${f.grid.height.toInt()} px, shown ${f.shown}, fit ${"%.3f".format(f.fit)}"}; " +
                        "${r.places} places, ${r.checked} rows checked; seconds: ${"%.1f".format(r.rowsSec)} rows, ${"%.1f".format(r.checkSec)} checks, ${"%.1f".format(r.fitSec)} fit, ${"%.1f".format(video.nanos / 1e9)} pictures"
            )
            if (f != null) println("   slope ${f.grid.slope} lean ${f.grid.lean} pitch/h ${f.grid.pitch / f.grid.height} gap/h ${f.grid.gap / f.grid.height} w/h ${f.grid.digitWidth / f.grid.height} decimals ${f.grid.decimals}")
            if ("4x4" !in v.name && (f != null) != ("qiyi" in v.name)) problems.add(v.name)
            video.close()
        }
        assertTrue("$problems", problems.isEmpty())
    }

    /** The seven-segment timers found, followed and scanned, against the keys (.frames.txt: time, first frame in seconds; "hint" = reset at once, a hint will do). */
    @Test
    fun scansTheSevenSegmentTimers() {
        val wanted = System.getenv("CUBECUT_REF_VIDEOS").orEmpty()
        assumeTrue("set CUBECUT_REF_VIDEOS=1 to run", wanted.isNotEmpty())
        fun key(v: File) = File(dir, v.name.replace(".mp4", ".frames.txt"))
        val videos = dir.listFiles { f -> f.name.endsWith(".mp4") && key(f).exists() && (wanted == "1" || wanted in f.name) }.orEmpty().sortedBy { it.name }
        assumeTrue("no such videos", videos.isNotEmpty())
        val problems = ArrayList<String>()
        for (v in videos) {
            val video = FfmpegVideo(v)
            val found = findSegments({ video.at(it) }, video.duration).found
            if (found == null) { problems.add("${v.name}: not found"); continue }
            video.nanos = 0
            val following = System.nanoTime()
            val timer = SegmentTracker.follow(found, { video.at(it) }, video.duration)
            val followSec = (System.nanoTime() - following - video.nanos) / 1e9
            video.stepsNear = true
            println("TRACK ${v.name}: from ${found.t} s; refined with ${timer.refinedWith}, fit ${"%.3f".format(timer.fit)} (${"%.3f".format(found.fit)} where found); ${"%.1f".format(followSec)} s without getting pictures")
            video.nanos = 0
            val scanning = System.nanoTime()
            val scan = scanSegments(timer, video.duration, video.frameSec, { t -> video.frameTime(t) }, exact = { video.exact = it })
            val seconds = (System.nanoTime() - scanning - video.nanos) / 1e9
            writeTrace(v, scan)
            val lines = key(v).readLines().filter { it.isNotBlank() && it[0].isDigit() }.map { it.split(" ") }
            val expected = lines.map { ExpectedSolve(it[1].toDouble(), it[0]) }
            val hintWillDo = lines.filter { it.getOrNull(3) == "hint" }.map { it[0] }
            val c = compareScan(expected, scan.solves.filter { it.include })
            val hints = hintsOf(scan)
            val missed = c.missed.filter { m -> m.time !in hintWillDo || hints.none { m.stopSec > it.fromSec && m.stopSec <= it.toSec + 1 } }
            println(
                "SCAN ${v.name}: expected ${c.expectedCount}, found ${c.matches.size}, missed ${c.missed.size}, false ${c.falseSolves.size}, wrong time ${c.wrongTime.size}; " +
                        "${c.onFrame} on the frame, ${c.matches.count { abs(it.offsetSec) <= 0.05 }} within 0.05 s, ${c.close} within 0.1 s, worst ${"%.3f".format(c.worstOffsetSec)} s; " +
                        "readable ${(scan.readableFraction * 100).toInt()}%; moved ${timer.moved()}; ${timer.reads} reads, ${timer.farLooks} far looks, ${timer.wholeAgain} whole again, ${"%.1f".format(seconds)} s without getting pictures; steps ${video.shift} frames off"
            )
            for (f in scan.solves.filter { it.note.isNotEmpty() }) println("   flagged: ${videoPlace(f.stopSec)} ${f.timeText}: ${f.note}")
            for (f in c.falseSolves) println("   false: ${videoPlace(f.stopSec)} ${f.timeText}")
            for (m in c.missed) println("   missed: ${videoPlace(m.stopSec)} ${m.time}${if (m in missed) "" else " (a hint tells)"}")
            for (h in hints) println("   hint: ${videoPlace(h.fromSec)} - ${videoPlace(h.toSec)} ${h.text}")
            for (m in c.wrongTime) println("   wrong: ${m.expected.time} as ${m.found.timeText}")
            for (m in c.matches.filter { abs(it.offsetSec) > 0.05 }) println("   cut off: ${m.expected.time} by ${"%+.3f".format(m.offsetSec)} s")
            if (missed.isNotEmpty() || c.falseSolves.isNotEmpty() || c.wrongTime.isNotEmpty() || c.close < c.matches.size) problems.add(v.name)
            video.close()
        }
        assertTrue("$problems", problems.isEmpty())
    }

    /**
     * (4.0) Find solves with the timer of the video before as a hint (TimerHint): CUBECUT_REF_HINT_FROM is that video (a path), the
     * videos are CUBECUT_REF_VIDEOS in CUBECUT_REF. Each video is found and scanned the usual way and with the hint: the solves must be
     * exactly the same; the lines tell how much searching the hint saved. A hint of another kind, or from elsewhere, must change nothing.
     */
    @Test
    fun findsWhereTheTimerOfTheVideoBeforeWas() {
        val from = System.getenv("CUBECUT_REF_HINT_FROM").orEmpty()
        assumeTrue("set CUBECUT_REF_HINT_FROM to run", from.isNotEmpty())
        val wanted = System.getenv("CUBECUT_REF_VIDEOS").orEmpty()
        assumeTrue("set CUBECUT_REF_VIDEOS to run", wanted.isNotEmpty())
        // the hint: where the timer of that video is, found as Find solves would
        val src = FfmpegVideo(File(from))
        val size = src.at(src.duration / 2)!!.let { Pair(it.w, it.h) }
        val srcSeg = findSegments({ src.at(it) }, src.duration).found
        val hint: TimerHint = if (srcSeg != null) {
            TimerHint.Segments(SegmentTracker.follow(srcSeg, { src.at(it) }, src.duration).grid, size.first, size.second)
        } else {
            val t = findTimer({ src.at(it) }, src.duration, fonts(File(from))).timer!!
            // CUBECUT_REF_HINT_DIGITS_FROM: the digits learned in another video (another timer in the same place: they must not be used)
            val digitsFrom = System.getenv("CUBECUT_REF_HINT_DIGITS_FROM").orEmpty()
            val examples = if (digitsFrom.isEmpty()) t.examples.takeIf { it.isLearned } else {
                val o = FfmpegVideo(File(digitsFrom))
                findTimer({ o.at(it) }, o.duration, fonts(File(digitsFrom))).timer!!.examples.also { o.close() }.takeIf { it.isLearned }
            }
            println("HINT digits: ${if (examples == null) "none" else if (digitsFrom.isEmpty()) "learned there" else "learned in ${File(digitsFrom).name}"}")
            TimerHint.Solid(t.numbers, t.digitHeight, size.first, size.second, examples)
        }
        src.close()
        println("HINT from ${File(from).name}: ${if (hint is TimerHint.Solid) "solid ${hint.numbers}" else "seven segments"}")
        val problems = ArrayList<String>()
        val targets = dir.listFiles { f -> f.name.endsWith(".mp4") && (wanted == "1" || wanted in f.name) }.orEmpty().sortedBy { it.name }
        for (v in targets) {
            val runs = listOf<TimerHint?>(null, hint).map { h ->
                val video = FfmpegVideo(v)
                val started = System.nanoTime()
                var seg = (h as? TimerHint.Segments)?.let { findSegmentsNear({ video.at(it) }, video.duration, it) }
                val how: String
                val solves: List<FoundSolve>
                if (seg == null) {
                    var segments: FoundSegments? = null
                    val r = findTimer({ video.at(it) }, video.duration, fonts(v), otherKind = {
                        segments = findSegments({ video.at(it) }, video.duration).found
                        segments != null
                    }, hint = h as? TimerHint.Solid)
                    seg = segments
                    how = "${r.places} places, ${r.wholeFrames} whole pictures, ${r.areaReads} area reads, from hint ${r.fromHint}, digits ${r.digits}; " +
                            r.notes.filter { it.startsWith("Learned") }.joinToString()
                    solves = if (seg != null) emptyList() else r.timer?.let { t ->
                        scanFoundTimer({ video.at(it)?.let { p -> t.read(p)?.text } }, video.duration, t.runningDecimals, t.stoppedDecimals, video.frameSec, { video.frameTime(it) }).solves
                    } ?: emptyList()
                } else {
                    how = "seven segments where the hint was"
                    solves = emptyList()
                }
                val found = seg
                val all = if (found != null) {
                    val timer = SegmentTracker.follow(found, { video.at(it) }, video.duration)
                    scanSegments(timer, video.duration, video.frameSec, { video.frameTime(it) }).solves
                } else solves
                val sec = (System.nanoTime() - started - video.nanos) / 1e9
                println("HINTED ${v.name} ${if (h == null) "usual" else "with hint"}: $how; ${"%.1f".format(sec)} s without getting pictures; " +
                        "${"%.1f".format(video.nanos / 1e9)} s getting them; solves ${all.map { "%.3f %s".format(Locale.ROOT, it.stopSec, it.timeText) }}")
                video.close()
                all.map { Triple(Math.round(it.stopSec * 1000), it.timeText, it.note) }
            }
            if (runs[0] != runs[1]) problems.add("${v.name}: the hint changed the solves")
        }
        assertTrue("$problems", problems.isEmpty())
    }

    /**
     * (4.0) What finding the timer costs, as Find solves does it (analyzeByItself, AutoScan.kt): the last video's timer first (F0), the
     * usual search with the other kinds after 40 + 16 places (F1-F7), then the scan. CUBECUT_MEASURE is the video files in CUBECUT_REF in
     * the order of the batch, separated by "|". Each video is measured alone (no timer before it), then the batch in that order with the
     * timer of the video before, as the app's queue keeps it. The fonts are all the app's. Prints the places and pictures (whole and area,
     * the phone's decoder cost) and the seconds without getting pictures; measures only, checks nothing. CUBECUT_MEASURE_SCAN=0 skips the scans.
     */
    @Test
    fun measuresFindingLikeTheApp() {
        val list = System.getenv("CUBECUT_MEASURE").orEmpty()
        assumeTrue("set CUBECUT_MEASURE to run", list.isNotEmpty())
        val scanToo = System.getenv("CUBECUT_MEASURE_SCAN") != "0"
        val fonts = DigitShapes.from(DrawnTime.styles)
        val files = list.split('|').map { File(dir, it.trim()) }
        for (f in files) assertTrue("missing ${f.name}", f.exists())
        // CUBECUT_MEASURE_BATCH=0: each video alone only
        val modes = if (System.getenv("CUBECUT_MEASURE_BATCH") == "0") listOf("ALONE") else listOf("ALONE", "BATCH")
        for (mode in modes) {
            var last: TimerHint? = null
            for (v in files) {
                val hint = if (mode == "BATCH") last else null
                val video = FfmpegVideo(v)
                var whole = 0
                var areas = 0
                val pic = object : PicAt {
                    override fun at(t: Double): GrayPic? { whole++; return video.at(t) }
                    override fun area(t: Double, area: Area): AreaPic? { areas++; return video.at(t)?.let { AreaPic.of(it, area) } }
                }
                val started = System.nanoTime()
                var segPlaces = 0
                var segments: FoundSegments? = null
                var segmentTimes: List<Pair<Sighting, SegmentGrid>> = emptyList()
                var fromHint = false
                if (hint is TimerHint.Segments) findSegmentsNear(pic, video.duration, hint)?.let { segments = it; fromHint = true }
                // each part: wall seconds without getting pictures, and whole pictures (F1-F3 | F4 | F5-F7 solid | F7 seven segments)
                val parts = ArrayList<String>()
                var partStart = System.nanoTime()
                var partNanos = video.nanos
                var partWhole = 0
                fun endPart(name: String) {
                    val now = System.nanoTime()
                    parts.add("$name ${"%.1f".format((now - partStart - (video.nanos - partNanos)) / 1e9)} s / ${whole - partWhole} pictures")
                    partStart = now; partNanos = video.nanos; partWhole = whole
                }
                var segDetail = ""
                val found = if (segments != null) null else findTimer(pic, video.duration, fonts, null, otherKind = {
                    endPart("F0-F3")
                    val r = findSegments(pic, video.duration)
                    segPlaces = r.places
                    segments = r.found
                    segmentTimes = r.held
                    segDetail = "rows ${"%.1f".format(r.rowsSec)} s, checks ${"%.1f".format(r.checkSec)} s (${r.checked} rows), ${r.held.size} final-time readings"
                    endPart("F4")
                    segments != null
                }, hint = hint as? TimerHint.Solid)
                endPart(if (segDetail.isEmpty()) "F0-F2" else "F5-F7 solid")
                if (found?.fromHint == true) fromHint = true
                // the last kind: a seven-segment timer by its final times (as analyzeByItself, after the search)
                var heldGrid: SegmentGrid? = null
                if (segments == null && found!!.timer == null && found.choices.isEmpty()) {
                    heldGrid = findHeldSegments(segmentTimes, pic)
                    endPart("F7 seven segments")
                }
                val findSec = (System.nanoTime() - started - video.nanos) / 1e9
                val findWhole = whole
                val findAreas = areas
                val s = segments
                val kind = when {
                    s != null -> "seven segments" + if (fromHint) " (where the last video's was)" else ""
                    heldGrid != null -> "seven segments by final times"
                    found!!.timer?.held == true -> "solid by final times"
                    found.timer != null -> "solid running" + if (fromHint) " (where the last video's was)" else ""
                    found.choices.isNotEmpty() -> "ASKS which number"
                    else -> "NOT FOUND"
                }
                val solidPlaces = found?.places ?: 0
                println("MEASURE $mode ${v.name}: $kind; finding ${"%.1f".format(findSec)} s without getting pictures, $findWhole whole pictures, " +
                        "$findAreas area reads; solid places $solidPlaces, segment places $segPlaces, hint ${if (hint == null) "none" else hint.javaClass.simpleName}")
                println("   parts: ${parts.joinToString(" | ")}" + if (segDetail.isEmpty()) "" else "; F4: $segDetail")
                found?.notes?.forEach { println("   $it") }
                // the scan, and the timer the next video starts from (only a running timer gives one, as in the app)
                var scan: ScanResult? = null
                var next: TimerHint? = null
                if (s != null) {
                    val timer = SegmentTracker.follow(s, pic, video.duration)
                    next = TimerHint.Segments(timer.grid, video.at(video.duration / 2)!!.w, video.at(video.duration / 2)!!.h)
                    if (scanToo) scan = scanSegments(timer, video.duration, video.frameSec, { video.frameTime(it) })
                } else if (heldGrid != null) {
                    if (scanToo) scan = scanHeldTimer({ t -> pic.at(t)?.let { HeldShown(heldSegmentRow(heldGrid, it)?.first?.text, null) } }, video.duration, video.frameSec, { video.frameTime(it) })
                } else found!!.timer?.let { timer ->
                    if (!timer.held) {
                        val p = video.at(video.duration / 2)!!
                        next = TimerHint.Solid(timer.numbers, timer.digitHeight, p.w, p.h, timer.examples.takeIf { it.isLearned })
                    }
                    if (scanToo) scan = if (timer.held) scanHeldTimer({ t -> pic.area(t, timer.area)?.let { timer.readHeld(it) } }, video.duration, video.frameSec, { video.frameTime(it) })
                    else scanFoundTimer({ t -> pic.area(t, timer.area)?.let { timer.read(it)?.text } }, video.duration, timer.runningDecimals, timer.stoppedDecimals, video.frameSec, { video.frameTime(it) })
                }
                val totalSec = (System.nanoTime() - started - video.nanos) / 1e9
                scan?.let { sc ->
                    println("   scan: ${sc.solves.size} solves ${sc.solves.map { "%.3f %s%s".format(Locale.ROOT, it.stopSec, it.timeText, if (it.note.isEmpty()) "" else " (check)") }}; " +
                            "${whole - findWhole} whole pictures, ${areas - findAreas} area reads; all ${"%.1f".format(totalSec)} s without getting pictures")
                }
                if (next != null) last = next
                video.close()
            }
        }
    }

    /** The whole chain on the first video, with the picture moved after a third and again after two thirds of it. */
    @Test
    fun smallCameraMoves() {
        val v = videos().first { it.name.startsWith("ref_cstimer") }
        val key = places(v).readLines().filter { !it.startsWith("#") && it.isNotBlank() }
            .map { l -> l.split(" ").let { ExpectedSolve(it[1].toDouble(), it[0]) } }
        // per stage: shift right, shift down (pixels of a 1080 x 1920 frame), zoom, turn (degrees)
        val cases = mapOf(
            "nudge" to listOf(doubleArrayOf(20.0, 12.0, 1.0, 0.5), doubleArrayOf(-15.0, -10.0, 1.0, -0.5)),
            "bump" to listOf(doubleArrayOf(60.0, 40.0, 1.03, 2.0), doubleArrayOf(40.0, -50.0, 0.97, -2.0)),
            "big" to listOf(doubleArrayOf(150.0, 150.0, 1.0, 3.0), doubleArrayOf(300.0, 350.0, 1.0, 0.0))
        )
        for ((name, stages) in cases) {
            val video = FfmpegVideo(v)
            val at = { t: Double ->
                video.at(t)?.let { p ->
                    when {
                        t < video.duration / 3 -> p
                        t < video.duration * 2 / 3 -> moved(p, stages[0])
                        else -> moved(p, stages[1])
                    }
                }
            }
            val r = findTimer({ at(it) }, video.duration, fonts(v))
            val timer = r.timer
            if (timer == null) { println("MOVES $name: NOT FOUND ${r.notes}"); continue }
            val scan = scanFoundTimer(
                { t -> at(t)?.let { timer.read(it)?.text } }, video.duration, timer.runningDecimals, timer.stoppedDecimals, video.frameSec, { t -> video.frameTime(t) }
            )
            val c = compareScan(key, scan.solves)
            println(
                "MOVES $name: area ${timer.area}; found ${c.matches.size} of ${c.expectedCount}, false ${c.falseSolves.size}, wrong time ${c.wrongTime.size}, " +
                        "${c.onFrame} on the frame, worst ${"%.3f".format(c.worstOffsetSec)} s; missed ${c.missed.map { it.time }}; flagged ${scan.solves.count { it.note.isNotEmpty() }}"
            )
            video.close()
        }
    }

    private fun moved(p: GrayPic, m: DoubleArray): GrayPic {
        val a = Math.toRadians(m[3])
        val cos = Math.cos(a) / m[2]
        val sin = Math.sin(a) / m[2]
        val out = ByteArray(p.d.size)
        for (y in 0 until p.h) for (x in 0 until p.w) {
            val dx = x - p.w / 2.0 - m[0]
            val dy = y - p.h / 2.0 - m[1]
            val sx = (dx * cos + dy * sin + p.w / 2.0).toInt().coerceIn(0, p.w - 1)
            val sy = (-dx * sin + dy * cos + p.h / 2.0).toInt().coerceIn(0, p.h - 1)
            out[y * p.w + x] = p.d[sy * p.w + sx]
        }
        return GrayPic(p.w, p.h, out)
    }

    /** Pictures of a video through ffmpeg: half-second steps from one running decode, frame by frame from short bursts, the rest by seeking. */
    private class FfmpegVideo(private val file: File) {
        // (ffmpeg turns the picture by the video's turn flag, so a stored 1920 x 1080 turned by 90 degrees comes out 1080 x 1920)
        private val size = text("ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "stream=width,height:stream_side_data=rotation", "-of", "csv=p=0", file.path)
            .trim().split(Regex("[,\\s]+")).mapNotNull { it.toIntOrNull() }
        private val sideways = size.size > 2 && Math.abs(size[2]) % 180 == 90
        private val w = if (sideways) size[1] else size[0]
        private val h = if (sideways) size[0] else size[1]
        private val times: DoubleArray = text(
            "ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=pts_time", "-of", "csv=p=0", file.path
        ).split(Regex("[,\\s]+")).mapNotNull { it.toDoubleOrNull() }.sorted().toDoubleArray()
        val duration: Double = text("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file.path).trim().toDouble()
        val frameSec: Double = duration / times.size
        var nanos = 0L                                          // spent getting pictures

        /**
         * (4.0) The phone's GraySource.setNear, imitated: with CUBECUT_REF_NEAR=n (frames, may be negative) every half-second step of a
         * scan ([stepsNear], while not [exact]) is the frame n frames after the one asked for, the worst a step may land on the phone;
         * [frameTime] tells that frame's time. Frame by frame ([exact]) the frame itself, as on the phone.
         */
        val shift: Int = System.getenv("CUBECUT_REF_NEAR")?.toIntOrNull() ?: 0
        var stepsNear = false
        var exact = false
        private val shifted get() = shift != 0 && stepsNear && !exact
        private val keptNear = LinkedHashMap<Int, GrayPic>()
        private var nearSteps: Process? = null
        private var nearIn: DataInputStream? = null
        private var nextNear = 0
        private val kept = LinkedHashMap<Int, GrayPic>()        // by frame number
        private val keptSteps = LinkedHashMap<Int, GrayPic>()   // by half second
        private var steps: Process? = null
        private var stepsIn: DataInputStream? = null
        private var nextStep = 0
        private var lastFrame = -10

        fun at(t: Double): GrayPic? {
            val started = System.nanoTime()
            val p = get(t)
            nanos += System.nanoTime() - started
            return p
        }

        private fun get(t: Double): GrayPic? {
            val k = Math.round(t * 2).toInt()
            val stepLike = abs(t * 2 - k) < 1e-6
            if (stepLike && shifted) {
                keptNear[k]?.let { return it }
                if (nearSteps == null) openNear()
                if (k >= nextNear && k - nextNear < 40) {
                    var p: GrayPic? = null
                    while (nextNear <= k) { p = readNear(); nextNear++ }
                    if (p != null) {
                        keptNear[k] = p
                        while (keptNear.size > 20) keptNear.remove(keptNear.keys.first())
                        return p
                    }
                }
                val i = ((nearest(t) ?: return null) + shift).coerceIn(times.indices)
                return frames(i, 1).firstOrNull()
            }
            if (stepLike && !(shift != 0 && stepsNear && exact)) {
                keptSteps[k]?.let { return it }
                if (steps == null && k == 0) openSteps()        // a scan begins: from here on the half seconds come in order
                if (steps != null && k >= nextStep && k - nextStep < 40) {
                    var p: GrayPic? = null
                    while (nextStep <= k) { p = readStep(); nextStep++ }
                    if (p != null) {
                        keptSteps[k] = p
                        while (keptSteps.size > 20) keptSteps.remove(keptSteps.keys.first())
                        return p
                    }
                }
            }
            val i = nearest(t) ?: return null
            val walking = i - lastFrame in 0..2                 // frame by frame: get a burst
            lastFrame = i
            kept[i]?.let { return it }
            for ((j, p) in frames(i, if (walking) 20 else 1).withIndex()) kept[i + j] = p
            while (kept.size > 50) kept.remove(kept.keys.first())
            return kept[i]
        }

        fun frameTime(t: Double): Double {
            val i = nearest(t) ?: return t
            val k = Math.round(t * 2).toInt()
            return if (shifted && abs(t * 2 - k) < 1e-6) times[(i + shift).coerceIn(times.indices)] else times[i]
        }

        /** The half seconds as [shift] frames later: every frame's time moved back by that much, then two a second as usual. */
        private fun openNear() {
            val d = "%.6f".format(Locale.ROOT, shift * frameSec)
            val p = ProcessBuilder("ffmpeg", "-v", "error", "-hwaccel", "cuda", "-i", file.path, "-vf", "setpts=PTS-$d/TB,fps=2:round=up", "-f", "rawvideo", "-pix_fmt", "gray", "-").start()
            nearSteps = p
            nearIn = DataInputStream(BufferedInputStream(p.inputStream, 1 shl 22))
        }

        private fun readNear(): GrayPic? = try {
            GrayPic(w, h, ByteArray(w * h).also { nearIn!!.readFully(it) })
        } catch (e: EOFException) {
            null
        }

        private fun nearest(t: Double): Int? {
            if (t < 0 || t > duration) return null
            var i = java.util.Arrays.binarySearch(times, t)
            if (i < 0) i = -i - 1
            if (i >= times.size || (i > 0 && t - times[i - 1] < times[i] - t)) i--
            return i.coerceIn(times.indices)
        }

        private fun frames(i: Int, n: Int): List<GrayPic> {
            val b = run(
                "ffmpeg", "-v", "error", "-ss", "%.5f".format(Locale.ROOT, maxOf(0.0, times[i] - 0.004)), "-i", file.path,
                "-frames:v", "$n", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "-"
            )
            return List(b.size / (w * h)) { GrayPic(w, h, b.copyOfRange(it * w * h, (it + 1) * w * h)) }
        }

        private fun openSteps() {
            val p = ProcessBuilder("ffmpeg", "-v", "error", "-hwaccel", "cuda", "-i", file.path, "-vf", "fps=2:round=up", "-f", "rawvideo", "-pix_fmt", "gray", "-").start()
            steps = p
            stepsIn = DataInputStream(BufferedInputStream(p.inputStream, 1 shl 22))
        }

        private fun readStep(): GrayPic? = try {
            GrayPic(w, h, ByteArray(w * h).also { stepsIn!!.readFully(it) })
        } catch (e: EOFException) {
            null
        }

        fun close() {
            nearSteps?.destroy()
            steps?.destroy()
        }

        private fun text(vararg command: String) = run(*command).toString(Charsets.UTF_8)

        private fun run(vararg command: String): ByteArray {
            val p = ProcessBuilder(*command).start()
            val out = p.inputStream.readBytes()
            p.waitFor(60, TimeUnit.SECONDS)
            return out
        }
    }
}
