package com.example.cubecut   // <-- keep whatever package line your own files have

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/** One detected sound, after the timer has been checked. */
class SolveCandidate(
    val soundSec: Double,       // when the sound happened
    val digits: String?,        // the number the timer settled on (digits only), if any
    val timeText: String,       // the same number written nicely, e.g. "11.263", or "-" if unknown
    val votes: Int,             // on how many frames that number was read
    val isSolve: Boolean,       // true = looks like a real stop of the timer
    val reason: String          // if it is not a solve: why not
)

/**
 * For each loud sound, reads the timer on the frames that follow and decides whether it was a real stop.
 * Same rules as the PC script (cubecut.py).
 */
object SolveAnalyzer {
    const val READ_FROM = 0.4         // start reading this long after the sound (seconds)
    const val READ_STEP = 0.1         // ...then every 0.1 s
    const val READ_COUNT = 27         // ...for 27 frames (about 2.6 s)
    const val MIN_VOTES = 5           // the same time must be read at least this many times
    const val SAME_TIME_WINDOW = 25.0 // a repeat of the previous solve's time within this many seconds is noise

    /** Reads the display on many frames after [s] and counts how often each number was seen. */
    private fun readAfter(retriever: MediaMetadataRetriever, s: Double, durationMs: Long, engine: TimerEngine): HashMap<String, Int> {
        val votes = HashMap<String, Int>()
        for (i in 0 until READ_COUNT) {
            val t = s + READ_FROM + i * READ_STEP
            if (t * 1000.0 > durationMs) break
            val frame = retriever.getFrameAtTime((t * 1_000_000.0).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                ?: continue
            val digits = engine.readDigits(frame)
            frame.recycle()                  // a 1080p frame is about 8 MB - give it back right away
            if (digits != null) votes[digits] = (votes[digits] ?: 0) + 1
        }
        return votes
    }

    /** Turns the vote counts into a verdict. */
    private fun judge(s: Double, votes: HashMap<String, Int>, decimals: Int, lastDigits: String?, lastSec: Double): SolveCandidate {
        if (votes.isEmpty()) return SolveCandidate(s, null, "-", 0, false, "couldn't read the timer after this sound")
        val best = votes.entries.maxByOrNull { it.value }!!
        val digits = best.key
        val count = best.value
        val text = TimerVision.formatDigits(digits, decimals)
        return when {
            count < MIN_VOTES -> SolveCandidate(s, digits, text, count, false, "timer not steady (best guess $text, seen only $count times)")
            digits.all { it == '0' } -> SolveCandidate(s, digits, text, count, false, "timer shows zero - probably not a stop")
            digits == lastDigits && s - lastSec < SAME_TIME_WINDOW ->
                SolveCandidate(s, digits, text, count, false, "same time as the previous solve - repeated noise")
            else -> SolveCandidate(s, digits, text, count, true, "")
        }
    }

    /** Checks every sound in the list. [onProgress] is told (done, total) as it goes. */
    fun analyze(
        context: Context,
        uri: Uri,
        sounds: List<Double>,
        durationMs: Long,
        engine: TimerEngine,
        onProgress: (Int, Int) -> Unit
    ): List<SolveCandidate> {
        val out = mutableListOf<SolveCandidate>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            var lastDigits: String? = null
            var lastSec = -1e9
            for ((index, s) in sounds.withIndex()) {
                onProgress(index, sounds.size)
                val candidate = judge(s, readAfter(retriever, s, durationMs, engine), engine.decimals, lastDigits, lastSec)
                out.add(candidate)
                if (candidate.isSolve) {
                    lastDigits = candidate.digits
                    lastSec = s
                }
            }
            onProgress(sounds.size, sounds.size)
        } finally {
            retriever.release()
        }
        return out
    }

    /** Checks one moment chosen by the user ("Add cut here"). */
    fun analyzeOne(context: Context, uri: Uri, soundSec: Double, durationMs: Long, engine: TimerEngine): SolveCandidate {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            return judge(soundSec, readAfter(retriever, soundSec, durationMs, engine), engine.decimals, null, -1e9)
        } finally {
            retriever.release()
        }
    }
}