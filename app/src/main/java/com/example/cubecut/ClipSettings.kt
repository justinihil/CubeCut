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

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------
// Controls for one clip that both the editor's Edit window and the Gallery viewer's sheet show (3.2-C on):
//   PenaltyPicker        - No penalty / +2 (tap again for another) / DNF
//   PadEditor            - (3.2-D) this clip's buffers: Follow video, or Custom with two sliders
//   rememberExportRenamer - renames an exported file after its penalty changed, asking Android first when CubeCut no longer owns it
// ---------------------------------------------------------------------------------------

/** One choice button, solid blue with a tick when chosen (like the tag window's, so the two look alike). */
@Composable
fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val blue = MaterialTheme.colorScheme.primary
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        leadingIcon = if (selected) ({ Text("✓", fontWeight = FontWeight.Bold) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = blue,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled, selected = selected,
            borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = blue
        )
    )
}

/**
 * (3.2-C) The penalty of one solve: No penalty, +2 (each tap adds one more: "+2 ×2"), or DNF. DNF and +2 exclude each other.
 * [onChange] gets the new (plus2Count, dnf).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PenaltyPicker(plus2Count: Int, dnf: Boolean, enabled: Boolean = true, onChange: (Int, Boolean) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip("No penalty", !dnf && plus2Count == 0, enabled) { onChange(0, false) }
            ChoiceChip(if (plus2Count > 1) "+2 ×$plus2Count" else "+2", plus2Count > 0, enabled) {
                onChange(if (dnf) 1 else plus2Count + 1, false)
            }
            ChoiceChip("DNF", dnf, enabled) { onChange(0, !dnf) }
        }
        if (plus2Count > 0) {
            Text("Tap +2 again for another. No penalty clears them.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The buffer sliders' range and step, the same as the editor's Setup box (0 to 8 s in 0.5 s steps). */
private const val PAD_MAX = 8f
private const val PAD_STEPS = 15

/**
 * (3.2-D) One clip's own buffers. *Follow video* = null (the clip uses the video's sliders, [videoBefore] / [videoAfter]); *Custom*
 * starts from the video's values. [onChange] gets (before, after) when a choice is made or a slider is let go, not while it moves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PadEditor(videoBefore: Double, videoAfter: Double, before: Double?, after: Double?, enabled: Boolean = true, onChange: (Double?, Double?) -> Unit) {
    val custom = before != null || after != null
    var b by remember(before, videoBefore) { mutableStateOf((before ?: videoBefore).toFloat()) }
    var a by remember(after, videoAfter) { mutableStateOf((after ?: videoAfter).toFloat()) }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip("Follow video", !custom, enabled) { onChange(null, null) }
            ChoiceChip("Custom", custom, enabled) { if (!custom) onChange(videoBefore, videoAfter) }
        }
        if (!custom) {
            Text(
                "${"%.1f".format(videoBefore)} s before · ${"%.1f".format(videoAfter)} s after, from the video",
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            Text("Before the solve: ${"%.1f".format(b)} s", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = b, onValueChange = { b = it }, onValueChangeFinished = { onChange(b.toDouble(), a.toDouble()) },
                valueRange = 0f..PAD_MAX, steps = PAD_STEPS, enabled = enabled
            )
            Text("After the solve: ${"%.1f".format(a)} s", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = a, onValueChange = { a = it }, onValueChangeFinished = { onChange(b.toDouble(), a.toDouble()) },
                valueRange = 0f..PAD_MAX, steps = PAD_STEPS, enabled = enabled
            )
            Text("Before counts only when the scramble is left out.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** One rename waiting to happen: the exported file, its new name, and who to tell the name Android gave it. */
private class RenameJob(val export: ExportedClip, val newName: String, val onRenamed: (String) -> Unit)

/** What one try at renaming gave. */
private sealed class RenameOutcome {
    class Done(val name: String) : RenameOutcome()
    class NeedsPermission(val mediaUri: Uri) : RenameOutcome()
    class Failed(val reason: String) : RenameOutcome()
}

/**
 * Renames the file and records its new name in one background step (so leaving the screen can't lose the record). A file CubeCut
 * doesn't own any more (after a reinstall) gives NeedsPermission, unless [asked] says the user already allowed it.
 */
private suspend fun tryRename(context: Context, job: RenameJob, asked: Boolean): RenameOutcome = withContext(Dispatchers.IO) {
    val media = SessionStore.mediaStoreUriOf(job.export.uri)
    try {
        val name = ClipExporter.renameFile(context, media ?: Uri.parse(job.export.uri), job.newName)
        SessionStore.setExportFileName(context, job.export.id, name)
        RenameOutcome.Done(name)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        if (media != null && !asked) RenameOutcome.NeedsPermission(media) else RenameOutcome.Failed("Android didn't allow it")
    } catch (e: Exception) {
        RenameOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
}

/** Renames exported files (see rememberExportRenamer). */
class ExportRenamer internal constructor(private val start: (RenameJobArgs) -> Unit) {
    /** Renames [export] to [newName]; [onRenamed] gets the name the file really has afterwards. */
    fun rename(export: ExportedClip, newName: String, onRenamed: (String) -> Unit = {}) = start(RenameJobArgs(export, newName, onRenamed))
}

/** The arguments of one ExportRenamer.rename call. */
class RenameJobArgs(val export: ExportedClip, val newName: String, val onRenamed: (String) -> Unit)

/**
 * (3.2-C) Renames exported files whose penalty changed. The export keeps its link (`stop_sec`); only `exports.file_name` changes.
 * CubeCut can rename a file it made itself. After a reinstall it doesn't own the file any more, so Android shows its own "Allow
 * CubeCut to modify this video?" (MediaStore.createWriteRequest) first. [onMessage] gets a short line about how it went.
 */
@Composable
fun rememberExportRenamer(onMessage: (String) -> Unit): ExportRenamer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message by rememberUpdatedState(onMessage)
    val waiting = remember { arrayOfNulls<RenameJob>(1) }

    fun report(job: RenameJob, outcome: RenameOutcome) {
        when (outcome) {
            is RenameOutcome.Done -> {
                job.onRenamed(outcome.name)
                message("Renamed the file to ${outcome.name}")
            }
            is RenameOutcome.Failed -> message("Couldn't rename ${job.export.fileName}: ${outcome.reason}. It keeps its old name.")
            is RenameOutcome.NeedsPermission -> Unit
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val job = waiting[0]
        waiting[0] = null
        if (job != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                scope.launch { report(job, tryRename(context, job, asked = true)) }
            } else {
                message("${job.export.fileName} keeps its old name.")
            }
        }
    }

    return remember {
        ExportRenamer { args ->
            val job = RenameJob(args.export, args.newName, args.onRenamed)
            scope.launch {
                val outcome = tryRename(context, job, asked = false)
                if (outcome is RenameOutcome.NeedsPermission) {
                    try {
                        val request = MediaStore.createWriteRequest(context.contentResolver, listOf(outcome.mediaUri))
                        waiting[0] = job
                        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    } catch (e: Exception) {
                        waiting[0] = null
                        message("Couldn't ask Android to rename ${job.export.fileName}: ${e.message ?: e.javaClass.simpleName}")
                    }
                } else {
                    report(job, outcome)
                }
            }
        }
    }
}

/**
 * (3.2-C) The new name for [export] after its solve's penalty or time changed, or null when nothing needs renaming (same name, or a
 * file renamed by hand whose name CubeCut can't read).
 */
fun penaltyRename(export: ExportedClip, timeText: String, plus2Count: Int, dnf: Boolean): String? {
    val newName = renamedExportName(export.fileName, fileTimePart(timeText, plus2Count, dnf)) ?: return null
    return if (newName == export.fileName) null else newName
}
