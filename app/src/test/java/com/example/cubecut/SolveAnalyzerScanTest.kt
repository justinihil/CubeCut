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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * (3.3-A) The scan's decision logic on a simulated display ([FakeDisplay]), so changes to [SolveAnalyzer.scan] are measured, not
 * guessed. The noisy test is the simulated baseline in docs/algorithms.md § 8: when a scan change moves its numbers, update both.
 */
class SolveAnalyzerScanTest {

    @Test
    fun cleanSession_findsEverySolveOnItsFirstFrame() {
        val d = FakeDisplay.session(count = 12, seed = 7)
        val c = compareScan(d.expected, d.scan().solves)
        assertEquals(12, c.matches.size)
        assertEquals(0, c.missed.size)
        assertEquals(0, c.falseSolves.size)
        assertEquals(0, c.wrongTime.size)
        assertEquals("every cut on its frame, worst ${c.worstOffsetSec}", 12, c.onFrame)
    }

    @Test
    fun cleanSession_hasNoChecks() {
        val r = FakeDisplay.session(count = 8, seed = 3).scan()
        assertTrue(r.solves.all { it.include && it.note.isEmpty() })
        assertEquals(1.0, r.readableFraction, 1e-9)
    }

    @Test
    fun longSolve_overAMinute_isReadWithMinutes() {
        val d = FakeDisplay(listOf(Pair(10.0, 72.345)), 100.0)
        val r = d.scan()
        assertEquals(1, r.solves.size)
        assertEquals("1:12.345", r.solves[0].timeText)
    }

    @Test
    fun timeAlreadyShowingAtStart_isSwitchedOffAsLeftover() {
        val d = FakeDisplay(listOf(Pair(20.0, 11.263)), 40.0, leftoverAtStart = 12.412)
        val r = d.scan()
        val left = r.solves.filter { it.note.startsWith(SolveAnalyzer.LEFTOVER_NOTE_START) }
        assertEquals(1, left.size)
        assertFalse(left[0].include)
        val c = compareScan(d.expected, r.solves)
        assertEquals(1, c.matches.size)
        assertEquals(0, c.falseSolves.size)
        assertEquals(1, c.leftovers.size)
    }

    @Test
    fun fourOrSevenReadAsOne_isVotedBack() {
        // 14.747 has three digits the reader can turn into 1; the 4/7 version must still win
        val d = FakeDisplay(listOf(Pair(10.0, 14.747)), 40.0, oneForFourSeven = 0.35, seed = 11)
        val r = d.scan()
        assertEquals(1, r.solves.size)
        assertEquals("14.747", r.solves[0].timeText)
    }

    /**
     * The simulated baseline (algorithms § 8): 40 sessions of 22 solves, 12 % unreadable frames and 20 % 4/7 -> 1 misreads, as the old
     * Python prototype used. The floors here are the numbers measured when the harness was added (3.3-A); a scan change may only
     * raise them.
     */
    @Test
    fun noisySessions_baseline() {
        var expected = 0
        var found = 0
        var falseOnes = 0
        var wrongTime = 0
        var onFrame = 0
        var close = 0
        var worst = 0.0
        for (seed in 1L..40L) {
            val d = FakeDisplay.session(count = 22, seed = seed, unreadable = 0.12, oneForFourSeven = 0.20)
            val c = compareScan(d.expected, d.scan().solves)
            expected += c.expectedCount
            found += c.matches.size
            falseOnes += c.falseSolves.size
            wrongTime += c.wrongTime.size
            onFrame += c.onFrame
            close += c.close
            worst = maxOf(worst, c.worstOffsetSec)
        }
        println("noisy baseline: expected $expected, found $found, false $falseOnes, wrong time $wrongTime, on frame $onFrame, within 0.1 s $close, worst ${"%.3f".format(worst)} s")
        assertEquals(880, expected)
        assertTrue("found $found", found >= BASELINE_FOUND)
        assertTrue("false $falseOnes", falseOnes <= BASELINE_FALSE)
        assertTrue("wrong time $wrongTime", wrongTime <= BASELINE_WRONG_TIME)
        assertTrue("on frame $onFrame", onFrame >= BASELINE_ON_FRAME)
        assertTrue("within 0.1 s $close", close >= BASELINE_CLOSE)
    }

    /**
     * Known weakness (algorithms § 1), for 3.3-B: a held time read without its last digit splits the hold into several solves. With a
     * quarter of the frames short, 132 of 200 seeds split; the real time was lost altogether in 1. This seed is that one: two `1151` reads
     * in a row trigger the stop, tie the vote 4-4 with `11517` and win as the trigger, twice.
     */
    @Ignore("3.3-B: a held 11.517 read as 1151 splits the hold")
    @Test
    fun heldTimeMissingLastDigit_isTheSameHold() {
        val d = FakeDisplay(listOf(Pair(10.0, 11.517)), 40.0, holdSec = 12.0, dropLastWhileHeld = 0.25, seed = 5)
        val c = compareScan(d.expected, d.scan().solves)
        assertEquals(1, c.matches.size)
        assertEquals(0, c.falseSolves.size)
    }

    @Test
    fun cancel_stopsTheScan() {
        val d = FakeDisplay.session(count = 3, seed = 1)
        var reads = 0
        try {
            SolveAnalyzer.scan({ t -> reads++; d.read(t) }, d.durationSec, 3, { _, _ -> }, { reads > 20 })
            fail("expected ScanCancelled")
        } catch (e: ScanCancelled) {
            assertTrue(reads <= 40)
        }
    }

    /**
     * Every ground-truth file in the repo must be readable and self-consistent: solves in order, not overlapping, with a time, and a
     * clean simulated display built from it must give back exactly those solves. (The real videos stay on the owner's phone.)
     */
    @Test
    fun groundTruthFiles_areValid() {
        val dir = File("src/test/resources/groundtruth")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
        for (f in files) {
            val g = parseGroundTruth(f.readText())
            assertTrue("${f.name}: no solves", g.solves.isNotEmpty())
            assertTrue("${f.name}: no timer kind", g.timer in TIMER_KINDS)
            for ((a, b) in g.solves.zipWithNext()) {
                val len = parseTimeSeconds(b.time) ?: 0.0
                assertTrue("${f.name}: ${b.time} at ${videoPlace(b.stopSec)} starts before ${videoPlace(a.stopSec)}", b.stopSec - len > a.stopSec)
            }
            val d = FakeDisplay.fromGroundTruth(g)
            val c = compareScan(g.solves, d.scan().solves)
            assertEquals("${f.name}: simulated replay", g.solves.size, c.matches.size)
            assertTrue("${f.name}: simulated replay cuts", c.matches.all { abs(it.offsetSec) <= CUT_FRAME_SEC })
        }
    }

    private companion object {
        // measured when the harness was added (3.3-A); see noisySessions_baseline
        const val BASELINE_FOUND = 880
        const val BASELINE_FALSE = 0
        const val BASELINE_WRONG_TIME = 2
        const val BASELINE_ON_FRAME = 809
        const val BASELINE_CLOSE = 871
    }
}
