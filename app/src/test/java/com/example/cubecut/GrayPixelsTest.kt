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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/** (4.0) The one-pass, area-only picture must give exactly the pixels the pre-4.0 way gave: whole plane stretched, turned, then cut. */
class GrayPixelsTest {
    private val stretch = ByteArray(256) { y -> ((y - 16) * 255.0 / 219.0).roundToInt().coerceIn(0, 255).toByte() }

    /** The pre-4.0 way, copied from FrameSource.kt before the change: stretch row by row, then turn pixel by pixel. */
    private fun oldWay(plane: ByteArray, stride: Int, left: Int, top: Int, w: Int, h: Int, deg: Int, full: Boolean): GrayPic {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val from = (top + y) * stride + left
            if (full) System.arraycopy(plane, from, out, y * w, w)
            else for (x in 0 until w) out[y * w + x] = stretch[plane[from + x].toInt() and 0xFF]
        }
        if (deg == 0) return GrayPic(w, h, out)
        val turned = deg == 90 || deg == 270
        val ow = if (turned) h else w
        val oh = if (turned) w else h
        val r = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (nx, ny) = when (deg) {
                90 -> Pair(h - 1 - y, x)
                180 -> Pair(w - 1 - x, h - 1 - y)
                else -> Pair(y, w - 1 - x)
            }
            r[ny * ow + nx] = out[y * w + x]
        }
        return GrayPic(ow, oh, r)
    }

    /** The new way, as CodecSource does it: copy only the raw part [rawAreaFor] needs, then [uprightPart]. */
    private fun newWay(plane: ByteArray, stride: Int, left: Int, top: Int, w: Int, h: Int, deg: Int, full: Boolean, area: Area): GrayPic {
        val raw = rawAreaFor(area, w, h, deg)
        val rw = raw.right - raw.left
        val part = ByteArray(rw * (raw.bottom - raw.top))
        for (y in 0 until raw.bottom - raw.top) System.arraycopy(plane, (top + raw.top + y) * stride + left + raw.left, part, y * rw, rw)
        return uprightPart(RawPlane(part, rw, raw.left, raw.top, w, h), deg, if (full) null else stretch, area)
    }

    @Test
    fun wholeAndAreas_matchThePre40Pixels_inEveryTurn() {
        val rnd = Random(7)
        for (round in 0 until 40) {
            val w = 30 + rnd.nextInt(170)
            val h = 30 + rnd.nextInt(170)
            val left = rnd.nextInt(5)
            val top = rnd.nextInt(5)
            val stride = left + w + rnd.nextInt(9)
            val plane = ByteArray(stride * (top + h + 1)) { rnd.nextInt(256).toByte() }
            for (deg in intArrayOf(0, 90, 180, 270)) for (full in listOf(true, false)) {
                val old = oldWay(plane, stride, left, top, w, h, deg, full)
                val (uw, uh) = uprightSize(w, h, deg)
                assertEquals(old.w, uw)
                assertEquals(old.h, uh)
                assertArrayEquals(old.d, newWay(plane, stride, left, top, w, h, deg, full, Area(0, 0, uw, uh)).d)
                repeat(6) {
                    val x0 = rnd.nextInt(uw - 1)
                    val y0 = rnd.nextInt(uh - 1)
                    val area = Area(x0, y0, x0 + 1 + rnd.nextInt(uw - x0), y0 + 1 + rnd.nextInt(uh - y0))
                    val expected = AreaPic.of(old, area)
                    val got = newWay(plane, stride, left, top, w, h, deg, full, area)
                    assertEquals(expected.pic.w, got.w)
                    assertArrayEquals("deg $deg area $area", expected.pic.d, got.d)
                }
            }
        }
    }

    @Test
    fun rotated_isThePre40Turn() {
        val rnd = Random(3)
        val src = GrayPic(37, 23, ByteArray(37 * 23) { rnd.nextInt(256).toByte() })
        for (deg in intArrayOf(90, 180, 270)) {
            assertArrayEquals(oldWay(src.d, 37, 0, 0, 37, 23, deg, true).d, rotated(src, deg).d)
        }
        assertTrue(rotated(src, 0) === src)
    }

    // ---- frames nothing needs ----

    private fun nal(vararg b: Int) = byteArrayOf(0, 0, 0, 1) + b.map { it.toByte() }.toByteArray()

    /** HEVC NAL header: type, layer (temporal id), then some payload. */
    private fun hevc(type: Int, layer: Int) = nal(type shl 1, layer + 1, 0x55, 0x55, 0x55)

    @Test
    fun hevc_onlyTopLayerNonReferenceFramesAreLeftOut() {
        val sps = nal(33 shl 1, 1, 0x07 or 0x00, 0x00)      // SPS: id 0, max sub-layers - 1 = 3 (bits 3..1 of 0x07 = 011)
        assertEquals(3, hevcTopLayer(nal(32 shl 1, 1, 0, 0) + sps + nal(34 shl 1, 1, 0)))
        fun drop(sample: ByteArray) = isDroppable(sample, sample.size, FrameCodec.HEVC, 3)
        assertTrue(drop(hevc(2, 3)))                        // TSA_N on the top layer: nothing is decoded from it
        assertTrue(drop(hevc(0, 3)))                        // TRAIL_N on the top layer
        assertTrue(drop(nal(35 shl 1, 1, 0x10) + nal(39 shl 1, 1, 5, 5) + hevc(2, 3)))   // after a delimiter and an SEI
        assertFalse(drop(hevc(2, 2)))                       // non-reference, but a higher layer may be decoded from it
        assertFalse(drop(hevc(3, 3)))                       // TSA_R: a reference frame
        assertFalse(drop(hevc(1, 0)))                       // TRAIL_R
        assertFalse(drop(hevc(20, 0)))                      // IDR
        assertFalse(isDroppable(hevc(2, 3), 9, FrameCodec.HEVC, -1))   // layers unknown: keep everything
        assertFalse(isDroppable(hevc(2, 3), 9, FrameCodec.OTHER, 3))
        // 4-byte lengths instead of start codes
        val lengths = byteArrayOf(0, 0, 0, 5, (2 shl 1).toByte(), 4, 0x55, 0x55, 0x55)
        assertTrue(isDroppable(lengths, lengths.size, FrameCodec.HEVC, 3))
    }

    @Test
    fun avc_nonReferenceSlicesAreLeftOut() {
        fun drop(b: Int) = nal(b, 0x88, 0x84).let { isDroppable(it, it.size, FrameCodec.AVC, -1) }
        assertTrue(drop(0x01))                              // nal_ref_idc 0, non-IDR slice
        assertFalse(drop(0x21))                             // nal_ref_idc 1
        assertFalse(drop(0x65))                             // IDR
        assertTrue(nal(0x09, 0xF0).plus(nal(0x01, 0x88)).let { isDroppable(it, it.size, FrameCodec.AVC, -1) })   // after a delimiter
    }
}
