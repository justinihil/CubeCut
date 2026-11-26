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

import java.util.Locale

// ---------------------------------------------------------------------------------------
// (3.2-C) Penalties: +2 (any number of times) and DNF. Pure logic, no Android imports.
// The time the timer showed stays in CutItem.timeText. The penalty is stored next to it (plus2Count, dnf), so the solve-only clip
// still starts from the raw time, which is what was filmed. Only what is SHOWN and the file name use the penalised result:
//   no penalty  11.263            file ..._Solve_03_11.263s.mp4
//   +2          13.263 (+2)       file ..._Solve_03_13.263s_plus2.mp4
//   +2 twice    15.263 (+2×2)     file ..._Solve_03_15.263s_plus2x2.mp4
//   DNF         DNF(11.263)       file ..._Solve_03_DNF_11.263s.mp4
// DNF and +2 are one or the other: choosing one clears the other (see PenaltyPicker).
// ---------------------------------------------------------------------------------------

/** Seconds one +2 adds. */
private const val PLUS2_SEC = 2.0

/** True if the solve has a penalty. */
fun hasPenalty(plus2Count: Int, dnf: Boolean): Boolean = dnf || plus2Count > 0

/**
 * [timeText] with [add] seconds added, written with the same number of decimals ("11.263" + 2 = "13.263"). From one minute on it is
 * written as m:ss ("58.50" + 2 = "1:00.50"). Unknown or unreadable time: [timeText] unchanged.
 */
fun addSeconds(timeText: String, add: Double): String {
    val raw = parseTimeSeconds(timeText) ?: return timeText
    val t = timeText.trim()
    val decimals = if (t.contains('.')) t.substringAfterLast('.').length.coerceIn(0, 3) else 0
    return formatSeconds(raw + add, decimals)
}

/** 13.263 -> "13.263", 61.5 with 2 decimals -> "1:01.50". Worked out in whole units, so 59.9995 never shows as "60.000". */
fun formatSeconds(sec: Double, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val units = Math.round(sec * scale)
    val whole = units / scale
    val frac = units % scale
    val fracText = if (decimals > 0) "." + frac.toString().padStart(decimals, '0') else ""
    return if (whole >= 60) "%d:%02d%s".format(Locale.ROOT, whole / 60, whole % 60, fracText) else "$whole$fracText"
}

/** The result in seconds: the time plus 2 s per +2, or null for a DNF or an unknown time (so it sorts last and is never "best"). */
fun penalisedSeconds(timeText: String, plus2Count: Int, dnf: Boolean): Double? {
    if (dnf) return null
    val raw = parseTimeSeconds(timeText) ?: return null
    return raw + PLUS2_SEC * plus2Count.coerceAtLeast(0)
}

/** "(+2)" or "(+2×3)". */
private fun plus2Mark(n: Int): String = if (n > 1) "(+2×$n)" else "(+2)"

/**
 * How a time with its penalty is shown, without a unit: "11.263", "13.263 (+2)", "15.263 (+2×2)", "DNF(11.263)".
 * An unknown time: "" with no penalty, "DNF" or "? (+2)" with one.
 */
fun shownTime(timeText: String, plus2Count: Int, dnf: Boolean): String {
    val t = timeText.trim()
    return when {
        dnf -> if (t.isEmpty()) "DNF" else "DNF($t)"
        plus2Count > 0 -> (if (t.isEmpty()) "?" else addSeconds(t, PLUS2_SEC * plus2Count)) + " " + plus2Mark(plus2Count)
        else -> t
    }
}

/** (4.0-F) The Clips tab's penalty filter (owner, 2026-11-26). Only penalties set by hand: the scan doesn't read them. */
enum class PenaltyFilter(val label: String) {
    ANY("Any"), WITH("With penalty"), PLUS2("+2"), DNF("DNF"), NONE("No penalty");

    fun matches(plus2Count: Int, dnf: Boolean): Boolean = when (this) {
        ANY -> true
        WITH -> plus2Count > 0 || dnf
        PLUS2 -> plus2Count > 0
        DNF -> dnf
        NONE -> plus2Count == 0 && !dnf
    }

    companion object {
        fun from(name: String?): PenaltyFilter = entries.firstOrNull { it.name == name } ?: ANY
    }
}

/** The time as a clip title says it, with "s": "11.263s", "13.263s (+2)", "DNF(11.263s)", "time unknown". */
fun titleTime(timeText: String, plus2Count: Int, dnf: Boolean): String {
    val t = timeText.trim()
    return when {
        dnf -> if (t.isEmpty()) "DNF" else "DNF(${t}s)"
        plus2Count > 0 -> (if (t.isEmpty()) "time unknown" else addSeconds(t, PLUS2_SEC * plus2Count) + "s") + " " + plus2Mark(plus2Count)
        t.isEmpty() -> "time unknown"
        else -> "${t}s"
    }
}

/** A time made safe for a file name: "1:02.345" -> "1m02.345s", "" -> "unknown" (colons are not allowed in file names). */
private fun fileTime(timeText: String): String {
    val t = timeText.trim().replace(':', 'm')
    return if (t.isEmpty()) "unknown" else t + "s"
}

/** The time part of an exported file's name: "11.263s", "13.263s_plus2", "15.263s_plus2x2", "DNF_11.263s", "unknown". */
fun fileTimePart(timeText: String, plus2Count: Int, dnf: Boolean): String = when {
    dnf -> "DNF_" + fileTime(timeText)
    plus2Count > 0 -> {
        val total = if (parseTimeSeconds(timeText) == null) "unknown" else fileTime(addSeconds(timeText, PLUS2_SEC * plus2Count))
        total + "_plus2" + (if (plus2Count > 1) "x$plus2Count" else "")
    }
    else -> fileTime(timeText)
}

/** The part of an exported file's name after "Solve_NN_": the time part, an optional " (1)" Android added, ".mp4". */
private val EXPORT_NAME = Regex("^(.*Solve_\\d+_)(.*?)( \\(\\d+\\))?\\.mp4$")

/**
 * The new name of an exported file whose penalty changed: [oldName] with only its time part replaced by [timePart], so the title,
 * tag, solve number and any " (1)" stay as they are. Null when [oldName] isn't shaped like a CubeCut export (renamed by hand): the
 * file is then left alone.
 */
fun renamedExportName(oldName: String, timePart: String): String? {
    val m = EXPORT_NAME.matchEntire(oldName) ?: return null
    return m.groupValues[1] + timePart + m.groupValues[3] + ".mp4"
}
