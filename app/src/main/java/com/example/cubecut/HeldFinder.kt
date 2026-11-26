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

import kotlin.math.abs

// (3.3-C) Finds a timer that shows no running time with decimals: it hides it ("solve", "...") or shows whole seconds. Nothing rises with
// the clock then, so it is found by its FINAL TIMES: a number with decimals that stands still at one place, goes away, and is another
// time when it comes back, after at least as many seconds as it says. Averages and lists change like that too, but are smaller.
// Pure logic; it gets the numbers the normal search saw on its way (TimerFinder.kt), so it costs nearly nothing.

/** A number seen in a whole picture. (Without the digits' shapes: there can be thousands of these.) */
class Sighting(val t: Double, val row: TimeRow)

/** The numbers of a picture at second [t] that could be a final time. */
fun sightingsOf(t: Double, rows: List<TimeRow>): List<Sighting> =
    rows.filter { r -> r.likeness >= HELD_LIKENESS && r.text.count { it.isDigit() } >= HELD_DIGITS }.map { Sighting(t, it.withoutShapes()) }

/** So many different times must be seen at one place. Two could be chance... */
private const val VALUES_WANTED = 3

/** ...read at least this well, and with this many digits: short ones are seen in all kinds of things. */
private const val HELD_LIKENESS = 0.75f
private const val HELD_DIGITS = 3

/** Numbers are at the same place when they are as tall, as high up and as far across, give or take this much (the last two in digit heights). */
private const val SAME_HEIGHT = 1.25
private const val SAME_PLACE_DOWN = 0.8
private const val SAME_PLACE_ACROSS = 1.2

/** A final time stands still: this long before or after it was seen, it is still the same. (A running one has changed.) */
private const val STILL_SEC = 0.15

/** A time can't come sooner after the last one than it says. A little sooner is the display's own delay. */
private const val EARLY_SEC = 0.3

private class HeldPlace(first: Sighting) {
    val seen = arrayListOf(first)
    private val row = first.row

    fun has(r: TimeRow): Boolean =
        r.dark == row.dark && r.decimals == row.decimals && r.height < SAME_HEIGHT * row.height && row.height < SAME_HEIGHT * r.height &&
                abs((r.top + r.bottom) - (row.top + row.bottom)) / 2.0 < SAME_PLACE_DOWN * row.height &&
                abs((r.left + r.right) - (row.left + row.right)) / 2.0 < SAME_PLACE_ACROSS * row.height

    /** One reading per picture. */
    val shown: List<Sighting> by lazy {
        seen.groupBy { it.t }.toSortedMap().map { (t, s) -> Sighting(t, pickRow(s.map { it.row })!!) }
    }
    val height: Double get() = shown.map { it.row.height }.sorted()[shown.size / 2]
    val values: List<String> get() = shown.map { it.row.text }.filter { shownSeconds(it) > 0 }.distinct()

    /** Does a time that went away never come back, and does every time come late enough to have been timed? A few misreads may break it. */
    fun behavesLikeATimer(): Boolean {
        val times = shown.filter { shownSeconds(it.row.text) > 0 }
        val stays = ArrayList<Pair<Sighting, Sighting>>()           // the first and the last picture of each time
        for (s in times) {
            if (stays.isNotEmpty() && stays.last().first.row.text == s.row.text) stays[stays.size - 1] = Pair(stays.last().first, s)
            else stays.add(Pair(s, s))
        }
        val texts = stays.map { it.first.row.text }
        val again = texts.size - texts.distinct().size
        val early = stays.zipWithNext().count { (a, b) -> b.first.t - a.second.t < shownSeconds(b.first.row.text) - EARLY_SEC }
        return again + early <= stays.size / 5
    }
}

/**
 * The timer among the numbers [seen] in pictures all over the video, or null. [pic] is asked for a few pictures more, to see whether the
 * times stand still. The tallest number that behaves like a timer is it. [notes] gets a line for the report.
 */
fun findHeldTimer(seen: List<Sighting>, pic: PicAt, fonts: DigitShapes, notes: MutableList<String> = ArrayList()): FoundTimer? {
    val (shown, still) = heldPlace(seen, { standsStill(it, pic, fonts) }, notes) ?: return null
    val rows = shown.map { it.row }
    val frame = pic.at(still[0].t) ?: return null
    val height = rows.map { it.height }.sorted()[rows.size / 2]
    val numbers = Area(rows.minOf { it.left }, rows.minOf { it.top }, rows.maxOf { it.right }, rows.maxOf { it.bottom })
    return FoundTimer(roomAround(rows, height, frame.w, frame.h), numbers, height, rows[0].dark, 0, rows[0].decimals, fonts, held = true)
}

/**
 * The place among the numbers [seen] whose numbers behave like a timer's final times, the tallest first, with at least [VALUES_WANTED] that
 * [standsStill]: one reading per picture there, and the ones that stand still. Null if there is none. Shared by both kinds of digits.
 */
fun heldPlace(seen: List<Sighting>, standsStill: (Sighting) -> Boolean, notes: MutableList<String>): Pair<List<Sighting>, List<Sighting>>? {
    val places = ArrayList<HeldPlace>()
    for (s in seen) {
        val p = places.firstOrNull { it.has(s.row) }
        if (p != null) p.seen.add(s) else places.add(HeldPlace(s))
    }
    for (p in places.filter { it.values.size >= VALUES_WANTED && it.behavesLikeATimer() }.sortedByDescending { it.height }) {
        val still = p.values.map { v -> p.shown.first { it.row.text == v } }.take(2 * VALUES_WANTED).filter(standsStill)
        notes.add("Final times at one place (${p.height.toInt()} px): ${p.values.take(8)}, ${still.size} stand still")
        if (still.size >= VALUES_WANTED) return Pair(p.shown, still)
    }
    return null
}

private fun standsStill(s: Sighting, pic: PicAt, fonts: DigitShapes): Boolean = listOf(-STILL_SEC, STILL_SEC).any { d ->
    val p = pic.at(s.t + d)
    p != null && FoundTimer(roomAround(listOf(s.row), s.row.height, p.w, p.h), Area(0, 0, 1, 1), s.row.height, s.row.dark, 0, s.row.decimals, fonts)
        .read(p)?.text == s.row.text
}
