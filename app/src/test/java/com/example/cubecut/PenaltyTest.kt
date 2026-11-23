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
import org.junit.Assert.assertNull
import org.junit.Test

/** (3.2-C) How a penalty is shown, named in files and sorted, and that it never moves a clip. */
class PenaltyTest {
    private val eps = 1e-9

    @Test
    fun shownTime_formats() {
        assertEquals("11.263", shownTime("11.263", 0, false))
        assertEquals("13.263 (+2)", shownTime("11.263", 1, false))
        assertEquals("15.263 (+2×2)", shownTime("11.263", 2, false))
        assertEquals("DNF(11.263)", shownTime("11.263", 0, true))
        assertEquals("", shownTime("", 0, false))
        assertEquals("DNF", shownTime("", 0, true))
        assertEquals("? (+2)", shownTime("", 1, false))
    }

    @Test
    fun addSeconds_keepsDecimals_andRollsIntoMinutes() {
        assertEquals("13.26", addSeconds("11.26", 2.0))
        assertEquals("1:00.50", addSeconds("58.50", 2.0))
        assertEquals("1:04.345", addSeconds("1:02.345", 2.0))
        assertEquals("14", addSeconds("12", 2.0))
        assertEquals("1:01.000", addSeconds("59.000", 2.0))
        assertEquals("1:00.000", formatSeconds(59.99996, 3))      // rounds in whole units: never "60.000"
        assertEquals("abc", addSeconds("abc", 2.0))      // unreadable stays as it is
    }

    @Test
    fun titleTime_hasUnits() {
        assertEquals("11.263s", titleTime("11.263", 0, false))
        assertEquals("13.263s (+2)", titleTime("11.263", 1, false))
        assertEquals("DNF(11.263s)", titleTime("11.263", 0, true))
        assertEquals("time unknown", titleTime("", 0, false))
        assertEquals("Sunday · Solve 03 · DNF(9.100s)", clipTitle("Sunday", 3, "9.100", 0, true))
    }

    @Test
    fun fileTimePart_matchesTheOwnersFormat() {
        assertEquals("11.263s", fileTimePart("11.263", 0, false))
        assertEquals("13.263s_plus2", fileTimePart("11.263", 1, false))
        assertEquals("15.263s_plus2x2", fileTimePart("11.263", 2, false))
        assertEquals("DNF_11.263s", fileTimePart("11.263", 0, true))
        assertEquals("1m04.345s_plus2", fileTimePart("1:02.345", 1, false))
        assertEquals("unknown", fileTimePart("", 0, false))
        assertEquals("unknown_plus2", fileTimePart("", 1, false))
        assertEquals("DNF_unknown", fileTimePart("", 0, true))
    }

    @Test
    fun fileName_usesThePenalty() {
        val c = Clip(3, 0.0, 10.0, "11.263", 2, true, 0.0, false, 1, false)
        assertEquals("Sunday_Solve_03_13.263s_plus2.mp4", ClipExporter.fileName(c, "Sunday"))
        val d = Clip(3, 0.0, 10.0, "11.263", 2, true, 0.0, false, 0, true)
        assertEquals("Sunday_3x3_Solve_03_DNF_11.263s.mp4", ClipExporter.fileName(d, "Sunday", "3x3"))
    }

    @Test
    fun renamedExportName_replacesOnlyTheTime() {
        assertEquals("Sunday_3x3 OH_Solve_03_13.263s_plus2.mp4", renamedExportName("Sunday_3x3 OH_Solve_03_11.263s.mp4", "13.263s_plus2"))
        // Android's " (1)" for a second export stays
        assertEquals("Sunday_Solve_03_DNF_11.263s (1).mp4", renamedExportName("Sunday_Solve_03_11.263s (1).mp4", "DNF_11.263s"))
        // back to no penalty
        assertEquals("Sunday_Solve_03_11.263s.mp4", renamedExportName("Sunday_Solve_03_13.263s_plus2.mp4", "11.263s"))
        // a title that itself says "Solve_1_": the last "Solve_NN_" counts
        assertEquals("Solve_1_x_Solve_02_9.000s.mp4", renamedExportName("Solve_1_x_Solve_02_unknown.mp4", "9.000s"))
        // renamed by hand: left alone
        assertNull(renamedExportName("my best solve.mp4", "9.000s"))
    }

    @Test
    fun penalisedSeconds_forSortingAndBest() {
        assertEquals(13.263, penalisedSeconds("11.263", 1, false)!!, eps)
        assertEquals(11.263, penalisedSeconds("11.263", 0, false)!!, eps)
        assertNull(penalisedSeconds("11.263", 0, true))
        assertNull(penalisedSeconds("", 1, false))
    }

    @Test
    fun penalty_neverMovesTheClip() {
        // the solve-only start uses the time the timer showed, with or without a penalty
        val plain = computeClips(listOf(CutItem(30.0, "10.000", true, "", false)), 2.0, 2.0, 100.0)
        val penalised = computeClips(listOf(CutItem(30.0, "10.000", true, "", false, plus2Count = 3, dnf = false)), 2.0, 2.0, 100.0)
        val dnf = computeClips(listOf(CutItem(30.0, "10.000", true, "", false, dnf = true)), 2.0, 2.0, 100.0)
        assertEquals(plain[0].startSec, penalised[0].startSec, eps)
        assertEquals(plain[0].startSec, dnf[0].startSec, eps)
        assertEquals(18.0, plain[0].startSec, eps)
        assertEquals(3, penalised[0].plus2Count)
    }
}
