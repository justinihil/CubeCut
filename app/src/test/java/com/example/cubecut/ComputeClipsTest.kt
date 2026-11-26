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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the behaviour of [parseTimeSeconds] and [computeClips] as it was in 3.1, so the per-clip buffers of 3.2 can be added
 * without moving any clip that doesn't use them.
 */
class ComputeClipsTest {
    private val eps = 1e-9

    private fun cut(stop: Double, time: String, included: Boolean = true, note: String = "", scramble: Boolean = true) =
        CutItem(stop, time, included, note, scramble)

    // ------------------------------------------------------------ parseTimeSeconds

    @Test
    fun parseTime_plainSeconds() {
        assertEquals(12.112, parseTimeSeconds("12.112")!!, eps)
        assertEquals(9.5, parseTimeSeconds("  9.5 ")!!, eps)
        assertEquals(7.0, parseTimeSeconds("7")!!, eps)
    }

    @Test
    fun parseTime_minutes() {
        assertEquals(62.345, parseTimeSeconds("1:02.345")!!, eps)
        assertEquals(600.0, parseTimeSeconds("10:00")!!, eps)
    }

    @Test
    fun parseTime_rejectsEmptyNonsenseAndOutOfRange() {
        assertNull(parseTimeSeconds(""))
        assertNull(parseTimeSeconds("   "))
        assertNull(parseTimeSeconds("abc"))
        assertNull(parseTimeSeconds("1:2:3"))
        assertNull(parseTimeSeconds("x:12"))
        assertNull(parseTimeSeconds("0"))
        assertNull(parseTimeSeconds("-3"))
        assertNull(parseTimeSeconds("3600"))
        assertNull(parseTimeSeconds("60:00"))
    }

    // ------------------------------------------------------------ computeClips

    @Test
    fun withScramble_startsWherePreviousClipEnded() {
        val clips = computeClips(listOf(cut(30.0, "10.000"), cut(60.0, "12.000")), 2.0, 3.0, 100.0)
        assertEquals(2, clips.size)
        val a = clips[0]
        assertEquals(1, a.number); assertEquals(0, a.cutIndex)
        assertEquals(0.0, a.startSec, eps); assertEquals(33.0, a.endSec, eps)
        assertEquals(18.0, a.solveStartSec, eps)
        assertTrue(a.withScramble); assertFalse(a.noTime)
        val b = clips[1]
        assertEquals(2, b.number); assertEquals(1, b.cutIndex)
        assertEquals(33.0, b.startSec, eps); assertEquals(63.0, b.endSec, eps)
        assertEquals(46.0, b.solveStartSec, eps)
        assertEquals("12.000", b.timeText)
    }

    @Test
    fun withScramble_neverStartsAfterSolveStartMinusBuffer() {
        // a 25 s solve: its solve part starts before the previous clip ended, so the clip starts there instead
        val clips = computeClips(listOf(cut(30.0, "10.000"), cut(55.0, "25.000")), 2.0, 3.0, 100.0)
        assertEquals(28.0, clips[1].startSec, eps)
        assertEquals(28.0, clips[1].solveStartSec, eps)
    }

    @Test
    fun solveOnly_startsTimePlusBufferBeforeStop() {
        val clips = computeClips(listOf(cut(30.0, "10.000"), cut(60.0, "12.000", scramble = false)), 2.0, 3.0, 100.0)
        val b = clips[1]
        assertEquals(46.0, b.startSec, eps); assertEquals(63.0, b.endSec, eps)
        assertFalse(b.withScramble); assertFalse(b.noTime)
    }

    @Test
    fun solveOnly_unknownTime_usesFullClipAndSaysSo() {
        val clips = computeClips(listOf(cut(30.0, "10.000"), cut(60.0, "", scramble = false)), 2.0, 3.0, 100.0)
        val b = clips[1]
        assertEquals(33.0, b.startSec, eps)
        assertEquals(33.0, b.solveStartSec, eps)
        assertTrue(b.noTime); assertTrue(b.withScramble)
    }

    @Test
    fun soloStart_isNeverBeforeZero() {
        val clips = computeClips(listOf(cut(5.0, "10.000", scramble = false)), 2.0, 3.0, 100.0)
        assertEquals(0.0, clips[0].startSec, eps)
        assertEquals(8.0, clips[0].endSec, eps)
    }

    @Test
    fun end_isClampedToDuration_onlyWhenDurationKnown() {
        assertEquals(100.0, computeClips(listOf(cut(98.0, "5.000")), 2.0, 3.0, 100.0)[0].endSec, eps)
        assertEquals(101.0, computeClips(listOf(cut(98.0, "5.000")), 2.0, 3.0, 0.0)[0].endSec, eps)
    }

    @Test
    fun cutTooCloseToPrevious_getsNoClipAndNoNumber() {
        val clips = computeClips(listOf(cut(30.0, "10.000"), cut(30.5, "0.400"), cut(60.0, "12.000")), 2.0, 3.0, 100.0)
        assertEquals(2, clips.size)
        assertEquals(listOf(0, 2), clips.map { it.cutIndex })
        assertEquals(listOf(1, 2), clips.map { it.number })
        assertEquals(33.0, clips[1].startSec, eps)
    }

    @Test
    fun switchedOffCut_givesNoClipButStillEndsTheNextScramble() {
        val clips = computeClips(listOf(cut(30.0, "10.000", included = false), cut(60.0, "12.000")), 2.0, 3.0, 100.0)
        assertEquals(1, clips.size)
        assertEquals(1, clips[0].cutIndex)
        assertEquals(1, clips[0].number)
        assertEquals(33.0, clips[0].startSec, eps)
    }

    @Test
    fun leftoverCut_isTreatedAsDeleted() {
        val leftover = cut(1.0, "8.000", included = false, note = LEFTOVER_NOTE_START + " when the video began")
        val clips = computeClips(listOf(leftover, cut(60.0, "12.000")), 2.0, 3.0, 100.0)
        assertEquals(1, clips.size)
        assertEquals(0.0, clips[0].startSec, eps)   // the leftover doesn't move the scramble start
    }

    @Test
    fun leftoverNote_onAnIncludedCut_isANormalSolve() {
        val c = cut(30.0, "10.000", included = true, note = LEFTOVER_NOTE_START + " when the video began")
        assertEquals(1, computeClips(listOf(c), 2.0, 3.0, 100.0).size)
    }

    @Test
    fun unsortedInput_isTakenInTimeOrder_andKeepsOriginalIndex() {
        val clips = computeClips(listOf(cut(60.0, "12.000"), cut(30.0, "10.000")), 2.0, 3.0, 100.0)
        assertEquals(listOf(1, 0), clips.map { it.cutIndex })
        assertEquals(listOf(1, 2), clips.map { it.number })
        assertEquals(33.0, clips[1].startSec, eps)
    }

    @Test
    fun noCuts_noClips() {
        assertTrue(computeClips(emptyList(), 2.0, 3.0, 100.0).isEmpty())
    }

    // ------------------------------------------------------------ per-clip buffers (3.2-A)

    @Test
    fun perClipAfterPad_movesOnlyItsOwnEnd_andTheNextScrambleStart() {
        val clips = computeClips(listOf(cut(30.0, "10.000").copy(afterPad = 5.0), cut(60.0, "12.000")), 2.0, 3.0, 100.0)
        assertEquals(35.0, clips[0].endSec, eps)
        assertEquals(35.0, clips[1].startSec, eps)   // the next scramble starts where this clip now ends
        assertEquals(63.0, clips[1].endSec, eps)     // the next clip still uses the video's buffer
    }

    @Test
    fun perClipBeforePad_movesTheSolveStart() {
        val clips = computeClips(listOf(cut(30.0, "10.000"), cut(60.0, "12.000", scramble = false).copy(beforePad = 1.0)), 2.0, 3.0, 100.0)
        assertEquals(47.0, clips[1].startSec, eps)
        assertEquals(47.0, clips[1].solveStartSec, eps)
        assertEquals(18.0, clips[0].solveStartSec, eps)
    }

    @Test
    fun perClipPadOfZero_isUsed_notTreatedAsUnset() {
        val clips = computeClips(listOf(cut(30.0, "10.000", scramble = false).copy(beforePad = 0.0, afterPad = 0.0)), 2.0, 3.0, 100.0)
        assertEquals(20.0, clips[0].startSec, eps)
        assertEquals(30.0, clips[0].endSec, eps)
    }

    @Test
    fun newFieldsOtherThanPads_neverMoveAClip() {
        val plain = listOf(cut(30.0, "10.000"), cut(60.0, "12.000", scramble = false))
        val tagged = plain.map { it.copy(puzzle = "3x3", category = "OH", plus2Count = 2, dnf = true, sharedOn = listOf("TikTok")) }
        val a = computeClips(plain, 2.0, 3.0, 100.0)
        val b = computeClips(tagged, 2.0, 3.0, 100.0)
        assertEquals(a.map { Triple(it.startSec, it.endSec, it.solveStartSec) }, b.map { Triple(it.startSec, it.endSec, it.solveStartSec) })
    }
}
