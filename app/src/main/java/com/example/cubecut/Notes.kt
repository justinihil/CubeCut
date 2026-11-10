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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------
// Short messages, drawn the same way everywhere: a small round symbol and one or two short lines on a soft tinted card.
// Rule of thumb for the words: one idea per line, say what to do, put the long explanation behind an InfoButton.
// ---------------------------------------------------------------------------------------

/** What a note is about: INFO = how things work (blue), CHECK = please look at this (amber), PROBLEM = something went wrong (red). */
enum class NoteKind { INFO, CHECK, PROBLEM }

@Composable
private fun noteColor(kind: NoteKind): Color = when (kind) {
    NoteKind.INFO -> MaterialTheme.colorScheme.primary
    NoteKind.CHECK -> AppColors.check
    NoteKind.PROBLEM -> AppColors.danger
}

/** The small round symbol: "i" for info, "!" for check and problem. */
@Composable
fun NoteSymbol(kind: NoteKind, modifier: Modifier = Modifier) {
    val color = noteColor(kind)
    Box(
        modifier
            .size(20.dp)
            .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (kind == NoteKind.INFO) "i" else "!",
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * A short message on a soft tinted card. [title] is optional (one short line, bold); [text] is one or two short lines.
 * [action] / [onAction] add one small text button at the end (e.g. "Learn more").
 */
@Composable
fun InfoNote(
    text: String,
    modifier: Modifier = Modifier,
    kind: NoteKind = NoteKind.INFO,
    title: String? = null,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    val color = noteColor(kind)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        NoteSymbol(kind, Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                Text(
                    action,
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onAction)
                )
            }
        }
    }
}

/** A small round "i" that opens a window with the longer explanation. Keep the screen itself to one line. */
@Composable
fun InfoButton(title: String, lines: List<String>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(28.dp)
            .clickable { open = true },
        contentAlignment = Alignment.Center
    ) {
        NoteSymbol(NoteKind.INFO)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { BulletLines(lines) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Got it") } }
        )
    }
}

/**
 * Short lines under each other, each with a bullet. Used in windows instead of one long paragraph. [style] is the text size
 * (normal body text unless given, e.g. smaller for the licence terms in the About window).
 */
@Composable
fun BulletLines(lines: List<String>, modifier: Modifier = Modifier, style: TextStyle? = null) {
    val textStyle = style ?: MaterialTheme.typography.bodyMedium
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (line in lines) {
            Row {
                Text("\u2022", style = textStyle, modifier = Modifier.width(16.dp))
                Text(line, style = textStyle, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A window body: a short lead-in line, then bullets. */
@Composable
fun LeadAndBullets(lead: String, lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(lead, style = MaterialTheme.typography.bodyMedium)
        BulletLines(lines)
    }
}

/** Picks the colour of a status message from its words: trouble reads red, everything else blue. */
fun kindOfMessage(text: String): NoteKind {
    val t = text.lowercase()
    return if (t.contains("couldn't") || t.contains("can't") || t.contains("failed") || t.contains("problems:") || t.contains("stopped:")) NoteKind.PROBLEM else NoteKind.INFO
}