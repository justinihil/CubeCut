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
 * [loadAttempt] gives the video's timer setup as last tried, also one that was refused (null = never tried).
 * [canRun] is false while a scan, an import or a timer setup is running. A timer that can't be found does NOT stop the report: that
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
    loadAttempt: suspend () -> SetupAttempt?,
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

    suspend fun listTruth() = GroundTruth(videoName, timerKind, durationSec, listSolves(), setup = loadAttempt())

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
                "Saved ${truth.solves.size} solves" + (if (truth.setup != null) " and the timer setup." else ". No timer setup was tried yet.")
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
                    // an answer key without a setup (none was tried when it was saved) uses this video's own
                    onRun(if (truth.setup != null) truth else truth.copy(setup = loadAttempt()), videoName)
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
                            "No timer setup, or a refused one? The report counts 0 found.",
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
                    if (!canRun) Text("Wait for the import, the scan or the timer setup to finish.", style = MaterialTheme.typography.labelSmall)

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

// ---- a refused timer setup is remembered per video (debug builds only, outside the database), so the report and the answer key know
// where the display was and what it showed. A setup that worked is in the database (SessionStore.saveTimerSetup) and wins. ----
private const val ATTEMPT_PREFS = "scan_report_setups"

/** Remembers a setup that was refused on this video ([attempt] null = forget it, a setup worked). */
fun rememberRefusedSetup(context: Context, footageId: Long, attempt: SetupAttempt?) {
    val e = context.getSharedPreferences(ATTEMPT_PREFS, Context.MODE_PRIVATE).edit()
    if (attempt == null) e.remove(footageId.toString()) else e.putString(footageId.toString(), setupAttemptToJson(attempt))
    e.apply()
}

/** The video's timer setup as last tried: the one that worked, else the refused one, else null. Reads storage: call it on IO. */
fun lastSetupAttempt(context: Context, footageId: Long): SetupAttempt? {
    val f = SessionStore.getFootage(context, footageId)
    val corners = f?.let { SessionStore.timerCornersOf(it) }
    if (f != null && corners != null) return SetupAttempt(f.timerFrameMs, corners.toList(), f.timerTyped)
    return context.getSharedPreferences(ATTEMPT_PREFS, Context.MODE_PRIVATE).getString(footageId.toString(), null)?.let { parseSetupAttempt(it) }
}

/**
 * Runs the report's three stages and returns its text: finds the timer, reads the held times, then scans. Until the app can find a timer
 * by itself (3.3-C), the timer is set up from scratch as [truth] says, so a changed reader is tried on the same display.
 * Blocking; run it on Dispatchers.Default. Its engine is its own and is freed here.
 */
fun runScanReportStages(
    context: Context, uri: Uri, durationMs: Long, truth: GroundTruth, videoName: String, app: String,
    onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean
): String {
    val timesOnly = truth.timesOnly.isNotEmpty()
    fun failed(why: String) =
        if (timesOnly) timesReportText(truth, videoName, false, why, compareTimes(truth.timesOnly, emptyList()), 0.0, 0.0, app)
        else scanReportText(truth, videoName, false, why, emptyList(), compareScan(truth.solves, emptyList()), 0.0, 0.0, app)
    val attempt = truth.setup ?: return failed("CubeCut can't find a timer by itself yet (3.3-C), and none was set up by hand.")
    val frame = grabFrame(context, uri, attempt.frameMs) ?: return failed("No picture at ${videoPlace(attempt.frameMs / 1000.0)}.")
    val res = TimerVision.createSetup(frame, attempt.corners.toDoubleArray(), attempt.typed)
    val setup = res.setup
    val ref = res.reference
    if (setup == null || ref == null) return failed("The setup by hand was refused: " + res.message)
    val engine = TimerEngine(setup, ref)
    try {
        val started = System.currentTimeMillis()
        val scan = SolveAnalyzer.analyze(context, uri, durationMs, engine, onProgress, isCancelled)
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        val found = "Set up by hand. " + res.message
        if (timesOnly) {
            return timesReportText(truth, videoName, true, found, compareTimes(truth.timesOnly, scan.solves), seconds, scan.readableFraction, app)
        }
        engine.forgetPosition()
        val source = openFrameSource(context, uri, durationMs)
        val reading = try {
            gradeReading(truth.solves, engine.decimals, durationMs / 1000.0) { t ->
                if (isCancelled()) throw ScanCancelled()
                val gray = source.gray(t)
                if (gray == null) null else try { engine.readDigitsGray(gray) } finally { gray.release() }
            }
        } finally {
            source.close()
        }
        return scanReportText(truth, videoName, true, found, reading, compareScan(truth.solves, scan.solves), seconds, scan.readableFraction, app)
    } finally {
        engine.release()
    }
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
