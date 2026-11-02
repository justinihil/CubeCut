package com.example.cubecut

import org.opencv.core.Mat

// ---------------------------------------------------------------------------------------
// "Readers": the part that turns a straightened display picture into digits.
//
// Everything BEFORE this point is shared by all readers and does not care about fonts:
//   find the display in the frame (ShapeFinder, or the saved corners in fixed-camera mode)
//   -> straighten it, even out the light, undo the lean, make it black-and-white (TimerVision.prep)
// Everything AFTER this point is shared too:
//   vote over many frames (SolveAnalyzer) -> format the number (TimerVision.formatDigits)
//
// A reader only has to do two things:
//   learn(): from one frame + the number you typed, work out the settings that make it read that number
//   read():  from a straightened black-and-white picture, return the digits ("11108") or null if unsure
//
// "Set up timer" tries the readers in the order of [DisplayReaders.all] and keeps the first one whose learn()
// succeeds. The winner's id is saved inside the DigitLayout (readerId), so the right reader is used every time
// the timer is loaded again.
// ---------------------------------------------------------------------------------------

interface DisplayReader {
    /** Saved inside the layout (DigitLayout.readerId). Never change an id once it has shipped. */
    val id: String

    /** false = skipped everywhere (used by readers that are not built yet). */
    val available: Boolean

    /**
     * [gray] is the whole frame, [q] the display's four corners (TL, TR, BR, BL as 8 numbers), [typed] what you typed.
     * Return a layout whose readerId is this reader's id, or null if this reader can't reproduce [typed].
     */
    fun learn(gray: Mat, q: DoubleArray, typed: String): DigitLayout?

    /** Digits only, no punctuation (e.g. "11108"), or null if the picture isn't clear. */
    fun read(th: Bin, l: DigitLayout): String?

    /**
     * Last resort when learn() found nothing that reproduces [typed] exactly: the closest layout it can build, or null.
     * Setup saves it with a warning instead of stopping. Readers that can't do this keep the default (null).
     */
    fun learnUnverified(gray: Mat, q: DoubleArray, typed: String): DigitLayout? = null
}

object DisplayReaders {
    /** Order matters: cheapest / most reliable first. Add new readers here. */
    val all: List<DisplayReader> = listOf(SegmentReader, GlyphReader)

    /** The reader a saved layout asks for. Falls back to the segment reader if that id is unknown or not available. */
    fun byId(id: String): DisplayReader = all.firstOrNull { it.id == id && it.available } ?: SegmentReader
}

/**
 * Reader 1 (approach 1): seven-segment style digits - Stackmat LCDs, and screens that use a seven-segment font.
 * The work itself lives in TimerVision.learn / TimerVision.readBin; this just plugs them into the reader seam.
 */
object SegmentReader : DisplayReader {
    override val id = "segment"
    override val available = true
    override fun learn(gray: Mat, q: DoubleArray, typed: String): DigitLayout? = TimerVision.learn(gray, q, typed)
    override fun read(th: Bin, l: DigitLayout): String? = TimerVision.readBin(th, l)
    override fun learnUnverified(gray: Mat, q: DoubleArray, typed: String): DigitLayout? =
        TimerVision.learn(gray, q, typed, true)
}

/**
 * Reader 2 (approach 2, NOT BUILT YET): any font. Placeholder so the rest of the app is already wired for it.
 *
 * To build it later, only this object (plus a model file) needs to change:
 *
 *  1. Set [available] to true.
 *  2. learn(): find the digit boxes without assuming even spacing (connected components on the black-and-white
 *     picture instead of TimerVision.groups, because proportional fonts have a narrow "1"), run the glyph model on
 *     each box, and accept only if the result equals the digits in [typed]. Reuse TimerVision.canvasSize / prep /
 *     autoBand for the straightening and the digit band - they don't depend on the font.
 *  3. Return DigitLayout(invert, shear, bandTop, bandBottom, digitW, offsets = IntArray(0), canvasW, canvasH,
 *     decimals, readerId = "glyph", readerData = "<model name or version>"). Unused numbers can be 0 / empty.
 *     readerData is saved and loaded for you (TimerStore) - put anything the reader needs to remember there.
 *  4. read(): same segmentation, run the model, return the digits. Return null whenever any glyph is unsure
 *     (the vote in SolveAnalyzer relies on "null when unsure" rather than a wrong guess).
 *  5. If the model works better on grayscale than black-and-white, add a grayscale variant of TimerVision.prep
 *     (same warp / contrast / un-lean steps, stop before the threshold) and pass it to read() as a second parameter.
 *
 * The expected model: a small TFLite digit classifier (0-9) trained on digits rendered from many fonts, with blur,
 * noise and skew added. Keep it in app/src/main/assets and load it lazily the first time learn() or read() runs.
 */
object GlyphReader : DisplayReader {
    override val id = "glyph"
    override val available = false
    override fun learn(gray: Mat, q: DoubleArray, typed: String): DigitLayout? = null
    override fun read(th: Bin, l: DigitLayout): String? = null
}