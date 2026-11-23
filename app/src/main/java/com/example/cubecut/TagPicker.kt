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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------
// (3.2-B) The window that sets a puzzle and type tag. Used for a whole video (the editor's Setup box, the import question, the
// Gallery's Footage menu) and for clips (a Gallery selection, the viewer sheet). The stored values are explained in Tags.kt.
// ---------------------------------------------------------------------------------------

/** What a tag window chose, as stored values (see Tags.kt). null = leave each clip as it is (only when the clips were mixed). */
class TagChoice(val puzzle: String?, val category: String?)

// the buttons that are not a name (a name is stored as it is)
private const val PICK_FOLLOW = "\u0001follow"
private const val PICK_NONE = "\u0001none"
private const val PICK_OTHER = "\u0001other"

/** One half of the window (Puzzle or Type): the chosen button (null = nothing yet, the clips are mixed) and the typed "Other" name. */
private class TagSection(start: String?, val known: List<String>, val forClip: Boolean) {
    var pick by mutableStateOf(
        when {
            start == null -> null
            forClip && start == "" -> PICK_FOLLOW
            start == "" || start == NO_TAG -> PICK_NONE
            start in known -> start
            else -> PICK_OTHER
        }
    )
    var other by mutableStateOf(if (start != null && start != NO_TAG && start != "" && start !in known) start else "")

    /** True if Other is chosen but no name was typed: Done waits for a name. */
    val missingName: Boolean get() = pick == PICK_OTHER && cleanTag(other, known).isEmpty()

    /** The stored value of the choice (null = leave as it is). */
    fun result(): String? = when (val p = pick) {
        null -> null
        PICK_FOLLOW -> ""
        PICK_NONE -> if (forClip) NO_TAG else ""
        PICK_OTHER -> cleanTag(other, known)
        else -> p
    }
}

/**
 * The tag window. [puzzle] / [category]: the stored values to start from (null = the clips differ, nothing is chosen).
 * [forClip]: the window sets clips, so it also offers "As the video" (follow) and its "Not tagged" overrides the video's tag.
 * [followPuzzle] / [followCategory]: the video's tags, shown on the "As the video" buttons (null = clips from several videos).
 * [intro]: one short line at the top. [dismissLabel]: "Cancel", or "Decide later" for the import question.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagDialog(
    title: String,
    puzzle: String?,
    category: String?,
    forClip: Boolean,
    followPuzzle: String? = "",
    followCategory: String? = "",
    intro: String? = null,
    dismissLabel: String = "Cancel",
    onDismiss: () -> Unit,
    onDone: (TagChoice) -> Unit
) {
    val puzzles = remember { TagSection(puzzle, PUZZLES, forClip) }
    val types = remember { TagSection(category, CATEGORIES, forClip) }

    @Composable
    fun Section(label: String, s: TagSection, follow: String?) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (s.pick == null) {
            Text("Mixed. Pick one to set them all, or leave it.", style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (forClip) {
                val shown = if (follow == null) "As each video" else "As the video (${follow.ifEmpty { "not tagged" }})"
                FilterChip(selected = s.pick == PICK_FOLLOW, onClick = { s.pick = PICK_FOLLOW }, label = { Text(shown) })
            }
            for (name in s.known) {
                FilterChip(selected = s.pick == name, onClick = { s.pick = name }, label = { Text(name) })
            }
            FilterChip(selected = s.pick == PICK_OTHER, onClick = { s.pick = PICK_OTHER }, label = { Text("Other") })
            FilterChip(selected = s.pick == PICK_NONE, onClick = { s.pick = PICK_NONE }, label = { Text("Not tagged") })
        }
        if (s.pick == PICK_OTHER) {
            OutlinedTextField(
                value = s.other,
                onValueChange = { s.other = it },
                singleLine = true,
                placeholder = { Text("Type its name") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (intro != null) {
                    InfoNote(intro)
                    Spacer(Modifier.height(10.dp))
                }
                Section("Puzzle", puzzles, followPuzzle)
                Spacer(Modifier.height(12.dp))
                Section("Type", types, followCategory)
            }
        },
        confirmButton = {
            Button(
                onClick = { onDone(TagChoice(puzzles.result(), types.result())) },
                enabled = !puzzles.missingName && !types.missingName
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}
