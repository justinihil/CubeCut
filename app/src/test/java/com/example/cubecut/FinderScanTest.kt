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
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale
import kotlin.math.floor

/** (3.3-C) The scan of a timer the finder found, on a simulated csTimer: inspection isn't read, running shows one decimal, stopped two. */
class FinderScanTest {

    private val frame = 1.0 / 60
    private val times = listOf(12.69, 14.07, 9.41, 13.88, 11.53)
    private val cycle = 40.0
    private val duration = times.size * cycle

    private fun start(i: Int) = i * cycle + 10
    private fun stop(i: Int) = start(i) + times[i]
    private fun two(s: Double) = "%.2f".format(Locale.ROOT, s)

    /** What csTimer shows at [t] (null = inspection, which has no decimal point). */
    private fun shown(t: Double): String? {
        val i = floor(t / cycle).toInt().coerceIn(times.indices)
        val s = t - i * cycle
        return when {
            s < 4 -> "0.00"
            s < 10 -> null
            t < stop(i) - 1e-9 -> "%.1f".format(Locale.ROOT, floor((t - start(i)) * 10 + 1e-9) / 10)
            else -> two(times[i])
        }
    }

    private fun scan(read: (Double) -> String?) = scanFoundTimer(read, duration, 1, 2, frame).solves

    private fun assertAllFound(found: List<FoundSolve>, within: Double = frame + 1e-6) {
        assertEquals(times.map { two(it) }, found.map { it.timeText })
        for (i in times.indices) assertEquals("cut of ${found[i].timeText}", stop(i), found[i].stopSec, within)
    }

    @Test
    fun everySolve_onItsFirstFrame_confirmedByTheClock() {
        val found = scan { shown(it) }
        assertAllFound(found)
        for (f in found) { assertEquals("", f.note); assertTrue(f.include) }
    }

    @Test
    fun blendedFrames_dontMoveTheCut() {
        // around every stop two frames show both numbers and read as something else
        assertAllFound(scan { t -> if (times.indices.any { t > stop(it) - 2 * frame && t < stop(it) - 1e-6 }) "1.1" else shown(t) })
    }

    @Test
    fun aTimeThatShowsAgain_isTheSameHold() {
        // something covers the timer for 12 s in every hold (a scramble window), and reads as junk once
        assertAllFound(scan { t ->
            val i = floor(t / cycle).toInt().coerceIn(times.indices)
            val after = t - stop(i)
            if (after in 2.0..14.0) (if (after in 7.0..7.4) "4.1" else null) else shown(t)
        })
    }

    @Test
    fun hiddenStop_comesFromTheClock_andIsFlagged() {
        // the timer is covered from just before the stop of no. 2 until 3 s after it
        val found = scan { t -> if (t > stop(1) - 0.3 && t < stop(1) + 3) null else shown(t) }
        assertEquals(times.map { two(it) }, found.map { it.timeText })
        assertEquals(stop(1), found[1].stopSec, 0.15)
        assertEquals(HIDDEN_STOP_NOTE, found[1].note)
        assertEquals("", found[0].note)
    }

    @Test
    fun aMisreadTime_losesToTheOneTheClockConfirms() {
        // no. 3 (9.41) is read as 4.41 more often than not
        val found = scan { t ->
            val v = shown(t)
            if (v == "9.41" && floor(t * 2).toInt() % 3 != 0) "4.41" else v
        }
        assertEquals(times.map { two(it) }, found.map { it.timeText })
    }

    @Test
    fun aTimeThatNeverRan_isKeptAndFlagged() {
        // a quick tap: 0.34 without a running reading the scan could see
        val found = scan { t -> if (t in 5.0..8.0) "0.34" else if (t < 5) "0.00" else shown(t) }
        assertEquals(listOf("0.34") + times.map { two(it) }, found.map { it.timeText })
        assertEquals(CLOCK_NOTE, found[0].note)
        assertTrue(found[0].include)
    }

    @Test
    fun aTimeAlreadyThereAtTheStart_isALeftOver() {
        val found = scan { t -> if (t < 4) "15.55" else shown(t) }
        assertEquals("15.55", found[0].timeText)
        assertFalse(found[0].include)
        assertTrue(found[0].note.startsWith(SolveAnalyzer.LEFTOVER_NOTE_START))
        assertEquals(times.size + 1, found.size)
    }

    @Test
    fun theSameTimeTwice_isTwoSolves() {
        val same = { t: Double ->
            val s = t % cycle
            when {
                s < 4 -> "0.00"
                s < 10 -> null
                s < 22.5 - 1e-9 -> "%.1f".format(Locale.ROOT, floor((s - 10) * 10 + 1e-9) / 10)
                else -> "12.50"
            }
        }
        // also when the reset to 0.00 was never readable
        assertEquals(5, scan(same).size)
        assertEquals(5, scan { t -> same(t).let { if (it == "0.00") null else it } }.size)
    }

    @Test
    fun unreadableFrames_dontLoseSolves() {
        val found = scan { t -> if ((floor(t * 60 + 1e-6).toLong() * 2654435761L and 0xffff) < 0x2000) null else shown(t) }
        assertAllFound(found, within = 3 * frame)
    }

    @Test
    fun aTimerThatShowsAllDecimalsWhileRunning() {
        val found = scanFoundTimer({ t ->
            val i = floor(t / cycle).toInt().coerceIn(times.indices)
            val s = t - i * cycle
            if (s < 10) "0.00" else if (t < stop(i) - 1e-9) two(floor((t - start(i)) * 100 + 1e-9) / 100) else two(times[i])
        }, duration, 2, 2, frame).solves
        assertAllFound(found)
        for (f in found) assertEquals("", f.note)
    }

    /** Three decimals all the time, like flowtimer. [corrected]: the stopped time minus the running one (a smart cube knows better). */
    private fun threeDecimals(t: Double, corrected: Double = 0.0): String {
        val i = floor(t / cycle).toInt().coerceIn(times.indices)
        val s = t - i * cycle
        return "%.3f".format(Locale.ROOT, if (s < 10) 0.0 else if (t < stop(i) - 1e-9) floor((t - start(i)) * 1000 + 1e-9) / 1000 else times[i] + corrected)
    }

    @Test
    fun runningReadingsThatLookAlike_areNoStop() {
        // in the third solve two readings half a second apart come out the same
        val found = scanFoundTimer({ t -> if (t > start(2) + 4.9 && t < start(2) + 5.6) "5.308" else threeDecimals(t) }, duration, 3, 3, frame).solves
        assertEquals(times.map { "%.3f".format(Locale.ROOT, it) }, found.map { it.timeText })
        for (i in times.indices) assertEquals(stop(i), found[i].stopSec, frame + 1e-6)
    }

    /** Three decimals all the time, the last two blurred while running: then a full text is often missing or wrong, the tenths are right. */
    private fun blurred(t: Double): Pair<String?, Double?> {
        val i = floor(t / cycle).toInt().coerceIn(times.indices)
        val runs = t - i * cycle >= 10 && t < stop(i) - 1e-9
        val shown = threeDecimals(t)
        val tenths = floor(shown.toDouble() * 10 + 1e-6) / 10
        if (!runs) return Pair(shown, tenths)
        val odd = (t * 2).toInt() % 5
        return Pair(if (odd < 2) "%.1f88".format(Locale.ROOT, tenths) else null, tenths)
    }

    private fun scanBlurred(read: (Double) -> Pair<String?, Double?>) =
        scanFoundTimer({ read(it).first }, duration, 3, 3, frame) { read(it).second }.solves

    @Test
    fun blurredDecimals_runningTimesComeFromTheTenths() {
        val found = scanBlurred { blurred(it) }
        assertEquals(times.map { "%.3f".format(Locale.ROOT, it) }, found.map { it.timeText })
        for (i in times.indices) { assertEquals(stop(i), found[i].stopSec, frame + 1e-6); assertEquals("", found[i].note) }
    }

    @Test
    fun blurredDecimals_aRunningTimeReadTwice_isNoStop() {
        // in the third solve two readings come out the same, and the ones after them can be read in full
        val from = start(2) + 4.9
        val found = scanBlurred { t -> if (t > from && t < from + 0.7) Pair("5.388", 5.3) else if (t >= from && t < start(2) + 9) Pair(threeDecimals(t), blurred(t).second) else blurred(t) }
        assertEquals(times.map { "%.3f".format(Locale.ROOT, it) }, found.map { it.timeText })
    }

    @Test
    fun blurredDecimals_oneDigitMisreadAfterTheStop_doesntMoveTheCut() {
        // the first pictures after the stop of no. 2 (14.070) read as 11.070 now and then
        val found = scanBlurred { t -> blurred(t).let { if (it.first == "14.070" && t < stop(1) + 1 && floor(t * 2) == t * 2) Pair("11.070", 11.0) else it } }
        assertEquals(times.map { "%.3f".format(Locale.ROOT, it) }, found.map { it.timeText })
        assertEquals(stop(1), found[1].stopSec, frame + 1e-6)
    }

    @Test
    fun blurredDecimals_aHiddenStopComesFromTheClock() {
        val found = scanBlurred { t -> if (t > stop(1) - 0.3 && t < stop(1) + 3) Pair(null, null) else blurred(t) }
        assertEquals(times.map { "%.3f".format(Locale.ROOT, it) }, found.map { it.timeText })
        assertEquals(stop(1), found[1].stopSec, 0.15)
        assertEquals(HIDDEN_STOP_NOTE, found[1].note)
    }

    @Test
    fun aTimeCorrectedWhenItStops_keepsItsCut_andIsFlagged() {
        val found = scanFoundTimer({ t -> threeDecimals(t, -0.62) }, duration, 3, 3, frame).solves
        assertEquals(times.map { "%.3f".format(Locale.ROOT, it - 0.62) }, found.map { it.timeText })
        for (i in times.indices) {
            assertEquals(stop(i), found[i].stopSec, frame + 1e-6)
            assertEquals(CLOCK_NOTE, found[i].note)
        }
    }

    @Test
    fun timesOverAMinute() {
        val big = listOf(58.31, 64.42, 187.05)
        val begin = listOf(10.0, 90.0, 180.0)
        fun text(s: Double, decimals: Int): String {
            val rest = "%.${decimals}f".format(Locale.ROOT, s % 60)
            return if (s < 60) rest else "${(s / 60).toInt()}:${rest.padStart(decimals + 3, '0')}"
        }
        val found = scanFoundTimer({ t ->
            val i = begin.indexOfLast { it <= t }
            when {
                i < 0 -> "0.00"
                t < begin[i] + big[i] - 1e-9 -> text(floor((t - begin[i]) * 10 + 1e-9) / 10, 1)
                else -> text(big[i], 2)
            }
        }, 385.0, 1, 2, frame).solves
        assertEquals(listOf("58.31", "1:04.42", "3:07.05"), found.map { it.timeText })
        for (i in big.indices) {
            assertEquals(begin[i] + big[i], found[i].stopSec, frame + 1e-6)
            assertEquals("", found[i].note)
        }
    }

    @Test
    fun canBeCancelled() {
        try {
            scanFoundTimer({ shown(it) }, duration, 1, 2, frame, isCancelled = { true })
            fail("not cancelled")
        } catch (e: ScanCancelled) {
            // as it should
        }
    }
}
