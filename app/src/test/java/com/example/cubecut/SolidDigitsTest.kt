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
import kotlin.math.abs

/** (3.3-C) The solid-digit reader on drawn pictures. Every picture uses a font the reader is NOT given, as a real timer would. */
class SolidDigitsTest {

    private val styles = DrawnTime.styles
    private fun without(vararg families: String) = DrawnTime.without(*families)

    private fun draw(
        text: String, style: String, scale: Double = 2.0, slope: Double = 0.0, lean: Double = 0.0, paper: Int = 30, pen: Int = 230, noise: Int = 0,
        pointWidth: Double = 11.0
    ): GrayPic = DrawnTime.Canvas(900, 500).put(text, style, 120.0, 220.0, scale, slope, lean, pointWidth).pic(paper, pen, noise)

    private fun read(p: GrayPic, examples: DigitShapes, dark: Boolean = false): String? = pickRow(findTimeRows(p, examples, listOf(dark)))?.text

    @Test
    fun assetHasAllDigitsOfEveryStyle() {
        assertEquals(22, styles.size)
        for (s in styles) {
            assertEquals(10, s.digits.size)
            for (d in s.digits) assertTrue(s.name, d.any { it.toInt() != 0 })
        }
    }

    @Test
    fun everyDigit_inFontsTheReaderDoesNotHave() {
        for (family in listOf("arimo", "roboto", "lato", "barlow")) {
            val r = without(family)
            for (t in listOf("12.69", "13.58", "40.27", "9.41", "0.00")) assertEquals(family, t, read(draw(t, "$family Regular"), r))
        }
    }

    @Test
    fun sizes_fromSmallToBig() {
        val r = without("arimo")
        for (scale in listOf(0.6, 1.0, 2.0, 3.0)) assertEquals("scale $scale", "12.69", read(draw("12.69", "arimo Regular", scale), r))
    }

    @Test
    fun tilted_leaning_darkAndFaint() {
        val r = without("arimo")
        for (slope in listOf(-0.5, -0.27, 0.27)) assertEquals("slope $slope", "13.47", read(draw("13.47", "arimo Regular", slope = slope), r))
        for (lean in listOf(-0.3, 0.3)) assertEquals("lean $lean", "13.47", read(draw("13.47", "arimo Regular", lean = lean), r))
        assertEquals("turned", "13.47", read(draw("13.47", "arimo Regular", slope = -0.3, lean = -0.3), r))
        assertEquals("light theme", "13.47", read(draw("13.47", "arimo Regular", paper = 220, pen = 40), r, dark = true))
        assertEquals("faint", "13.47", read(draw("13.47", "arimo Regular", paper = 10, pen = 45), r))
        assertEquals("noisy", "13.47", read(draw("13.47", "arimo Regular", noise = 20), r))
    }

    @Test
    fun notATime_isNotRead() {
        val r = without()
        assertNull("no point", read(draw("1269", "arimo Regular"), r))
        assertNull("two points", read(draw("1.2.69", "arimo Regular"), r))
        assertNull("nothing there", read(GrayPic(400, 300, ByteArray(400 * 300) { 60 }), r))
        assertNull("wrong way round", read(draw("12.69", "arimo Regular"), r, dark = true))
    }

    @Test
    fun minutes_inFontsTheReaderDoesNotHave() {
        for (family in listOf("arimo", "roboto", "lato", "barlow", "robotomono")) {
            val r = without(family)
            for (t in listOf("1:04.42", "2:47.85", "10:36.09", "1:59.9", "0:09.41", "3:00.127"))
                for (colon in listOf(':', ';')) assertEquals("$family $colon", t, read(draw(t.replace(':', colon), "$family Regular", 1.5), r))
        }
    }

    @Test
    fun minutes_tiltedLeaningSmallAndFaint() {
        val r = without("arimo")
        for (slope in listOf(-0.35, -0.2, 0.2)) assertEquals("slope $slope", "1:04.42", read(draw("1:04.42", "arimo Regular", 1.5, slope = slope), r))
        for (lean in listOf(-0.3, 0.3)) assertEquals("lean $lean", "1:04.42", read(draw("1:04.42", "arimo Regular", 1.5, lean = lean), r))
        for (scale in listOf(0.6, 1.0, 2.5)) assertEquals("scale $scale", "1:04.42", read(draw("1:04.42", "arimo Regular", scale), r))
        assertEquals("light theme", "1:04.42", read(draw("1:04.42", "arimo Regular", 1.5, paper = 220, pen = 40), r, dark = true))
        assertEquals("noisy", "1:04.42", read(draw("1:04.42", "arimo Regular", 1.5, noise = 20), r))
    }

    @Test
    fun fixedWidthFonts_wherePointsAreAsWideAsDigits() {
        val r = without("robotomono")
        for (t in listOf("10.161", "11.681", "7.480", "1:04.423")) assertEquals(t, read(draw(t, "robotomono Regular", 1.2, pointWidth = 38.0), r))
        // without a point in between, numbers that far apart stay two numbers
        assertNull(read(draw("11 681", "robotomono Regular", 1.2, pointWidth = 38.0), r))
    }

    @Test
    fun minutes_thatCannotBe_areNotRead() {
        val r = without()
        assertNull("no decimals (a clock)", read(draw("12:45", "arimo Regular"), r))
        assertNull("75 seconds", read(draw("1:75.20", "arimo Regular", 1.5), r))
        assertNull("one digit of seconds", read(draw("1:5.20", "arimo Regular", 1.5), r))
        assertNull("colon behind the point", read(draw("1.04:42", "arimo Regular", 1.5), r))
        assertNull("hours", read(draw("1:02:03.4", "arimo Regular", 1.2), r))
    }

    @Test
    fun shownSeconds_ofBothForms() {
        assertEquals(12.69, shownSeconds("12.69"), 1e-9)
        assertEquals(64.42, shownSeconds("1:04.42"), 1e-9)
        assertEquals(636.0, shownSeconds("10:36.0"), 1e-9)
        assertEquals(0.0, shownSeconds("0:00.00"), 1e-9)
    }

    @Test
    fun rowTellsWhereItIs() {
        val row = pickRow(findTimeRows(draw("12.69", "arimo Regular"), without("arimo"), listOf(false), offX = 1000, offY = 500))!!
        assertEquals(12.69, row.seconds, 1e-9)
        assertEquals(2, row.decimals)
        assertEquals(88.0, row.height, 6.0)
        assertTrue("left ${row.left}", row.left in 1110..1140)
        assertTrue(abs((row.bottom - row.top) - row.height) < 12)
        assertFalse(row.dark)
    }

    @Test
    fun learnedShapes_readMoreThanTheyWereLearnedFrom() {
        val fonts = without("ptserif")
        val shapes = List(10) { ArrayList<FloatArray>() }
        for (t in listOf("10.23", "45.67", "89.01")) {
            val row = pickRow(findTimeRows(draw(t, "ptserif Regular", slope = -0.27), fonts, listOf(false)))!!
            t.filter { it != '.' }.forEachIndexed { i, c -> shapes[c - '0'].add(row.shapes[i]) }
        }
        val own = fonts.learned(shapes)
        assertTrue(own.isLearned)
        assertEquals("38.52", read(draw("38.52", "ptserif Regular", slope = -0.27), own))
        assertEquals("96.74", read(draw("96.74", "ptserif Regular", scale = 1.0, slope = -0.27), own))
    }

    @Test
    fun blackAndWhite_comparesWithTheSurroundings() {
        // a light square on a dark ground that gets slowly lighter: the square stands out everywhere, the ground nowhere
        val p = GrayPic(200, 100, ByteArray(200 * 100) {
            val x = it % 200
            val y = it / 200
            (if (x in 95..104 && y in 45..54) 200 else x / 2).toByte()
        })
        val on = standsOut(p, 31, false)
        assertTrue(on[50 * 200 + 100])
        assertFalse(on[50 * 200 + 20])
        assertFalse(on[50 * 200 + 190])
    }
}
