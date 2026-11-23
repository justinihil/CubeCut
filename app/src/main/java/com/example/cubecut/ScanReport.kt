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
// touching the list, and compares what it finds with the expected solves: the list you fixed by hand, or a ground-truth file.
// It can also save the list as a ground-truth file for the repo (app/src/test/resources/groundtruth/). Model and maths: GroundTruth.kt.

/** One finished report: the text shown and copied. */
class ScanReportResult(val text: String)

/**
 * Everything the scan report needs from the editor. [listSolves] are the expected solves from the editor's list right now.
 * [canRun] is false while a scan or an export is running, or the timer isn't set up. [onRun] starts the scan (the editor owns the timer
 * engine and the `analyzing` guard); the editor then sets [result].
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
        val truth = listTruth()
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("couldn't open the file")
                    out.use { it.write(groundTruthToJson(truth).toByteArray(Charsets.UTF_8)) }
                }
                "Saved ${truth.solves.size} solves. Copy the file to app/src/test/resources/groundtruth/."
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
                    parseGroundTruth(text)
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
                            "Runs Find solves again. Your list doesn't change.",
                            "Then shows found, missed and false solves, and how far off each cut is."
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onClose(); onRun(listTruth(), videoName) },
                        enabled = canRun && count > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Compare with the list ($count solves)") }
                    OutlinedButton(
                        onClick = { message = ""; openFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                        enabled = canRun,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Compare with a file...") }
                    if (!canRun) Text("Set up the timer, and wait for any scan or export to finish.", style = MaterialTheme.typography.labelSmall)

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
