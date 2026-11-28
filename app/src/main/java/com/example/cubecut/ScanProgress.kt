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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------
// (4.0-C) The Gallery's progress panel for Find solves in the background (AnalysisQueue). Collapsed it is one slim line with a thin
// bar; tapped, it lists every video asked for since the app started: waiting, scanning, or how it ended, with what can be done next.
// Results stay until Clear, so a batch of imports can be checked afterwards: the videos to look at say so.
// ---------------------------------------------------------------------------------------

/** How tall the open list may get before it scrolls: about six videos. */
private const val PANEL_LIST_MAX_DP = 300

/** One video's state in words, for the panel. */
fun scanStateText(job: ScanJob): String = when (job.state) {
    ScanState.WAITING -> "Waiting"
    ScanState.CANCELLED -> "Cancelled. It stays in Sessions, not scanned."
    ScanState.SCANNING -> (if (job.finding) "Looking for the timer" else "Reading the timer") + " ${job.percent}%"
    else -> job.outcome?.text ?: ""
}

/**
 * (4.0) The progress panel's *Copy report* (owner, 2026-11-27): for every video, why it took as long as it did. Plain text, to paste
 * into a message: the video, how it ended, the timer, how long it waited, found, followed and scanned, and what the pictures took.
 */
fun batchReportText(jobs: List<ScanJob>, installedMs: Long = 0L): String {
    val out = ArrayList<String>()
    val ran = jobs.filter { it.startedMs > 0 }
    val total = ran.sumOf { (if (it.endedMs > 0) it.endedMs else System.currentTimeMillis()) - it.startedMs } / 1000.0
    // (4.0) which build: the commit it was made from ("+" = with changes not committed yet) and when it was installed (owner, 2026-11-28)
    val installed = if (installedMs > 0) " (installed " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(installedMs)) + ")" else ""
    out.add("CubeCut ${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " debug" else ""}, build ${BuildConfig.COMMIT}$installed: Find solves report, ${jobs.size} video" +
            (if (jobs.size == 1) "" else "s") + ", ${"%.0f".format(total)} s scanning in all")
    for ((i, j) in jobs.withIndex()) {
        val tm = j.timing
        out.add("")
        val fps = if (tm != null && tm.frameSec > 0) ", ${"%.0f".format(1 / tm.frameSec)} fps" else ""
        val size = if (tm != null && tm.size.isNotEmpty()) ", ${tm.size}" else ""
        out.add("${i + 1}. ${j.title} (${formatTime(j.durationMs)}$fps$size)")
        out.add("   " + scanStateText(j))
        if (j.startedMs == 0L) continue
        val waited = (j.startedMs - j.queuedMs) / 1000.0
        val took = ((if (j.endedMs > 0) j.endedMs else System.currentTimeMillis()) - j.startedMs) / 1000.0
        val perMinute = if (j.durationMs > 0) took / (j.durationMs / 60000.0) else 0.0
        out.add("   Took ${"%.1f".format(took)} s (${"%.1f".format(perMinute)} s per minute of video), after waiting ${"%.0f".format(waited)} s")
        if (tm == null) continue
        out.add("   Timer: ${tm.timer}")
        val waitSec = if (tm.enteredMs > 0) (tm.enteredMs - j.startedMs) / 1000.0 else 0.0
        if (waitSec >= 0.05) out.add("   Waiting to start ${"%.1f".format(waitSec)} s")
        out.add("   Getting ready ${"%.1f".format(tm.readySec)} s")
        out.add("   Finding the timer ${"%.1f".format(tm.findSec)} s" + (if (tm.segmentsSec > 0) " (seven segments ${"%.1f".format(tm.segmentsSec)} s)" else "") +
                (if (tm.findPictures.isNotEmpty()) ": pictures ${tm.findPictures}" else ""))
        if (tm.lastCheckSec >= 0.05) out.add("   Last check (seven segments by final times) ${"%.1f".format(tm.lastCheckSec)} s")
        if (tm.followSec > 0) out.add("   Following it ${"%.1f".format(tm.followSec)} s" + (if (tm.followPictures.isNotEmpty()) ": pictures ${tm.followPictures}" else ""))
        if (tm.scanSec > 0) out.add("   Scanning ${"%.1f".format(tm.scanSec)} s" + (if (tm.scanPictures.isNotEmpty()) ": pictures ${tm.scanPictures}" else ""))
        if (tm.closeSec >= 0.05) out.add("   Closing the video ${"%.1f".format(tm.closeSec)} s")
        if (tm.backSec >= 0.05) out.add("   Back to the queue ${"%.1f".format(tm.backSec)} s")
        if (tm.saveSec >= 0.05) out.add("   Saving ${"%.1f".format(tm.saveSec)} s")
        // (4.0) whatever no line above holds, so a gap can't hide (owner, 2026-11-28)
        val lines = waitSec + tm.readySec + tm.findSec + tm.lastCheckSec + tm.followSec + tm.scanSec + tm.closeSec + tm.backSec + tm.saveSec
        if (took - lines >= 0.5) out.add("   In none of these lines ${"%.1f".format(took - lines)} s")
        heatText(tm)?.let { out.add("   $it") }
        out.add("   ${tm.pictures} pictures; source: ${tm.source}")
    }
    return out.joinToString("\n")
}

/** (4.0) "Phone: battery 31.5 → 38.2 °C; heat none, at worst moderate (slowing down)", or null when nothing is known. */
private fun heatText(tm: ScanTiming): String? {
    val parts = ArrayList<String>()
    if (!tm.batteryStartC.isNaN() && !tm.batteryEndC.isNaN()) parts.add("battery ${"%.1f".format(tm.batteryStartC)} → ${"%.1f".format(tm.batteryEndC)} °C")
    if (tm.heatStart >= 0) parts.add("heat ${heatName(tm.heatStart)}" + if (tm.heatWorst > tm.heatStart) ", at worst ${heatName(tm.heatWorst)}" else "")
    return if (parts.isEmpty()) null else "Phone: " + parts.joinToString("; ")
}

/** Android's heat status in words; from "moderate" on the phone slows itself down to cool off. */
private fun heatName(status: Int): String = when (status) {
    0 -> "none"
    1 -> "light"
    2 -> "moderate (slowing down)"
    3 -> "severe (slowing down a lot)"
    4 -> "critical"
    5 -> "emergency"
    6 -> "shutting down"
    else -> "unknown"
}

/** Does this finished video need the user (to choose the timer, to add solves by hand, or because it stopped)? */
fun needsLook(job: ScanJob): Boolean = job.state == ScanState.ASKS || job.state == ScanState.NO_TIMER || job.state == ScanState.FAILED ||
        (job.state == ScanState.DONE && (job.outcome?.maybeMissed ?: 0) > 0)

/** (4.0-G) "1 place to check", "3 places to check". */
fun placesToCheckText(n: Int): String = if (n == 1) "1 place to check" else "$n places to check"

/**
 * (4.0-G) The places in one video where a solve may be missing, after a scan in the background (owner, 2026-11-26: never a popup on its
 * own; it opens from the card or the progress list). *Go there* opens the editor at that place; *Nothing here* drops the hint.
 */
@Composable
fun MissedHintsDialog(title: String, hints: List<MissedHint>, onGoThere: (MissedHint) -> Unit, onNothing: (MissedHint) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (hints.isEmpty()) "Nothing left to check." else "A solve may be missing here. Go there to add it, or drop the hint.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                for (h in hints) MissedHintCard(h, enabled = true, onGoThere = { onGoThere(h) }, onNothing = { onNothing(h) })
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

/**
 * The panel. Shows nothing when no video was asked for. [onOpen] opens a video in the editor (to add its solves by hand, or to look
 * at what was found); [onChoose] asks which number is the timer.
 */
@Composable
fun ScanProgressPanel(
    onOpen: (ScanJob) -> Unit, onChoose: (ScanJob) -> Unit, onHints: (ScanJob) -> Unit = { }, onAgain: (ScanJob) -> Unit = { },
    modifier: Modifier = Modifier
) {
    val jobs = AnalysisQueue.jobs.toList()
    if (jobs.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    var askCancelAll by remember { mutableStateOf(false) }
    var copied by remember(jobs.size, AnalysisQueue.finished) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val active = jobs.count { it.state.isActive }
    val done = jobs.filter { !it.state.isActive }
    val toLook = done.count { needsLook(it) }
    val batch = AnalysisQueue.batchProgress()      // (4.0) the bar shows the whole batch, not one video (owner, 2026-11-26)

    Card(
        modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (active > 0) "Finding solves: ${AnalysisQueue.batchLabel(batch)} · ${batch.percent}%"
                    else "Find solves: ${done.size} done" + (if (toLook > 0) " · $toLook to look at" else ""),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active == 0 && toLook > 0) AppColors.check else MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelLarge)
            }
            if (active > 0) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { batch.fraction }, modifier = Modifier.fillMaxWidth().height(3.dp))
            }
        }
        if (open) {
            Column(
                Modifier
                    .heightIn(max = PANEL_LIST_MAX_DP.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 4.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (job in jobs.asReversed()) ScanJobRow(job, onOpen, onChoose, onHints, onAgain)
                Row(Modifier.align(Alignment.End)) {
                    // (4.0) stop the whole batch; the videos stay, each can be scanned again
                    if (active > 1) TextButton(onClick = { askCancelAll = true }) { Text("Cancel all") }
                    // (4.0) for the owner's test rounds: why some videos took longer (owner, 2026-11-27)
                    if (done.isNotEmpty()) TextButton(onClick = {
                        val installed = try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (e: Exception) { 0L }
                        val text = batchReportText(jobs, installed)
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CubeCut Find solves report", text))
                        copied = true
                    }) { Text(if (copied) "Copied" else "Copy report") }
                    if (done.isNotEmpty()) TextButton(onClick = { AnalysisQueue.clearFinished() }) { Text("Clear finished") }
                }
            }
        }
    }
    if (askCancelAll) {
        AlertDialog(
            onDismissRequest = { askCancelAll = false },
            title = { Text("Cancel $active scans?") },
            text = { Text("The videos stay in Sessions, not scanned. Scan them again any time.") },
            confirmButton = { TextButton(onClick = { askCancelAll = false; AnalysisQueue.cancelAll() }) { Text("Cancel all") } },
            dismissButton = { TextButton(onClick = { askCancelAll = false }) { Text("Keep going") } }
        )
    }
}

@Composable
private fun ScanJobRow(job: ScanJob, onOpen: (ScanJob) -> Unit, onChoose: (ScanJob) -> Unit, onHints: (ScanJob) -> Unit, onAgain: (ScanJob) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                scanStateText(job),
                style = MaterialTheme.typography.bodySmall,
                color = if (needsLook(job)) AppColors.check else MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2
            )
        }
        Spacer(Modifier.width(4.dp))
        when (job.state) {
            ScanState.WAITING, ScanState.SCANNING -> TextButton(onClick = { AnalysisQueue.cancel(job.footageId) }) { Text("Cancel") }
            ScanState.ASKS -> TextButton(onClick = { onChoose(job) }) { Text("Choose") }
            ScanState.NO_TIMER -> TextButton(onClick = { onOpen(job) }) { Text("Add by hand") }
            // (4.0-G) places where a solve may be missing are shown on request, never on their own
            ScanState.DONE -> {
                val missed = job.outcome?.maybeMissed ?: 0
                if (missed > 0) TextButton(onClick = { onHints(job) }) { Text("Check $missed") } else TextButton(onClick = { onOpen(job) }) { Text("Open") }
            }
            // (4.0) a cancelled or failed video stays in Sessions: scan it again, or open it
            ScanState.CANCELLED, ScanState.FAILED -> Row {
                TextButton(onClick = { onAgain(job) }) { Text("Scan again") }
                TextButton(onClick = { onOpen(job) }) { Text("Open") }
            }
        }
    }
}
