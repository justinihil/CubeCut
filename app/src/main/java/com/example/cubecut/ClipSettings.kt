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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
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
 * (3.2-D) One clip's own extra time ("buffers" in the code). *Same as video* = null (the clip uses the video's sliders, [videoBefore] / [videoAfter]); *Custom*
 * starts from the video's values. [onChange] gets (before, after) when a choice is made or a slider is let go, not while it moves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PadEditor(videoBefore: Double, videoAfter: Double, before: Double?, after: Double?, enabled: Boolean = true, onChange: (Double?, Double?) -> Unit) {
    val custom = before != null || after != null
    var b by remember(before, videoBefore) { mutableStateOf((before ?: videoBefore).toFloat()) }
    var a by remember(after, videoAfter) { mutableStateOf((after ?: videoAfter).toFloat()) }
    Column {
        Text("Seconds kept before the solve starts and after the timer stops.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip("Same as video", !custom, enabled) { onChange(null, null) }
            ChoiceChip("Custom", custom, enabled) { if (!custom) onChange(videoBefore, videoAfter) }
        }
        if (!custom) {
            Text(
                "${"%.1f".format(videoBefore)} s before · ${"%.1f".format(videoAfter)} s after, same as the video",
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

/**
 * (4.0) "It's fine": a cut that needs a check was looked at. The same amber outlined button in the editor's list and in the viewer
 * (owner, 2026-11-26), so it reads the same everywhere.
 */
@Composable
fun ItsFineButton(onClick: () -> Unit, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Text(
        "\u2713  It's fine",
        color = AppColors.check,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(shape)
            .border(1.dp, AppColors.check, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/** (4.0) What "It's fine" leaves behind: a green "✓  Checked" (or "✓  Fixed"), in the editor and the viewer. */
@Composable
fun CheckedMark(text: String = "\u2713  Checked", modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    Text(text, color = AppColors.keep, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** (4.0) What a *Clip settings* window chose. null = leave it as it is; the extra time is both values or neither. */
class ClipSettingsChoice(val scramble: Boolean?, val before: Double?, val after: Double?)

/**
 * (4.0) The scramble and the extra time for many clips at once (owner, 2026-11-26): ticked clips or ticked sessions.
 * [scramble] / [before] / [after] are what the clips have now (null = they differ). The sliders are there at once (owner): the extra
 * time changes only if one is moved, so "leave it" needs no extra tap. [effects] are the warning's lines (how many clips, how many had
 * their own setting, how many are exported).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipSettingsDialog(
    title: String,
    scramble: Boolean?,
    before: Double?,
    after: Double?,
    effects: List<String>,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onDone: (ClipSettingsChoice) -> Unit
) {
    var pickScramble by remember { mutableStateOf<Boolean?>(null) }     // null = keep as it is
    var b by remember { mutableStateOf((before ?: 2.5).toFloat()) }
    var a by remember { mutableStateOf((after ?: 2.5).toFloat()) }
    var padsMoved by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                androidx.compose.ui.Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Scramble", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip(if (scramble == null) "Keep as it is (mixed)" else "Keep as it is", pickScramble == null) { pickScramble = null }
                    ChoiceChip("With scramble", pickScramble == true) { pickScramble = true }
                    ChoiceChip("Solve only", pickScramble == false) { pickScramble = false }
                }
                Text("Extra time", style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        padsMoved -> "Every clip gets these."
                        before == null || after == null -> "Mixed now. Unchanged unless you move a slider."
                        else -> "Unchanged unless you move a slider."
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                Text("Before the solve: ${"%.1f".format(b)} s", style = MaterialTheme.typography.bodyMedium)
                Slider(value = b, onValueChange = { b = it; padsMoved = true }, valueRange = 0f..PAD_MAX, steps = PAD_STEPS)
                Text("After the solve: ${"%.1f".format(a)} s", style = MaterialTheme.typography.bodyMedium)
                Slider(value = a, onValueChange = { a = it; padsMoved = true }, valueRange = 0f..PAD_MAX, steps = PAD_STEPS)
                Text("Before counts only when the scramble is left out.", style = MaterialTheme.typography.labelSmall)
                if (effects.isNotEmpty()) {
                    Spacer(androidx.compose.ui.Modifier.height(6.dp))
                    BulletLines(effects, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDone(ClipSettingsChoice(pickScramble, if (padsMoved) b.toDouble() else null, if (padsMoved) a.toDouble() else null))
                },
                enabled = pickScramble != null || padsMoved
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * (4.0) The *Clip settings* button when nothing is ticked (owner, 2026-11-26: always there, instead of in Settings): what new videos'
 * clips start with, and, ticked, the same for every video now. [videos] / [ownN] / [exportedN] describe "every video" for the warning.
 * [onSave] gets (scramble, before, after, apply to all videos).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipDefaultsDialog(
    scramble: Boolean, before: Float, after: Float, videos: Int, ownN: Int, exportedN: Int,
    onDismiss: () -> Unit, onSave: (Boolean, Float, Float, Boolean) -> Unit
) {
    var s by remember { mutableStateOf(scramble) }
    var b by remember { mutableStateOf(before) }
    var a by remember { mutableStateOf(after) }
    var all by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clip settings") },
        text = {
            Column(
                androidx.compose.ui.Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Videos you import from now on start like this.", style = MaterialTheme.typography.bodySmall)
                Text("Scramble", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip("With scramble", s) { s = true }
                    ChoiceChip("Solve only", !s) { s = false }
                }
                Text("Extra time", style = MaterialTheme.typography.labelLarge)
                Text("Before the solve: ${"%.1f".format(b)} s", style = MaterialTheme.typography.bodyMedium)
                Slider(value = b, onValueChange = { b = it }, valueRange = 0f..PAD_MAX, steps = PAD_STEPS)
                Text("After the solve: ${"%.1f".format(a)} s", style = MaterialTheme.typography.bodyMedium)
                Slider(value = a, onValueChange = { a = it }, valueRange = 0f..PAD_MAX, steps = PAD_STEPS)
                Text("Before counts only when the scramble is left out.", style = MaterialTheme.typography.labelSmall)
                if (videos > 0) {
                    androidx.compose.foundation.layout.Row(
                        androidx.compose.ui.Modifier.clickable { all = !all },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(checked = all, onCheckedChange = { all = it })
                        Text("Also apply to all $videos videos now", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (all) {
                        BulletLines(
                            listOfNotNull(
                                if (ownN > 0) "$ownN clip" + (if (ownN == 1) " has its own setting: it gets these too." else "s have their own setting: they get these too.") else null,
                                if (exportedN > 0) "$exportedN " + (if (exportedN == 1) "clip is exported: export it again to update the file." else "clips are exported: export them again to update the files.") else null
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(s, b, a, all) }) { Text(if (all) "Save and apply" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
