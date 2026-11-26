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

import java.util.Random
import kotlin.math.ceil

/**
 * (3.3-A) A simulated timer display: what a digit reader would return at second t. (4.0-0) The scan it was made for (of a timer
 * set up by hand) is gone; the ground-truth tests still use it.
 *
 * The video is [fps] frames a second and a read gives the frame nearest t, as the real frame source does. Between solves the display
 * shows 0.000; during a solve it counts up; at the stop it holds the final time for [holdSec] (or until the next solve starts), then
 * resets. A frame always reads the same, like the real reader behind `memoReader`, so the noise depends on the frame number only.
 *
 * Noise (each a chance per frame, from [seed]):
 *  - [unreadable]: null, as when a hand covers the display;
 *  - [oneForFourSeven]: one 4 or 7 read as 1, the reader's known error;
 *  - [dropLastWhileHeld]: a held final time read without its last digit (`1151` for `11517`), the weakness 3.3-B fixes.
 */
class FakeDisplay(
    val solves: List<Pair<Double, Double>>,     // (start second, solve length in seconds), in time order
    val durationSec: Double,
    val decimals: Int = 3,
    val fps: Double = 60.0,
    val holdSec: Double = 6.0,
    val leftoverAtStart: Double? = null,        // a time already showing when the video begins (held until the first solve)
    val unreadable: Double = 0.0,
    val oneForFourSeven: Double = 0.0,
    val dropLastWhileHeld: Double = 0.0,
    val seed: Long = 1
) {
    /** The first frame that shows each solve's final time: where the cut should be. */
    val expectedStops: List<Double> get() = solves.map { (start, len) -> frameTime(firstFrameAtOrAfter(start + len)) }

    /** The final times as the scan writes them ("12.412"). */
    val expectedTimes: List<String> get() = solves.map { (_, len) -> formatDigits(digitsOf(len), decimals) }

    val expected: List<ExpectedSolve> get() = expectedStops.zip(expectedTimes).map { (s, t) -> ExpectedSolve(s, t) }

    private fun frameTime(frame: Long): Double = frame / fps
    private fun firstFrameAtOrAfter(t: Double): Long = ceil(t * fps - 1e-9).toLong()

    /** What the display shows, as digits without the point: 12.412 -> "12412", 0.5 -> "0500", 62.345 -> "102345". */
    fun digitsOf(sec: Double): String {
        val unit = Math.pow(10.0, decimals.toDouble())
        val ticks = Math.floor(sec * unit + 1e-6).toLong()
        val frac = (ticks % unit.toLong()).toString().padStart(decimals, '0')
        val whole = ticks / unit.toLong()
        val head = if (whole >= 60) "${whole / 60}${(whole % 60).toString().padStart(2, '0')}" else whole.toString()
        return head + frac
    }

    /** (digits shown, is it a held final time) on [frame], without noise. */
    private fun clean(frame: Long): Pair<String, Boolean> {
        val t = frameTime(frame)
        val zero = digitsOf(0.0)
        for ((i, s) in solves.withIndex()) {
            val (start, len) = s
            val stop = start + len
            val nextStart = solves.getOrNull(i + 1)?.first ?: Double.MAX_VALUE
            if (t < start) {
                // a left-over time is reset a few seconds before the first solve, as when the cuber starts a new one
                return if (i == 0 && leftoverAtStart != null && t < start - 5.0) Pair(digitsOf(leftoverAtStart), true) else Pair(zero, false)
            }
            if (t < stop) return Pair(digitsOf(t - start), false)
            // held from the first frame at or after the stop until the reset
            if (t < minOf(stop + holdSec, nextStart - 1.0)) return Pair(digitsOf(len), true)
            if (t < nextStart) return Pair(zero, false)
        }
        return Pair(zero, false)
    }

    /** What the display reads at second [t]. */
    fun read(t: Double): String? {
        val frame = Math.round(t * fps)
        // mixed first: java.util.Random gives nearly the same first number for neighbouring seeds, which made long unreadable streaks
        val rnd = Random(mix(seed * 1_000_003L + frame))
        val (d, held) = clean(frame)
        if (rnd.nextDouble() < unreadable) return null
        var out = d
        if (held && out.length > 1 && rnd.nextDouble() < dropLastWhileHeld) out = out.dropLast(1)
        if (rnd.nextDouble() < oneForFourSeven) {
            val spots = out.indices.filter { out[it] == '4' || out[it] == '7' }
            if (spots.isNotEmpty()) {
                val i = spots[rnd.nextInt(spots.size)]
                out = out.substring(0, i) + '1' + out.substring(i + 1)
            }
        }
        return out
    }

    companion object {
        /** SplitMix64's finaliser: neighbouring inputs give unrelated outputs. */
        private fun mix(x: Long): Long {
            var z = x + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /**
         * A session like a real one: [count] solves of 8 to 25 s with 15 to 40 s between them (scrambling and inspection),
         * starting after a short lead-in. [seed] makes it repeatable.
         */
        fun session(count: Int, seed: Long, unreadable: Double = 0.0, oneForFourSeven: Double = 0.0, dropLastWhileHeld: Double = 0.0): FakeDisplay {
            val rnd = Random(seed)
            val solves = ArrayList<Pair<Double, Double>>()
            var t = 10.0 + rnd.nextDouble() * 10.0
            repeat(count) {
                val len = 8.0 + rnd.nextDouble() * 17.0
                solves.add(Pair(t, len))
                t += len + 15.0 + rnd.nextDouble() * 25.0
            }
            return FakeDisplay(
                solves, t + 5.0, holdSec = 4.0 + rnd.nextDouble() * 6.0,
                unreadable = unreadable, oneForFourSeven = oneForFourSeven, dropLastWhileHeld = dropLastWhileHeld, seed = seed
            )
        }

        /** A clean display that gives exactly the solves in a ground-truth file (start = stop minus the time). */
        fun fromGroundTruth(g: GroundTruth): FakeDisplay {
            val solves = g.solves.map { s ->
                val len = parseTimeSeconds(s.time) ?: 10.0
                Pair(s.stopSec - len, len)
            }
            return FakeDisplay(solves, maxOf(g.durationSec, (g.solves.lastOrNull()?.stopSec ?: 0.0) + 5.0))
        }
    }
}
