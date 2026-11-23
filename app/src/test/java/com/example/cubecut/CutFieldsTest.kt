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

    @Test
    fun newVideo_isASession_untrimmed() {
        val f = footage()
        assertEquals(KIND_SESSION, f.kind)
        assertEquals(null, f.trimStartSec)
        assertEquals(null, f.trimEndSec)
    }
}
