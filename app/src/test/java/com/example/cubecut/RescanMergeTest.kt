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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the behaviour of [mergeRescan] and [checkCuts] as it was in 3.1. */
class RescanMergeTest {
    private val eps = 1e-9

    private fun cut(stop: Double, time: String, note: String = "", edited: Boolean = false, reviewed: Boolean = false, included: Boolean = true) =
        CutItem(stop, time, included, note, true, edited = edited, reviewed = reviewed)

    // ------------------------------------------------------------ mergeRescan

    @Test
    fun untouchedMatch_takesTheNewScan() {
        val saved = listOf(cut(30.0, "10.000"))
        val found = listOf(cut(30.1, "10.100"))
        val r = mergeRescan(saved, found, emptyList())
        assertEquals(1, r.cuts.size)
        assertEquals(30.1, r.cuts[0].stopSec, eps)
        assertEquals("10.100", r.cuts[0].timeText)
        assertEquals(1, r.updated); assertEquals(0, r.added); assertEquals(0, r.kept); assertEquals(0, r.notFound)
    }

    @Test
    fun untouchedMatch_identical_isNotCountedAsUpdated() {
        val r = mergeRescan(listOf(cut(30.0, "10.000")), listOf(cut(30.0, "10.000")), emptyList())
        assertEquals(0, r.updated)
        assertEquals("nothing changed", r.summary())
    }

    @Test
    fun editedCut_isKeptAsIs() {
        val mine = cut(30.0, "10.000", edited = true).copy(userNote = "PB!")
        val r = mergeRescan(listOf(mine), listOf(cut(30.1, "10.100")), emptyList())
        assertSame(mine, r.cuts.single())
        assertEquals(1, r.kept); assertEquals(0, r.updated)
    }

    @Test
    fun exportedCut_isKeptAsIs() {
        val saved = cut(30.0, "10.000")
        val r = mergeRescan(listOf(saved), listOf(cut(30.1, "10.100")), listOf(30.02))
        assertSame(saved, r.cuts.single())
        assertEquals(1, r.kept)
    }

    @Test
    fun newSolve_isAdded() {
        val r = mergeRescan(listOf(cut(30.0, "10.000")), listOf(cut(30.0, "10.000"), cut(60.0, "12.000")), emptyList())
        assertEquals(listOf(30.0, 60.0), r.cuts.map { it.stopSec })
        assertEquals(1, r.added)
    }

    @Test
    fun secondFindOfTheSameSolve_isIgnored() {
        val r = mergeRescan(listOf(cut(30.0, "10.000")), listOf(cut(30.0, "10.000"), cut(30.05, "10.050")), emptyList())
        assertEquals(1, r.cuts.size)
        assertEquals(0, r.added)
    }

    @Test
    fun matchingUsesSolveSpan_notTheStopMoment() {
        // stops 5 s apart, but a 12 s solve stopping at 35 overlaps a 10 s solve stopping at 30: same solve
        val r = mergeRescan(listOf(cut(30.0, "10.000")), listOf(cut(35.0, "12.000")), emptyList())
        assertEquals(1, r.cuts.size)
        assertEquals(35.0, r.cuts[0].stopSec, eps)
    }

    @Test
    fun notFoundAgain_untouched_getsANote_editedDoesNot() {
        val untouched = cut(30.0, "10.000")
        val withNote = cut(60.0, "12.000", note = "blurry")
        val mine = cut(90.0, "11.000", edited = true)
        val r = mergeRescan(listOf(untouched, withNote, mine), emptyList(), emptyList())
        assertEquals(NOT_FOUND_AGAIN_NOTE, r.cuts[0].note)
        assertEquals("blurry; $NOT_FOUND_AGAIN_NOTE", r.cuts[1].note)
        assertSame(mine, r.cuts[2])
        assertEquals(2, r.notFound); assertEquals(1, r.kept)
    }

    @Test
    fun notFoundAgain_leftoverOrAlreadyNoted_keepsItsNote() {
        val leftoverNote = LEFTOVER_NOTE_START + " when the video began"
        val r = mergeRescan(listOf(cut(1.0, "8.000", note = leftoverNote, included = false), cut(30.0, "10.000", note = NOT_FOUND_AGAIN_NOTE)), emptyList(), emptyList())
        assertEquals(leftoverNote, r.cuts[0].note)
        assertEquals(NOT_FOUND_AGAIN_NOTE, r.cuts[1].note)
    }

    @Test
    fun result_isSortedByStop() {
        val r = mergeRescan(listOf(cut(90.0, "11.000", edited = true)), listOf(cut(30.0, "10.000")), emptyList())
        assertEquals(listOf(30.0, 90.0), r.cuts.map { it.stopSec })
    }

    @Test
    fun editedCut_keepsEveryV7Field() {
        val mine = cut(30.0, "10.000", edited = true).copy(
            puzzle = "3x3", category = "OH", plus2Count = 1, dnf = true, beforePad = 1.0, afterPad = 4.0, sharedOn = listOf("TikTok")
        )
        assertSame(mine, mergeRescan(listOf(mine), listOf(cut(30.1, "10.100")), emptyList()).cuts.single())
    }

    @Test
    fun untouchedMatch_takesTheScan_butCarriesUserOnlyFields() {
        // not marked edited (should never happen, but a re-scan must still not drop what only the user sets)
        val old = cut(30.0, "10.000").copy(
            userNote = "n", puzzle = "2x2", category = "Relay", plus2Count = 2, dnf = true, beforePad = 0.5, afterPad = 1.5, sharedOn = listOf("X")
        )
        val got = mergeRescan(listOf(old), listOf(cut(30.1, "10.100", note = "blurry")), emptyList()).cuts.single()
        assertEquals(30.1, got.stopSec, eps); assertEquals("10.100", got.timeText); assertEquals("blurry", got.note)
        assertEquals("n", got.userNote); assertEquals("2x2", got.puzzle); assertEquals("Relay", got.category)
        assertEquals(2, got.plus2Count); assertTrue(got.dnf)
        assertEquals(0.5, got.beforePad!!, eps); assertEquals(1.5, got.afterPad!!, eps)
        assertEquals(listOf("X"), got.sharedOn)
    }

    @Test
    fun rescan_keepsWhenAClipWasSeen_andANewSolveIsNew() {
        // (4.0-D) a clip opened in the viewer stays seen after a re-scan; a solve the re-scan adds shows the New badge
        val seen = cut(30.0, "10.000").copy(seenMs = 1234L)
        val merged = mergeRescan(listOf(seen), listOf(cut(30.1, "10.100"), cut(90.0, "11.000")), emptyList()).cuts
        assertEquals(1234L, merged.first { it.stopSec < 60 }.seenMs)
        assertTrue(merged.first { it.stopSec > 60 }.isNew)
    }

    @Test
    fun aCutYouChangedOrAdded_isNeverNew() {
        assertTrue(cut(30.0, "10.000").isNew)
        assertTrue(!cut(30.0, "10.000", edited = true).isNew)
        assertTrue(!cut(30.0, "10.000").copy(seenMs = 1L).isNew)
    }

    @Test
    fun summary_listsEveryPart() {
        val r = RescanResult(emptyList(), 2, 1, 12, 1)
        assertEquals("2 new, 1 updated, 12 kept as you left them, 1 not found again (check them)", r.summary())
    }

    // ------------------------------------------------------------ checkCuts

    private fun check(cuts: List<CutItem>) = checkCuts(cuts, computeClips(cuts, 2.0, 2.0, 1000.0))

    @Test
    fun wellSpacedSolves_haveNoWarnings() {
        val c = check(listOf(cut(30.0, "10.000"), cut(60.0, "12.000")))
        assertTrue(c.warnings.isEmpty())
        assertTrue(c.noClip.isEmpty())
    }

    @Test
    fun tooClose_flagsBoth_andTheSecondHasNoClip() {
        val c = check(listOf(cut(30.0, "10.000"), cut(30.5, "")))
        assertEquals(setOf(1), c.noClip)
        assertTrue(c.warnings[1]!!.startsWith("too close to the solve before it"))
        assertTrue(c.warnings[0]!!.startsWith("too close to the solve after it"))
    }

    @Test
    fun overlap_flagsBoth() {
        // second solve: 25 s ending at 50 would have started at 25, before the first stopped at 30
        val c = check(listOf(cut(30.0, "10.000"), cut(50.0, "25.000")))
        assertTrue(c.warnings[1]!!.startsWith("it would have started before the solve before it stopped"))
        assertTrue(c.warnings[0]!!.startsWith("the solve after it would have started"))
    }

    @Test
    fun tooFast_flagsBoth() {
        val c = check(listOf(cut(30.0, "10.000"), cut(34.0, "1.500")))
        assertTrue(c.warnings[1]!!.contains("too fast to scramble and solve"))
        assertTrue(c.warnings[0]!!.startsWith("the solve after it ended only"))
        assertTrue(c.noClip.isEmpty())
    }

    @Test
    fun reviewed_hidesWarnings_butNotNoClip() {
        val c = check(listOf(cut(30.0, "10.000", reviewed = true), cut(30.5, "", reviewed = true)))
        assertFalse(c.warnings.containsKey(0))
        assertTrue(c.warnings[1]!!.startsWith("too close to the solve before it"))
        assertEquals(setOf(1), c.noClip)
    }

    @Test
    fun firstCutTooCloseToStart_hasNoClip() {
        val cuts = listOf(cut(0.3, ""))
        val c = checkCuts(cuts, computeClips(cuts, 2.0, 0.0, 1000.0))
        assertEquals(setOf(0), c.noClip)
        assertEquals("too close to the start of the video, so it has no clip", c.warnings[0])
    }

    @Test
    fun leftover_isIgnored() {
        val leftover = cut(1.0, "8.000", note = LEFTOVER_NOTE_START + " when the video began", included = false)
        val c = check(listOf(leftover, cut(4.0, "2.000")))
        assertTrue(c.warnings.isEmpty())
        assertTrue(c.noClip.isEmpty())
    }

    @Test
    fun withoutLeftovers_dropsOnlyTheSwitchedOffLeftovers() {
        // (4.0, owner 2026-11-27) a time already on the timer when the video began is never kept; a real solve with a note stays
        val leftover = cut(1.0, "8.000", note = LEFTOVER_NOTE_START + " when the video began", included = false)
        val real = cut(30.0, "10.000", note = "time not steady (read on 3 of 9 frames) - check it")
        assertEquals(listOf(real), withoutLeftovers(listOf(leftover, real)))
    }
}
