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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------
// (3.2-G) Backup and restore (ADR-0015). A backup is one small JSON file the user saves wherever they choose (CreateDocument): every
// video's record (identified by file name, size and length, never by its old link), every cut and every live export record,
// including all the v3.2 fields. No video is in it and nothing is sent anywhere (no INTERNET permission).
// Restore merges: a video this phone already has keeps what it has (the phone wins); the others come back without a link to their
// file, and Reconnect videos / Reconnect exported clips give them one by picking many files at once.
// ---------------------------------------------------------------------------------------

/** The backup file's own version. Bump it when a field changes meaning; a reader accepts every version up to its own. */
private const val BACKUP_FORMAT = 1
private const val BACKUP_APP = "CubeCut"

/** What a restore did. */
class RestoreResult(val added: Int, val solves: Int, val alreadyHere: Int, val problems: List<String>)

/** A backup file that can't be read as one. */
class BackupFormatException(message: String) : Exception(message)

object Backup {

    private fun JSONObject.doubleOrNull(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
    private fun Double?.orNull(): Any = this ?: JSONObject.NULL

    /** "CubeCut backup 2026-11-23.json": the name the save window suggests. */
    fun suggestedName(): String = "CubeCut backup " + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".json"

    private fun cutJson(c: CutItem) = JSONObject()
        .put("stop_sec", c.stopSec).put("time_text", c.timeText).put("included", c.included).put("note", c.note)
        .put("include_scramble", c.includeScramble).put("user_note", c.userNote).put("user_edited", c.edited).put("reviewed", c.reviewed)
        .put("puzzle", c.puzzle).put("category", c.category).put("plus2_count", c.plus2Count).put("dnf", c.dnf)
        .put("before_pad", c.beforePad.orNull()).put("after_pad", c.afterPad.orNull()).put("shared_on", encodeSharedOn(c.sharedOn))
        .put("seen_ms", c.seenMs)

    private fun cutOf(o: JSONObject) = CutItem(
        stopSec = o.getDouble("stop_sec"), timeText = o.optString("time_text"), included = o.optBoolean("included", true),
        note = o.optString("note"), includeScramble = o.optBoolean("include_scramble", true), userNote = o.optString("user_note"),
        edited = o.optBoolean("user_edited"), reviewed = o.optBoolean("reviewed"),
        puzzle = o.optString("puzzle"), category = o.optString("category"), plus2Count = o.optInt("plus2_count"), dnf = o.optBoolean("dnf"),
        beforePad = o.doubleOrNull("before_pad"), afterPad = o.doubleOrNull("after_pad"), sharedOn = decodeSharedOn(o.optString("shared_on")),
        // (4.0-D) a backup from before 4.0 has no seen_ms: its clips count as seen, as on the upgrade
        seenMs = if (o.has("seen_ms")) o.optLong("seen_ms") else System.currentTimeMillis()
    )

    private fun exportJson(e: ExportedClip) = JSONObject()
        .put("stop_sec", e.stopSec).put("time_text", e.timeText).put("file_name", e.fileName)
        .put("offset_sec", e.offsetSec).put("exported_ms", e.exportedMs)

    private fun exportOf(o: JSONObject) = ExportedClip(
        0L, 0L, o.getDouble("stop_sec"), o.optString("time_text"), "", o.getString("file_name"), o.optDouble("offset_sec", 0.0), o.optLong("exported_ms")
    )

    private fun hintJson(h: MissedHint) = JSONObject()
        .put("from_sec", h.fromSec).put("to_sec", h.toSec).put("seek_sec", h.seekSec).put("reason", h.reason.key).put("dismissed", h.dismissed)

    /** (3.3-C) Null for a reason this version doesn't know. Backups before 3.3 have no hints. */
    private fun hintOf(o: JSONObject): MissedHint? = HintReason.entries.firstOrNull { it.key == o.optString("reason") }?.let {
        MissedHint(o.getDouble("from_sec"), o.getDouble("to_sec"), o.getDouble("seek_sec"), it, o.optBoolean("dismissed"))
    }

    /** Writes the backup of everything saved into [out]. Returns (videos, solves) written. Run it on Dispatchers.IO. */
    fun write(context: Context, out: Uri): Pair<Int, Int> {
        val videos = JSONArray()
        var solves = 0
        for (f in SessionStore.listFootage(context)) {
            val cuts = SessionStore.loadCuts(context, f.id)
            solves += cuts.size
            val exports = SessionStore.loadExports(context, f.id).filter { it.trashedMs == 0L }
            videos.put(
                JSONObject()
                    .put("display_name", f.displayName).put("size_bytes", f.sizeBytes).put("duration_ms", f.durationMs)
                    .put("title", f.title).put("date_taken_ms", f.dateTakenMs).put("added_ms", f.addedMs).put("last_scan_ms", f.lastScanMs)
                    .put("before_pad", f.beforePad.toDouble()).put("after_pad", f.afterPad.toDouble()).put("scramble_all", f.scrambleAll)
                    .put("timer_frame_ms", f.timerFrameMs).put("timer_corners", f.timerCorners).put("timer_typed", f.timerTyped)
                    .put("puzzle", f.puzzle).put("category", f.category).put("kind", f.kind)
                    .put("trim_start_sec", f.trimStartSec.orNull()).put("trim_end_sec", f.trimEndSec.orNull())
                    .put("cuts", JSONArray().apply { cuts.forEach { put(cutJson(it)) } })
                    .put("exports", JSONArray().apply { exports.forEach { put(exportJson(it)) } })
                    .put("hints", JSONArray().apply { SessionStore.loadHints(context, f.id).forEach { put(hintJson(it)) } })
            )
        }
        val root = JSONObject()
            .put("app", BACKUP_APP).put("format", BACKUP_FORMAT).put("made_ms", System.currentTimeMillis())
            .put("videos", videos)
        val stream = context.contentResolver.openOutputStream(out, "w") ?: throw java.io.IOException("couldn't open the file to write")
        stream.use { it.write(root.toString(1).toByteArray(Charsets.UTF_8)) }
        return Pair(videos.length(), solves)
    }

    /** Reads a backup from [input] and merges it into the saved data (the phone wins). Run it on Dispatchers.IO. */
    fun restore(context: Context, input: Uri): RestoreResult {
        val text = context.contentResolver.openInputStream(input)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw java.io.IOException("couldn't open the file")
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackupFormatException("This isn't a CubeCut backup file.")
        }
        if (root.optString("app") != BACKUP_APP || !root.has("videos")) throw BackupFormatException("This isn't a CubeCut backup file.")
        if (root.optInt("format") > BACKUP_FORMAT) throw BackupFormatException("This backup was made by a newer CubeCut. Update the app first.")
        val videos = root.getJSONArray("videos")
        var added = 0
        var solves = 0
        var already = 0
        val problems = ArrayList<String>()
        for (i in 0 until videos.length()) {
            val o = videos.getJSONObject(i)
            val name = o.optString("title", o.optString("display_name", "video ${i + 1}"))
            try {
                val f = Footage(
                    id = 0L, uri = "", displayName = o.getString("display_name"), sizeBytes = o.optLong("size_bytes", -1L),
                    durationMs = o.getLong("duration_ms"), title = o.optString("title", o.getString("display_name")),
                    dateTakenMs = o.optLong("date_taken_ms"), addedMs = o.optLong("added_ms", System.currentTimeMillis()),
                    lastScanMs = o.optLong("last_scan_ms"), beforePad = o.optDouble("before_pad", 2.5).toFloat(),
                    afterPad = o.optDouble("after_pad", 2.5).toFloat(), scrambleAll = o.optBoolean("scramble_all", true), cutCount = 0,
                    timerFrameMs = o.optLong("timer_frame_ms", -1L), timerCorners = o.optString("timer_corners"),
                    timerTyped = o.optString("timer_typed"), trashedMs = 0L, puzzle = o.optString("puzzle"), category = o.optString("category"),
                    kind = o.optString("kind", KIND_SESSION).ifEmpty { KIND_SESSION },
                    trimStartSec = o.doubleOrNull("trim_start_sec"), trimEndSec = o.doubleOrNull("trim_end_sec")
                )
                val cutsJson = o.optJSONArray("cuts") ?: JSONArray()
                val cuts = (0 until cutsJson.length()).map { cutOf(cutsJson.getJSONObject(it)) }
                val exportsJson = o.optJSONArray("exports") ?: JSONArray()
                val exports = (0 until exportsJson.length()).map { exportOf(exportsJson.getJSONObject(it)) }
                val hintsJson = o.optJSONArray("hints") ?: JSONArray()
                val hints = (0 until hintsJson.length()).mapNotNull { hintOf(hintsJson.getJSONObject(it)) }
                if (SessionStore.restoreVideo(context, f, cuts, exports, hints)) {
                    added++
                    solves += cuts.size
                } else {
                    already++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems.add("$name: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return RestoreResult(added, solves, already, problems)
    }
}

/**
 * The *Backup and restore* window (Gallery ⋮ menu). [onMessage] gets one short line to show in the Gallery; [onChanged] is called
 * after anything that changes the saved data, so the Gallery loads again.
 */
@Composable
fun BackupDialog(onMessage: (String) -> Unit, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }                          // what is running ("" = nothing)
    var restored by remember { mutableStateOf<RestoreResult?>(null) }    // the summary after a restore, with the next steps

    fun run(what: String, job: suspend () -> String) {
        if (busy.isNotEmpty()) return
        busy = what
        scope.launch {
            val text = try {
                job()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupFormatException) {
                e.message ?: "This isn't a CubeCut backup file."
            } catch (e: Exception) {
                "$what failed: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = ""
            if (text.isNotEmpty()) onMessage(text)
            onChanged()
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run("Backup") {
            val (videos, solves) = withContext(Dispatchers.IO) { Backup.write(context, uri) }
            "Backed up $videos videos and $solves solves. Keep the file somewhere safe, off this phone too."
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run("Restore") {
            val r = withContext(Dispatchers.IO) { Backup.restore(context, uri) }
            restored = r
            ""
        }
    }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run("Reconnect") {
            val (done, missed) = withContext(Dispatchers.IO) {
                uris.forEach { SessionStore.keepAccess(context, it) }
                SessionStore.reconnectVideos(context, uris)
            }
            "Reconnected $done videos." + (if (missed > 0) " $missed didn't match a saved video." else "")
        }
    }
    val pickExports = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run("Reconnect") {
            val (done, missed) = withContext(Dispatchers.IO) {
                uris.forEach { SessionStore.keepAccess(context, it) }
                SessionStore.reconnectExports(context, uris)
            }
            "Reconnected $done exported clips." + (if (missed > 0) " $missed didn't match an export by name." else "")
        }
    }

    val r = restored
    if (r != null) {
        AlertDialog(
            onDismissRequest = { restored = null },
            title = { Text("Restored") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BulletLines(
                        listOfNotNull(
                            "Added ${r.added} videos with ${r.solves} solves.",
                            if (r.alreadyHere > 0) "${r.alreadyHere} were already here and kept as they are." else null,
                            if (r.problems.isNotEmpty()) "Couldn't read ${r.problems.size}: " + r.problems.take(2).joinToString("; ") else null
                        )
                    )
                    if (r.added > 0) {
                        InfoNote("Next: reconnect the videos, then the exported clips. Pick many at once.")
                        Button(onClick = { pickVideos.launch(arrayOf("video/*")) }, enabled = busy.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("Reconnect videos")
                        }
                        OutlinedButton(onClick = { pickExports.launch(arrayOf("video/*")) }, enabled = busy.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("Reconnect exported clips")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { restored = null }) { Text(if (r.added > 0) "Later" else "OK") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (busy.isEmpty()) onDismiss() },
        title = { Text("Backup and restore") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BulletLines(
                    listOf(
                        "A backup is one small file: your solves, tags, notes and exports. No videos.",
                        "You choose where it goes. Nothing is sent anywhere.",
                        "Restore adds what this phone doesn't have yet. Nothing here is replaced."
                    )
                )
                Button(onClick = { saveFile.launch(Backup.suggestedName()) }, enabled = busy.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Back up now")
                }
                OutlinedButton(onClick = { openFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, enabled = busy.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from a backup")
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Text("After a restore or a reinstall", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { pickVideos.launch(arrayOf("video/*")) }, enabled = busy.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Reconnect videos")
                }
                OutlinedButton(onClick = { pickExports.launch(arrayOf("video/*")) }, enabled = busy.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Reconnect exported clips")
                }
                Text(
                    "Pick many files at once. Videos match by name, size and length; exported clips by file name (in Movies/CubeCut).",
                    style = MaterialTheme.typography.bodySmall
                )
                if (busy.isNotEmpty()) Text("$busy…", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { Button(onClick = onDismiss, enabled = busy.isEmpty()) { Text("Close") } }
    )
}
