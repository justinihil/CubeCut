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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The editor timeline: film strip on top, audio waveform below, markers for the cuts and a red playhead.
 * At zoom 1 the whole video fits the screen; above that you scroll sideways. Tap to jump; at zoom 1 you can also drag to scrub.
 * markerStates: 0 = just a loud sound (orange), 1 = a cut that will be exported (green), 2 = a cut left out (grey).
 */
@Composable
fun Timeline(
    sounds: List<Double>,
    markerStates: List<Int>,
    selectedIndex: Int = -1,        // the cut being looked at (drawn bigger, in white); -1 = none
    thumbs: List<ImageBitmap>,
    waveform: FloatArray?,
    positionMs: Long,
    durationMs: Long,
    zoom: Float,
    scrollState: ScrollState,
    onSeek: (Long) -> Unit
) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val zoomed = zoom > 1.01f

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(Color(0xFF1B1B1F))
    ) {
        val viewportWidth = maxWidth
        val viewportPx = constraints.maxWidth
        val contentPx = viewportPx * zoom

        // when zoomed in, keep the playhead on screen
        LaunchedEffect(positionMs / 400, zoom) {
            if (zoomed && durationMs > 0) {
                val x = (contentPx * fraction).toInt()
                val left = scrollState.value
                if (x < left + viewportPx * 0.1f || x > left + viewportPx * 0.9f) {
                    scrollState.scrollTo(max(0, x - viewportPx / 2))
                }
            }
        }

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
                    // audio waveform
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val wave = waveform
                        if (wave != null && wave.isNotEmpty()) {
                            val barWidth = size.width / wave.size
                            val mid = size.height / 2f
                            for (i in wave.indices) {
                                val barHeight = max(wave[i] * size.height, 1f)
                                val x = i * barWidth + barWidth / 2f
                                drawLine(
                                    color = Color(0xFF4FC3F7),
                                    start = Offset(x, mid - barHeight / 2f),
                                    end = Offset(x, mid + barHeight / 2f),
                                    strokeWidth = max(barWidth, 1f)
                                )
                            }
                        }
                    }
                }
                // markers: one per detected sound / cut
                Canvas(Modifier.fillMaxSize()) {
                    if (durationMs > 0) {
                        for ((i, t) in sounds.withIndex()) {
                            val x = (size.width * (t * 1000.0 / durationMs)).toFloat()
                            val markerColor = when (markerStates.getOrElse(i) { 0 }) {
                                1 -> Color(0xFF66BB6A)
                                2 -> Color(0xFF9E9E9E)
                                else -> Color(0xFFFFA726)
                            }
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
                    val x = size.width * fraction
                    drawLine(Color.Red, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                }
            }
        }
    }
}