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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * What one clip covers, for drawing: [startSec]..[solveStartSec] is the scramble part, [solveStartSec]..[endSec] the solve part.
 * [cutIndex]: the cut this clip belongs to (to outline the selected solve's clip); -1 = not known.
 */
class ClipBar(val startSec: Double, val solveStartSec: Double, val endSec: Double, val cutIndex: Int = -1)

// The timeline is a dark panel in light and dark mode alike (like the video above it), so its colours are fixed here.
// Only the "check" amber comes from the theme (AppColors.check), to match the word "check" on the solve cards.
private val PANEL = Color(0xFF1B1B1F)
private val TRACK = Color(0x14FFFFFF)                 // the empty lane under the clip bars
private val SCRAMBLE_PART = Color(0x664CAF50)         // dim green: the scramble part of a clip
private val SOLVE_PART = Color(0xFF43A047)            // bright green: the solve with its buffers
private val SOLVE_MARKER = Color(0xFF66BB6A)          // a solve's cut
private val LEFTOVER_MARKER = Color(0xFF9E9E9E)       // a cut left over from before the video began (no clip)
private val HALO = Color(0x99000000)                  // dark edge around lines, so they stand out over the film strip
private val SELECTED_BAND = Color(0x1FFFFFFF)         // light band over the selected solve's clip
private val PLAYHEAD = Color(0xFFFF3B30)

// Markers and clip bars get thinner when the timeline is crowded (many solves at zoom 1) and thicker when there is room
// (few solves, or zoomed in). The room each cut has on screen decides it: up to CROWDED_DP per cut = thinnest,
// from ROOMY_DP per cut = thickest, in between = in between.
private const val CROWDED_DP = 8f
private const val ROOMY_DP = 36f

/** 0 = crowded, 1 = plenty of room, for [count] cuts on a timeline [widthPx] wide (the whole zoomed width). */
private fun roominess(widthPx: Float, count: Int, density: Float): Float {
    if (count <= 0 || density <= 0f) return 1f
    val roomDp = widthPx / count / density
    return ((roomDp - CROWDED_DP) / (ROOMY_DP - CROWDED_DP)).coerceIn(0f, 1f)
}

/** [thin] when crowded, [thick] with plenty of room. */
private fun mix(thin: Float, thick: Float, room: Float) = thin + (thick - thin) * room

/**
 * The editor timeline: film strip on top, a strip below it showing what each clip will contain, markers for the cuts
 * and a red playhead.
 * At zoom 1 the whole video fits the screen; above that you scroll sideways. Tap to jump; at zoom 1 you can also drag to scrub.
 * markerStates: 1 = a solve (green), 3 = a solve that needs a check (the "check" amber), anything else (2) = a cut left over
 * from before the video began, which makes no clip (grey).
 * A cut marker sits on the FIRST frame that shows the final time (the moment the timer stopped).
 * clipBars: dim green = scramble part of a clip, bright green = the solve part (solve with its buffers). The selected solve's
 * clip gets a white outline and a light band over the whole height.
 * onSeek: moves the playhead (every tap, and every step of a drag).
 * onPick: a tap, or the end of a drag (where the finger was let go): the editor selects the solve there. Not called during a
 * drag, so the selection and the list don't jump around under the finger.
 * The touch handlers below are set up once and keep running, so they always call the newest onSeek / onPick
 * (rememberUpdatedState); otherwise a tap would still use the solves list from when the handler was set up.
 * position: where the playhead is, in ms. It is a function, read only while drawing the red line,
 * so the playhead moving redraws just that line: the film strip, the clip bars, the markers and the rest of the editor stay as
 * they are.
 */
@Composable
fun Timeline(
    stops: List<Double>,
    markerStates: List<Int>,
    selectedIndex: Int = -1,        // the cut being looked at (drawn bigger, with a white ring); -1 = none
    clipBars: List<ClipBar> = emptyList(),
    thumbs: List<ImageBitmap>,
    position: () -> Long,
    durationMs: Long,
    zoom: Float,
    scrollState: ScrollState,
    onSeek: (Long) -> Unit,
    onPick: (Long) -> Unit = {}
) {
    fun fraction(): Float = if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val zoomed = zoom > 1.01f
    val seek by rememberUpdatedState(onSeek)
    val pick by rememberUpdatedState(onPick)
    val checkColor = AppColors.check

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PANEL)
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
                    // tap anywhere on the timeline to jump there and select the solve there
                    // (size is read at the moment of the tap, so a zoom change needs no restart)
                    .pointerInput(durationMs) {
                        detectTapGestures { tap ->
                            if (durationMs > 0) {
                                val ms = (tap.x / size.width * durationMs).toLong().coerceIn(0L, durationMs)
                                seek(ms)
                                pick(ms)
                            }
                        }
                    }
                    // at zoom 1: drag a finger along it to scrub (when zoomed, dragging scrolls instead).
                    // The playhead follows the finger; the solve is selected only where the finger is let go.
                    .pointerInput(durationMs, zoomed) {
                        if (!zoomed) {
                            var last = -1L
                            detectHorizontalDragGestures(
                                onDragStart = { last = -1L },
                                onDragEnd = { if (last >= 0) pick(last) },
                                onDragCancel = { if (last >= 0) pick(last) }
                            ) { change, _ ->
                                if (durationMs > 0) {
                                    val ms = (change.position.x / size.width * durationMs).toLong().coerceIn(0L, durationMs)
                                    last = ms
                                    seek(ms)
                                }
                            }
                        }
                    }
            ) {
                Column(Modifier.fillMaxSize()) {
                    // film strip (a thin dark gap between the pictures)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
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
                            val room = roominess(size.width, stops.size, density)
                            val top = 4.dp.toPx()
                            val barHeight = (size.height - 7.dp.toPx()).coerceAtLeast(2f)
                            val r = mix(1f, 3f, room).dp.toPx()
                            val corner = CornerRadius(r, r)
                            val gap = mix(0.5f, 1f, room).dp.toPx()     // neighbouring clips touch; this keeps them apart
                            // the empty lane, so the strip doesn't look blank where there is no clip
                            drawRoundRect(TRACK, Offset(0f, top), Size(size.width, barHeight), corner)
                            for (bar in clipBars) {
                                val x0 = (size.width * (bar.startSec * 1000.0 / durationMs)).toFloat() + gap
                                val xs = (size.width * (bar.solveStartSec * 1000.0 / durationMs)).toFloat().coerceAtLeast(x0)
                                val x1 = (size.width * (bar.endSec * 1000.0 / durationMs)).toFloat() - gap
                                if (x1 <= x0) continue
                                if (xs > x0) {
                                    drawRoundRect(SCRAMBLE_PART, Offset(x0, top), Size(x1 - x0, barHeight), corner)
                                }
                                if (x1 > xs) {
                                    drawRoundRect(SOLVE_PART, Offset(xs, top), Size(x1 - xs, barHeight), corner)
                                }
                                if (selectedIndex >= 0 && bar.cutIndex == selectedIndex) {
                                    // the selected solve's whole clip: white outline
                                    val w = 1.5.dp.toPx()
                                    drawRoundRect(
                                        Color.White,
                                        Offset(x0 + w / 2, top + w / 2),
                                        Size(x1 - x0 - w, barHeight - w),
                                        corner,
                                        style = Stroke(width = w)
                                    )
                                }
                            }
                        }
                    }
                }
                // markers: one per cut (the selected one drawn last, on top)
                Canvas(Modifier.fillMaxSize()) {
                    if (durationMs > 0) {
                        // a light band over the selected solve's clip, from the film strip down
                        if (selectedIndex >= 0) {
                            val bar = clipBars.firstOrNull { it.cutIndex == selectedIndex }
                            if (bar != null) {
                                val x0 = (size.width * (bar.startSec * 1000.0 / durationMs)).toFloat()
                                val x1 = (size.width * (bar.endSec * 1000.0 / durationMs)).toFloat()
                                if (x1 > x0) drawRect(SELECTED_BAND, Offset(x0, 0f), Size(x1 - x0, size.height))
                            }
                        }
                        // thin when crowded, thick with room (see roominess); the selected one always stays easy to see
                        val room = roominess(size.width, stops.size, density)
                        val line = mix(1f, 2.5f, room).dp.toPx()
                        val selectedLine = mix(2f, 3.5f, room).dp.toPx()
                        val halo = mix(0f, 1f, room).dp.toPx()          // no dark edge when crowded: it made the strip look heavy
                        val dot = mix(2f, 4.5f, room).dp.toPx()
                        val selectedDot = mix(3f, 4.5f, room).dp.toPx()
                        val dotY = 7.5.dp.toPx()                           // room above the dot for the selected one's white ring
                        fun marker(i: Int, t: Double) {
                            val x = (size.width * (t * 1000.0 / durationMs)).toFloat()
                            val selected = i == selectedIndex
                            val color = when (markerStates.getOrElse(i) { 1 }) {
                                1 -> SOLVE_MARKER
                                3 -> checkColor
                                else -> LEFTOVER_MARKER
                            }
                            val w = if (selected) selectedLine else line
                            val d = if (selected) selectedDot else dot
                            val center = Offset(x, dotY)
                            if (halo > 0.5f) {
                                drawLine(HALO, Offset(x, 0f), Offset(x, size.height), strokeWidth = w + 2 * halo)
                                drawCircle(HALO, radius = d + halo, center = center)
                            }
                            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = w)
                            drawCircle(color, radius = d, center = center)
                            if (selected) {
                                // the cut you are looking at: white ring around its dot
                                drawCircle(Color.White, radius = d + 3.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
                            }
                        }
                        for ((i, t) in stops.withIndex()) if (i != selectedIndex) marker(i, t)
                        if (selectedIndex in stops.indices) marker(selectedIndex, stops[selectedIndex])
                    }
                }
                // red playhead, with a small triangle on top
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width * fraction()
                    val w = 2.5.dp.toPx()
                    drawLine(HALO, Offset(x, 0f), Offset(x, size.height), strokeWidth = w + 2.dp.toPx())
                    drawLine(PLAYHEAD, Offset(x, 0f), Offset(x, size.height), strokeWidth = w)
                    val half = 6.dp.toPx()
                    val head = Path().apply {
                        moveTo(x - half, 0f)
                        lineTo(x + half, 0f)
                        lineTo(x, 8.dp.toPx())
                        close()
                    }
                    drawPath(head, PLAYHEAD)
                }
            }
        }
    }
}