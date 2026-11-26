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
import org.junit.Test

/** (3.2-B) Puzzle and type tags: how a clip's tag is worked out, shown, filtered, set from a selection, and put into file names. */
class TagsTest {

    @Test
    fun resolve_followsTheVideo_unlessTheClipHasItsOwn_orIsUnticked() {
        assertEquals("3x3", resolveTag("", "3x3"))
        assertEquals("2x2", resolveTag("2x2", "3x3"))
        assertEquals("", resolveTag(NO_TAG, "3x3"))         // the editor's "not this puzzle" untick
        assertEquals("", resolveTag("", ""))
    }

    @Test
    fun clean_tidiesTypedNames_andMatchesTheButtons() {
        assertEquals("Mirror blocks", cleanTag("  Mirror   blocks ", PUZZLES))
        assertEquals("OH", cleanTag("oh", CATEGORIES))
        assertEquals("Megaminx", cleanTag("MEGAMINX", PUZZLES))
        assertEquals("", cleanTag(NO_TAG, PUZZLES))         // a typed "-" never becomes the hidden "not tagged" value
        assertEquals(24, cleanTag("x".repeat(40), PUZZLES).length)
    }

    @Test
    fun label_leavesNormalOut_unlessItIsAllThereIs() {
        assertEquals("3x3 OH", tagLabel("3x3", "OH"))
        assertEquals("3x3", tagLabel("3x3", NORMAL_CATEGORY))
        assertEquals("Normal", tagLabel("", NORMAL_CATEGORY))
        assertEquals("BLD", tagLabel("", "BLD"))
        assertEquals("", tagLabel("", ""))
    }

    @Test
    fun order_putsButtonsFirst_thenTypedNames() {
        assertEquals(
            listOf("3x3", "Clock", "Mirror", "zz custom"),
            orderTags(listOf("zz custom", "Clock", "", "3x3", "Mirror", "3x3"), PUZZLES)
        )
    }

    @Test
    fun filter_anyOrNotTaggedOrOne() {
        assertTrue(tagMatches("3x3", null))
        assertTrue(tagMatches("", ""))
        assertFalse(tagMatches("3x3", ""))
        assertTrue(tagMatches("3x3", "3x3"))
        assertFalse(tagMatches("2x2", "3x3"))
    }

    @Test
    fun selection_partOfAVideo_setsTheClipsOwnTag() {
        assertEquals("2x2", applyTagChoice("", "2x2"))
        assertEquals("2x2", applyTagChoice("2x2", null))     // mixed, left as it was
        assertEquals("", applyTagChoice("2x2", ""))          // "As the video"
        assertEquals(NO_TAG, applyTagChoice("", NO_TAG))
    }

    @Test
    fun selection_wholeVideo_tagsTheVideo_andItsClipsFollow() {
        assertEquals("2x2", videoTagFor("3x3", "2x2"))
        assertEquals("3x3", videoTagFor("3x3", ""))         // "As the video": the video keeps its tag
        assertEquals("3x3", videoTagFor("3x3", null))
        assertEquals("", videoTagFor("3x3", NO_TAG))        // "Not tagged"
        assertEquals("", resetOwnTag("Skewb", "2x2"))
        assertEquals("Skewb", resetOwnTag("Skewb", null))
    }

    private fun clip(index: Int, time: String) = Clip(index + 1, 0.0, 10.0, time, index, true, 0.0, false)

    @Test
    fun fileName_putsTheTagAfterTheTitle() {
        assertEquals("Sunday_3x3 OH_Solve_03_11.263s.mp4", ClipExporter.fileName(clip(2, "11.263"), "Sunday", "3x3 OH"))
        assertEquals("Sunday_Solve_03_11.263s.mp4", ClipExporter.fileName(clip(2, "11.263"), "Sunday", ""))
        assertEquals("Sunday_Solve_03_11.263s.mp4", ClipExporter.fileName(clip(2, "11.263"), "Sunday"))
        assertEquals("Sunday_a_b_Solve_01_unknown.mp4", ClipExporter.fileName(clip(0, ""), "Sunday", "a/b"))
        assertEquals("Solve_01_1m02.345s.mp4", ClipExporter.fileName(clip(0, "1:02.345")))
    }

    @Test
    fun galleryClip_untickedClip_isNotTagged() {
        val f = Footage(
            id = 1, uri = "content://x", displayName = "a.mp4", sizeBytes = 1, durationMs = 1000, title = "a", dateTakenMs = 0,
            addedMs = 0, lastScanMs = 0, beforePad = 2.5f, afterPad = 2.5f, scrambleAll = true, cutCount = 1, timerFrameMs = -1,
            timerCorners = "", timerTyped = "", trashedMs = 0, puzzle = "3x3", category = "OH"
        )
        val unticked = GalleryClip(f, 0, 1, CutItem(10.0, "9.000", true, "", true, puzzle = NO_TAG, category = NO_TAG), null, false)
        assertEquals("", unticked.puzzle)
        assertEquals("", unticked.tagText)
        assertEquals("3x3 OH", GalleryClip(f, 0, 1, CutItem(10.0, "9.000", true, "", true), null, false).tagText)
    }

    // ------------------------------------------------------------ a clip tagged the same as its video follows it

    @Test
    fun normalizeClipTag_sameAsVideoIsStoredAsFollow() {
        assertEquals("", normalizeClipTag("3x3", "3x3"))       // same as the video: no distinction
        assertEquals("", normalizeClipTag("", "3x3"))
        assertEquals("4x4", normalizeClipTag("4x4", "3x3"))    // a different puzzle stays its own
        assertEquals(NO_TAG, normalizeClipTag(NO_TAG, "3x3"))  // "not this puzzle" stays
        assertEquals("", normalizeClipTag(NO_TAG, ""))         // not tagged in a video that isn't tagged either
        assertEquals("Skewb", normalizeClipTag("Skewb", ""))
    }

    @Test
    fun followsVideoTags_ignoresTheSameTagStoredOnTheClip() {
        assertTrue(followsVideoTags("3x3", "", "3x3", "OH"))
        assertTrue(followsVideoTags("3x3", "OH", "3x3", "OH"))
        assertFalse(followsVideoTags("3x3", "BLD", "3x3", "OH"))
        assertFalse(followsVideoTags(NO_TAG, NO_TAG, "3x3", ""))
    }

    // ------------------------------------------------------------ (4.0-F) cube, place, penalty filter

    @Test
    fun tagKinds_placeIsTheVideosOnly_andHasNoTypedNames() {
        assertTrue(TagKind.PLACE !in TagKind.forClips)
        assertTrue(TagKind.CUBE in TagKind.forClips)
        assertTrue(!TagKind.PLACE.typedNames)
        assertEquals(listOf("Home", "Competition", "Other"), TagKind.PLACE.fixed)
    }

    @Test
    fun aClipsCube_followsItsVideo_unlessItHasItsOwn() {
        assertEquals("GAN 12", resolveTag("", "GAN 12"))
        assertEquals("RS3M", resolveTag("RS3M", "GAN 12"))
        assertEquals("", resolveTag(NO_TAG, "GAN 12"))
    }

    @Test
    fun penaltyFilter_matches() {
        assertTrue(PenaltyFilter.ANY.matches(0, false))
        assertTrue(PenaltyFilter.WITH.matches(1, false) && PenaltyFilter.WITH.matches(0, true) && !PenaltyFilter.WITH.matches(0, false))
        assertTrue(PenaltyFilter.PLUS2.matches(2, false) && !PenaltyFilter.PLUS2.matches(0, true))
        assertTrue(PenaltyFilter.DNF.matches(0, true) && !PenaltyFilter.DNF.matches(1, false))
        assertTrue(PenaltyFilter.NONE.matches(0, false) && !PenaltyFilter.NONE.matches(1, false))
    }
}
