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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// (3.3-A) The debug-only scan report, shown in the editor of debug builds only (BuildConfig.DEBUG). It runs Find solves again WITHOUT
// touching the list, and compares what it finds with the expected solves: the list you fixed by hand, a ground-truth file, or a plain list
// of times (csTimer's export).
// It can also save the list as a ground-truth file for the repo (app/src/test/resources/groundtruth/). Model and maths: GroundTruth.kt.

/** One finished report: the text shown and copied. */
class ScanReportResult(val text: String)

/**
 * Everything the scan report needs from the editor. [listSolves] are the expected solves from the editor's list right now.
 * [canRun] is false while a scan or an import is running. A timer that can't be found does NOT stop the report: that
 * is the result then. [onRun] starts it (the editor owns the `analyzing` guard); the editor then sets [result].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanReportHost(
    open: Boolean,
    onClose: () -> Unit,
    videoUri: Uri?,
    durationSec: Double,
    listSolves: () -> List<ExpectedSolve>,
    canRun: Boolean,
    result: ScanReportResult?,
    onResultClose: () -> Unit,
    onRun: (GroundTruth, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var timerKind by remember { mutableStateOf(TIMER_KINDS[0]) }
    val videoName = remember(videoUri) { videoUri?.let { displayNameOf(context, it) } ?: "" }

    fun listTruth() = GroundTruth(videoName, timerKind, durationSec, listSolves())

    // (launchers live here, outside the dialogs, so a result still arrives after the dialog closed for the file picker)
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val truth = listTruth()
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("couldn't open the file")
                    out.use { it.write(groundTruthToJson(truth).toByteArray(Charsets.UTF_8)) }
                }
                "Saved ${truth.solves.size} solves."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't save: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val truth = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw java.io.IOException("couldn't open the file")
                    // a ground-truth file is JSON; anything else has to be a list of times
                    if (text.trimStart().startsWith("{")) parseGroundTruth(text)
                    else GroundTruth("", "", durationSec, emptyList(), timesOnly = parseTimesList(text))
                }
                if (canRun) {
                    onClose()
                    onRun(truth, videoName)
                } else {
                    message = "Can't run now: wait for Find solves or the export to finish."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Couldn't read it: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    if (open) {
        val count = listSolves().size
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Scan report (debug)") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BulletLines(
                        listOf(
                            "Finds the timer, reads it and finds solves. Your list doesn't change.",
                            "A file can be a reference file or csTimer's CSV export."
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onClose(); scope.launch { onRun(listTruth(), videoName) } },
                        enabled = canRun && count > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Compare with the list ($count solves)") }
                    OutlinedButton(
                        onClick = { message = ""; openFile.launch(arrayOf("*/*")) },
                        enabled = canRun,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Compare with a file...") }
                    if (!canRun) Text("Wait for the import or the scan to finish.", style = MaterialTheme.typography.labelSmall)

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("Save the list as a reference file", style = MaterialTheme.typography.titleSmall)
                    Text("Fix the list by hand first: it becomes the expected solves.", style = MaterialTheme.typography.bodySmall)
                    Text("Timer", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (k in TIMER_KINDS) ChoiceChip(k, k == timerKind) { timerKind = k }
                    }
                    OutlinedButton(
                        onClick = { message = ""; saveFile.launch(videoName.substringBeforeLast('.').ifEmpty { "reference" } + ".groundtruth.json") },
                        enabled = count > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save file...") }
                    if (message.isNotEmpty()) InfoNote(message, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
        )
    }

    if (result != null) {
        AlertDialog(
            onDismissRequest = onResultClose,
            title = { Text("Scan report") },
            text = {
                Text(
                    result.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                Button(onClick = { copyText(context, result.text) }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = onResultClose) { Text("Close") } }
        )
    }
}

/**
 * Runs the report and returns its text: CubeCut finds the timer, reads the held times and scans, as Find solves does (3.3-C).
 * Blocking; run it on Dispatchers.Default. Everything it opens is freed here.
 */
fun runScanReportStages(
    context: Context, uri: Uri, durationMs: Long, truth: GroundTruth, videoName: String, app: String,
    onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean
): String {
    return byItselfReport(context, uri, durationMs, truth, videoName, app, onProgress, isCancelled)
}

private fun noTimerText(truth: GroundTruth, videoName: String, why: String, app: String) =
    if (truth.timesOnly.isNotEmpty()) timesReportText(truth, videoName, false, why, compareTimes(truth.timesOnly, emptyList()), 0.0, 0.0, app)
    else scanReportText(truth, videoName, false, why, emptyList(), compareScan(truth.solves, emptyList()), 0.0, 0.0, app)

/** (3.3-C) What CubeCut makes of this video by itself, and how long each part takes on this phone. The answer key is only used to grade. */
private fun byItselfReport(
    context: Context, uri: Uri, durationMs: Long, truth: GroundTruth, videoName: String, app: String,
    onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean
): String {
    val durationSec = durationMs / 1000.0
    val fonts = loadDigitFonts(context)
    val source = openFrameSource(context, uri, durationMs)
    try {
        var pictures = 0
        var inPictures = 0L
        val pic = object : PicAt {
            override fun at(t: Double): GrayPic? {
                val start = System.nanoTime()
                val p = source.gray(t)
                pictures++
                inPictures += System.nanoTime() - start
                return p
            }

            // (4.0) as Find solves does: a found timer's area only
            override fun area(t: Double, area: Area): AreaPic? {
                val start = System.nanoTime()
                val p = source.grayArea(t, area)
                pictures++
                inPictures += System.nanoTime() - start
                return p
            }
        }

        val atStart = source.stats()?.copy()
        val start = System.nanoTime()
        val segments = ArrayList<String>()
        var segmentSec = 0.0
        var segmentTimer: FoundSegments? = null
        var segmentTimes: List<Pair<Sighting, SegmentGrid>> = emptyList()
        val r = findTimer(pic, durationSec, fonts, null, { onProgress(it / 10, 100) }, isCancelled) {
            val before = pictures
            val beforeNanos = inPictures
            val from = System.nanoTime()
            val s = findSegments(pic, durationSec, { onProgress(it, 100) }, isCancelled)
            segmentSec = (System.nanoTime() - from) / 1e9
            segments.addAll(segmentNotes(s, segmentSec, pictures - before, (inPictures - beforeNanos) / 1e9))
            pictures = before
            inPictures = beforeNanos
            segmentTimer = s.found
            segmentTimes = s.held
            s.found != null
        }
        val finding = (System.nanoTime() - start) / 1e9 - segmentSec
        val how = ArrayList<String>()
        how.add("")
        how.add("How it was found:")
        how.addAll(r.notes)
        how.add(
            "Took ${"%.1f".format(finding)} s: ${"%.1f".format(inPictures / 1e9)} s for $pictures pictures, ${"%.1f".format(finding - inPictures / 1e9)} s " +
                    "for ${r.wholeFrames} whole-picture searches and ${r.areaReads} area reads"
        )
        source.stats()?.let { s -> atStart?.let { how.add("Pictures while finding: " + s.since(it)) } }
        how.addAll(segments)
        segmentTimer?.let { return segmentReport(context, uri, durationSec, it, source, truth, videoName, app, how, onProgress, isCancelled) }
        if (r.timer == null && r.choices.isEmpty()) findHeldSegments(segmentTimes, pic, how)?.let {
            return heldSegmentReport(context, uri, durationSec, it, source, truth, videoName, app, how, onProgress, isCancelled)
        }
        val timer = r.timer ?: return noTimerText(truth, videoName, if (r.choices.isEmpty()) "CubeCut didn't find one by itself." else "Two numbers run at the same time, CubeCut would ask which one.", app) + how.joinToString("\n", "\n")
        val a = timer.area
        val found = "Found by itself at x ${a.left}-${a.right}, y ${a.top}-${a.bottom}, digits ${timer.digitHeight.toInt()} px, ${if (timer.dark) "dark" else "light"}, " +
                (if (timer.held) "by its final times (${timer.stoppedDecimals} decimals, no running time with decimals), " else "decimals ${timer.runningDecimals} running and ${timer.stoppedDecimals} stopped, ") + (if (timer.examples.isLearned) "digits learned." else "digits NOT learned.")

        pictures = 0
        inPictures = 0L
        var inReading = 0L
        val atScan = source.stats()?.copy()
        fun <T> timed(t: Double, look: (AreaPic) -> T?): T? {
            val p = pic.area(t, timer.area) ?: return null
            val s = System.nanoTime()
            return look(p).also { inReading += System.nanoTime() - s }
        }
        fun read(t: Double): String? = if (timer.held) timed(t) { timer.readHeld(it).text } else timed(t) { timer.read(it)?.text }
        val scanStart = System.nanoTime()
        val scan = if (timer.held) scanHeldTimer(
            { t -> timed(t) { timer.readHeld(it) } }, durationSec, videoFrameSec(context, uri),
            { t -> frameTimeOf(source, t) }, { done, total -> onProgress(10 + 90 * done / total, 100) }, isCancelled
        ) else scanFoundTimer(
            ::read, durationSec, timer.runningDecimals, timer.stoppedDecimals, videoFrameSec(context, uri),
            { t -> frameTimeOf(source, t) }, { done, total -> onProgress(10 + 90 * done / total, 100) }, isCancelled
        )
        val seconds = (System.nanoTime() - scanStart) / 1e9
        how.add(
            "Scan: ${"%.1f".format(seconds)} s, ${"%.1f".format(inPictures / 1e9)} s for $pictures pictures, ${"%.1f".format(inReading / 1e9)} s reading " +
                    "(${"%.1f".format(inReading / 1e6 / maxOf(1, pictures))} ms each)"
        )
        source.stats()?.let { s -> atScan?.let { how.add("Pictures while scanning: " + s.since(it)) } }
        val flagged = scan.solves.filter { it.note.isNotEmpty() }
        if (flagged.isNotEmpty()) how.add("Flagged for checking:")
        for (f in flagged) how.add("${videoPlace(f.stopSec)} ${f.timeText}: ${f.note}")
        val hints = hintsOf(scan)
        if (hints.isNotEmpty()) how.add("Maybe missed:")
        for (h in hints) how.add("${videoPlace(h.fromSec)} - ${videoPlace(h.toSec)}: ${h.text}")

        val text = if (truth.timesOnly.isNotEmpty()) {
            timesReportText(truth, videoName, true, found, compareTimes(truth.timesOnly, scan.solves), seconds, scan.readableFraction, app)
        } else {
            val reading = gradeReading(truth.solves, timer.stoppedDecimals, durationSec) { t ->
                if (isCancelled()) throw ScanCancelled()
                read(t)?.filter { it.isDigit() }
            }
            scanReportText(truth, videoName, true, found, reading, compareScan(truth.solves, scan.solves), seconds, scan.readableFraction, app)
        }
        return text + how.joinToString("\n", "\n")
    } finally {
        source.close()
    }
}

/** What the seven-segment finder did, and how long it took on this phone. */
private fun segmentNotes(s: SegmentResult, seconds: Double, pictures: Int, pictureSec: Double): List<String> {
    val out = arrayListOf("", if (s.found == null) "Seven-segment timer: NOT FOUND" else "Seven-segment timer: FOUND")
    if (s.found != null) {
        val g = s.found.grid
        val b = g.box(g.decimals + 2)
        out.add(
            "At ${videoPlace(s.found.t)}, x ${b.left}-${b.right}, y ${b.top}-${b.bottom}, digits ${g.height.toInt()} px, ${if (g.dark) "dark" else "light"}, " +
                    "${g.decimals} decimals, fit ${"%.2f".format(s.found.fit)}"
        )
        out.add("It showed, half a second apart (without tens): " + s.found.shown.joinToString(" ") { "%.1f".format(it % 10) })
    }
    out.add(
        "Took ${"%.1f".format(seconds)} s for ${s.places} places: ${"%.1f".format(pictureSec)} s for $pictures pictures, ${"%.1f".format(s.rowsSec)} s looking for rows, " +
                "the rest for checking ${s.checked} rows"
    )
    return out
}

/** A seven-segment timer followed and scanned, graded like the others. */
private fun segmentReport(
    context: Context, uri: Uri, durationSec: Double, found: FoundSegments, source: GraySource, truth: GroundTruth, videoName: String, app: String,
    how: MutableList<String>, onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean
): String {
    var pictures = 0
    var inPictures = 0L
    val pic = PicAt { t ->
        val start = System.nanoTime()
        val p = source.gray(t)
        pictures++
        inPictures += System.nanoTime() - start
        p
    }
    val atFollow = source.stats()?.copy()
    val followStart = System.nanoTime()
    val timer = SegmentTracker.follow(found, pic, durationSec, { onProgress(10 + it / 10, 100) }, isCancelled)
    how.add(
        "Followed: ${"%.1f".format((System.nanoTime() - followStart) / 1e9)} s, ${"%.1f".format(inPictures / 1e9)} s of it for $pictures pictures; " +
                "grid refined with ${timer.refinedWith} pictures, fit ${"%.3f".format(timer.fit)}"
    )
    source.stats()?.let { s -> atFollow?.let { how.add("Pictures while following: " + s.since(it)) } }
    pictures = 0
    inPictures = 0L
    val atScan = source.stats()?.copy()
    val scanStart = System.nanoTime()
    val scan = scanSegments(
        timer, durationSec, videoFrameSec(context, uri), { t -> frameTimeOf(source, t) }, { done, total -> onProgress(20 + 80 * done / total, 100) }, isCancelled
    )
    val seconds = (System.nanoTime() - scanStart) / 1e9
    val (across, down) = timer.moved()
    how.add(
        "Scan: ${"%.1f".format(seconds)} s, ${"%.1f".format(inPictures / 1e9)} s for $pictures pictures, the rest reading " +
                "(${"%.1f".format((seconds * 1e3 - inPictures / 1e6) / maxOf(1, pictures))} ms each); ${timer.farLooks} looks further away; it moved ${across.toInt()} x ${down.toInt()} px"
    )
    source.stats()?.let { s -> atScan?.let { how.add("Pictures while scanning: " + s.since(it)) } }
    val flagged = scan.solves.filter { it.note.isNotEmpty() }
    if (flagged.isNotEmpty()) how.add("Flagged for checking:")
    for (f in flagged) how.add("${videoPlace(f.stopSec)} ${f.timeText}: ${f.note}")
    val hints = hintsOf(scan)
    if (hints.isNotEmpty()) how.add("Maybe missed:")
    for (h in hints) how.add("${videoPlace(h.fromSec)} - ${videoPlace(h.toSec)}: ${h.text}")

    val b = timer.grid.box(timer.decimals + 2)
    val where = "Found by itself: seven-segment, x ${b.left}-${b.right}, y ${b.top}-${b.bottom} at first, digits ${timer.grid.height.toInt()} px, ${if (timer.grid.dark) "dark" else "light"}, ${timer.decimals} decimals."
    val text = if (truth.timesOnly.isNotEmpty()) {
        timesReportText(truth, videoName, true, where, compareTimes(truth.timesOnly, scan.solves), seconds, scan.readableFraction, app)
    } else {
        val reading = gradeReading(truth.solves, timer.decimals, durationSec) { t -> timer.shown(t).text?.filter { it.isDigit() } }
        scanReportText(truth, videoName, true, where, reading, compareScan(truth.solves, scan.solves), seconds, scan.readableFraction, app)
    }
    return text + how.joinToString("\n", "\n")
}

/** A seven-segment timer found by its final times, scanned and graded like the others. */
private fun heldSegmentReport(
    context: Context, uri: Uri, durationSec: Double, grid: SegmentGrid, source: GraySource, truth: GroundTruth, videoName: String, app: String,
    how: MutableList<String>, onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean
): String {
    var pictures = 0
    var inPictures = 0L
    val pic = PicAt { t ->
        val start = System.nanoTime()
        val p = source.gray(t)
        pictures++
        inPictures += System.nanoTime() - start
        p
    }
    fun read(t: Double): String? = pic.at(t)?.let { heldSegmentRow(grid, it)?.first?.text }
    val atScan = source.stats()?.copy()
    val scanStart = System.nanoTime()
    val scan = scanHeldTimer(
        { t -> pic.at(t)?.let { HeldShown(heldSegmentRow(grid, it)?.first?.text, null) } }, durationSec, videoFrameSec(context, uri),
        { t -> frameTimeOf(source, t) }, { done, total -> onProgress(10 + 90 * done / total, 100) }, isCancelled
    )
    val seconds = (System.nanoTime() - scanStart) / 1e9
    how.add(
        "Scan: ${"%.1f".format(seconds)} s, ${"%.1f".format(inPictures / 1e9)} s for $pictures pictures, the rest reading " +
                "(${"%.1f".format((seconds * 1e3 - inPictures / 1e6) / maxOf(1, pictures))} ms each)"
    )
    source.stats()?.let { s -> atScan?.let { how.add("Pictures while scanning: " + s.since(it)) } }
    val flagged = scan.solves.filter { it.note.isNotEmpty() }
    if (flagged.isNotEmpty()) how.add("Flagged for checking:")
    for (f in flagged) how.add("${videoPlace(f.stopSec)} ${f.timeText}: ${f.note}")
    val hints = hintsOf(scan)
    if (hints.isNotEmpty()) how.add("Maybe missed:")
    for (h in hints) how.add("${videoPlace(h.fromSec)} - ${videoPlace(h.toSec)}: ${h.text}")

    val b = grid.box(grid.decimals + 2)
    val where = "Found by itself: seven-segment, by its final times, x ${b.left}-${b.right}, y ${b.top}-${b.bottom}, digits ${grid.height.toInt()} px, " +
            "${if (grid.dark) "dark" else "light"}, ${grid.decimals} decimals."
    val text = if (truth.timesOnly.isNotEmpty()) {
        timesReportText(truth, videoName, true, where, compareTimes(truth.timesOnly, scan.solves), seconds, scan.readableFraction, app)
    } else {
        val reading = gradeReading(truth.solves, grid.decimals, durationSec) { t ->
            if (isCancelled()) throw ScanCancelled()
            read(t)?.filter { it.isDigit() }
        }
        scanReportText(truth, videoName, true, where, reading, compareScan(truth.solves, scan.solves), seconds, scan.readableFraction, app)
    }
    return text + how.joinToString("\n", "\n")
}

private fun copyText(context: Context, text: String) {
    try {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CubeCut scan report", text))
    } catch (e: Exception) {
    }
}

/** The file name of [uri] ("" if the provider doesn't say). */
private fun displayNameOf(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) ?: "" else ""
    } ?: ""
} catch (e: Exception) {
    ""
}
