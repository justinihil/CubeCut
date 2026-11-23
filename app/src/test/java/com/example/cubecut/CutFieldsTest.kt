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

/** The v7 (3.2-A) cut and video fields that have logic of their own: *Shared on* storage, the upgrade's fill, and tag fallback. */
class CutFieldsTest {

    @Test
    fun sharedOn_roundTrips() {
        assertEquals("TikTok,YouTube", encodeSharedOn(listOf("TikTok", " YouTube ", "", "TikTok")))
        assertEquals(listOf("TikTok", "YouTube"), decodeSharedOn("TikTok, YouTube,"))
        assertEquals("", encodeSharedOn(emptyList()))
        assertTrue(decodeSharedOn("").isEmpty())
    }

    @Test
    fun v7Fill_readsPlatformsFromTheNote_likeTheButtons() {
        assertEquals(listOf("TikTok", "YouTube"), sharedOnFromNoteV7("PB! youtube, TikTok"))
        assertEquals(listOf("X"), sharedOnFromNoteV7("posted on X"))
        assertTrue(sharedOnFromNoteV7("Xbox controller, x marks the spot").isEmpty())   // X is whole-word and capital only
        assertTrue(sharedOnFromNoteV7("").isEmpty())
        // the frozen list matches today's buttons, so the fill agrees with what the Gallery showed before the upgrade
        val note = SOCIAL_PLATFORMS.joinToString(", ")
        assertEquals(platformsIn(note), sharedOnFromNoteV7(note))
    }

    @Test
    fun sharedOn_toggleKeepsThePlatformOrder() {
        assertEquals(listOf("TikTok", "YouTube"), toggleShared(listOf("YouTube"), "TikTok"))
        assertEquals(listOf("YouTube"), toggleShared(listOf("TikTok", "YouTube"), "TikTok"))
        assertEquals(listOf("TikTok", "Mastodon"), toggleShared(listOf("Mastodon"), "TikTok"))   // a name no button has stays
    }

    @Test
    fun sharedOn_filter() {
        assertTrue(sharedMatches(listOf("TikTok"), null))
        assertTrue(sharedMatches(emptyList(), ""))
        assertTrue(!sharedMatches(listOf("TikTok"), ""))
        assertTrue(sharedMatches(listOf("TikTok", "X"), "X"))
        assertTrue(!sharedMatches(listOf("TikTok"), "YouTube"))
    }

    @Test
    fun clipSharedOn_comesFromTheField_notTheNote() {
        val c = GalleryClip(footage(), 0, 1, CutItem(10.0, "9.000", true, "", true, userNote = "posted on TikTok", sharedOn = listOf("YouTube")), null, false)
        assertEquals(listOf("YouTube"), c.sharedList)
    }

    private fun footage(puzzle: String = "", category: String = "") = Footage(
        id = 1, uri = "content://x", displayName = "a.mp4", sizeBytes = 1, durationMs = 1000, title = "a", dateTakenMs = 0,
        addedMs = 0, lastScanMs = 0, beforePad = 2.5f, afterPad = 2.5f, scrambleAll = true, cutCount = 1, timerFrameMs = -1,
        timerCorners = "", timerTyped = "", trashedMs = 0, puzzle = puzzle, category = category
    )

    @Test
    fun clipTags_followTheVideo_unlessSetOnTheClip() {
        val f = footage(puzzle = "3x3", category = "OH")
        val follows = GalleryClip(f, 0, 1, CutItem(10.0, "9.000", true, "", true), null, false)
        assertEquals("3x3", follows.puzzle); assertEquals("OH", follows.category)
        val own = GalleryClip(f, 0, 1, CutItem(10.0, "9.000", true, "", true, puzzle = "2x2"), null, false)
        assertEquals("2x2", own.puzzle); assertEquals("OH", own.category)
        assertEquals("", GalleryClip(footage(), 0, 1, CutItem(10.0, "9.000", true, "", true), null, false).puzzle)
    }

    private fun single(trimStart: Double? = null, trimEnd: Double? = null, durationMs: Long = 20_000) = Footage(
        id = 2, uri = "content://y", displayName = "b.mp4", sizeBytes = 1, durationMs = durationMs, title = "b", dateTakenMs = 0,
        addedMs = 0, lastScanMs = 0, beforePad = 2.5f, afterPad = 2.5f, scrambleAll = true, cutCount = 1, timerFrameMs = -1,
        timerCorners = "", timerTyped = "", trashedMs = 0, kind = KIND_SINGLE, trimStartSec = trimStart, trimEndSec = trimEnd
    )

    @Test
    fun singleSolve_untrimmed_isTheWholeFile() {
        val f = single()
        assertTrue(f.isSingle)
        assertTrue(!f.isTrimmed)
        assertEquals(Pair(0.0, 20.0), singleRange(f))
        // the buffers and the scramble don't matter: the clip is the file
        val clips = clipsFor(f, listOf(CutItem(20.0, "12.000", true, "", false, plus2Count = 1)))
        assertEquals(1, clips.size)
        assertEquals(0.0, clips[0].startSec, 1e-9)
        assertEquals(20.0, clips[0].endSec, 1e-9)
        assertEquals(1, clips[0].plus2Count)
        assertEquals("b_Solve_01_14.000s_plus2.mp4", ClipExporter.fileName(clips[0], "b"))
    }

    @Test
    fun singleSolve_trimmed_isThatPart_andStaysInTheFile() {
        val f = single(3.0, 15.5)
        assertTrue(f.isTrimmed)
        assertEquals(Pair(3.0, 15.5), singleRange(f))
        assertEquals(Pair(0.0, 20.0), singleRange(single(-1.0, 99.0)))      // never outside the file
        assertEquals(Pair(5.0, 20.0), singleRange(single(5.0, null)))
        assertTrue(clipsFor(f, emptyList()).isEmpty())
    }

    @Test
    fun singleSolve_titleHasNoNumber() {
        val c = GalleryClip(single(), 0, 1, CutItem(20.0, "9.000", true, "", true), null, false)
        assertEquals("b · 9.000s", c.title)
        assertEquals("One solve", c.placeText)
        val s = GalleryClip(footage(), 2, 5, CutItem(20.0, "9.000", true, "", true), null, false)
        assertEquals("Solve 3 of 5", s.placeText)
    }

    @Test
    fun newVideo_isASession_untrimmed() {
        val f = footage()
        assertEquals(KIND_SESSION, f.kind)
        assertEquals(null, f.trimStartSec)
        assertEquals(null, f.trimEndSec)
    }
}
