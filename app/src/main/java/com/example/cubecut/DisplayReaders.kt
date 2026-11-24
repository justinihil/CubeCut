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
// succeeds. The winner's id is kept inside the DigitLayout (readerId), so the right reader is used every time
// the timer is read.
// ---------------------------------------------------------------------------------------

interface DisplayReader {
    /** Saved inside the layout (DigitLayout.readerId). Never change an id once it has shipped. */
    val id: String

    /** false = skipped everywhere. */
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
    val all: List<DisplayReader> = listOf(SegmentReader)

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
