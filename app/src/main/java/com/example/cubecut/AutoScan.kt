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

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// (3.3-C) Find solves without a timer setup: the glue between the real video and the pure parts (TimerFinder.kt, FinderScan.kt), and the
// window that asks which number is the timer when CubeCut isn't sure.

/** What came of it: [scan] and [numbers] (the box around the timer's numbers) when it was found; else [choices] = the numbers to ask about (empty = no timer found). */
class AutoScanResult(val scan: ScanResult?, val numbers: Area?, val choices: List<TimerChoice>)

/** Find solves can't go on by itself: the timer wasn't found, or the user has to choose. [message] is the status line. */
class NeedsUser(message: String) : Exception(message)

/** The example digits shipped with the app. Takes a moment: keep the result while you need it. */
fun loadDigitFonts(context: Context): DigitShapes = DigitShapes.from(context.assets.open("digit_shapes.bin").use { readDigitStyles(it) })

/** The real time of the frame [source] gave last when asked for second [t]. */
fun frameTimeOf(source: GraySource, t: Double): Double = source.lastFrameSec().takeIf { abs(it - t) < 0.05 } ?: t

/**
 * Finds the timer and then the solves. Solid digits first (csTimer); a seven-segment timer (QiYi) only when none of those was seen running;
 * a seven-segment one by its final times only when nothing else was found.
 * [near]: the number the user chose, or null. [onProgress] gets (finding the timer?, percent).
 * Blocking; run it on Dispatchers.Default. [isCancelled] is polled; true stops it with [ScanCancelled].
 */
fun analyzeByItself(
    context: Context, uri: Uri, durationMs: Long, near: Area?, onProgress: (Boolean, Int) -> Unit, isCancelled: () -> Boolean
): AutoScanResult {
    val durationSec = durationMs / 1000.0
    val fonts = loadDigitFonts(context)
    val frameSec = videoFrameSec(context, uri)
    val source = openFrameSource(context, uri, durationMs)
    try {
        val pic = PicAt { t -> source.gray(t) }
        var segments: FoundSegments? = null
        var segmentTimes: List<Pair<Sighting, SegmentGrid>> = emptyList()
        val otherKind = {
            val r = findSegments(pic, durationSec, { onProgress(true, 5 + it / 20) }, isCancelled)
            segments = r.found
            segmentTimes = r.held
            segments != null
        }
        // (a number the user chose is a solid one)
        val found = findTimer(pic, durationSec, fonts, near, { onProgress(true, it / 10) }, isCancelled, if (near == null) otherKind else null)
        segments?.let { s ->
            val timer = SegmentTracker.follow(s, pic, durationSec, { onProgress(true, 10 + it / 10) }, isCancelled)
            val scan = scanSegments(
                timer, durationSec, frameSec, { t -> frameTimeOf(source, t) }, { done, total -> onProgress(false, 20 + 80 * done / total) }, isCancelled
            )
            return AutoScanResult(scan, timer.numbers(), emptyList())
        }
        // last: a seven-segment timer that shows only its final times (csTimer's "digital" fonts with "solve"), from what the search above read
        if (found.timer == null && found.choices.isEmpty()) findHeldSegments(segmentTimes, pic)?.let { grid ->
            val scan = scanHeldTimer(
                { t -> pic.at(t)?.let { HeldShown(heldSegmentRow(grid, it)?.first?.text, null) } }, durationSec, frameSec,
                { t -> frameTimeOf(source, t) }, { done, total -> onProgress(false, 10 + 90 * done / total) }, isCancelled
            )
            return AutoScanResult(scan, grid.box(grid.decimals + 2), emptyList())
        }
        val timer = found.timer ?: return AutoScanResult(null, null, found.choices)
        val scan = if (timer.held) scanHeldTimer(
            { t -> pic.at(t)?.let { timer.readHeld(it) } }, durationSec, frameSec,
            { t -> frameTimeOf(source, t) }, { done, total -> onProgress(false, 10 + 90 * done / total) }, isCancelled
        ) else scanFoundTimer(
            { t -> pic.at(t)?.let { timer.read(it)?.text } }, durationSec, timer.runningDecimals, timer.stoppedDecimals, frameSec,
            { t -> frameTimeOf(source, t) }, { done, total -> onProgress(false, 10 + 90 * done / total) }, isCancelled
        )
        return AutoScanResult(scan, timer.numbers, emptyList())
    } finally {
        source.close()
    }
}

/** What to ask about: the numbers, and the picture they are outlined in. */
class TimerQuestion(val choices: List<TimerChoice>, val picture: Bitmap)

// fixed colours: they lie over a picture (and match the buttons)
private val CHOICE_COLORS = listOf(Color(0xFF2E7DFF), Color(0xFFFF8A00))
private val CHOICE_NAMES = listOf("Blue", "Orange")

/** Two numbers run at the same time: which one is the timer? */
@Composable
fun TimerQuestionDialog(question: TimerQuestion, onChoose: (TimerChoice) -> Unit, onNeither: () -> Unit, onDismiss: () -> Unit) {
    val picture = question.picture
    val image = remember(picture) { picture.asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which one is your timer?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Two numbers run at the same time.")
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = image,
                    contentDescription = "The video, with the numbers outlined",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(picture.width.toFloat() / picture.height)
                        .drawWithContent {
                            drawContent()
                            val s = size.width / picture.width
                            val room = 4.dp.toPx()
                            for ((i, c) in question.choices.withIndex()) {
                                drawRect(
                                    CHOICE_COLORS[i % CHOICE_COLORS.size],
                                    topLeft = Offset(c.box.left * s - room, c.box.top * s - room),
                                    size = Size((c.box.right - c.box.left) * s + 2 * room, (c.box.bottom - c.box.top) * s + 2 * room),
                                    style = Stroke(3.dp.toPx())
                                )
                            }
                        }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, c) in question.choices.withIndex()) {
                        Button(
                            onClick = { onChoose(c) },
                            colors = ButtonDefaults.buttonColors(containerColor = CHOICE_COLORS[i % CHOICE_COLORS.size], contentColor = Color.White),
                            modifier = Modifier.weight(1f)
                        ) { Text(CHOICE_NAMES[i % CHOICE_NAMES.size]) }
                    }
                }
                OutlinedButton(onClick = onNeither, modifier = Modifier.fillMaxWidth()) { Text("Neither of them") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
