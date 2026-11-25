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
import kotlin.math.floor

/** (3.3-C) Timers that hide their running time or show whole seconds: found by their final times, and scanned. The real videos are in [ReferenceVideoTest]. */
class HeldTimerTest {

    private val fonts = DrawnTime.without("roboto")
    private val times = listOf(12.69, 14.07, 9.41, 13.88, 11.53, 16.32)
    private val cycle = 34.0
    private val duration = times.size * cycle
    private val frame = 1.0 / 60

    private fun start(i: Int) = i * cycle + 10
    private fun stop(i: Int) = start(i) + times[i]
    private fun two(s: Double) = "%.2f".format(Locale.ROOT, s)

    /** What the timer shows: 4 s 0.00, an inspection that counts down from 6, the solve (null, or whole seconds), then the time for [hold] seconds and 0.00 again. */
    private fun shown(t: Double, whole: Boolean, hold: Double = 100.0): HeldShown {
        val i = (t / cycle).toInt().coerceIn(times.indices)
        val s = t - i * cycle
        return when {
            s < 4 -> HeldShown("0.00", null)
            s < 10 -> HeldShown(null, (10 - s).toInt())
            t < stop(i) - 1e-9 -> HeldShown(null, if (whole) floor(t - start(i) + 1e-9).toInt() else null)
            t < stop(i) + hold -> HeldShown(two(times[i]), null)
            else -> HeldShown("0.00", null)
        }
    }

    private fun scan(read: (Double) -> HeldShown?) = scanHeldTimer(read, duration, frame)

    private fun checkAll(read: (Double) -> HeldShown?) {
        val r = scan(read)
        assertEquals(times.map { two(it) }, r.solves.map { it.timeText })
        for ((i, s) in r.solves.withIndex()) {
            assertEquals(stop(i), s.stopSec, 2 * frame)
            assertEquals("", s.note)
        }
        assertEquals(emptyList<MissedHint>(), hintsOf(r))
    }

    @Test fun hiddenRunningTime() = checkAll { shown(it, whole = false) }
    @Test fun wholeSeconds() = checkAll { shown(it, whole = true) }
    @Test fun timesShownForLessThanASecond() = checkAll { shown(it, whole = true, hold = 0.75) }
    @Test fun timesShownForLessThanASecond_hidden() = checkAll { shown(it, whole = false, hold = 0.75) }

    @Test
    fun aTimeTheWholeSecondsDontAgreeWith_isFlagged() {
        val s = scan { t -> shown(t, true).let { if (it.text == "9.41") HeldShown("8.41", null) else it } }.solves
        assertEquals(times.size, s.size)
        assertEquals(listOf(CLOCK_NOTE), s.filter { it.note.isNotEmpty() }.map { it.note })
        assertEquals("8.41", s[2].timeText)
    }

    @Test
    fun aTimeThatComesTooSoon_isFlagged() {
        // the display is read again as something else entirely
        val s = scan { t -> shown(t, false).let { if (it.text == "9.41" && t > stop(2) + 5) HeldShown("57.10", null) else it } }.solves
        assertEquals(listOf("57.10"), s.filter { it.note == SOON_NOTE }.map { it.timeText })
        assertEquals(times.size + 1, s.size)
    }

    @Test
    fun aHandOverTheFirstDigits_isNoTime() {
        checkAll { t -> shown(t, true).let { if (it.text == "12.69" && t > stop(0) + 1 && t < stop(0) + 2.2) HeldShown("2.69", null) else it } }
        val s = scan { t -> shown(t, true).let { if (it.text == "12.69" && t < stop(0) + 0.7) HeldShown("2.69", null) else it } }.solves
        assertEquals(times.map { two(it) }, s.map { it.timeText })
    }

    @Test fun aMisreadZero_isNoTime() = checkAll { t -> shown(t, false).let { if (it.text == "0.00" && t % cycle > 1 && t % cycle < 3) HeldShown("8.00", null) else it } }

    @Test fun aTimerThatWasCovered_isTheSameTime() = checkAll { t -> if (t > stop(1) + 2 && t < stop(1) + 5) null else shown(t, false) }

    /** Two blended pictures read as something once. */
    @Test fun oneOddReading_isNoTime() = checkAll { t -> if (t > start(1) + 3 && t < start(1) + 3.05) HeldShown("7.77", null) else shown(t, false) }

    @Test
    fun theSameTimeTwice_isTwoSolves() {
        // the second solve starts 1.38 s later and is 12.69 too, hidden or in whole seconds
        for (whole in listOf(true, false)) {
            val s = scan { t ->
                if (t > start(1) && t < stop(1) - 1e-9) HeldShown(null, if (whole && t > stop(1) - 12.69) floor(t - stop(1) + 12.69).toInt() else null)
                else shown(t, whole).let { if (it.text == "14.07") HeldShown("12.69", null) else it }
            }.solves
            assertEquals(listOf("12.69", "12.69"), s.take(2).map { it.timeText })
            assertEquals(listOf("", ""), s.take(2).map { it.note })
        }
    }

    @Test
    fun aTimeLeftOverFromBefore_isSwitchedOff() {
        val s = scan { t -> if (t < 4) HeldShown("10.44", null) else shown(t, false) }.solves
        assertEquals("10.44", s[0].timeText)
        assertFalse(s[0].include)
        assertEquals(times.map { two(it) }, s.drop(1).map { it.timeText })
    }

    @Test
    fun aFinalTimeThatWasNeverSeen_isAHint_withWholeSeconds() {
        val h = hintsOf(scan { t -> if (t > stop(2) - 0.2 && t < 3 * cycle + 1) HeldShown(null, null) else shown(t, true) })
        assertEquals("$h", 1, h.size)
        assertTrue("$h", stop(2) > h[0].fromSec && stop(2) < h[0].toSec)
    }

    @Test
    fun canBeCancelled() {
        try {
            scanHeldTimer({ shown(it, false) }, duration, frame, isCancelled = { true })
            fail("not cancelled")
        } catch (e: ScanCancelled) {
        }
    }

    // ---- finding it ----

    private val pictures = HashMap<String, GrayPic>()

    /** The timer, and an average under it that changes with every time. */
    private fun draw(main: String, average: String): GrayPic = pictures.getOrPut("$main $average") {
        val c = DrawnTime.Canvas(420, 240)
        if (main.isNotEmpty()) c.put(main, "roboto Regular", 60.0, 60.0, 1.0)
        c.put(average, "lato Regular", 60.0, 150.0, 0.55).pic()
    }

    private fun picture(t: Double, whole: Boolean): GrayPic {
        val s = shown(t, whole)
        val done = times.indices.filter { t >= stop(it) }
        return draw(s.text ?: s.whole?.toString() ?: "", two(if (done.isEmpty()) 13.0 else done.map { times[it] }.average()))
    }

    private fun checkFound(whole: Boolean) {
        var asked = 0
        val r = findTimer({ picture(it, whole) }, duration, fonts, otherKind = { asked++; false })
        val timer = r.timer
        assertNotNull("${r.notes}", timer)
        timer!!
        assertTrue(timer.held)
        assertEquals(2, timer.stoppedDecimals)
        assertEquals(44.0, timer.digitHeight, 6.0)
        assertEquals("another kind of timer was looked for first", 0, asked)
        assertEquals("12.69", timer.readHeld(draw("12.69", "13.01")).text)
        assertEquals(7, timer.readHeld(draw("7", "13.01")).whole)
        assertEquals(13, timer.readHeld(draw("13", "13.01")).whole)
        val found = scanHeldTimer({ t -> timer.readHeld(picture(t, whole)) }, duration, frame).solves
        assertEquals(times.map { two(it) }, found.map { it.timeText })
        for (s in found) assertEquals("", s.note)
    }

    @Test fun findsATimerThatHidesItsRunningTime() = checkFound(false)
    @Test fun findsATimerThatShowsWholeSeconds() = checkFound(true)

    @Test
    fun aRunningTimerIsStillFoundByItsRunningTime() {
        val r = findTimer({ t ->
            val i = (t / cycle).toInt().coerceIn(times.indices)
            if (t > start(i) && t < stop(i)) draw("%.1f".format(Locale.ROOT, floor((t - start(i)) * 10) / 10), "13.00") else picture(t, false)
        }, duration, fonts, otherKind = { false })
        assertFalse(r.timer!!.held)
    }

    @Test
    fun numbersThatComeBackOrNeverChange_areNotATimer() {
        assertNull(findTimer({ draw("12.69", "13.01") }, duration, fonts, otherKind = { false }).timer)
        // three values in turn, as a page that flips
        val values = listOf("12.69", "14.07", "9.41")
        assertNull(findTimer({ t -> draw(values[(t / 7).toInt() % 3], "13.01") }, duration, fonts, otherKind = { false }).timer)
    }

    /** New numbers, but too often for the times they say. */
    @Test
    fun numbersThatChangeTooSoon_areNotATimer() {
        assertNull(findTimer({ t -> draw("%.2f".format(Locale.ROOT, 30 + floor(t / 6) * 0.37), "13.01") }, duration, fonts, otherKind = { false }).timer)
    }
}
