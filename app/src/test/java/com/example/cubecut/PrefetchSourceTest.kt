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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

/** (4.0) Reading ahead must hand out exactly what the source itself would, also its frame times, whatever the scan asks for next. */
class PrefetchSourceTest {
    private val frame = 1.0 / 60
    private val w = 120
    private val h = 80

    /** A video whose frame n has pixel (x, y) = n + 3x + 7y; in near mode a step takes the nearest 4th frame (the lower layers). */
    private class Fake(val frame: Double, val w: Int, val h: Int) : GraySource {
        var nearMode = false
        var last = Double.NaN
        var thread: Thread? = null
        private fun index(t: Double): Int {
            val n = floor((t * 1_000_000.0).toLong() / (frame * 1_000_000.0) + 0.5).toInt()     // (by the microsecond, as CodecSource)
            return if (nearMode) (n + 2) / 4 * 4 else n
        }
        private fun whole(n: Int) = GrayPic(w, h, ByteArray(w * h) { ((n + 3 * (it % w) + 7 * (it / w)) and 255).toByte() })
        private fun check() {
            // only one thread may use a source at a time (here: always the same one)
            val now = Thread.currentThread()
            if (thread == null) thread = now
        }
        override fun gray(t: Double): GrayPic? {
            check()
            if (t < 0 || t > 100) return null
            val n = index(t)
            last = n * frame
            return whole(n)
        }
        override fun grayArea(t: Double, area: Area): AreaPic? = gray(t)?.let { AreaPic.of(it, area) }
        override fun lastFrameSec(): Double = last
        override fun setNear(on: Boolean) { nearMode = on }
        override fun close() {}
    }

    @Test
    fun whatIsHandedOut_isWhatTheSourceGives() {
        val rnd = Random(11)
        val direct = Fake(frame, w, h)
        val ahead = PrefetchSource(Fake(frame, w, h), frame)
        ahead.setAhead(true)
        var t = 0.0
        var area = Area(10, 10, 60, 40)
        repeat(3000) { i ->
            when (rnd.nextInt(20)) {
                0 -> { val on = rnd.nextBoolean(); direct.setNear(on); ahead.setNear(on) }
                1 -> area = Area(rnd.nextInt(40), rnd.nextInt(30), 50 + rnd.nextInt(90), 40 + rnd.nextInt(60))     // (sometimes past the edge)
                2 -> t = maxOf(0.0, t - rnd.nextDouble() * 3)                                                       // back
                3 -> { val a = direct.gray(t); val b = ahead.gray(t); assertArrayEquals(a?.d, b?.d); assertEquals(direct.lastFrameSec(), ahead.lastFrameSec(), 0.0) }
                4 -> t += 2.0
                5, 6, 7 -> t += frame
                else -> t += 0.5
            }
            if (t > 101) t = 0.0
            val a = direct.grayArea(t, area)
            val b = ahead.grayArea(t, area)
            assertEquals("step $i", a?.area, b?.area)
            assertArrayEquals("step $i", a?.pic?.d, b?.pic?.d)
            assertEquals("step $i", direct.lastFrameSec(), ahead.lastFrameSec(), 0.0)
        }
        assertTrue("some pictures were ready", ahead.ready > 500)
        ahead.close()
    }

    @Test
    fun aScanGivesTheSameSolves_withAndWithoutReadingAhead() {
        // FinderScanTest's simulated csTimer, as pictures: the time written into the top-left pixels
        val times = listOf(12.69, 14.07, 9.41)
        fun shown(t: Double): String? {
            val i = min(times.size - 1, floor(t / 40).toInt())
            val s = t - i * 40
            val start = i * 40 + 10.0
            return when {
                s < 4 -> "0.00"
                s < 10 -> null
                t < start + times[i] - 1e-9 -> "%.1f".format(java.util.Locale.ROOT, floor((t - start) * 10 + 1e-9) / 10)
                else -> "%.2f".format(java.util.Locale.ROOT, times[i])
            }
        }
        fun scan(src: GraySource, ahead: PrefetchSource?): List<FoundSolve> {
            ahead?.setAhead(true)
            return scanFoundTimer(
                { t -> src.grayArea(t, Area(0, 0, 10, 10))?.let { shown(src.lastFrameSec()) } }, 120.0, 1, 2, frame,
                { src.lastFrameSec() }, exact = { src.setNear(!it) }
            ).solves
        }
        val plain = scan(Fake(frame, w, h).also { it.setNear(true) }, null)
        val ahead = PrefetchSource(Fake(frame, w, h), frame).also { it.setNear(true) }
        val read = scan(ahead, ahead)
        assertEquals(plain.map { Triple(it.stopSec, it.timeText, it.note) }, read.map { Triple(it.stopSec, it.timeText, it.note) })
        assertEquals(times.map { "%.2f".format(java.util.Locale.ROOT, it) }, read.map { it.timeText })
        ahead.close()
    }
}
