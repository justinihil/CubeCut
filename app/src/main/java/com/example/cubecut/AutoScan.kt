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
import kotlin.math.min
import kotlin.math.max

// (3.3-C) Find solves without a timer setup: the glue between the real video and the pure parts (TimerFinder.kt, FinderScan.kt), and the
// window that asks which number is the timer when CubeCut isn't sure.

/**
 * What came of it: [scan] and [numbers] (the box around the timer's numbers) when it was found; else [choices] = the numbers to ask about
 * (empty = no timer found). (4.0) [hint]: where this timer was, for the next video (TimerHint).
 */
class AutoScanResult(val scan: ScanResult?, val numbers: Area?, val choices: List<TimerChoice>, val hint: TimerHint? = null)

/**
 * (4.0) How one Find solves went, for the progress panel's *Copy report* (owner, 2026-11-27: why one video takes longer than another).
 * Filled in by [analyzeByItself] as it goes, so a scan that stops halfway still tells how far it got.
 */
class ScanTiming {
    var timer = "not found"             // which kind of timer, in words
    var source = ""                     // GraySource.note()
    var frameSec = 0.0
    var size = ""                       // "1080 x 1920"
    var readySec = 0.0                  // (4.0) before the search: the fonts, the frame rate, opening the video
    var findSec = 0.0
    var segmentsSec = 0.0               // (4.0) of findSec: looking for a seven-segment timer
    var lastCheckSec = 0.0              // (4.0) a seven-segment timer by its final times, after the search
    var saveSec = 0.0                   // (4.0) after the scan: saving the solves and the places to check
    // (4.0) the rest of the time a video took (owner, 2026-11-28: up to 434 s were in no line of the report)
    var enteredMs = 0L                  // when Find solves really began (waiting for a thread before)
    var leftMs = 0L                     // when it was over and the video closed
    var closeSec = 0.0                  // closing the video
    var backSec = 0.0                   // from then until the queue went on with it
    // (4.0) how warm the phone was: the battery at the start and the end (°C), and the heat status (PowerManager.THERMAL_STATUS_*)
    var batteryStartC = Double.NaN
    var batteryEndC = Double.NaN
    var heatStart = -1
    var heatWorst = -1
    var asleepSec = 0.0                 // (4.0) how long the phone slept meanwhile (the scan stood still), ADR-0019
    var followSec = 0.0                 // seven segments only
    var scanSec = 0.0
    var findPictures = ""               // FrameStats.since for each part
    var followPictures = ""
    var scanPictures = ""
    var pictures = 0                    // pictures asked for in all
}

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
    context: Context, uri: Uri, durationMs: Long, near: Area?, onProgress: (Boolean, Int) -> Unit, isCancelled: () -> Boolean,
    timing: ScanTiming = ScanTiming(), hint: TimerHint? = null
): AutoScanResult {
    val durationSec = durationMs / 1000.0
    timing.enteredMs = System.currentTimeMillis()
    val entered = System.nanoTime()
    val fonts = loadDigitFonts(context)
    val frameSec = videoFrameSec(context, uri)
    timing.frameSec = frameSec
    // (4.0) the next picture made while this one is read (PrefetchSource): off, see READ_AHEAD
    val source = if (READ_AHEAD) PrefetchSource(openFrameSource(context, uri, durationMs), frameSec) else openFrameSource(context, uri, durationMs)
    var partStart = System.nanoTime()
    timing.readySec = (partStart - entered) / 1e9
    var partStats = source.stats()?.copy()
    /** The seconds since the last part began, and what the pictures took in it; the next part begins now. */
    fun part(): Pair<Double, String> {
        val now = System.nanoTime()
        val sec = (now - partStart) / 1e9
        val pics = source.stats()?.let { s -> partStats?.let { s.since(it) } } ?: ""
        partStart = now
        partStats = source.stats()?.copy()
        return Pair(sec, pics)
    }
    try {
        // (4.0) a scan of a found timer asks for its area only, which the decoder makes much quicker than the whole picture
        var frameW = 0
        var frameH = 0
        val pic = object : PicAt {
            override fun at(t: Double): GrayPic? = source.gray(t).also { p ->
                timing.pictures++
                if (p != null && frameW == 0) { frameW = p.w; frameH = p.h; timing.size = "${p.w} x ${p.h}" }
            }
            override fun area(t: Double, area: Area): AreaPic? = source.grayArea(t, area).also { p ->
                timing.pictures++
                if (p != null && frameW == 0) { frameW = p.frameW; frameH = p.frameH; timing.size = "${p.frameW} x ${p.frameH}" }
            }
        }
        // (4.0) a timer chosen by the user is looked for where it was chosen, not where the one of the video before was
        val hinted = if (near == null) hint else null
        // (4.0) while looking for the timer, the bar shows the share of every place the search would look at if it found nothing, so it
        // moves all the way through a video without a timer (owner, 2026-11-28: it looked stuck at a few percent)
        val segHintPlaces = if (hinted is TimerHint.Segments) segmentHintBudget(durationSec) else 0
        val segPlaces = if (near == null) segmentPlaceBudget(durationSec) else 0
        val budget = max(1, segHintPlaces + solidPlaceBudget(durationSec, hinted is TimerHint.Solid) + segPlaces)
        var solidLooked = 0
        var segLooked = 0
        fun looking() = onProgress(true, min(99, 100 * (solidLooked + segLooked) / budget))
        var segments: FoundSegments? = null
        var segmentTimes: List<Pair<Sighting, SegmentGrid>> = emptyList()
        val otherKind = {
            val from = System.nanoTime()
            val r = findSegments(pic, durationSec, { segLooked = segHintPlaces + segPlaces * it / 100; looking() }, isCancelled)
            timing.segmentsSec = (System.nanoTime() - from) / 1e9
            segments = r.found
            segmentTimes = r.held
            segments != null
        }
        // (4.0) a seven-segment timer where the one of the video before was: then the usual search isn't needed
        var fromHint = false
        if (hinted is TimerHint.Segments) findSegmentsNear(pic, durationSec, hinted, { segLooked = segHintPlaces * it / 100; looking() }, isCancelled)?.let {
            segments = it
            fromHint = true
        }
        // (a number the user chose is a solid one)
        val found = if (segments != null) null
        else findTimer(pic, durationSec, fonts, near, { solidLooked = it; looking() }, isCancelled, if (near == null) otherKind else null, hinted as? TimerHint.Solid)
        if (found?.fromHint == true) fromHint = true
        part().let { (sec, pics) -> timing.findSec = sec; timing.findPictures = pics }
        segments?.let { s ->
            timing.timer = "seven segments (QiYi-like)" + (if (fromHint) ", where it was in the video before" else "")
            // (4.0) following it is the first tenth of reading it
            val timer = SegmentTracker.follow(s, pic, durationSec, { onProgress(false, it / 10) }, isCancelled)
            part().let { (sec, pics) -> timing.followSec = sec; timing.followPictures = pics }
            // (4.0) the half-second steps may take a nearby frame of the video's lower layers (GraySource.setNear)
            source.setNear(true)
            source.setAhead(true)
            val scan = scanSegments(
                timer, durationSec, frameSec, { t -> frameTimeOf(source, t) }, { done, total -> onProgress(false, 10 + 90 * done / total) }, isCancelled,
                { source.setNear(!it) }
            )
            source.setAhead(false)
            source.setNear(false)
            part().let { (sec, pics) -> timing.scanSec = sec; timing.scanPictures = pics }
            return AutoScanResult(scan, timer.numbers(), emptyList(), TimerHint.Segments(timer.grid, frameW, frameH))
        }
        found!!
        // last: a seven-segment timer that shows only its final times (csTimer's "digital" fonts with "solve"), from what the search above read
        val lastCheck = System.nanoTime()
        val heldGrid = if (found.timer == null && found.choices.isEmpty()) findHeldSegments(segmentTimes, pic) else null
        timing.lastCheckSec = (System.nanoTime() - lastCheck) / 1e9
        heldGrid?.let { grid ->
            timing.timer = "seven segments, by its final times"
            part()
            val scan = scanHeldTimer(
                { t -> pic.at(t)?.let { HeldShown(heldSegmentRow(grid, it)?.first?.text, null) } }, durationSec, frameSec,
                { t -> frameTimeOf(source, t) }, { done, total -> onProgress(false, 10 + 90 * done / total) }, isCancelled
            )
            part().let { (sec, pics) -> timing.scanSec = sec; timing.scanPictures = pics }
            return AutoScanResult(scan, grid.box(grid.decimals + 2), emptyList())
        }
        if (found.timer == null && found.choices.isNotEmpty()) timing.timer = "two numbers ran: asks which one"
        val timer = found.timer ?: return AutoScanResult(null, null, found.choices)
        timing.timer = if (timer.held) "solid digits, by its final times" else "solid digits, running time with ${timer.runningDecimals} decimal" +
                (if (timer.runningDecimals == 1) "" else "s") + ", ${timer.digitHeight.toInt()} px" + (if (fromHint) ", where it was in the video before" else "") +
                (if (found.digits.isNotEmpty()) "; digits ${found.digits}" else "")
        // (a timer found by its final times is read closely in time, so it gets the exact frames)
        val scan = if (timer.held) scanHeldTimer(
            { t -> pic.area(t, timer.area)?.let { timer.readHeld(it) } }, durationSec, frameSec,
            { t -> frameTimeOf(source, t) }, { done, total -> onProgress(false, 10 + 90 * done / total) }, isCancelled
        ) else {
            source.setNear(true)
            source.setAhead(true)
            scanFoundTimer(
                { t -> pic.area(t, timer.area)?.let { timer.read(it)?.text } }, durationSec, timer.runningDecimals, timer.stoppedDecimals, frameSec,
                { t -> frameTimeOf(source, t) }, { done, total -> onProgress(false, 10 + 90 * done / total) }, isCancelled,
                exact = { source.setNear(!it) }
            ).also { source.setAhead(false); source.setNear(false) }
        }
        part().let { (sec, pics) -> timing.scanSec = sec; timing.scanPictures = pics }
        return AutoScanResult(scan, timer.numbers, emptyList(), if (timer.held) null else TimerHint.Solid(timer.numbers, timer.digitHeight, frameW, frameH, timer.examples.takeIf { it.isLearned }))
    } finally {
        val closing = System.nanoTime()
        timing.source = source.note()
        source.close()
        timing.closeSec = (System.nanoTime() - closing) / 1e9
        timing.leftMs = System.currentTimeMillis()
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
