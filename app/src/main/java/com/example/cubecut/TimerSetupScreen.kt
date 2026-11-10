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

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Keeps the picture on screen: centred if it is smaller than the box, otherwise never dragged past its edges. */
private fun clampSetupPan(p: Offset, s: Float, fw: Float, fh: Float, bw: Float, bh: Float): Offset {
    val iw = fw * s
    val ih = fh * s
    val x = if (iw <= bw) (bw - iw) / 2f else p.x.coerceIn(bw - iw, 0f)
    val y = if (ih <= bh) (bh - ih) / 2f else p.y.coerceIn(bh - ih, 0f)
    return Offset(x, y)
}

/**
 * Full-screen "Set up timer": step 1 tap the timer's display, step 2 drag the four orange dots onto the display's
 * corners and type the number it shows.
 *
 * The WHOLE frame is shown the whole time and can be zoomed and moved:
 *   - pinch with two fingers to zoom, drag with two fingers to move the picture
 *   - one finger on an orange dot moves that dot; one finger on empty space moves the picture
 *   - "Fit" (top right, shown while zoomed) goes back to the whole frame
 * The dots live in frame pixels, so the corners handed to [onSave] need no conversion.
 */
@Composable
fun TimerSetupScreen(
    frame: Bitmap,
    busy: Boolean,
    message: String,
    onCancel: () -> Unit,
    onSave: (DoubleArray, String) -> Unit
) {
    var center by remember { mutableStateOf<Offset?>(null) }   // the spot you tapped, in frame pixels
    var typed by remember { mutableStateOf("") }
    val c = center

    val fw = frame.width.toFloat()
    val fh = frame.height.toFloat()
    val image = remember(frame) { frame.asImageBitmap() }

    // four draggable corner dots (frame pixels), in the order top-left, top-right, bottom-right, bottom-left
    val handles = remember(c) {
        mutableStateListOf<Offset>().also { list ->
            if (c != null) {
                val hw = (fw * 0.06f).coerceIn(40f, 220f)
                val hh = hw * 0.4f
                fun inside(x: Float, y: Float) = Offset(x.coerceIn(0f, fw), y.coerceIn(0f, fh))
                list.add(inside(c.x - hw, c.y - hh))
                list.add(inside(c.x + hw, c.y - hh))
                list.add(inside(c.x + hw, c.y + hh))
                list.add(inside(c.x - hw, c.y + hh))
            }
        }
    }

    // ---- the view: how the frame is placed inside the box on screen ----
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val bw = boxSize.width.toFloat()
    val bh = boxSize.height.toFloat()
    val fit = if (bw > 0f && bh > 0f) min(bw / fw, bh / fh) else 1f
    val maxScale = max(8f, fit * 4f)
    var zoomOn by remember { mutableStateOf(false) }           // false = the whole frame, fitted to the box
    var viewScale by remember { mutableStateOf(1f) }           // screen pixels per frame pixel (while zoomOn)
    var viewPan by remember { mutableStateOf(Offset.Zero) }    // where the frame's top-left sits in the box (while zoomOn)
    var dragIdx by remember { mutableStateOf(-1) }             // the dot being dragged (-1 = none): shows the magnifier
    var dragFinger by remember { mutableStateOf(Offset.Zero) } // where the finger is, in box pixels
    val sc = if (zoomOn) viewScale else fit
    val pan = if (zoomOn) viewPan else Offset((bw - fw * fit) / 2f, (bh - fh * fit) / 2f)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .padding(8.dp)
    ) {
        InfoNote(
            title = if (c == null) "Step 1 of 2" else "Step 2 of 2",
            text = if (c == null) {
                "Tap the timer's display. Pinch to zoom first if it's small."
            } else {
                "Drag each orange dot onto a corner of the display, then type the number it shows. Pinch to zoom, drag to move."
            }
        )
        Spacer(Modifier.height(6.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(c, boxSize) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            dragIdx = -1

                            // the current view, read fresh every time (it changes while the finger moves)
                            fun curScale(): Float = if (zoomOn) viewScale else fit
                            fun curPan(): Offset = if (zoomOn) viewPan else Offset((bw - fw * fit) / 2f, (bh - fh * fit) / 2f)
                            fun setView(s: Float, p: Offset) {
                                viewScale = s
                                viewPan = clampSetupPan(p, s, fw, fh, bw, bh)
                                zoomOn = true
                            }

                            // did this finger land on an orange dot? (generous touch area)
                            var grabbed = -1
                            if (handles.size == 4) {
                                var bestD = 110f * 110f
                                for (i in handles.indices) {
                                    val hp = curPan() + handles[i] * curScale()
                                    val dx = hp.x - down.position.x
                                    val dy = hp.y - down.position.y
                                    val d2 = dx * dx + dy * dy
                                    if (d2 < bestD) {
                                        bestD = d2
                                        grabbed = i
                                    }
                                }
                            }

                            if (grabbed >= 0) {
                                dragIdx = grabbed
                                dragFinger = down.position
                            }

                            var moved = false
                            var multi = false
                            var travel = 0f
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                if (fingers >= 2) {
                                    // pinch to zoom + two-finger move: the spot between the fingers stays under them
                                    multi = true
                                    grabbed = -1
                                    dragIdx = -1
                                    val zoom = event.calculateZoom()
                                    val panDelta = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    if (centroid != Offset.Unspecified) {
                                        val s0 = curScale()
                                        val p0 = curPan()
                                        val s1 = (s0 * zoom).coerceIn(fit, maxScale)
                                        val z = s1 / s0
                                        setView(s1, centroid - (centroid - panDelta - p0) * z)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (!multi) {
                                    val ch = event.changes.firstOrNull() ?: break
                                    val delta = ch.position - ch.previousPosition
                                    travel += delta.getDistance()
                                    if (grabbed >= 0) dragFinger = ch.position
                                    if (!moved && travel > slop) moved = true
                                    if (moved && ch.pressed) {
                                        if (grabbed >= 0) {
                                            val np = handles[grabbed] + delta / curScale()
                                            handles[grabbed] = Offset(np.x.coerceIn(0f, fw), np.y.coerceIn(0f, fh))
                                        } else {
                                            setView(curScale(), curPan() + delta)
                                        }
                                        ch.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            dragIdx = -1

                            // a tap (no drag, no pinch) in step 1 chooses the display and zooms in on it
                            if (!moved && !multi && c == null) {
                                val s = curScale()
                                val p = curPan()
                                val fx = (down.position.x - p.x) / s
                                val fy = (down.position.y - p.y) / s
                                if (fx >= 0f && fy >= 0f && fx <= fw && fy <= fh) {
                                    center = Offset(fx, fy)
                                    val z = (bw / min(fw, 600f)).coerceIn(fit, maxScale)
                                    setView(z, Offset(bw / 2f - fx * z, bh / 2f - fy * z))
                                }
                            }
                        }
                    }
            ) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(pan.x.roundToInt(), pan.y.roundToInt()),
                    dstSize = IntSize((fw * sc).roundToInt(), (fh * sc).roundToInt())
                )
                if (handles.size == 4) {
                    for (i in 0 until 4) {
                        val a = handles[i]
                        val b = handles[(i + 1) % 4]
                        drawLine(
                            color = Color(0xFFFFA726),
                            start = Offset(pan.x + a.x * sc, pan.y + a.y * sc),
                            end = Offset(pan.x + b.x * sc, pan.y + b.y * sc),
                            strokeWidth = 3f
                        )
                    }
                    for (i in 0 until 4) {
                        drawCircle(
                            color = Color(0xAAFFA726),
                            radius = 22f,
                            center = Offset(pan.x + handles[i].x * sc, pan.y + handles[i].y * sc)
                        )
                    }

                    // Magnifier: while a dot is being dragged, a round close-up of the spot it is on is shown above the
                    // finger (beside it when there is no room above), so the thumb doesn't hide where the dot is.
                    if (dragIdx in 0..3) {
                        val hp = handles[dragIdx]
                        val r = 85f
                        val ls = max(sc * 3f, 3f)                   // screen pixels per frame pixel inside the magnifier
                        val f = dragFinger
                        var lc = Offset(f.x, f.y - r - 150f)
                        if (lc.y - r < 0f) {
                            lc = Offset(if (f.x > size.width / 2f) f.x - r - 150f else f.x + r + 150f, f.y)
                        }
                        lc = Offset(
                            lc.x.coerceIn(r, max(r, size.width - r)),
                            lc.y.coerceIn(r, max(r, size.height - r))
                        )
                        val half = r / ls                           // half the width of the area shown, in frame pixels
                        val sx0 = floor(max(0f, hp.x - half)).toInt()
                        val sy0 = floor(max(0f, hp.y - half)).toInt()
                        val sx1 = ceil(min(fw, hp.x + half)).toInt()
                        val sy1 = ceil(min(fh, hp.y + half)).toInt()
                        val circle = Path().apply { addOval(Rect(lc.x - r, lc.y - r, lc.x + r, lc.y + r)) }
                        clipPath(circle) {
                            drawRect(Color.Black, topLeft = Offset(lc.x - r, lc.y - r), size = Size(2 * r, 2 * r))
                            if (sx1 > sx0 && sy1 > sy0) {
                                drawImage(
                                    image = image,
                                    srcOffset = IntOffset(sx0, sy0),
                                    srcSize = IntSize(sx1 - sx0, sy1 - sy0),
                                    dstOffset = IntOffset(
                                        (lc.x + (sx0 - hp.x) * ls).roundToInt(),
                                        (lc.y + (sy0 - hp.y) * ls).roundToInt()
                                    ),
                                    dstSize = IntSize(
                                        ((sx1 - sx0) * ls).roundToInt(),
                                        ((sy1 - sy0) * ls).roundToInt()
                                    )
                                )
                            }
                            // the border as drawn so far, to line up with the display's edge
                            for (i in 0 until 4) {
                                val a = handles[i]
                                val b2 = handles[(i + 1) % 4]
                                drawLine(
                                    color = Color(0xFFFFA726),
                                    start = Offset(lc.x + (a.x - hp.x) * ls, lc.y + (a.y - hp.y) * ls),
                                    end = Offset(lc.x + (b2.x - hp.x) * ls, lc.y + (b2.y - hp.y) * ls),
                                    strokeWidth = 3f
                                )
                            }
                        }
                        drawCircle(Color.White, radius = r, center = lc, style = Stroke(width = 4f))
                        drawCircle(Color(0xFFFF5252), radius = 9f, center = lc, style = Stroke(width = 2.5f))
                    }
                }
            }
            if (zoomOn) {
                TextButton(onClick = { zoomOn = false }, modifier = Modifier.align(Alignment.TopEnd)) { Text("Fit") }
            }
        }

        Spacer(Modifier.height(6.dp))
        if (c != null) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("Number on the display, e.g. 11.108") },
                supportingText = { Text("For 1:02.34, type 102.34") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            InfoNote(message, kind = kindOfMessage(message))
        }
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            if (c != null) {
                OutlinedButton(onClick = { center = null; zoomOn = false }, enabled = !busy) { Text("Start over") }
            }
            Button(
                onClick = {
                    if (c != null && handles.size == 4) {
                        val corners = DoubleArray(8)
                        for (i in 0 until 4) {
                            corners[2 * i] = handles[i].x.toDouble()
                            corners[2 * i + 1] = handles[i].y.toDouble()
                        }
                        onSave(corners, typed)
                    }
                },
                enabled = c != null && handles.size == 4 && typed.count { it.isDigit() } >= 3 && !busy
            ) { Text(if (busy) "Saving..." else "Save timer setup") }
        }
    }
}