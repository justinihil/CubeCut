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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

/**
 * (3.3-C) The finder on a drawn session: csTimer's way of showing things (inspection in whole seconds, one decimal while running, two
 * when stopped, in a font the finder isn't given), next to smaller numbers that don't run. The real videos are in [ReferenceVideoTest].
 */
class TimerFinderTest {

    private val fonts = DrawnTime.without("roboto")
    private val times = listOf(12.69, 14.07, 9.41, 13.88, 11.53, 16.32, 12.36, 15.10)
    private val cycle = 32.0
    private val duration = times.size * cycle
    private val pictures = HashMap<String, GrayPic>()

    /** What the timer shows at second [t]: 4 s 0.00, 6 s inspection, the solve, then the stopped time for the rest of the 32 s. */
    private fun shown(t: Double): String {
        val i = (t / cycle).toInt().coerceIn(times.indices)
        val s = t - i * cycle
        return when {
            s < 4 -> "0.00"
            s < 10 -> "${(s - 4).toInt()}"
            s < 10 + times[i] -> "%.1f".format(Locale.ROOT, Math.floor((s - 10) * 10 + 1e-9) / 10)
            else -> "%.2f".format(Locale.ROOT, times[i])
        }
    }

    private fun draw(main: String?): GrayPic = pictures.getOrPut(main ?: "") {
        val c = DrawnTime.Canvas(420, 240)
        if (main != null) c.put(main, "roboto Regular", 60.0, 60.0, 1.0, slope = -0.2)
        // averages and a list that don't run
        c.put("12.51", "lato Regular", 60.0, 150.0, 0.55).put("13.01", "lato Regular", 200.0, 150.0, 0.55).pic()
    }

    @Test
    fun findsAndLearnsTheRunningNumber() {
        val r = findTimer({ t -> draw(shown(t)) }, duration, fonts)
        val timer = r.timer
        assertNotNull("${r.notes}", timer)
        timer!!
        assertEquals(1, timer.runningDecimals)
        assertEquals(2, timer.stoppedDecimals)
        assertTrue("${r.notes}", timer.examples.isLearned)
        assertTrue("it saw ${r.frozen}", r.frozen.isNotEmpty() && r.frozen.all { it in times.map { t -> "%.2f".format(Locale.ROOT, t) } })
        assertTrue("${timer.area}", timer.area.top < 60 && timer.area.left <= 60)
        // with what it learned, it reads every stopped time
        for (t in times) assertEquals("%.2f".format(Locale.ROOT, t), timer.read(draw("%.2f".format(Locale.ROOT, t)))?.text)
    }

    @Test
    fun numbersThatDontRun_areNotATimer() {
        val r = findTimer({ draw("12.69") }, 20.0, fonts)
        assertNull(r.timer)
        assertTrue(r.frozen.isEmpty())
    }

    @Test
    fun anInspectionCounterOnly_isNotATimer() {
        val r = findTimer({ t -> draw("${(t % 15).toInt()}") }, 30.0, fonts)
        assertNull("${r.notes}", r.timer)
    }

    @Test
    fun aNumberThatRunsTooFast_isNotATimer() {
        val r = findTimer({ t -> draw("%.1f".format(Locale.ROOT, (t * 1.7) % 60)) }, 20.0, fonts)
        assertNull("${r.notes}", r.timer)
    }

    /** A second timer runs along (like a QiYi linked to csTimer), drawn [scale] times the size of the first. */
    private fun withSecond(t: Double, scale: Double): GrayPic = pictures.getOrPut("${shown(t)}/$scale") {
        val second = shown(t).let { if (it.contains('.')) it else "0.00" }
        DrawnTime.Canvas(420, 240).put(shown(t), "roboto Regular", 60.0, 30.0, 1.0).put(second, "lato Regular", 60.0, 150.0, scale).pic()
    }

    @Test
    fun twoNumbersOfTheSameSizeRun_itAsks_andTakesTheAnswer() {
        val r = findTimer({ t -> withSecond(t, 0.85) }, duration, fonts)
        assertNull(r.timer)
        assertEquals("${r.notes}", 2, r.choices.size)
        assertTrue(r.choices[0].box.bottom <= r.choices[1].box.top)
        for (c in r.choices) {
            val chosen = findTimer({ t -> withSecond(t, 0.85) }, duration, fonts, near = c.box)
            assertNotNull("${chosen.notes}", chosen.timer)
            val n = chosen.timer!!.numbers
            assertTrue("$n for ${c.box}", n.top < c.box.bottom && n.bottom > c.box.top && n.bottom - n.top < 80)
        }
    }

    @Test
    fun aMuchSmallerSecondTimer_isNoQuestion() {
        val r = findTimer({ t -> withSecond(t, 0.45) }, duration, fonts)
        assertTrue(r.choices.isEmpty())
        assertTrue("${r.timer?.numbers}", r.timer!!.numbers.bottom < 130)
    }

    /** One solve of 1:04.42 is the whole video: 10 s before it, 8 s after. Learning has to cope with the minute too. */
    @Test
    fun oneSolveOverAMinute() {
        fun text(t: Double): String {
            val s = t - 10
            val tenth = Math.floor(s * 10 + 1e-9) / 10
            return when {
                s < 0 -> "0.00"
                s < 60 -> "%.1f".format(Locale.ROOT, tenth)
                s < 64.42 -> "1:%04.1f".format(Locale.ROOT, tenth - 60)
                else -> "1:04.42"
            }
        }
        val r = findTimer({ t -> draw(text(t)) }, 82.0, fonts)
        assertNotNull("${r.notes}", r.timer)
        assertTrue("${r.notes}", r.timer!!.examples.isLearned)
        assertEquals("${r.notes}", setOf("1:04.42"), r.frozen.toSet())
        assertEquals(2, r.timer!!.stoppedDecimals)
        assertEquals("1:04.42", r.timer!!.read(draw("1:04.42"))?.text)
    }

    @Test
    fun noPicture_isNotFound() {
        assertNull(findTimer({ null }, 60.0, fonts).timer)
    }

    @Test
    fun canBeCancelled() {
        try {
            findTimer({ t -> draw(shown(t)) }, duration, fonts, isCancelled = { true })
            fail("not cancelled")
        } catch (e: ScanCancelled) {
            // as it should
        }
    }
}
