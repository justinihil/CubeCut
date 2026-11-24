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

import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** (3.3-C) Draws numbers for the tests of the solid-digit reader and the finder, with the app's own example digits. */
object DrawnTime {

    val styles: List<DigitStyle> = File("src/main/assets/digit_shapes.bin").inputStream().use { readDigitStyles(it) }

    /** The reader without the fonts of some families: a real timer's font is usually one it doesn't have either. */
    fun without(vararg families: String) = DigitShapes.from(styles.filter { s -> families.none { s.name.startsWith("$it ") } })

    /** An empty canvas; numbers are [put] on it, then [pic] makes the picture. */
    class Canvas(val w: Int, val h: Int) {
        private val cover = FloatArray(w * h)

        /**
         * [text] in [style], starting at ([x], [y]): 44 px digits times [scale]. [slope] tilts the line, [lean] the digits.
         * ':' is a colon that stands on the line, ';' draws one halfway up (some fonts do that).
         */
        fun put(text: String, style: String, x: Double, y: Double, scale: Double, slope: Double = 0.0, lean: Double = 0.0): Canvas {
            val s = styles.first { it.name == style }
            val n = s.size
            var pen = x
            for (c in text) {
                if (!c.isDigit()) {
                    // square dots: a point stands on the line the digits stand on
                    val bottom = (0 until n).last { r -> (0 until n).any { q -> ink(s.digits[0], r * n + q) > 127 } }
                    val dot = ByteArray(n * n)
                    for (up in when (c) { ':' -> listOf(0, 26); ';' -> listOf(8, 29); else -> listOf(0) })
                        for (r in bottom - up - 5..bottom - up) for (q in 2..7) dot[r * n + q] = -1
                    stamp(dot, n, 0, 10, pen, x, y, scale, slope, lean)
                    pen += 11 * scale
                } else {
                    val g = s.digits[c - '0']
                    val cols = (0 until n).filter { q -> (0 until n).any { r -> ink(g, r * n + q) > 127 } }
                    stamp(g, n, cols.first() - 1, cols.last() + 1, pen, x, y, scale, slope, lean)
                    pen += (cols.last() - cols.first() + 6) * scale
                }
            }
            return this
        }

        private fun stamp(p: ByteArray, n: Int, from: Int, to: Int, pen: Double, x0: Double, y0: Double, scale: Double, slope: Double, lean: Double) {
            val reach = n * scale * 1.6
            val xa = max(0, (pen - reach).toInt())
            val xb = min(w - 1, (pen + reach).toInt())
            for (py in 0 until h) for (px in xa..xb) {
                val ty = py - slope * (px - x0) - y0
                val gx = (px - pen - lean * (n * scale / 2 - ty)) / scale + from
                val gy = ty / scale
                val ix = floor(gx).toInt()
                val iy = floor(gy).toInt()
                if (ix < from || ix > to || ix + 1 >= n || iy < 0 || iy + 1 >= n) continue
                val fx = gx - ix
                val fy = gy - iy
                val up = ink(p, iy * n + ix) * (1 - fx) + ink(p, iy * n + ix + 1) * fx
                val down = ink(p, (iy + 1) * n + ix) * (1 - fx) + ink(p, (iy + 1) * n + ix + 1) * fx
                cover[py * w + px] = max(cover[py * w + px], ((up * (1 - fy) + down * fy) / 255.0).toFloat())
            }
        }

        fun pic(paper: Int = 30, pen: Int = 230, noise: Int = 0): GrayPic {
            val rnd = Random(5)
            return GrayPic(w, h, ByteArray(w * h) {
                val v = paper + (pen - paper) * cover[it] + (if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0)
                v.roundToInt().coerceIn(0, 255).toByte()
            })
        }
    }

    private fun ink(picture: ByteArray, i: Int): Int = picture[i].toInt() and 255
}
