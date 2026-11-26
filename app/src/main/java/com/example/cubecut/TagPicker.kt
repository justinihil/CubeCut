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
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------
// (3.2-B) The window that sets a puzzle and type tag. Used for a whole video (the editor's Setup box, the import question, the
// Gallery's Sessions menu) and for clips (a Gallery selection, the viewer sheet). The stored values are explained in Tags.kt.
// (4.0-F) It shows the kinds it is given (TagKind): also the cube model (your own list) and, for videos, the place.
// ---------------------------------------------------------------------------------------

/**
 * What a tag window chose, as stored values (see Tags.kt). null = leave each clip or video as it is (when they were mixed, or the
 * window didn't show that kind).
 */
class TagChoice(val puzzle: String?, val category: String?, val cube: String? = null, val place: String? = null)

// the buttons that are not a name (a name is stored as it is)
private const val PICK_FOLLOW = "\u0001follow"
private const val PICK_NONE = "\u0001none"
private const val PICK_OTHER = "\u0001other"

/**
 * One half of the window (Puzzle or Type): the chosen button (null = nothing yet, the clips are mixed) and the typed "Other" name.
 * [follow] is the video's tag for clips of one video: then "as the video" has no button of its own, the video's tag is simply
 * chosen, and choosing it stores "" (normalizeClipTag). For clips of several videos ([follow] null) "As each video" stays a button.
 */
private class TagSection(given: String?, val known: List<String>, val forClip: Boolean, val follow: String?, val typed: Boolean = true) {
    val oneVideo = forClip && follow != null
    private val start = if (oneVideo && given != null) resolveTag(given, follow!!) else given
    var pick by mutableStateOf(
        when {
            start == null -> null
            forClip && start == "" && !oneVideo -> PICK_FOLLOW
            start == "" || start == NO_TAG -> PICK_NONE
            start in known -> start
            !typed -> null
            else -> PICK_OTHER
        }
    )
    var other by mutableStateOf(if (start != null && start != NO_TAG && start != "" && start !in known) start else "")

    /** True if Other is chosen but no name was typed: Done waits for a name. */
    val missingName: Boolean get() = pick == PICK_OTHER && cleanTag(other, known).isEmpty()

    /** The stored value of the choice (null = leave as it is). */
    fun result(): String? {
        val v = when (val p = pick) {
            null -> null
            PICK_FOLLOW -> ""
            PICK_NONE -> if (forClip) NO_TAG else ""
            PICK_OTHER -> cleanTag(other, known)
            else -> p
        }
        return if (v != null && oneVideo) normalizeClipTag(v, follow!!) else v
    }
}

/**
 * One button of the tag window. The chosen one is solid blue (the theme's primary, in light and dark mode) with a tick, so it stands
 * out clearly: Material's default chosen colour is a grey that is hard to tell from the window behind it.
 */
@Composable
private fun TagChip(label: String, selected: Boolean, logo: String = "", onClick: () -> Unit) {
    val blue = MaterialTheme.colorScheme.primary
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        // a puzzle button shows its logo (the solid blue already marks the chosen one); other buttons get a tick when chosen
        leadingIcon = if (logo.isNotEmpty()) ({
            PuzzleIcon(logo, if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary, 18.dp)
        }) else if (selected) ({ Text("✓", fontWeight = FontWeight.Bold) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = blue,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = blue
        )
    )
}

/**
 * The tag window. [puzzle] / [category]: the stored values to start from (null = the clips differ, nothing is chosen).
 * [forClip]: the window sets clips: its "Not tagged" overrides the video's tag, and clips of several videos get "As each video".
 * [followPuzzle] / [followCategory]: the clips' video's tags (null = clips from several videos). The video's tag is shown as chosen
 * for a clip that follows it, and choosing it makes the clip follow it again.
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
    cube: String? = null,
    place: String? = null,
    followCube: String? = "",
    kinds: List<TagKind> = listOf(TagKind.PUZZLE, TagKind.TYPE),
    knownCubes: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onDone: (TagChoice) -> Unit
) {
    // (4.0-F) one section per kind shown; a clip window never shows the place (it is the video's only)
    val shown = kinds.filter { !forClip || it.clipMayDiffer }
    val sections = remember {
        mapOf(
            TagKind.PUZZLE to TagSection(puzzle, PUZZLES, forClip, followPuzzle),
            TagKind.TYPE to TagSection(category, CATEGORIES, forClip, followCategory),
            TagKind.CUBE to TagSection(cube, knownCubes, forClip, followCube),
            TagKind.PLACE to TagSection(place, PLACES, false, "", typed = false)
        )
    }

    @Composable
    fun Section(kind: TagKind, s: TagSection, follow: String?) {
        Text(kind.label, style = MaterialTheme.typography.labelLarge)
        if (s.pick == null) {
            Text("Mixed. Pick one to set them all, or leave it.", style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // clips of several videos can go back to each one's own video tag; for one video that is just its tag's button
            if (forClip && follow == null) TagChip("As each video", s.pick == PICK_FOLLOW) { s.pick = PICK_FOLLOW }
            for (name in s.known) {
                TagChip(name, s.pick == name, logo = if (kind == TagKind.PUZZLE) name else "") { s.pick = name }
            }
            if (s.typed) TagChip(if (kind == TagKind.CUBE) "New cube" else "Other", s.pick == PICK_OTHER) { s.pick = PICK_OTHER }
            TagChip("Not tagged", s.pick == PICK_NONE) { s.pick = PICK_NONE }
        }
        if (s.pick == PICK_OTHER) {
            OutlinedTextField(
                value = s.other,
                onValueChange = { s.other = it },
                singleLine = true,
                placeholder = { Text(if (kind == TagKind.CUBE) "Its name, e.g. GAN 12" else "Type its name") },
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
                for ((i, kind) in shown.withIndex()) {
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    val follow = when (kind) {
                        TagKind.PUZZLE -> followPuzzle
                        TagKind.TYPE -> followCategory
                        TagKind.CUBE -> followCube
                        TagKind.PLACE -> ""
                    }
                    Section(kind, sections.getValue(kind), follow)
                }
            }
        },
        confirmButton = {
            fun value(k: TagKind) = if (k in shown) sections.getValue(k).result() else null
            Button(
                onClick = { onDone(TagChoice(value(TagKind.PUZZLE), value(TagKind.TYPE), value(TagKind.CUBE), value(TagKind.PLACE))) },
                enabled = shown.none { sections.getValue(it).missingName }
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}
