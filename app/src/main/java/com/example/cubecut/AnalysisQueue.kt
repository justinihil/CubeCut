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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------
// (4.0-B) Find solves for the whole app (ADR-0018). Videos are scanned one after another, in the background, whatever screen is open:
// the work runs here, not in the editor, and the foreground service (AnalysisService) keeps Android from stopping it while CubeCut
// is in the background. A scan saves its result itself (the merge with what the user changed, the hints, the timer's place), so
// nobody has to stay open for it.
//
// The lock is per video: while a video is waiting or being scanned, only the actions on THAT video that would disturb the scan are
// off (the editor's list, Forget, Delete video, Reconnect). Everything else in the app works.
//
// State lives in Compose state, so screens redraw by themselves: [jobs] for the list, [finished] counts finished scans (a screen
// that shows saved data reloads when it changes).
// ---------------------------------------------------------------------------------------

/** Where a video is in the queue. */
enum class ScanState {
    WAITING, SCANNING,
    DONE,           // solves found (or none, with the timer found)
    NO_TIMER,       // the timer wasn't found: the solves are added by hand
    ASKS,           // two numbers run: the user has to say which one is the timer
    CANCELLED, FAILED;

    val isActive: Boolean get() = this == WAITING || this == SCANNING
}

/** What a finished scan found, for the editor's status line, the progress list and the notification. */
class ScanOutcome(
    val text: String,                   // "Done in 41s: 12 solves, 1 to check" or why it stopped
    val solves: Int = 0,
    val toCheck: Int = 0,
    val maybeMissed: Int = 0,
    val report: ScanResult? = null,     // for the editor's warnings (readable share, none found)
    val question: TimerQuestion? = null // ASKS: what to ask
)

/** One video in the queue. Its fields are Compose state: read them in a composable and it redraws. */
class ScanJob(val footageId: Long, val uri: Uri, val durationMs: Long, title: String, val near: Area?, val run: Int) {
    var title by mutableStateOf(title)
    var state by mutableStateOf(ScanState.WAITING)
    var percent by mutableIntStateOf(0)
    var finding by mutableStateOf(false)        // true while it looks for the timer, false while it reads it along the video
    var outcome by mutableStateOf<ScanOutcome?>(null)

    /** (4.0) For the progress panel's Copy report: when it was asked for, began and ended (ms), and where its time went. */
    val queuedMs = System.currentTimeMillis()
    var startedMs = 0L
    var endedMs = 0L
    var timing: ScanTiming? = null

    @Volatile
    var cancelAsked = false
}

object AnalysisQueue {
    /** Every video asked for since the app started, oldest first; finished ones stay until [clearFinished]. */
    val jobs = mutableStateListOf<ScanJob>()

    /** Goes up by one each time a scan ends, however it ended. */
    var finished by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null                 // for the marks in the database (the application, never a screen)
    private var running = false
    private var runs = 0
    private var batchFrom = 1                               // (4.0) the first job of the batch now running (see [batch])
    private var lastTimer: TimerHint? = null                // (4.0) where the timer of the video scanned last was (TimerHint)
    private var resumed = false                             // (4.0) [resume] ran in this process

    /**
     * (4.0) The videos of the batch now running, or of the last one: every job asked for since the queue last started from empty.
     * The bar and the notification show this batch as a whole (owner, 2026-11-26), not one video.
     */
    val batch: List<ScanJob> get() = jobs.filter { it.run >= batchFrom }

    /** (4.0) How far the batch is: videos done (also cancelled or failed), videos in it, and the share done, weighted by video length. */
    class BatchProgress(val done: Int, val total: Int, val fraction: Float) {
        val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)
    }

    fun batchProgress(): BatchProgress {
        val b = batch
        val totalMs = b.sumOf { maxOf(1L, it.durationMs) }.toDouble()
        var doneMs = 0.0
        for (j in b) doneMs += maxOf(1L, j.durationMs) * when {
            !j.state.isActive -> 1.0
            j.state == ScanState.SCANNING -> j.percent / 100.0
            else -> 0.0
        }
        return BatchProgress(b.count { !it.state.isActive }, b.size, if (totalMs > 0) (doneMs / totalMs).toFloat() else 0f)
    }

    /** (4.0) Stops the scan in progress and every waiting one. The videos stay in Sessions, not scanned; each can be scanned again. */
    fun cancelAll() {
        for (j in jobs.filter { it.state.isActive }) cancel(j.footageId)
    }

    /** The newest job of this video, or null. */
    fun jobOf(footageId: Long?): ScanJob? = if (footageId == null) null else jobs.lastOrNull { it.footageId == footageId }

    /** True while this video waits or is being scanned: its list and its Forget / Delete / Reconnect are off. */
    fun isBusy(footageId: Long?): Boolean = jobOf(footageId)?.state?.isActive == true

    /** True while any video waits or is being scanned. */
    val anyBusy: Boolean get() = jobs.any { it.state.isActive }

    /**
     * Puts a video in the queue, at the end, unless it is already waiting or being scanned. [near] is the number the user chose when
     * two ran. Call on the main thread.
     */
    fun enqueue(context: Context, footage: Footage, near: Area? = null) =
        enqueue(context, footage.id, Uri.parse(footage.uri), footage.durationMs, footage.title, near)

    /** The same, for a video whose job is known ([job]): its question was answered with [near]. */
    fun again(context: Context, job: ScanJob, near: Area?) = enqueue(context, job.footageId, job.uri, job.durationMs, job.title, near)

    private fun enqueue(context: Context, footageId: Long, uri: Uri, durationMs: Long, title: String, near: Area?) {
        if (isBusy(footageId)) return
        val app = context.applicationContext
        appContext = app
        jobs.add(ScanJob(footageId, uri, durationMs, title, near, ++runs))
        mark(app, footageId, SCAN_QUEUED)          // so it is queued again if Android ends the app first
        if (!running) start(app)
    }

    /**
     * (4.0-C) Puts back in the queue every video that was waiting (or asking) when the app last ended. Call once when the app starts.
     * A video that can't be opened any more is left alone: its mark stays until it is reconnected and scanned.
     */
    fun resume(context: Context) {
        // (4.0) once per process: Android can rebuild the screen (for example while the file picker is open), and running this again
        // queued every video still marked "Choose the timer" into the next batch (owner, 2026-11-27: 8 queued after picking 5)
        if (resumed) return
        resumed = true
        val app = context.applicationContext
        appContext = app
        scope.launch {
            val waiting = withContext(Dispatchers.IO) {
                try {
                    SessionStore.listFootage(app).filter {
                        (it.scanState == SCAN_QUEUED || it.scanState == SCAN_ASKS) && it.trashedMs == 0L && SessionStore.canOpen(app, Uri.parse(it.uri))
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            for (f in waiting) if (jobOf(f.id) == null) enqueue(app, f)        // a video this run already knows keeps its job
        }
    }

    /** Saves how a video's scan stands, in the background (a failed write only loses the mark). */
    private fun mark(app: Context, footageId: Long, state: String) {
        scope.launch(Dispatchers.IO) { try { SessionStore.setScanState(app, footageId, state) } catch (e: Exception) { } }
    }

    /** The mark a finished scan leaves on its video. */
    private fun markOf(state: ScanState): String = when (state) {
        ScanState.NO_TIMER -> SCAN_NO_TIMER
        ScanState.ASKS -> SCAN_ASKS
        else -> ""
    }

    /** Stops this video's scan (or takes it out of the queue). Nothing it found is saved. */
    fun cancel(footageId: Long) {
        val j = jobOf(footageId) ?: return
        appContext?.let { mark(it, footageId, "") }          // not queued again at the next start
        j.cancelAsked = true
        if (j.state == ScanState.WAITING) {
            j.state = ScanState.CANCELLED
            j.outcome = ScanOutcome("Cancelled.")
            finished++
        }
    }



    /** The user said neither number is the timer: the video's scan ends as "timer not found" (its solves are added by hand). */
    fun settleWithoutTimer(context: Context, footageId: Long) {
        mark(context.applicationContext, footageId, SCAN_NO_TIMER)
        val j = jobOf(footageId) ?: return
        if (j.state != ScanState.ASKS) return
        j.outcome = ScanOutcome("Couldn't find the timer.")
        j.state = ScanState.NO_TIMER
    }

    /** Forgets the finished scans (the progress list's *Clear*). */
    fun clearFinished() {
        jobs.removeAll { !it.state.isActive }
    }

    /** Forgets everything about one video (it was forgotten, so no result of it should show any more). */
    fun forget(footageId: Long) {
        cancel(footageId)
        jobs.removeAll { it.footageId == footageId && !it.state.isActive }
    }

    private fun start(app: Context) {
        running = true
        batchFrom = jobs.firstOrNull { it.state == ScanState.WAITING }?.run ?: (runs + 1)
        AnalysisService.start(app)
        scope.launch {
            var done = 0
            var solves = 0
            try {
                while (true) {
                    val job = jobs.firstOrNull { it.state == ScanState.WAITING } ?: break
                    scanOne(app, job)
                    job.endedMs = System.currentTimeMillis()
                    // written before `finished` changes, so a screen that reloads then sees the new mark
                    val state = markOf(job.state)
                    withContext(Dispatchers.IO) { try { SessionStore.setScanState(app, job.footageId, state) } catch (e: Exception) { } }
                    done++
                    solves += job.outcome?.solves ?: 0
                    finished++
                }
            } finally {
                running = false
                AnalysisService.stop(app)
            }
            val last = jobs.lastOrNull { !it.state.isActive }
            if (done == 1 && last != null) AnalysisService.notifyDone(app, "${last.title}: ${last.outcome?.text ?: ""}")
            else if (done > 1) AnalysisService.notifyDone(app, "$done videos scanned: ${countText(solves, "solve")}")
        }
    }

    /** (4.0) The batch in words, for the notification and the bar: "2 of 10 videos done", or one video's name. */
    fun batchLabel(p: BatchProgress = batchProgress()): String {
        // (4.0) what is left, not "3 of 8 done": a count over several picks read as videos added that weren't picked (owner, 2026-11-27)
        val scanning = jobs.filter { it.state == ScanState.SCANNING }
        val waiting = jobs.count { it.state == ScanState.WAITING }
        return if (waiting == 0 && scanning.size == 1) scanning[0].title
        else listOfNotNull(if (scanning.isNotEmpty()) "${scanning.size} scanning" else null, if (waiting > 0) "$waiting waiting" else null)
            .joinToString(", ").ifEmpty { "${p.done} done" }
    }

    /** Scans one video and saves what it found. Never throws: how it ended is in [ScanJob.state] and [ScanJob.outcome]. */
    private suspend fun scanOne(app: Context, job: ScanJob) {
        job.state = ScanState.SCANNING
        job.percent = 0
        job.finding = true
        val started = System.currentTimeMillis()
        job.startedMs = started
        val timing = ScanTiming()
        job.timing = timing
        try {
            val auto = withContext(Dispatchers.Default) {
                analyzeByItself(
                    app, job.uri, job.durationMs, job.near,
                    onProgress = { finding, percent ->
                        job.finding = finding
                        job.percent = percent
                        val p = batchProgress()
                        AnalysisService.update(app, p.percent, 100, batchLabel(p))
                    },
                    isCancelled = { job.cancelAsked },
                    timing = timing,
                    hint = lastTimer
                )
            }
            auto.hint?.let { lastTimer = it }
            if (auto.choices.isNotEmpty()) {
                val picture = withContext(Dispatchers.IO) { grabFrame(app, job.uri, (auto.choices[0].t * 1000).toLong()) }
                if (picture != null) {
                    job.outcome = ScanOutcome("Which number is your timer?", question = TimerQuestion(auto.choices, picture))
                    job.state = ScanState.ASKS
                    return
                }
            }
            val report = auto.scan
            if (report == null) {
                job.outcome = ScanOutcome("Couldn't find the timer.")
                job.state = ScanState.NO_TIMER
                return
            }
            val outcome = withContext(Dispatchers.IO) { saveScan(app, job, auto, report, started) }
            if (outcome == null) {
                job.outcome = ScanOutcome(FORGOTTEN)
                job.state = ScanState.CANCELLED
                return
            }
            job.outcome = outcome
            job.state = ScanState.DONE
        } catch (e: ScanCancelled) {
            job.outcome = ScanOutcome("Cancelled.")
            job.state = ScanState.CANCELLED
        } catch (e: CancellationException) {
            job.outcome = ScanOutcome("Cancelled.")
            job.state = ScanState.CANCELLED
            throw e
        } catch (e: Exception) {
            job.outcome = ScanOutcome("Find solves stopped: ${e.message ?: e.javaClass.simpleName}")
            job.state = ScanState.FAILED
        }
    }

    /**
     * Merges what the scan found with what is saved, and saves it all: the cuts, the hints, the timer's place, "scanned". Everything
     * saved is read again here, at the end of the scan, so a change made meanwhile (a note in the Gallery) isn't lost. A re-scan keeps
     * what the user changed (mergeRescan, ADR-0013). Null if the video was forgotten meanwhile. Run on IO.
     */
    private fun saveScan(app: Context, job: ScanJob, auto: AutoScanResult, report: ScanResult, started: Long): ScanOutcome? {
        val f = SessionStore.getFootage(app, job.footageId) ?: return null
        auto.numbers?.let { try { SessionStore.saveFoundTimer(app, f.id, it) } catch (e: Exception) { } }
        val saved = SessionStore.loadCuts(app, f.id)
        // (4.0) a time already on the timer when the video began is no solve: it is dropped here, not listed (owner, 2026-11-27)
        val found = withoutLeftovers(report.solves.map { r -> normalizeIncluded(CutItem(r.stopSec, r.timeText, r.include, r.note, f.scrambleAll)) })
        val exportedStops = SessionStore.loadExports(app, f.id).map { it.stopSec }
        val merged = mergeRescan(saved, found, exportedStops)
        SessionStore.saveSession(app, f.id, merged.cuts, f.beforePad, f.afterPad, f.scrambleAll)
        val firstScan = f.lastScanMs <= 0
        SessionStore.markScanned(app, f.id)
        // where a solve may be missing; what was dismissed before stays away
        val hints = mergeHints(SessionStore.loadHints(app, f.id), hintsOf(report))
        SessionStore.saveHints(app, f.id, hints)

        val maybeMissed = hintsToShow(hints, merged.cuts).size
        val checks = checkCuts(merged.cuts, computeClips(merged.cuts, f.beforePad.toDouble(), f.afterPad.toDouble(), job.durationMs / 1000.0))
        val solveCount = merged.cuts.size
        val toCheck = merged.cuts.indices.count { merged.cuts[it].noteNeedsCheck() || checks.warnings.containsKey(it) }
        val seconds = (System.currentTimeMillis() - started) / 1000
        val text = if (saved.isEmpty()) {
            "Done in ${seconds}s: $solveCount solves, $toCheck to check" + (if (maybeMissed > 0) ", $maybeMissed maybe missed" else "")
        } else {
            (if (firstScan) "Done" else "Re-scan done") + " in ${seconds}s: " + merged.summary() + (if (toCheck > 0) ". $toCheck to check" else "") +
                    (if (maybeMissed > 0) ". $maybeMissed maybe missed" else "")
        }
        return ScanOutcome(text, solveCount, toCheck, maybeMissed, report)
    }

    private fun countText(n: Int, word: String) = "$n $word" + (if (n == 1) "" else "s")

    private const val FORGOTTEN = "The video was forgotten meanwhile. Nothing saved."
}
