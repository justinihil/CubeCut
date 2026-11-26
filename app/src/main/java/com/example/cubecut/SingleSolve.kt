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

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------
// (3.2-F) Clips of single solves, imported as "One solve" before 4.0. Such a single solve skipped Find solves: its time was typed in
// the viewer, over the video (penalty, puzzle and trim are in the details). Untrimmed, the file itself is the clip (nothing to export);
// trimmed, it is exported as a copy like any clip. The solve stops where the clip ends. It never opens in the editor.
// (4.0-C) Import no longer asks: every video is scanned, and a video of one solve is a session with one solve. The old single solves
// keep working as they are, and are listed under Sessions too (switch: Single solves).
// ---------------------------------------------------------------------------------------

/** What the single-solve window gives back. [puzzle] / [category]: null = not asked (editing), else the video's tags ("" = none). */
class SingleSolveInput(
    val timeText: String, val plus2Count: Int, val dnf: Boolean,
    val trimStart: Double?, val trimEnd: Double?,
    val puzzle: String?, val category: String?
)

/** A single solve picked again with Import video: it is already in the Gallery. *Show it* opens it in the viewer. */
@Composable
fun KnownSingleDialog(footage: Footage, onShow: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Already in your Gallery") },
        text = {
            BulletLines(
                listOf(
                    footage.title,
                    "Imported as one solve on ${formatDate(footage.addedMs)}.",
                    "Find it in the Clips tab."
                )
            )
        },
        confirmButton = { Button(onClick = onShow) { Text("Show it") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** One end of the trim: the picture there, or a dark box while it loads. */
@Composable
private fun TrimFrame(uri: Uri, ms: Long, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val picture by produceState<ImageBitmap?>(initialValue = null, uri, ms) {
        delay(200)                       // the handle may still be moving: wait for it to rest
        value = withContext(Dispatchers.IO) {
            try { ThumbCache.frame(context, uri, ms, 240)?.asImageBitmap() } catch (e: Exception) { null }
        }
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val p = picture
            if (p != null) Image(bitmap = p, contentDescription = label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Text("$label ${formatTime(ms)}", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The single-solve window: time, penalty ([askPenalty]), puzzle ([askPuzzle]) and an optional trim. Used to import a single solve
 * and (time and trim only) to edit one from the viewer. [durationMs] is the file's length. Save waits for a readable time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleSolveDialog(
    title: String,
    uri: Uri,
    durationMs: Long,
    startTime: String = "",
    startTrim: Pair<Double?, Double?> = null to null,
    askPenalty: Boolean,
    askPuzzle: Boolean,
    exported: Boolean = false,
    onCancel: () -> Unit,
    onSave: (SingleSolveInput) -> Unit
) {
    val durSec = (durationMs / 1000.0).toFloat().coerceAtLeast(0f)
    var text by remember { mutableStateOf(startTime) }
    var plus2 by remember { mutableStateOf(0) }
    var dnf by remember { mutableStateOf(false) }
    var puzzle by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var tagWindow by remember { mutableStateOf(false) }
    var trim by remember { mutableStateOf(startTrim.first != null || startTrim.second != null) }
    var range by remember {
        mutableStateOf(((startTrim.first ?: 0.0).toFloat().coerceIn(0f, durSec))..((startTrim.second ?: durSec.toDouble()).toFloat().coerceIn(0f, durSec)))
    }
    val timeOk = text.isBlank() || parseTimeSeconds(text) != null      // a time can also be typed later, over the video
    val longEnough = !trim || range.endInclusive - range.start >= MIN_SINGLE_SEC
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Text("Solve time", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ':' } },
                    label = { Text("Seconds, e.g. 12.412") },
                    supportingText = { Text("What the timer showed. Use 1:02.34 for a minute or more.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (askPenalty) {
                    Spacer(Modifier.height(6.dp))
                    Text("Penalty (optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    PenaltyPicker(plus2, dnf) { n, d -> plus2 = n; dnf = d }
                }
                if (askPuzzle) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Puzzle: " + tagLabel(puzzle, category).ifEmpty { "not tagged" },
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { tagWindow = true }) { Text("Choose…") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Trim the video", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            if (trim) "The clip is a copy of this part." else "Off: the whole file is the clip.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = trim, onCheckedChange = { trim = it }, enabled = durSec > MIN_SINGLE_SEC)
                }
                if (trim) {
                    RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..durSec)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrimFrame(uri, (range.start * 1000).toLong(), "Start", Modifier.weight(1f))
                        TrimFrame(uri, (range.endInclusive * 1000).toLong(), "End", Modifier.weight(1f))
                    }
                    Text("End it just after the timer stops: the end is the solve's stop.", style = MaterialTheme.typography.labelSmall)
                    if (!longEnough) Text(highlightCheck("check: the clip must be at least 1 s long"), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("The solve stops at the end of the file.", style = MaterialTheme.typography.labelSmall)
                }
                if (exported) {
                    Spacer(Modifier.height(8.dp))
                    InfoNote(kind = NoteKind.CHECK, title = "Already exported", text = "A new trim needs a new export.")
                }
            }
        },
        confirmButton = {
            Button(enabled = timeOk && longEnough, onClick = {
                val start = if (trim) range.start.toDouble() else null
                val end = if (trim) range.endInclusive.toDouble() else null
                onSave(
                    SingleSolveInput(
                        text.trim(), plus2, dnf, start, end,
                        if (askPuzzle) puzzle else null, if (askPuzzle) category else null
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
    if (tagWindow) {
        TagDialog(
            title = "Which puzzle is this?",
            puzzle = puzzle,
            category = category,
            forClip = false,
            onDismiss = { tagWindow = false },
            onDone = { choice ->
                tagWindow = false
                puzzle = choice.puzzle ?: puzzle
                category = choice.category ?: category
            }
        )
    }
}
