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
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------
// "Imported before": importing a video CubeCut already knows asks first, instead of silently reopening its saved session.
// The Gallery's Import video uses this when ONE video is picked (4.0-C: several skip the ones seen before). Opening a session from
// the Gallery does not ask.
// ---------------------------------------------------------------------------------------

/**
 * The saved record of [uri] if CubeCut has imported this video before (same rules as `SessionStore.openOrCreate`), else null.
 * Reading the video or the database failing also gives null: the import then goes ahead as before, and `openOrCreate` still
 * reopens the saved session if there is one.
 */
suspend fun findImportedBefore(context: Context, uri: Uri): Footage? = withContext(Dispatchers.IO) {
    try {
        SessionStore.findKnown(context, uri, readDurationMs(context, uri))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** Asks before reopening [footage]: what is saved for it, then *Open saved* or *Cancel*. [uri] is the newly picked link. */
@Composable
fun ReimportDialog(footage: Footage, uri: Uri, onOpen: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Imported before") },
        text = {
            Column {
                Thumb(
                    "f_${footage.id}", uri, footage.durationMs / 10,
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.height(10.dp))
                BulletLines(
                    listOf(
                        footage.title,
                        when (footage.cutCount) {
                            0 -> "No solves saved yet."
                            1 -> "1 solve saved."
                            else -> "${footage.cutCount} solves saved."
                        },
                        if (footage.lastScanMs > 0) "Last scanned ${formatDate(footage.lastScanMs)}." else "Never scanned.",
                        "Open saved brings back its solves and timer setup."
                    )
                )
            }
        },
        confirmButton = { Button(onClick = onOpen) { Text("Open saved") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
