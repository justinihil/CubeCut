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

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min

// ---------------------------------------------------------------------------------------
// (4.0) Reading while the decoder works (owner, 2026-11-27). The scan asks for a picture, reads it, and asks for the next; the decoder
// (the phone's video chip) waits while the picture is read, and the reading waits while the decoder works. Here the next picture the
// scan will most likely ask for (half a second on in its steps, the next frame while it goes frame by frame) is made on a second
// thread while the scan reads the current one. Only ever one thread uses the source, and a picture is made the same way whichever
// thread asks: what the scan gets is the same picture as without this, only sooner. Pure (no Android), so it is tested on the PC.
// ---------------------------------------------------------------------------------------

/** The scan's step: the picture after the one it reads is mostly this much later (FinderScan's SCAN_STEP). */
private const val AHEAD_STEP = 0.5

class PrefetchSource(private val inner: GraySource, private val frameSec: Double) : GraySource {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "CubeCut pictures").apply { isDaemon = true } }

    private class Got(val pic: AreaPic?, val time: Double)

    /** A picture being made ahead: for second [t], part [area], in the near mode [near]. */
    private class Ahead(val t: Double, val area: Area, val near: Boolean, val result: Future<Got>)

    private var ahead: Ahead? = null
    private var near = false
    private var on = false
    private var lastTime = Double.NaN

    /** For the report: pictures that were ready, and pictures made ahead that weren't asked for. */
    var ready = 0
        private set
    var wasted = 0
        private set

    /** [on]: make the next picture ahead (only during a scan of a found timer, whose requests come in steps). */
    override fun setAhead(on: Boolean) {
        this.on = on
        if (!on) drop()
    }

    private fun <T> onWorker(block: () -> T): T = try {
        worker.submit(Callable { block() }).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    /** Waits for the picture being made ahead, and forgets it. */
    private fun drop() {
        val a = ahead ?: return
        ahead = null
        try { a.result.get() } catch (e: Exception) { }
        wasted++
    }

    override fun gray(t: Double): GrayPic? {
        drop()
        return onWorker { inner.gray(t).also { lastTime = inner.lastFrameSec() } }
    }

    override fun grayArea(t: Double, area: Area): AreaPic? {
        val a = ahead
        ahead = null
        var got: Got? = null
        if (a != null) {
            val r = try { a.result.get() } catch (e: Exception) { null }     // (it failed: asked for again below, the same way)
            if (r != null && micros(a.t) == micros(t) && a.near == near && holds(r.pic, area)) { got = r; ready++ } else wasted++
        }
        val g = got ?: onWorker { Got(inner.grayArea(t, area), inner.lastFrameSec()) }
        if (g.pic != null) lastTime = g.time            // (no picture: the time of the last one stays, as the source does)
        if (on && g.pic != null) {
            val next = t + if (near) AHEAD_STEP else frameSec
            val n = near
            ahead = Ahead(next, area, n, worker.submit(Callable { Got(inner.grayArea(next, area), inner.lastFrameSec()) }))
        }
        return g.pic?.let { cut(it, area) }
    }

    /** The microsecond a source decodes for second [t] (CodecSource): two seconds with the same one give the same picture. */
    private fun micros(t: Double): Long = (t * 1_000_000.0).toLong()

    /** Does [pic] hold all of [area] (as the source would cut it: right and bottom kept inside the picture)? */
    private fun holds(pic: AreaPic?, area: Area): Boolean {
        if (pic == null) return true                    // no picture at that second: the same answer
        if (area.left < 0 || area.top < 0) return false
        val p = pic.area
        return p.left <= area.left && p.top <= area.top && p.right >= min(pic.frameW, area.right) && p.bottom >= min(pic.frameH, area.bottom)
    }

    /** [area] cut out of [pic], exactly as the source would have made it. */
    private fun cut(pic: AreaPic, area: Area): AreaPic {
        val want = Area(area.left, area.top, min(pic.frameW, area.right), min(pic.frameH, area.bottom))
        if (want == pic.area) return pic
        val p = pic.area
        return AreaPic(pic.pic.crop(want.left - p.left, want.top - p.top, want.right - p.left, want.bottom - p.top), want, pic.frameW, pic.frameH)
    }

    override fun lastFrameSec(): Double = lastTime

    override fun setNear(on: Boolean) {
        near = on
        onWorker { inner.setNear(on) }                  // (after the picture being made ahead: the worker does one thing at a time)
    }

    override fun stats(): FrameStats? {
        drop()
        return onWorker { inner.stats()?.copy() }
    }

    override fun note(): String {
        val n = onWorker { inner.note() }
        return if (ready + wasted == 0) n else "$n; read ahead: $ready ready, $wasted not asked for"
    }

    override fun close() {
        drop()
        try { onWorker { inner.close() } } finally { worker.shutdown() }
    }
}
