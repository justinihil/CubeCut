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
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** (3.3-C) The solid-digit reader on drawn pictures. Every picture uses a font the reader is NOT given, as a real timer would. */
class SolidDigitsTest {

    private val styles = File("src/main/assets/digit_shapes.bin").inputStream().use { readDigitStyles(it) }

    /** The reader without the fonts of some families. */
    private fun without(vararg families: String) = DigitShapes.from(styles.filter { s -> families.none { s.name.startsWith("$it ") } })

    private fun ink(picture: ByteArray, i: Int): Int = picture[i].toInt() and 255

    /** [text] drawn with a style of the asset (44 px digits times [scale]). [slope] tilts the line, [lean] the digits. */
    private fun draw(
        text: String, style: String, scale: Double = 2.0, slope: Double = 0.0, lean: Double = 0.0,
        paper: Int = 30, pen: Int = 230, noise: Int = 0
    ): GrayPic {
        val s = styles.first { it.name == style }
        val n = s.size
        val w = 900
        val h = 500
        val cover = DoubleArray(w * h)
        var penX = 120.0
        fun put(picture: ByteArray, from: Int, to: Int) {
            for (y in 0 until h) for (x in 0 until w) {
                val ty = y - slope * (x - 100) - 220.0
                val gx = (x - penX - lean * (110 - ty)) / scale + from
                val gy = ty / scale
                val ix = floor(gx).toInt()
                val iy = floor(gy).toInt()
                if (ix < from || ix > to || ix + 1 >= n || iy < 0 || iy + 1 >= n) continue
                val fx = gx - ix
                val fy = gy - iy
                val up = ink(picture, iy * n + ix) * (1 - fx) + ink(picture, iy * n + ix + 1) * fx
                val down = ink(picture, (iy + 1) * n + ix) * (1 - fx) + ink(picture, (iy + 1) * n + ix + 1) * fx
                cover[y * w + x] = max(cover[y * w + x], (up * (1 - fy) + down * fy) / 255.0)
            }
        }
        for (c in text) {
            if (c == '.') {
                // a square point on the line the digits stand on
                val bottom = (0 until n).last { y -> (0 until n).any { x -> ink(s.digits[0], y * n + x) > 127 } }
                val dot = ByteArray(n * n)
                for (y in bottom - 5..bottom) for (x in 2..7) dot[y * n + x] = -1
                put(dot, 0, 10)
                penX += 11 * scale
            } else {
                val g = s.digits[c - '0']
                val cols = (0 until n).filter { x -> (0 until n).any { y -> ink(g, y * n + x) > 127 } }
                put(g, cols.first() - 1, cols.last() + 1)
                penX += (cols.last() - cols.first() + 6) * scale
            }
        }
        val rnd = Random(5)
        return GrayPic(w, h, ByteArray(w * h) {
            val v = paper + (pen - paper) * cover[it] + (if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0)
            v.roundToInt().coerceIn(0, 255).toByte()
        })
    }

    private fun read(p: GrayPic, examples: DigitShapes, dark: Boolean = false): String? = pickRow(findTimeRows(p, examples, listOf(dark)))?.text

    @Test
    fun assetHasAllDigitsOfEveryStyle() {
        assertEquals(21, styles.size)
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
