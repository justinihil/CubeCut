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
import org.junit.Test

/** (3.3-A) The ground-truth file format and how a scan is compared with it. */
class GroundTruthTest {
    private fun found(stop: Double, time: String, include: Boolean = true, note: String = "") = FoundSolve(stop, time, note, include)

    @Test
    fun json_roundTrip() {
        val g = GroundTruth("My_movie_7.mp4", "csTimer (PC)", 212.5, listOf(ExpectedSolve(41.2333, "12.412"), ExpectedSolve(80.0, "")), "dim")
        val back = parseGroundTruth(groundTruthToJson(g))
        assertEquals("My_movie_7.mp4", back.video)
        assertEquals("csTimer (PC)", back.timer)
        assertEquals(212.5, back.durationSec, 1e-9)
        assertEquals("dim", back.note)
        assertEquals(2, back.solves.size)
        assertEquals(41.233, back.solves[0].stopSec, 1e-9)    // kept to the millisecond
        assertEquals("", back.solves[1].time)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parse_refusesANewerFormat() {
        parseGroundTruth("""{"format": 99, "solves": []}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parse_refusesOtherJson() {
        parseGroundTruth("""{"videos": []}""")
    }

    @Test
    fun compare_sortsIntoFoundMissedFalseAndWrongTime() {
        val expected = listOf(ExpectedSolve(10.0, "9.000"), ExpectedSolve(40.0, "12.412"), ExpectedSolve(80.0, "11.263"))
        val f = listOf(
            found(10.01, "9.000"),         // on the frame
            found(40.3, "12.112"),         // late, and a 4 read as 1
            found(60.0, "11.51"),          // nothing expected near: false
        )
        val c = compareScan(expected, f)
        assertEquals(3, c.expectedCount)
        assertEquals(2, c.matches.size)
        assertEquals(listOf(80.0), c.missed.map { it.stopSec })
        assertEquals(listOf(60.0), c.falseSolves.map { it.stopSec })
        assertEquals(1, c.wrongTime.size)
        assertEquals(1, c.onFrame)
        assertEquals(0.3, c.worstOffsetSec, 1e-9)
    }

    @Test
    fun compare_closestPairsWinAndEachSolveIsUsedOnce() {
        // two found solves near one expected: the nearer one matches, the other is false
        val c = compareScan(listOf(ExpectedSolve(30.0, "10.000")), listOf(found(29.0, "10.000"), found(30.1, "10.000")))
        assertEquals(30.1, c.matches.single().found.stopSec, 1e-9)
        assertEquals(29.0, c.falseSolves.single().stopSec, 1e-9)
    }

    @Test
    fun compare_leftoversAreNeitherSolvesNorFalse() {
        val left = found(0.0, "12.412", include = false, note = SolveAnalyzer.LEFTOVER_NOTE_START + " when the video began")
        val c = compareScan(emptyList(), listOf(left))
        assertEquals(0, c.falseSolves.size)
        assertEquals(1, c.leftovers.size)
    }

    @Test
    fun expectedFromCuts_skipsLeftoversAndSorts() {
        val cuts = listOf(
            CutItem(50.0, "11.000", true, ""),
            CutItem(0.5, "12.412", false, SolveAnalyzer.LEFTOVER_NOTE_START + "..."),
            CutItem(20.0, "9.000", true, ADDED_BY_YOU_NOTE)
        )
        assertEquals(listOf(20.0, 50.0), expectedFromCuts(cuts).map { it.stopSec })
    }

    @Test
    fun reportText_listsEachProblem() {
        val g = GroundTruth("a.mp4", "Stackmat", 100.0, listOf(ExpectedSolve(10.0, "9.000"), ExpectedSolve(40.0, "12.412")))
        val c = compareScan(g.solves, listOf(found(10.0, "9.000"), found(70.0, "8.000")))
        val reading = listOf(ReadGrade(g.solves[0], 5, listOf("9.001"), 0), ReadGrade(g.solves[1], 0, emptyList(), 6))
        val text = scanReportText(g, "b.mp4", true, "Timer saved.", reading, c, 12.34, 0.8, "3.3.0")
        assertTrue(text, text.contains("1. Setup: Timer saved."))
        assertTrue(text, text.contains("2. Reading: 5 right, 1 wrong, 6 unreadable of 12 held frames"))
        assertTrue(text, text.contains("Misread: 0:10.00 9.000 as 9.001"))
        assertTrue(text, text.contains("Never read: 0:40.00 12.412"))
        assertTrue(text, text.contains("WARNING: the expected solves are for a.mp4"))
        assertTrue(text, text.contains("Expected 2, found 1, missed 1, false 1, wrong time 0"))
        assertTrue(text, text.contains("Missed: 0:40.00 12.412"))
        assertTrue(text, text.contains("False: 1:10.00 8.000"))
        assertTrue(text, text.contains("Readable 80%"))
    }

    @Test
    fun reportText_aRefusedSetupIsAResult() {
        val g = GroundTruth("a.mp4", "csTimer (PC)", 100.0, listOf(ExpectedSolve(10.0, "9.00"), ExpectedSolve(40.0, "12.25")))
        val text = scanReportText(g, "a.mp4", false, "Couldn't find digit shapes", emptyList(), compareScan(g.solves, emptyList()), 0.0, 0.0, "3.3.0")
        assertTrue(text, text.contains("1. Setup: FAILED. Couldn't find digit shapes"))
        assertTrue(text, text.contains("2. Reading: not tested"))
        assertTrue(text, text.contains("Expected 2, found 0, missed 2"))
    }

    @Test
    fun json_keepsTheSetupAttempt() {
        val s = SetupAttempt(33400, listOf(25.0, 444.0, 810.0, 388.0, 796.0, 600.0, 70.0, 704.0), "12.25")
        val g = GroundTruth("a.mp4", "csTimer (PC)", 100.0, listOf(ExpectedSolve(10.0, "9.00")), setup = s)
        assertEquals(s, parseGroundTruth(groundTruthToJson(g)).setup)
        assertEquals(s, parseSetupAttempt(setupAttemptToJson(s)))
        assertEquals(null, parseGroundTruth(groundTruthToJson(g.copy(setup = null))).setup)
        assertEquals(null, parseSetupAttempt("nonsense"))
    }

    @Test
    fun sameTime_ignoresHowItIsWritten() {
        assertTrue(sameTime("12.5", "12.50"))
        assertTrue(sameTime("1:02.34", "62.34"))
        assertTrue(!sameTime("12.25", "12.26"))
    }

    @Test
    fun gradeReading_countsRightWrongUnreadableAndStopsAtAReset() {
        val expected = listOf(ExpectedSolve(10.0, "12.25"), ExpectedSolve(30.0, ""), ExpectedSolve(50.0, "9.00"))
        val grades = gradeReading(expected, 2, 100.0) { t ->
            when {
                t < 10.2 -> "1225"
                t < 10.4 -> "1226"
                t < 10.6 -> null
                t < 20.0 -> "1225"
                t < 50.2 -> "900"
                else -> "000"                 // reset at once: not counted as wrong
            }
        }
        assertEquals(2, grades.size)          // the solve with no known time is skipped
        assertEquals(4, grades[0].right)
        assertEquals(listOf("12.26"), grades[0].wrong)
        assertEquals(1, grades[0].unreadable)
        assertEquals(1, grades[1].right)
        assertEquals(0, grades[1].wrong.size)
    }

    @Test
    fun gradeReading_onACleanDisplay_isAllRight() {
        val d = FakeDisplay.session(count = 6, seed = 2)
        val grades = gradeReading(d.expected, d.decimals, d.durationSec, d::read)
        assertEquals(6 * 6, grades.sumOf { it.right })
    }

    @Test
    fun videoPlace_formats() {
        assertEquals("0:00.00", videoPlace(0.0))
        assertEquals("1:23.46", videoPlace(83.456))
        assertEquals("61:01.00", videoPlace(3661.0))
    }
}
