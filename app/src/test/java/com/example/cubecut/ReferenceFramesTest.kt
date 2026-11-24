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

/**
 * (3.3-C) The solid-digit reader on whole frames of the reference videos, with nothing said about where the timer is.
 * Only runs on the PC that has them: the frames are private and never in the repo (docs/algorithms.md § 8). Elsewhere it is skipped.
 * A frame is named video_seconds_time.pgm, with the time from the answer key.
 */
class ReferenceFramesTest {

    @Test
    fun heldTimes_areReadFromWholeFrames() {
        val dir = File(System.getenv("CUBECUT_REF") ?: "D:/CubeCutRef", "kotlin/held")
        val files = dir.listFiles { f -> f.name.endsWith(".pgm") }.orEmpty().sortedBy { it.name }
        assumeTrue("no reference frames on this PC", files.isNotEmpty())
        // the font that looks like csTimer's is left out, so the reader has to manage with other fonts
        val styles = File("src/main/assets/digit_shapes.bin").inputStream().use { readDigitStyles(it) }
        val shapes = DigitShapes.from(styles.filter { !it.name.startsWith("arimo ") })
        var right = 0
        var nanos = 0L
        val notRight = ArrayList<String>()
        for (f in files) {
            val expected = f.name.removeSuffix(".pgm").substringAfterLast('_')
            val pic = readPgm(f)
            val start = System.nanoTime()
            val rows = findTimeRows(pic, shapes, listOf(false, true))
            nanos += System.nanoTime() - start
            // one still also holds junk (a window edge that reads 1.1). Telling the timer from that takes several frames and is not the
            // reader's job: here only what it reads at the timer's place counts
            val there = rows.firstOrNull { it.text == expected }
            val read = there?.let { e -> pickRow(rows.filter { it.left < e.right && it.right > e.left && it.top < e.bottom && it.bottom > e.top && it.height > 0.7 * e.height })?.text }
            if (read == expected) right++ else notRight.add("${f.name} as $read")
        }
        println("REFERENCE FRAMES: $right of ${files.size} right, ${nanos / 1_000_000 / files.size} ms per whole frame. Not right: $notRight")
        assertTrue("$right of ${files.size}: $notRight", right >= files.size * 0.95)
    }

    private fun readPgm(f: File): GrayPic {
        val b = f.readBytes()
        var at = 0
        fun word(): String {
            while (b[at].toInt().toChar().isWhitespace()) at++
            val from = at
            while (!b[at].toInt().toChar().isWhitespace()) at++
            return String(b, from, at - from)
        }
        require(word() == "P5") { "not a PGM picture" }
        val w = word().toInt()
        val h = word().toInt()
        word()
        at++
        return GrayPic(w, h, b.copyOfRange(at, at + w * h))
    }
}
