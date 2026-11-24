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

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * (3.3-C) The app's own finder on the whole reference videos, on the PC that has them (docs/algorithms.md § 8). It gets the video and
 * its length, nothing else. Slow (ffmpeg starts once per picture), so it only runs when asked: set CUBECUT_REF_VIDEOS=1. Else skipped.
 */
class ReferenceVideoTest {

    private val dir = File(System.getenv("CUBECUT_REF") ?: "D:/CubeCutRef")

    @Test
    fun findsAndLearnsTheTimer() {
        assumeTrue("set CUBECUT_REF_VIDEOS=1 to run", System.getenv("CUBECUT_REF_VIDEOS") == "1")
        val videos = dir.listFiles { f -> f.name.startsWith("ref_") && f.name.endsWith(".mp4") }.orEmpty().sortedBy { it.name }
        assumeTrue("no reference videos on this PC", videos.isNotEmpty())
        // the font that looks like csTimer's is left out
        val styles = File("src/main/assets/digit_shapes.bin").inputStream().use { readDigitStyles(it) }
        val fonts = DigitShapes.from(styles.filter { !it.name.startsWith("arimo ") })
        val problems = ArrayList<String>()
        for (v in videos) {
            val duration = run("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", v.path).toString(Charsets.UTF_8).trim().toDouble()
            var inPictures = 0L
            val started = System.nanoTime()
            val r = findTimer({ t ->
                val s = System.nanoTime()
                picture(v, t).also { inPictures += System.nanoTime() - s }
            }, duration, fonts)
            val total = (System.nanoTime() - started) / 1e9
            println("FINDER ${v.name}: ${if (r.timer != null) "FOUND" else "NOT FOUND"}; ${r.places} places, ${r.wholeFrames} whole frames, ${r.areaReads} area reads; ${"%.1f".format(total - inPictures / 1e9)} s without getting pictures")
            for (n in r.notes) println("   $n")
            println("   froze at ${r.frozen}")
            val timer = r.timer
            if (timer == null) { problems.add("${v.name}: not found"); continue }
            println("   ${timer.area}, digits ${timer.digitHeight.toInt()} px, dark ${timer.dark}, decimals ${timer.runningDecimals} running, ${timer.stoppedDecimals} stopped, learned ${timer.examples.isLearned}")
            // with what it learned: every stop of the answer key, half a second and 1.5 s later
            val key = File(dir, v.name.replace(".mp4", ".places.txt")).readLines().filter { !it.startsWith("#") && it.isNotBlank() }.map { it.split(" ") }
            var right = 0
            var areaNanos = 0L
            val bad = ArrayList<String>()
            for (k in key) for (after in listOf(0.5, 1.5)) {
                val p = picture(v, k[1].toDouble() + after)
                val s = System.nanoTime()
                val got = p?.let { timer.read(it) }?.text
                areaNanos += System.nanoTime() - s
                if (got == k[0]) right++ else bad.add("${k[0]} as $got")
            }
            println("   ${"%.1f".format(areaNanos / 1e6 / (2 * key.size))} ms per area read"); println("   held times read with the learned digits: $right of ${2 * key.size} $bad")
            if (!timer.examples.isLearned) problems.add("${v.name}: not learned")
            if (r.frozen.isEmpty() || r.frozen.any { f -> key.none { it[0] == f } }) problems.add("${v.name}: froze at ${r.frozen}")
            if (bad.isNotEmpty()) problems.add("${v.name}: $bad")
        }
        assertTrue("$problems", problems.isEmpty())
    }

    private fun picture(v: File, t: Double): GrayPic? {
        val w = 1080
        val h = 1920
        val b = run("ffmpeg", "-v", "error", "-ss", "%.4f".format(java.util.Locale.ROOT, maxOf(0.0, t - 0.008)), "-i", v.path, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gray", "-")
        return if (b.size == w * h) GrayPic(w, h, b) else null
    }

    private fun run(vararg command: String): ByteArray {
        val p = ProcessBuilder(*command).start()
        val out = p.inputStream.readBytes()
        p.waitFor(60, TimeUnit.SECONDS)
        return out
    }
}
