package com.example.cubecut

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** One solve found by the scan. */
class FoundSolve(
    val stopSec: Double,        // the FIRST frame on which the final time is showing = the moment the timer stopped
    val digits: String,         // the final time as digits, e.g. "12412"
    val timeText: String,       // the same, written nicely, e.g. "12.412"
    val votes: Int,             // on how many frames that exact number was read
    val reads: Int,             // how many frames were read to decide (votes out of reads)
    val note: String,           // "" = looks fine, otherwise why it needs a look
    val include: Boolean        // false = listed but switched off (needs a look first)
)

/** A stretch of the video where the timer could not be read at all (hand in the way, camera moved...). */
class UnreadableSpan(val startSec: Double, val endSec: Double)

/** Everything the scan learned. */
class ScanResult(
    val solves: List<FoundSolve>,
    val unreadable: List<UnreadableSpan>,   // long stretches where the timer was unreadable - "check these parts"
    val readableFraction: Double,           // 0..1: how many of the sampled frames had a readable timer
    val framesRead: Int
)

/** A cut added by hand ("+ Cut at playhead"), moved to the first frame that shows the time. */
class ManualCut(val stopSec: Double, val timeText: String, val note: String)

/** Thrown inside the scan when the user pressed Cancel. */
class ScanCancelled : Exception("cancelled")

/**
 * Finds the solves by READING THE TIMER along the whole video (no sound involved).
 *
 * The idea: when a solve ends, the timer freezes and keeps showing the same non-zero time for a while.
 * So a time that shows up on several frames in a row is a finished solve, and the FIRST frame that shows it is the
 * moment the timer stopped. That frame is the cut.
 *
 * How it works, in short:
 *  1. COARSE SCAN: read the display every 0.5 s (wider steps while it shows 0.000 or a time we already found,
 *     because nothing can finish there).
 *  2. A time seen on 2 reads with nothing clearly different in between = a stopped timer. A few more reads after it
 *     are used to vote for the exact digits (the reader sometimes sees a 4 or 7 as a 1).
 *  3. REFINE: walk backwards from there in finer and finer steps (0.5 s, 0.1 s, one frame) to the first frame that
 *     shows that time.
 *
 * The decision logic lives in [scan] / [firstAppearance] / [locate] and only needs a function "digits shown at second t",
 * so it was tested on a computer with simulated, noisy readings before being written here.
 */
object SolveAnalyzer {
    // ---- scanning ----
    const val SCAN_STEP = 0.5           // seconds between reads while looking for a stop
    const val ZERO_STEP = 2.0           // wider step while the display shows 0.000 (a stop is at least ~3 s away; a running time is then seen on the next read)
    const val HOLD_STEP = 2.0           // wider step while the display still shows a time we already found
    const val MIN_AGREE = 2             // the same time on this many reads = a stopped timer
    const val RECENT_N = 6              // ...looking at the last 6 readable reads
    const val AGREE_WINDOW_SEC = 6.0    // ...which must lie within this many seconds of each other
    const val LOCK_SEC = 3.0            // after a stop is spotted, keep reading this long to vote on the exact digits
    const val HOLD_MERGE_SEC = 12.0     // look-alike readings of a found time within this long are the same hold, not a new solve
    const val UNREADABLE_MIN_SEC = 15.0 // an unreadable stretch at least this long is reported as "check this part"

    /** Start of the note on a cut whose time was already showing when the video began. Such a cut is switched off and ignored like a deleted one. */
    const val LEFTOVER_NOTE_START = "this time was already on the timer"

    // ---- finding the first frame ----
    const val COARSE_BACK_STEP = 0.5
    const val BACK_STEP = 0.1
    const val FINE_STEP = 1.0 / 60.0    // one frame of 60 fps video
    const val FINE_WINDOW = 0.12        // the one-frame search never goes further back than this
    const val BACK_MAX_SEC = 4.0        // automatic scan: look back at most this far for the first frame
    const val MANUAL_BACK_MAX_SEC = 30.0 // "+ Cut at playhead": the playhead may sit anywhere in the hold

    // ---- parallel scan ----
    const val MAX_WORKERS = 3           // at most this many pieces of the video are scanned at the same time
    const val MIN_CHUNK_SEC = 60.0      // a piece is never shorter than this (short videos are scanned in one go)
    const val CHUNK_OVERLAP_SEC = 10.0  // each piece keeps scanning this far into the next one, so a solve on the border is not missed
    const val DUPLICATE_SEC = 6.0       // two equal-looking solves closer than this (from neighbouring pieces) are the same solve

    // ---------------------------------------------------------------------------------------
    // small helpers on the digit strings
    // ---------------------------------------------------------------------------------------

    private fun isZero(d: String): Boolean = d.all { it == '0' }

    private fun confusable(x: Char, y: Char): Boolean =
        (x == '1' && (y == '4' || y == '7')) || (y == '1' && (x == '4' || x == '7'))

    /** Same number, or the same except for ONE digit the reader is known to mix up (1 <-> 4, 1 <-> 7). */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            if (a[i] != b[i]) {
                if (!confusable(a[i], b[i])) return false
                diff++
                if (diff > 1) return false
            }
        }
        return diff == 1
    }

    /** true if [a] is [b] with a 4 or a 7 where [b] has a 1 (the reader tends to see 4 and 7 as 1, never the other way). */
    private fun beats(a: String, b: String): Boolean {
        if (a.length != b.length || a == b) return false
        var diffs = 0
        var ok = false
        for (i in a.indices) {
            if (a[i] != b[i]) {
                diffs++
                ok = b[i] == '1' && (a[i] == '4' || a[i] == '7')
            }
        }
        return diffs == 1 && ok
    }

    /** The most likely true number among the votes. A 4/7 version beats its "1" look-alike (counted double). */
    private fun pickWinner(counts: Map<String, Int>, trigger: String): String {
        fun score(c: String): Int {
            val weak = counts.keys.any { beats(c, it) }
            return (counts[c] ?: 0) * (if (weak) 2 else 1)
        }
        val best = counts.keys.maxOf { score(it) }
        if (score(trigger) == best) return trigger
        return counts.keys.sortedWith(
            compareByDescending<String> { score(it) }.thenByDescending { counts[it] ?: 0 }
        ).first()
    }

    // ---------------------------------------------------------------------------------------
    // finding the first frame that shows a time
    // ---------------------------------------------------------------------------------------

    private class WalkResult(val earliest: Double, val boundary: Double, val foundOther: Boolean)

    /**
     * Walks back from [anchor] (a frame that shows [w]) in jumps of [step], never reaching [lowest].
     * Frames that can't be read are skipped. Two readable frames in a row that show something else end the walk
     * (one odd reading is forgiven). Returns the earliest frame that showed [w], and the nearest frame found that did NOT
     * (or [lowest]) so the next, finer walk knows where to search.
     */
    private fun walkBack(read: (Double) -> String?, w: String, anchor: Double, step: Double, lowest: Double): WalkResult {
        var earliest = anchor
        var contra = 0
        var contraT = Double.NaN
        var t = anchor - step
        while (t > lowest + 1e-9) {
            val v = read(t)
            if (v != null) {
                if (similar(v, w)) {
                    earliest = t
                    contra = 0
                    contraT = Double.NaN
                } else {
                    contra++
                    if (contra == 1) contraT = t
                    if (contra >= 2) break
                }
            }
            t -= step
        }
        val found = !contraT.isNaN()
        return WalkResult(earliest, if (found) contraT else lowest, found)
    }

    /**
     * Binary search for the first frame that shows [w], between [falseT] (a frame that was read and shows something else)
     * and [trueT] (a frame that shows [w]). Returns the earliest frame that shows [w] (to one frame, 1/60 s),
     * or null if a frame in the middle couldn't be read (the caller then walks step by step instead).
     */
    private fun bisect(read: (Double) -> String?, w: String, falseT: Double, trueT: Double): Double? {
        var lo = falseT
        var hi = trueT
        while (true) {
            val frames = Math.round((hi - lo) / FINE_STEP).toInt()
            if (frames <= 1) return hi
            val mid = lo + (frames / 2) * FINE_STEP
            val v = read(mid) ?: return null
            if (similar(v, w)) hi = mid else lo = mid
        }
    }

    /**
     * The earliest frame (to 1/60 s) from which the display shows [w], searching back from [startT] (a frame that shows it),
     * but not before [lower]. Returns (time, certain). certain = a frame that clearly shows something else was found before
     * it, so the start of the time really is where we say.
     */
    private fun firstAppearance(read: (Double) -> String?, w: String, startT: Double, lower: Double): Pair<Double, Boolean> {
        val low0 = lower - 1e-6
        val r1 = walkBack(read, w, startT, COARSE_BACK_STEP, low0)
        if (r1.foundOther) {
            // we know a frame that shows something else (boundary) and one that shows the time (earliest):
            // halve the gap until the two are one frame apart (about 5 reads instead of about 12)
            val first = bisect(read, w, r1.boundary, r1.earliest)
            if (first != null) return Pair(first, true)
            // a frame in the middle couldn't be read: fall back to the slower step-by-step walk below
        }
        val r2 = walkBack(read, w, r1.earliest, BACK_STEP, max(low0, r1.boundary))
        val r3 = walkBack(read, w, r2.earliest, FINE_STEP, max(low0, max(r2.boundary, r2.earliest - FINE_WINDOW)))
        return Pair(r3.earliest, r1.foundOther || r2.foundOther || r3.foundOther)
    }

    // ---------------------------------------------------------------------------------------
    // the whole-video scan
    // ---------------------------------------------------------------------------------------

    /**
     * [read] returns the digits the timer shows at a given second (or null if it can't be read).
     * [onProgress] is told (percent, 100). [isCancelled] is checked before every read; when it says true, [ScanCancelled] is thrown.
     * [fromSec]..[toSec] is the part of the video to scan (the whole video by default). [durationSec] is always the real length.
     * A time that is already showing at [fromSec] and started before it is reported as "left over" (switched off).
     */
    fun scan(
        read: (Double) -> String?,
        durationSec: Double,
        decimals: Int,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
        fromSec: Double = 0.0,
        toSec: Double = durationSec
    ): ScanResult {
        val solves = mutableListOf<FoundSolve>()
        val unreadable = mutableListOf<UnreadableSpan>()
        var total = 0
        var readable = 0
        var t = fromSec
        var confirmed: String? = null          // the time of the solve we just found (while the display still shows it)
        var confirmedSeen = -1e9
        val recent = ArrayList<Pair<Double, String>>()   // the last few readable, non-zero reads
        var nullStart = -1.0                   // start of a run of unreadable frames (-1 = not in one)
        var lastPercent = -1

        val span = toSec - fromSec
        while (t <= toSec) {
            if (isCancelled()) throw ScanCancelled()
            val pct = if (span > 0) ((t - fromSec) / span * 100).toInt().coerceIn(0, 100) else 0
            if (pct != lastPercent) {
                lastPercent = pct
                onProgress(pct, 100)
            }

            val v = read(t)
            total++
            if (v == null) {
                if (nullStart < 0) nullStart = t
                t += SCAN_STEP
                continue
            }
            readable++
            if (nullStart >= 0) {
                if (t - nullStart >= UNREADABLE_MIN_SEC) unreadable.add(UnreadableSpan(nullStart, t))
                nullStart = -1.0
            }

            // 0.000: the display was reset, a new solve is about to start
            if (isZero(v)) {
                confirmed = null
                recent.clear()
                t += ZERO_STEP
                continue
            }

            // still the time we already found (or a misreading of it): the same hold, nothing new
            val c = confirmed
            if (c != null) {
                if (v == c) {
                    confirmedSeen = t
                    t += HOLD_STEP
                    continue
                }
                if (similar(v, c) && t - confirmedSeen < HOLD_MERGE_SEC) {
                    confirmedSeen = t
                    t += HOLD_STEP
                    continue
                }
            }
            confirmed = null

            recent.add(Pair(t, v))
            if (recent.size > RECENT_N) recent.removeAt(0)

            // the newest run of reads that are v (or a look-alike of v): a stopped timer is stable, a running one is not
            var exact = 0
            var firstT = t
            for (i in recent.indices.reversed()) {
                val r = recent[i]
                if (r.first < t - AGREE_WINDOW_SEC || !similar(r.second, v)) break
                if (r.second == v) exact++
                firstT = r.first
            }

            if (exact >= MIN_AGREE) {
                // ---- a stopped timer: read a bit further and vote on the exact digits ----
                val votesReads = ArrayList<Pair<Double, String>>()
                for (r in recent) if (r.first >= firstT) votesReads.add(r)
                var lt = t + SCAN_STEP
                val lockEnd = min(durationSec, t + LOCK_SEC)
                while (lt <= lockEnd + 1e-9) {
                    if (isCancelled()) throw ScanCancelled()
                    val lv = read(lt)
                    total++
                    if (lv != null) {
                        readable++
                        if (!isZero(lv)) votesReads.add(Pair(lt, lv))
                    }
                    lt += SCAN_STEP
                }
                val counts = HashMap<String, Int>()
                for (r in votesReads) counts[r.second] = (counts[r.second] ?: 0) + 1
                val w = pickWinner(counts, v)
                val votes = counts[w] ?: 0
                val reads = votesReads.size

                // ---- the first frame that shows it ----
                var startT = Double.MAX_VALUE
                for (r in votesReads) if (similar(r.second, w) && r.first < startT) startT = r.first
                val lower = max(0.0, startT - BACK_MAX_SEC)
                val (earliest, certain) = firstAppearance(read, w, startT, lower)

                val text = TimerVision.formatDigits(w, decimals)
                var note = ""
                var include = true
                if (!certain && earliest < fromSec + 1.0) {
                    note = "$LEFTOVER_NOTE_START when the video began - probably left over from an earlier solve"
                    include = false
                } else if (votes < reads * 0.6) {
                    note = "time not steady (read on $votes of $reads frames) - check it"
                }
                solves.add(FoundSolve(earliest, w, text, votes, reads, note, include))

                confirmed = w
                confirmedSeen = t + LOCK_SEC
                recent.clear()
                t += LOCK_SEC + SCAN_STEP
                continue
            }
            t += SCAN_STEP
        }
        if (nullStart >= 0 && toSec - nullStart >= UNREADABLE_MIN_SEC) {
            unreadable.add(UnreadableSpan(nullStart, toSec))
        }
        onProgress(100, 100)
        return ScanResult(
            solves.sortedBy { it.stopSec },
            unreadable,
            if (total > 0) readable.toDouble() / total else 0.0,
            total
        )
    }

    /**
     * "+ Cut at playhead": looks at the display from the playhead onwards (the playhead may be a little before the stop
     * or anywhere while the time is held), finds the time and moves the cut back to the first frame that shows it.
     */
    fun locate(read: (Double) -> String?, atSec: Double, durationSec: Double, decimals: Int): ManualCut {
        val reads = ArrayList<Pair<Double, String>>()
        for (k in 0..12) {
            val t = atSec + 0.25 * k
            if (t > durationSec) break
            val v = read(t)
            if (v != null && !isZero(v)) reads.add(Pair(t, v))
        }
        if (reads.isEmpty()) {
            return ManualCut(atSec, "", "added by you - couldn't read a time here (is the timer in view? is it set up?)")
        }
        val counts = HashMap<String, Int>()
        for (r in reads) counts[r.second] = (counts[r.second] ?: 0) + 1
        val steady = counts.filter { it.value >= 2 }
        if (steady.isEmpty()) {
            return ManualCut(atSec, "", "added by you - no steady time here: put the playhead on a frame where the stopped time is showing")
        }
        val top = steady.maxByOrNull { it.value }!!.key
        val w = pickWinner(steady, top)
        var startT = Double.MAX_VALUE
        for (r in reads) if (similar(r.second, w) && r.first < startT) startT = r.first
        val lower = max(0.0, startT - MANUAL_BACK_MAX_SEC)
        val (earliest, _) = firstAppearance(read, w, startT, lower)
        val text = TimerVision.formatDigits(w, decimals)
        return ManualCut(earliest, text, "added by you")
    }

    // ---------------------------------------------------------------------------------------
    // glue to the real video and timer reader
    // ---------------------------------------------------------------------------------------

    /** Reads the display on the frame closest to second [t]. Frames are given back right away (a 1080p frame is ~8 MB). */
    /** How long one worker spent getting frames out of the video (for the log). */
    private class DecodeStats {
        var nanos = 0L
        var count = 0
    }

    private fun readFrame(retriever: MediaMetadataRetriever, t: Double, durationMs: Long, engine: TimerEngine, stats: DecodeStats): String? {
        if (t < 0.0 || t * 1000.0 > durationMs) return null
        val t0 = System.nanoTime()
        val frame = retriever.getFrameAtTime((t * 1_000_000.0).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
        stats.nanos += System.nanoTime() - t0
        stats.count++
        if (frame == null) return null
        return try {
            engine.readDigits(frame)
        } finally {
            frame.recycle()
        }
    }

    /** A reading function that remembers what it already read (the refining steps revisit frames the scan has seen). */
    private fun memoReader(retriever: MediaMetadataRetriever, durationMs: Long, engine: TimerEngine, stats: DecodeStats): (Double) -> String? {
        val memo = HashMap<Long, String?>()
        return { t ->
            val key = Math.round(t * 1000.0)
            if (memo.containsKey(key)) {
                memo[key]
            } else {
                val r = readFrame(retriever, t, durationMs, engine, stats)
                memo[key] = r
                r
            }
        }
    }

    /** How many pieces the video is split into (each scanned by its own worker). */
    private fun workerCount(durationSec: Double): Int {
        val byLength = (durationSec / MIN_CHUNK_SEC).toInt()
        val byCpu = Runtime.getRuntime().availableProcessors() / 2
        return max(1, min(MAX_WORKERS, min(byLength, byCpu)))
    }

    /** Scans [fromSec]..[toSec] of the video with its own video reader. */
    private fun scanRange(
        context: Context, uri: Uri, durationMs: Long, engine: TimerEngine,
        fromSec: Double, toSec: Double,
        onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean, stats: DecodeStats
    ): ScanResult {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val read = memoReader(retriever, durationMs, engine, stats)
            return scan(read, durationMs / 1000.0, engine.decimals, onProgress, isCancelled, fromSec, toSec)
        } finally {
            retriever.release()
        }
    }

    /**
     * Puts the results of the pieces together. A solve belongs to the piece its cut lies in (the overlap only exists so
     * that a solve on the border is seen completely); a "left over" note on a later piece just means its scan started
     * in the middle of a time that the previous piece has already reported. Equal-looking solves that still end up
     * next to each other (the two pieces disagreed by a frame) are kept once.
     */
    private fun merge(parts: List<ScanResult>, bounds: List<Pair<Double, Double>>): ScanResult {
        val owned = ArrayList<FoundSolve>()
        for ((k, part) in parts.withIndex()) {
            val (from, to) = bounds[k]
            for (s in part.solves) {
                if (s.stopSec < from || s.stopSec >= to) continue
                if (k > 0 && s.note.startsWith(LEFTOVER_NOTE_START)) continue
                owned.add(s)
            }
        }
        owned.sortBy { it.stopSec }
        val solves = ArrayList<FoundSolve>()
        for (s in owned) {
            val prev = solves.lastOrNull()
            if (prev != null && s.stopSec - prev.stopSec < DUPLICATE_SEC && similar(prev.digits, s.digits)) {
                if (s.votes > prev.votes) solves[solves.size - 1] = s
                continue
            }
            solves.add(s)
        }

        val spans = parts.flatMap { it.unreadable }.sortedBy { it.startSec }
        val unreadable = ArrayList<UnreadableSpan>()
        for (sp in spans) {
            val last = unreadable.lastOrNull()
            if (last != null && sp.startSec <= last.endSec + 1.0) {
                unreadable[unreadable.size - 1] = UnreadableSpan(last.startSec, max(last.endSec, sp.endSec))
            } else {
                unreadable.add(sp)
            }
        }

        val frames = parts.sumOf { it.framesRead }
        val readable = parts.sumOf { it.readableFraction * it.framesRead }
        return ScanResult(solves, unreadable, if (frames > 0) readable / frames else 0.0, frames)
    }

    /**
     * Scans the whole video. [onProgress] gets (percent, 100). [isCancelled] is polled; true stops the scan with [ScanCancelled].
     *
     * Getting frames out of the video is by far the slowest part, so a long video is cut into up to [MAX_WORKERS] equal
     * pieces that are scanned at the same time (each with its own video reader and its own [TimerEngine]).
     * Every piece keeps scanning [CHUNK_OVERLAP_SEC] into the next one, and the results are put together by [merge].
     */
    fun analyze(
        context: Context,
        uri: Uri,
        durationMs: Long,
        engine: TimerEngine,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean
    ): ScanResult {
        val durationSec = durationMs / 1000.0
        val n = workerCount(durationSec)
        val stats = List(n) { DecodeStats() }
        val engines = arrayOfNulls<TimerEngine>(n)
        engines[0] = engine
        engine.resetStats()
        val startNs = System.nanoTime()
        try {
            if (n == 1) {
                return scanRange(context, uri, durationMs, engine, 0.0, durationSec, onProgress, isCancelled, stats[0])
            }

            val abort = AtomicBoolean(false)                  // set when one piece fails: the others stop too
            val stopNow = { abort.get() || isCancelled() }
            val progress = IntArray(n)
            val progressLock = Any()
            var lastReported = -1
            fun report(k: Int, pct: Int) {
                synchronized(progressLock) {
                    progress[k] = pct
                    val overall = progress.sum() / n
                    if (overall > lastReported) {             // never goes backwards, and one thread at a time
                        lastReported = overall
                        onProgress(overall, 100)
                    }
                }
            }

            val len = durationSec / n
            val bounds = List(n) { k ->
                Pair(if (k == 0) -Double.MAX_VALUE else k * len, if (k == n - 1) Double.MAX_VALUE else (k + 1) * len)
            }
            val pool = Executors.newFixedThreadPool(n)
            try {
                val futures = (0 until n).map { k ->
                    pool.submit(Callable {
                        try {
                            val eng = if (k == 0) engine else engine.duplicate()
                            engines[k] = eng
                            val from = k * len
                            val to = if (k == n - 1) durationSec else min(durationSec, (k + 1) * len + CHUNK_OVERLAP_SEC)
                            scanRange(context, uri, durationMs, eng, from, to, { pct, _ -> report(k, pct) }, stopNow, stats[k])
                        } catch (e: Throwable) {
                            abort.set(true)
                            throw e
                        }
                    })
                }
                val parts = ArrayList<ScanResult>()
                var failure: Throwable? = null
                var cancelled = false
                for (f in futures) {
                    try {
                        parts.add(f.get())
                    } catch (e: ExecutionException) {
                        val cause = e.cause
                        if (cause is ScanCancelled) cancelled = true else if (failure == null) failure = cause ?: e
                    } catch (e: InterruptedException) {
                        abort.set(true)
                        cancelled = true
                    }
                }
                failure?.let { throw it }
                if (cancelled) throw ScanCancelled()
                onProgress(100, 100)
                return merge(parts, bounds)
            } finally {
                abort.set(true)
                pool.shutdownNow()
            }
        } finally {
            val sb = StringBuilder("Find solves took ${(System.nanoTime() - startNs) / 1_000_000} ms with $n worker(s)")
            for (k in 0 until n) {
                sb.append("\n  worker $k: ${stats[k].count} frames decoded in ${stats[k].nanos / 1_000_000} ms; ")
                sb.append(engines[k]?.statsText() ?: "not started")
            }
            android.util.Log.d("CubeCut", sb.toString())
        }
    }

    /** Checks one moment chosen by the user ("+ Cut at playhead"). */
    fun analyzeOne(context: Context, uri: Uri, atSec: Double, durationMs: Long, engine: TimerEngine): ManualCut {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val read = memoReader(retriever, durationMs, engine, DecodeStats())
            return locate(read, atSec, durationMs / 1000.0, engine.decimals)
        } finally {
            retriever.release()
        }
    }
}