package com.example.cubecut

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// How many pictures make up the film strip, and how many bars the waveform has.
const val THUMB_COUNT = 12
const val WAVE_BUCKETS = 2000

// Sound-spike detector settings (same idea as the PC script)
const val DEFAULT_SPIKE_RATIO = 4.0f   // a spike must be this many times louder than the previous second
const val SPIKE_MIN_LEVEL = 0.02f      // ...and at least this loud (0..1)
const val SPIKE_COOLDOWN_SEC = 2.0     // ignore this long after a spike

// ---------------------------------------------------------------------------------------
// Helpers that read the video file (all of these run in the background, not on the screen thread)
// ---------------------------------------------------------------------------------------

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (ms % 1000) / 100
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}

fun readDurationMs(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        retriever.release()
    }
}

/** Grabs [count] evenly spaced pictures from the video, shrunk to a small size. */
fun buildThumbnails(context: Context, uri: Uri, durationMs: Long, count: Int, onProgress: (Float) -> Unit = {}): List<ImageBitmap> {
    val retriever = MediaMetadataRetriever()
    val result = mutableListOf<ImageBitmap>()
    try {
        retriever.setDataSource(context, uri)
        for (i in 0 until count) {
            onProgress(i.toFloat() / count)
            val timeUs = ((i + 0.5) / count * durationMs * 1000).toLong()
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: continue
            val targetHeight = 160
            val targetWidth = max(1, frame.width * targetHeight / frame.height)
            val small = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
            if (small !== frame) frame.recycle()
            result.add(small.asImageBitmap())
        }
    } finally {
        retriever.release()
    }
    return result
}

/**
 * Decodes the audio track and returns the loudness (0..1) of every 10 ms slice of the video.
 * "Loudness" here is the RMS: square every sample, average them, take the square root.
 * Stereo is mixed down to mono first, like the PC script does.
 */
fun decodeLoudness(context: Context, uri: Uri, durationMs: Long, onProgress: (Float) -> Unit = {}): FloatArray {
    val windowCount = (durationMs / 10 + 2).toInt().coerceAtLeast(2)
    val sumSq = DoubleArray(windowCount)
    val counts = IntArray(windowCount)
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(context, uri, null)

        var audioTrack = -1
        for (i in 0 until extractor.trackCount) {
            val trackMime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (trackMime.startsWith("audio/")) {
                audioTrack = i
                break
            }
        }
        if (audioTrack < 0 || durationMs <= 0) return FloatArray(windowCount)

        extractor.selectTrack(audioTrack)
        val format = extractor.getTrackFormat(audioTrack)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(windowCount)
        var sampleRate =
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
        var channels =
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastPercent = -1

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuffer = decoder.getInputBuffer(inIndex)
                    val size = if (inBuffer != null) extractor.readSampleData(inBuffer, 0) else -1
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // the decoder tells us the real sample rate / channel count of what it outputs
                val outFormat = decoder.outputFormat
                if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (outIndex >= 0) {
                val outBuffer = decoder.getOutputBuffer(outIndex)
                if (outBuffer != null && info.size > 0 && channels > 0 && sampleRate > 0) {
                    outBuffer.position(info.offset)
                    outBuffer.limit(info.offset + info.size)
                    val samples = outBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = samples.remaining() / channels
                    val usPerFrame = 1_000_000.0 / sampleRate
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) sum += samples.get() / 32768f
                        val mono = sum / channels
                        val window = ((info.presentationTimeUs + f * usPerFrame) / 10_000.0).toInt()
                        if (window in 0 until windowCount) {
                            sumSq[window] += (mono * mono).toDouble()
                            counts[window]++
                        }
                    }
                }
                decoder.releaseOutputBuffer(outIndex, false)
                // tell the caller how far we are (at most once per percent)
                val percent = (info.presentationTimeUs * 100 / (durationMs * 1000)).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent / 100f)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
            // (other negative values just mean "nothing ready yet" - safe to ignore)
        }
    } finally {
        try {
            codec?.stop()
        } catch (e: Exception) {
        }
        codec?.release()
        extractor.release()
    }

    return FloatArray(windowCount) { i ->
        if (counts[i] > 0) sqrt(sumSq[i] / counts[i]).toFloat() else 0f
    }
}

/** Squeezes the 10 ms loudness values into [buckets] bars for drawing. */
fun makeWaveform(loudness: FloatArray, buckets: Int): FloatArray {
    val bars = FloatArray(buckets)
    if (loudness.isEmpty()) return bars
    for (i in loudness.indices) {
        val b = (i.toLong() * buckets / loudness.size).toInt().coerceIn(0, buckets - 1)
        if (loudness[i] > bars[b]) bars[b] = loudness[i]
    }
    val biggest = bars.maxOrNull() ?: 0f
    if (biggest > 0f) {
        for (i in bars.indices) bars[i] = (bars[i] / biggest).pow(0.6f)
    }
    return bars
}

/**
 * Finds sudden loud sounds (the cube / timer slap). A window counts as a spike when it is [ratio] times
 * louder than the average of the second before it. After a spike we ignore [SPIKE_COOLDOWN_SEC] seconds.
 * Returns the times in seconds.
 */
fun findSpikes(loudness: FloatArray, ratio: Float): List<Double> {
    val spikes = mutableListOf<Double>()
    val n = loudness.size
    if (n < 110) return spikes

    // running totals, so "average of the previous second" is quick to look up
    val prefix = DoubleArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + loudness[i]

    var last = -1e9
    for (i in 100 until n) {
        val baseline = (prefix[i - 5] - prefix[i - 100]) / 95.0 + 1e-6
        val t = i * 0.01
        if (loudness[i] / baseline > ratio && loudness[i] > SPIKE_MIN_LEVEL && t - last > SPIKE_COOLDOWN_SEC) {
            spikes.add(t)
            last = t
        }
    }
    return spikes
}


/** Grabs one exact picture from the video at [ms]. */
fun grabFrame(context: Context, uri: Uri, ms: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
    } finally {
        retriever.release()
    }
}