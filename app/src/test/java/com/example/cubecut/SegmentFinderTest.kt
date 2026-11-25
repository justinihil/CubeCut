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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * (3.3-C) The seven-segment finder on a drawn display: separate strokes, three decimals, the last two digits blurred while it runs. Its
 * strokes have round ends and other gaps than the shapes they are compared with. The real videos are in [ReferenceVideoTest].
 */
class SegmentFinderTest {

    private val w = 520
    private val h = 420
    private val duration = 40.0
    private val pictures = HashMap<String, GrayPic>()

    /** The strokes of one digit, 0..1 across and down: from one point to the other. */
    private val strokes = mapOf(
        'a' to doubleArrayOf(0.2, 0.0, 0.8, 0.0), 'g' to doubleArrayOf(0.2, 0.5, 0.8, 0.5), 'd' to doubleArrayOf(0.2, 1.0, 0.8, 1.0),
        'f' to doubleArrayOf(0.0, 0.1, 0.0, 0.4), 'b' to doubleArrayOf(1.0, 0.1, 1.0, 0.4),
        'e' to doubleArrayOf(0.0, 0.6, 0.0, 0.9), 'c' to doubleArrayOf(1.0, 0.6, 1.0, 0.9)
    )

    private data class Look(
        val size: Double = 44.0, val degrees: Double = 0.0, val lean: Double = 0.0, val dark: Boolean = true, val noise: Int = 0, val strength: Double = 60.0
    )

    /** [text], right-aligned as on a display, in the middle of the picture or [dx], [dy] from it. [blurred]: the last two digits are a mix of two. */
    private fun draw(text: String, look: Look, dx: Double = 0.0, dy: Double = 0.0, blurred: Boolean = false): GrayPic {
        val ink = FloatArray(w * h)
        val cos = Math.cos(Math.toRadians(look.degrees))
        val sin = Math.sin(Math.toRadians(look.degrees))
        val digitW = 0.48 * look.size
        val pitch = 0.75 * look.size
        val thick = 0.055 * look.size
        fun line(x0: Double, y0: Double, x1: Double, y1: Double, amount: Float) {
            // only the part of the picture around the stroke
            fun px(x: Double, y: Double) = cos * (x + look.lean * y) - sin * y + w / 2.0 + dx
            fun py(x: Double, y: Double) = sin * (x + look.lean * y) + cos * y + h / 2.0 + dy
            val room = 2 * thick + 3
            val xs = listOf(px(x0, y0), px(x1, y1))
            val ys = listOf(py(x0, y0), py(x1, y1))
            for (py in maxOf(0, (ys.min() - room).toInt())..minOf(h - 1, (ys.max() + room).toInt()))
                for (px in maxOf(0, (xs.min() - room).toInt())..minOf(w - 1, (xs.max() + room).toInt())) {
                val ux = px - w / 2.0 - dx
                val uy = py - h / 2.0 - dy
                val y = -sin * ux + cos * uy
                val x = cos * ux + sin * uy - look.lean * y
                val t = (((x - x0) * (x1 - x0) + (y - y0) * (y1 - y0)) / ((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))).coerceIn(0.0, 1.0)
                if (Math.hypot(x - x0 - t * (x1 - x0), y - y0 - t * (y1 - y0)) < thick) ink[py * w + px] = maxOf(ink[py * w + px], amount)
            }
        }
        var right = 2.4 * pitch
        val decimals = text.length - 1 - text.indexOf('.')
        text.filter { it.isDigit() }.reversed().forEachIndexed { i, c ->
            if (i == decimals) right -= 0.14 * look.size
            val left = right - digitW
            fun put(digit: Int, amount: Float) {
                for (s in SEGMENT_DIGITS.first { it.first == digit }.second) {
                    val p = strokes.getValue(s)
                    line(left + p[0] * digitW, (p[1] - 0.5) * look.size, left + p[2] * digitW, (p[3] - 0.5) * look.size, amount)
                }
            }
            if (blurred && i < 2) { put(c - '0', 0.5f); put((c - '0' + 3) % 10, 0.5f) } else put(c - '0', 1f)
            right -= pitch
        }
        line(-2.4 * pitch, 0.9 * look.size, 2.4 * pitch, 0.9 * look.size, 0.3f)         // the display's edge
        val rnd = Random(3)
        return GrayPic(w, h, ByteArray(w * h) {
            val v = 120 + (if (look.dark) -look.strength else look.strength) * ink[it] + (if (look.noise > 0) rnd.nextInt(-look.noise, look.noise + 1) else 0)
            v.toInt().coerceIn(0, 255).toByte()
        })
    }

    /** One solve: 0.000 until second 6, runs to 21.437, then stays. */
    private fun at(t: Double, look: Look): GrayPic {
        val shown = if (t < 6) 0.0 else if (t < 27.437) floor((t - 6) * 1000 + 1e-6) / 1000 else 21.437
        val text = "%.3f".format(Locale.ROOT, shown)
        return pictures.getOrPut("$text $look") { draw(text, look, blurred = t >= 6 && t < 27.437) }
    }

    private fun check(look: Look) {
        val r = findSegments({ t -> at(t, look) }, duration)
        val f = r.found
        assertNotNull("not found in ${r.places} places, ${r.checked} rows checked", f)
        f!!
        assertEquals(3, f.grid.decimals)
        assertEquals(look.dark, f.grid.dark)
        for (k in f.shown.indices) assertEquals((f.t - 6 + 0.5 * k) % 10, f.shown[k] % 10, 0.11)
        // with that grid it reads stopped times
        for (text in listOf("21.437", "8.906", "15.283")) assertEquals(text, segmentText(f.grid.read(draw(text, look)), 3))
    }

    @Test fun plainDisplay() = check(Look())
    @Test fun lightDigits() = check(Look(dark = false))
    @Test fun smallDigits() = check(Look(size = 30.0))
    @Test fun bigDigits() = check(Look(size = 80.0))
    @Test fun leaningDigits() = check(Look(lean = -0.2))
    @Test fun turnedLeft() = check(Look(degrees = -33.0))
    @Test fun turnedRight() = check(Look(degrees = 33.0))
    @Test fun noisyPicture() = check(Look(noise = 8))
    @Test fun faintDigits() = check(Look(strength = 25.0))

    @Test
    fun aDisplayThatDoesntRun_isNotATimer() {
        val still = draw("12.345", Look())
        val r = findSegments({ still }, duration)
        assertNull("${r.found?.shown}", r.found)
        assertTrue("nothing was checked", r.checked > 0)
    }

    @Test
    fun aDisplayThatRunsTooFast_isNotATimer() {
        val r = findSegments({ t -> draw("%.3f".format(Locale.ROOT, (t * 1.3) % 60), Look()) }, 12.0)
        assertNull("${r.found?.shown}", r.found)
    }

    @Test
    fun solidDigitsAreNotASevenSegmentTimer() {
        val r = findSegments({ t -> DrawnTime.Canvas(420, 240).put("%.1f".format(Locale.ROOT, t), "roboto Regular", 60.0, 60.0, 1.0).pic() }, 12.0)
        assertNull("${r.found?.shown}", r.found)
    }

    @Test
    fun theGridFollowsASmallMove() {
        val look = Look()
        val f = findSegments({ t -> at(t, look) }, duration).found!!
        val (there, fits) = f.grid.located(draw("21.437", look, dx = 5.0, dy = -4.0), 6)
        assertTrue("$fits", fits > 0.8)
        assertEquals(f.grid.middleX + 5, there.middleX, 2.5)
        assertEquals(f.grid.middleY - 4, there.middleY, 2.5)
        assertEquals("21.437", segmentText(there.read(draw("21.437", look, dx = 5.0, dy = -4.0)), 3))
    }

    /** Against the definition, pixel by pixel: grow by the circle, then take back what isn't grown all around. */
    /** Two solves; the display is hit and jumps while it shows the first one, and is covered for a while in the second hold. */
    @Test fun followsAndScans() = followsAndScans(34.0, -22.0)

    /** Pushed 2.4 digit heights down, as by a thumb on the reset button. */
    @Test fun followsAFarPush() = followsAndScans(-20.0, 105.0)

    private fun followsAndScans(jumpX: Double, jumpY: Double) {
        pictures.clear()
        val look = Look(lean = -0.1)
        fun picture(t: Double): GrayPic {
            val running = t in 4.0..13.437 || t in 30.0..42.345
            val shown = when {
                t < 4 || (t >= 24 && t < 30) -> 0.0
                t < 13.437 -> floor((t - 4) * 1000 + 1e-6) / 1000
                t < 24 -> 9.437
                t < 42.345 -> floor((t - 30) * 1000 + 1e-6) / 1000
                else -> 12.345
            }
            if (t in 46.0..49.0) return GrayPic(w, h, ByteArray(w * h) { 90 })
            val jumped = t >= 20
            val text = "%.3f".format(Locale.ROOT, shown)
            return pictures.getOrPut("$text $running $jumped") { draw(text, look, if (jumped) jumpX else 0.0, if (jumped) jumpY else 0.0, blurred = running && shown > 0) }
        }
        val duration = 58.0
        val found = findSegments({ picture(it) }, duration).found
        assertNotNull(found)
        val timer = SegmentTracker.follow(found!!, { picture(it) }, duration)
        val frame = 1.0 / 30
        val solves = scanSegments(timer, duration, frame).solves
        assertEquals(listOf("9.437", "12.345"), solves.map { it.timeText })
        assertEquals(13.437, solves[0].stopSec, 2 * frame)
        assertEquals(42.345, solves[1].stopSec, 2 * frame)
        for (s in solves) assertEquals("", s.note)
        val (across, down) = timer.moved()
        assertEquals(abs(jumpX), across, 4.0)
        assertEquals(abs(jumpY), down, 4.0)
    }

    @Test
    fun aDisplayCutOffByTheEdge_isNotRead() {
        val look = Look()
        val f = findSegments({ t -> at(t, look) }, duration).found!!
        assertFalse(f.grid.isCutOff(w, h))
        assertTrue(f.grid.moved(w / 2.0, 0.0).isCutOff(w, h))
    }

    @Test
    fun closing() {
        val rnd = Random(1)
        for (bw in listOf(64, 70, 130)) for (k in listOf(3, 5, 9)) {
            val bh = 24
            val b = Bits(bw, bh)
            val on = Array(bh) { y -> BooleanArray(bw) { x -> (rnd.nextInt(12) == 0).also { if (it) b.set(x, y) } } }
            val r = k / 2
            val reach = IntArray(k) { Math.round(r * Math.sqrt((r * r - (it - r) * (it - r)) / (r * r).toDouble())).toInt() }
            fun around(x: Int, y: Int, a: Array<BooleanArray>, outside: Boolean) = (-r..r).flatMap { dy ->
                (-reach[dy + r]..reach[dy + r]).map { dx -> if (y + dy in 0 until bh && x + dx in 0 until bw) a[y + dy][x + dx] else outside }
            }
            val grown = Array(bh) { y -> BooleanArray(bw) { x -> around(x, y, on, false).any { it } } }
            val want = Array(bh) { y -> BooleanArray(bw) { x -> around(x, y, grown, true).all { it } } }
            val got = b.closed(k).toBooleans()
            for (y in 0 until bh) for (x in 0 until bw) assertEquals("$bw $k: $x, $y", want[y][x], got[y * bw + x])
        }
    }

    @Test
    fun noPicture_isNotFound() {
        assertNull(findSegments({ null }, 30.0).found)
    }

    @Test
    fun canBeCancelled() {
        try {
            findSegments({ t -> at(t, Look()) }, duration, isCancelled = { true })
            fail("not cancelled")
        } catch (e: ScanCancelled) {
            // as it should
        }
    }

    /** The solid search asks for the other kind early, when no number was seen running, and stops when there is one. */
    @Test
    fun theSolidSearchAsksForTheOtherKind() {
        val fonts = DrawnTime.without("roboto")
        val empty = GrayPic(64, 64, ByteArray(64 * 64))
        var asked = 0
        var pictures = 0
        assertNull(findTimer({ pictures++; empty }, 600.0, fonts, otherKind = { asked++; true }).timer)
        assertEquals(1, asked)
        val early = pictures
        pictures = 0
        assertNull(findTimer({ pictures++; empty }, 600.0, fonts, otherKind = { asked++; false }).timer)
        assertEquals(2, asked)
        val all = pictures
        pictures = 0
        assertNull(findTimer({ pictures++; empty }, 600.0, fonts).timer)
        assertEquals("going on looks at the same places", pictures, all)
        assertTrue("$early of $all", early * 3 < all)
    }
}
