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

/** The puzzle logos' geometry: every puzzle has one, it stays inside its box, and NxN logos have N² stickers. */
class PuzzleIconsTest {
    private fun inBox(v: Float) = v >= 0f && v <= 1f

    @Test
    fun everyPuzzleAndOtherNames_drawInsideTheBox() {
        for (p in PUZZLES + listOf("Mirror blocks", "x")) {
            val shapes = puzzleShapes(p)
            assertTrue(p, shapes.isNotEmpty())
            for (s in shapes) when (s) {
                is LogoShape.Poly -> assertTrue(p, s.points.all { (x, y) -> inBox(x) && inBox(y) })
                is LogoShape.Dot -> assertTrue(p, inBox(s.x - s.r) && inBox(s.x + s.r) && inBox(s.y - s.r) && inBox(s.y + s.r))
                is LogoShape.Ring -> assertTrue(p, inBox(s.x - s.r - s.width / 2) && inBox(s.x + s.r + s.width / 2))
            }
        }
    }

    @Test
    fun nxn_hasOneStickerPerSquare() {
        for (n in 2..7) assertEquals(n * n, puzzleShapes("${n}x$n").size)
    }

    @Test
    fun megaminx_hasCentreEdgesAndCorners() {
        assertEquals(11, puzzleShapes("Megaminx").size)
    }
}
