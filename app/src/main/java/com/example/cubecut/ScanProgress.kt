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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    ScanState.SCANNING -> (if (job.finding) "Looking for the timer" else "Reading the timer") + " ${job.percent}%"
    else -> job.outcome?.text ?: ""
}

/** Does this finished video need the user (to choose the timer, to add solves by hand, or because it stopped)? */
fun needsLook(job: ScanJob): Boolean = job.state == ScanState.ASKS || job.state == ScanState.NO_TIMER || job.state == ScanState.FAILED

/**
 * The panel. Shows nothing when no video was asked for. [onOpen] opens a video in the editor (to add its solves by hand, or to look
 * at what was found); [onChoose] asks which number is the timer.
 */
@Composable
fun ScanProgressPanel(onOpen: (ScanJob) -> Unit, onChoose: (ScanJob) -> Unit, modifier: Modifier = Modifier) {
    val jobs = AnalysisQueue.jobs.toList()
    if (jobs.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val current = jobs.firstOrNull { it.state == ScanState.SCANNING }
    val waiting = jobs.count { it.state == ScanState.WAITING }
    val done = jobs.filter { !it.state.isActive }
    val toLook = done.count { needsLook(it) }

    Card(
        modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (current != null) {
                        "Finding solves: ${current.title} ${current.percent}%" + (if (waiting > 0) " · $waiting waiting" else "")
                    } else if (waiting > 0) {
                        "Finding solves: $waiting waiting"
                    } else {
                        "Find solves: ${done.size} done" + (if (toLook > 0) " · $toLook to look at" else "")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (current == null && toLook > 0) AppColors.check else MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelLarge)
            }
            if (current != null) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { current.percent / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp))
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
                for (job in jobs.asReversed()) ScanJobRow(job, onOpen, onChoose)
                if (done.isNotEmpty()) {
                    TextButton(onClick = { AnalysisQueue.clearFinished() }, modifier = Modifier.align(Alignment.End)) { Text("Clear finished") }
                }
            }
        }
    }
}

@Composable
private fun ScanJobRow(job: ScanJob, onOpen: (ScanJob) -> Unit, onChoose: (ScanJob) -> Unit) {
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
            ScanState.DONE -> TextButton(onClick = { onOpen(job) }) { Text("Open") }
            ScanState.CANCELLED, ScanState.FAILED -> TextButton(onClick = { onOpen(job) }) { Text("Open") }
        }
    }
}
