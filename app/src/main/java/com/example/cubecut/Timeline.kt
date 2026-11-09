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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** What one exported clip covers, for drawing: [startSec]..[solveStartSec] is the scramble part, [solveStartSec]..[endSec] the solve part. */
class ClipBar(val startSec: Double, val solveStartSec: Double, val endSec: Double)

/**
 * The editor timeline: film strip on top, a strip below it showing what each clip will contain, markers for the cuts
 * and a red playhead.
 * At zoom 1 the whole video fits the screen; above that you scroll sideways. Tap to jump; at zoom 1 you can also drag to scrub.
 * markerStates: 1 = a solve (green), anything else (2) = a cut left over from before the video began, which makes no clip (grey).
 * A cut marker sits on the FIRST frame that shows the final time (the moment the timer stopped).
 * clipBars: dim green = scramble part of a clip, bright green = the solve part (solve with its buffers).
 * position: where the playhead is, in ms. It is a function, read only while drawing the red line,
 * so the playhead moving redraws just that line: the film strip, the clip bars, the markers and the rest of the editor stay as
 * they are.
 */
@Composable
fun Timeline(
    stops: List<Double>,
    markerStates: List<Int>,
    selectedIndex: Int = -1,        // the cut being looked at (drawn bigger, in white); -1 = none
    clipBars: List<ClipBar> = emptyList(),
    thumbs: List<ImageBitmap>,
    position: () -> Long,
    durationMs: Long,
    zoom: Float,
    scrollState: ScrollState,
    onSeek: (Long) -> Unit
) {
    fun fraction(): Float = if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val zoomed = zoom > 1.01f

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color(0xFF1B1B1F))
    ) {
        val viewportWidth = maxWidth

        // (keeping the playhead on screen when zoomed in is done by the editor, in keepTimelineOn: it knows when the playhead jumps)

        Box(
            Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState, enabled = zoomed)
        ) {
            Box(
                Modifier
                    .width(viewportWidth * zoom)
                    .fillMaxHeight()
                    // tap anywhere on the timeline to jump there
                    .pointerInput(durationMs, zoom) {
                        detectTapGestures { tap ->
                            if (durationMs > 0) {
                                onSeek((tap.x / size.width * durationMs).toLong().coerceIn(0L, durationMs))
                            }
                        }
                    }
                    // at zoom 1: drag a finger along it to scrub (when zoomed, dragging scrolls instead)
                    .pointerInput(durationMs, zoom) {
                        if (!zoomed) {
                            detectHorizontalDragGestures { change, _ ->
                                if (durationMs > 0) {
                                    onSeek((change.position.x / size.width * durationMs).toLong().coerceIn(0L, durationMs))
                                }
                            }
                        }
                    }
            ) {
                Column(Modifier.fillMaxSize()) {
                    // film strip
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                    ) {
                        thumbs.forEach { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                    // what each clip contains: dim = scramble, bright = the solve with its buffers
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (durationMs > 0) {
                            val top = 4f
                            val barHeight = (size.height - 8f).coerceAtLeast(2f)
                            for (bar in clipBars) {
                                val x0 = (size.width * (bar.startSec * 1000.0 / durationMs)).toFloat()
                                val xs = (size.width * (bar.solveStartSec * 1000.0 / durationMs)).toFloat()
                                val x1 = (size.width * (bar.endSec * 1000.0 / durationMs)).toFloat()
                                if (xs > x0) {
                                    drawRect(Color(0x552E7D32), Offset(x0, top), Size(xs - x0, barHeight))
                                }
                                if (x1 > xs) {
                                    drawRect(Color(0xFF43A047), Offset(xs, top), Size(x1 - xs, barHeight))
                                }
                            }
                        }
                    }
                }
                // markers: one per cut
                Canvas(Modifier.fillMaxSize()) {
                    if (durationMs > 0) {
                        for ((i, t) in stops.withIndex()) {
                            val x = (size.width * (t * 1000.0 / durationMs)).toFloat()
                            val markerColor = if (markerStates.getOrElse(i) { 1 } == 1) Color(0xFF66BB6A) else Color(0xFF9E9E9E)
                            drawLine(markerColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                            drawCircle(color = markerColor, radius = 7f, center = Offset(x, 7f))
                            if (i == selectedIndex) {
                                // the cut you are looking at: white ring around its dot
                                drawCircle(color = Color.White, radius = 11f, center = Offset(x, 7f), style = Stroke(width = 3f))
                            }
                        }
                    }
                }
                // red playhead
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width * fraction()
                    drawLine(Color.Red, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                }
            }
        }
    }
}