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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.floor

/** (3.3-C) The hints where a solve may be missing: on a simulated session, and on what the scan saw in the reference videos (traces). */
class MissedSolvesTest {

    private val frame = 1.0 / 60
    private val times = listOf(12.69, 14.07, 9.41, 13.88, 11.53, 12.02)
    private val cycle = 40.0
    private val duration = times.size * cycle

    private fun start(i: Int) = i * cycle + 10
    private fun stop(i: Int) = start(i) + times[i]
    private fun two(s: Double) = "%.2f".format(Locale.ROOT, s)

    private fun shown(t: Double): String? {
        val i = floor(t / cycle).toInt().coerceIn(times.indices)
        val s = t - i * cycle
        return when {
            s < 4 -> "0.00"
            s < 10 -> null
            t < stop(i) - 1e-9 -> "%.1f".format(Locale.ROOT, floor((t - start(i)) * 10 + 1e-9) / 10)
            else -> two(times[i])
        }
    }

    private fun solvesOf(r: ScanResult) = r.solves.filter { it.include }.map { Pair(it.stopSec, shownSeconds(it.timeText)) }

    private fun hints(read: (Double) -> String?): List<MissedHint> {
        val r = scanFoundTimer(read, duration, 1, 2, frame)
        return findMissedSolves(r.trace, solvesOf(r))
    }

    @Test
    fun nothingMissing_noHints() {
        assertEquals(emptyList<MissedHint>(), hints { shown(it) })
    }

    @Test
    fun aFinalTimeThatWasNeverSeen_isAHint() {
        // the timer is covered from just before the stop of no. 3 until the next 0.00
        val h = hints { t -> if (t > stop(2) - 0.2 && t < 3 * cycle + 1) null else shown(t) }
        assertEquals(1, h.size)
        assertEquals(HintReason.RAN, h[0].reason)
        assertTrue("$h", h[0].seekSec > stop(2) - 1.5 && h[0].seekSec < stop(2))
        assertTrue("$h", stop(2) > h[0].fromSec && stop(2) < h[0].toSec)
    }

    @Test
    fun aRunThatGoesBackToZero_isAHint() {
        // no. 3 is reset the moment it stops
        val h = hints { t -> if (t >= stop(2) && t < 3 * cycle) "0.00" else shown(t) }
        assertEquals(1, h.size)
        assertEquals(HintReason.RESET, h[0].reason)
        assertEquals("The timer ran and was reset.", h[0].text)
    }

    @Test
    fun aTimerThatCantBeReadForLong_isAHint() {
        val h = hints { t -> if (t > stop(1) + 5 && t < stop(3) - 5) null else shown(t) }
        assertEquals("$h", 1, h.size)
        assertEquals(HintReason.UNREADABLE, h[0].reason)
        assertTrue("$h", stop(2) > h[0].fromSec && stop(2) < h[0].toSec)
    }

    @Test
    fun aTimerCoveredWhileItShowsTheSameTime_isNoHint() {
        assertEquals(emptyList<MissedHint>(), hints { t -> if (t > stop(5) + 2 && t < duration - 3) null else shown(t) })
        val long = { t: Double -> if (t > 400) null else if (t > duration) two(times.last()) else shown(t) }
        val r = scanFoundTimer({ t -> if (t > stop(5) + 3 && t < 330) null else if (t > duration) two(times.last()) else long(t) }, 340.0, 1, 2, frame)
        assertEquals(emptyList<MissedHint>(), findMissedSolves(r.trace, solvesOf(r)))
    }

    @Test
    fun aBreakWithAReadableTimer_isNoHint() {
        // 90 s of 0.00 in the middle
        val h = scanFoundTimer({ t -> if (t < 80) shown(t) else if (t < 170) "0.00" else shown(t - 90) }, duration + 90, 1, 2, frame)
        assertEquals(emptyList<MissedHint>(), findMissedSolves(h.trace, solvesOf(h)))
    }

    @Test
    fun aVideoThatEndsWhileTheTimerRuns_isNoHint() {
        val r = scanFoundTimer({ shown(it) }, stop(4) - 1, 1, 2, frame)
        assertEquals(4, r.solves.size)
        assertEquals(emptyList<MissedHint>(), findMissedSolves(r.trace, solvesOf(r)))
    }

    @Test
    fun aTimerOutOfViewAtTheEnd_isAHint() {
        val r = scanFoundTimer({ t -> if (t > stop(3) + 3) null else shown(t) }, duration + 60, 1, 2, frame)
        val h = findMissedSolves(r.trace, solvesOf(r))
        assertEquals("$h", listOf(HintReason.UNREADABLE), h.map { it.reason })
    }

    private fun cut(stop: Double, time: String) = CutItem(stop, time, true, "", true)

    @Test
    fun aSolveAddedByHand_removesItsHint_andADismissedOneStaysAway() {
        val h = MissedHint(100.0, 125.0, 112.0, HintReason.RAN)
        assertEquals(listOf(h), hintsToShow(listOf(h), listOf(cut(90.0, "12.00"), cut(140.0, "12.00"))))
        assertEquals(emptyList<MissedHint>(), hintsToShow(listOf(h), listOf(cut(112.4, "12.40"))))
        assertEquals(emptyList<MissedHint>(), hintsToShow(listOf(h.copy(dismissed = true)), emptyList()))
        // a re-scan finds it again, a little different: still away; a new one shows
        val again = mergeHints(listOf(h.copy(dismissed = true)), listOf(h.copy(fromSec = 100.4), MissedHint(300.0, 320.0, 310.0, HintReason.RESET)))
        assertEquals(listOf(310.0), hintsToShow(again, emptyList()).map { it.seekSec })
        // one that isn't found again and wasn't dismissed goes
        assertEquals(emptyList<MissedHint>(), mergeHints(listOf(h), emptyList()))
    }

    @Test
    fun reasonsAreStoredByTheirKey() {
        assertEquals(listOf("ran", "reset", "unreadable"), HintReason.entries.map { it.key })
    }

    // ---- what the scan saw in the reference videos (ReferenceVideoTest writes the traces) ----

    private class Trace(val name: String, val trace: ScanTrace, val solves: List<Pair<Double, Double>>)

    /** Filmed to test the hints: four stops are hidden or reset at once (and one whole solve is covered). */
    private val WITH_HIDDEN_STOPS = "ref_qiyi_old_03"

    private fun traces(hidden: Boolean = false): List<Trace> {
        val dir = File(File(System.getenv("CUBECUT_REF") ?: "D:/CubeCutRef"), "traces")
        val files = dir.listFiles { f -> f.name.endsWith(".trace.txt") && f.name.startsWith(WITH_HIDDEN_STOPS) == hidden }.orEmpty().sortedBy { it.name }
        assumeTrue("no traces on this PC", files.isNotEmpty())
        return files.map { f ->
            val seen = ArrayList<SeenAt>()
            val running = ArrayList<RunningAt>()
            val solves = ArrayList<Pair<Double, Double>>()
            var length = 0.0
            f.forEachLine { l ->
                val p = l.split(" ")
                when (p[0]) {
                    "D" -> length = p[1].toDouble()
                    "S" -> seen.add(SeenAt(p[1].toDouble(), p[2].takeIf { it != "null" }, p[3] == "true"))
                    "R" -> running.add(RunningAt(p[1].toDouble(), p[2].toDouble()))
                    "F" -> if (p[3] == "true") solves.add(Pair(p[1].toDouble(), shownSeconds(p[2])))
                }
            }
            Trace(f.name.removeSuffix(".trace.txt"), ScanTrace(seen, running, length), solves)
        }
    }

    @Test
    fun referenceVideos_haveNoHints() {
        val all = traces().associate { it.name to findMissedSolves(it.trace, it.solves) }
        for ((name, h) in all) for (x in h) println("HINT $name: ${videoPlace(x.fromSec)} - ${videoPlace(x.toSec)} ${x.text}")
        assertEquals("$all", emptyList<String>(), all.filter { it.value.isNotEmpty() }.keys.toList())
    }

    @Test
    fun hiddenStops_areTold() {
        val t = traces(hidden = true).single()
        val h = findMissedSolves(t.trace, t.solves)
        assertEquals("$h", listOf(HintReason.RAN, HintReason.RESET, HintReason.RAN, HintReason.RESET), h.map { it.reason })
        for ((x, stop) in h.zip(listOf(139.5, 220.05, 254.25, 292.04))) assertTrue("$x", stop > x.fromSec && stop < x.toSec)
    }

    /**
     * Every solve is taken out in turn: its final time can't be read ([alsoRunning]: the running time neither). Not for timers whose running time
     * is hidden or in whole seconds (by name, or nothing seen running, like csTimer's "digital 1" video): their hints are weaker, as known-issues says.
     */
    private fun takenOut(alsoRunning: Boolean): Map<String, Pair<Int, Int>> =
        traces().filter { "hidden" !in it.name && "seconds" !in it.name && it.trace.running.isNotEmpty() }.associate { t ->
        var told = 0
        for ((i, s) in t.solves.withIndex()) {
            val (stop, sec) = s
            // until the next solve starts, or something else than its time shows
            val until = t.solves.getOrNull(i + 1)?.let { it.first - it.second - 1 } ?: t.trace.durationSec
            val change = t.trace.seen.firstOrNull { it.t > stop + 1 && it.t < until && it.text != null && shownSeconds(it.text) == 0.0 }?.t ?: until
            val from = if (alsoRunning) stop - sec - 1 else stop - 0.3
            val trace = ScanTrace(
                t.trace.seen.map { if (it.t >= from && it.t < change) SeenAt(it.t, null, false) else it },
                t.trace.running.filter { it.t < from || it.t >= change }, t.trace.durationSec
            )
            val h = findMissedSolves(trace, t.solves.filterIndexed { k, _ -> k != i })
            if (hintsToShow(h, emptyList()).any { it.fromSec < stop && it.toSec > stop - 1 }) told++ else println("NOT TOLD ${t.name} ${videoPlace(stop)} $sec (${if (alsoRunning) "all of it" else "final time"})")
        }
        t.name to Pair(told, t.solves.size)
    }

    @Test
    fun referenceVideos_aFinalTimeTakenOut_isTold() {
        val told = takenOut(false)
        println("TOLD final time: $told")
        assertTrue("$told", told.values.all { it.first >= it.second - 1 })
    }

    @Test
    fun referenceVideos_aWholeSolveTakenOut_isMostlyTold() {
        val told = takenOut(true)
        println("TOLD whole solve: $told")
        assertTrue("$told", told.values.sumOf { it.first } >= 0.85 * told.values.sumOf { it.second })
    }
}
