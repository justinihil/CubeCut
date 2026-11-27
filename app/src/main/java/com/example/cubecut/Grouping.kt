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

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.floor

// (4.0) Grouping the Gallery's tabs (owner, 2026-11-27): the Clips tab by session, puzzle or time band, the Sessions tab by month. Each
// group has a heading you tap to fold it. Plain functions over lists, no Android, so they are unit tested (GroupingTest).

/** Time bands are 1 s wide below this many seconds (sub-9, sub-10 … sub-20)… */
private const val ONE_SECOND_BANDS_BELOW = 20
/** …then 5 s wide below a minute (sub-25 … sub-1:00): 4x4 and 5x5 times still split, without one group per second… */
private const val FIVE_SECOND_BANDS_BELOW = 60
/** …then 10 s wide below two minutes (sub-1:10 … sub-2:00), then a minute wide (sub-3:00 …: 6x6, 7x7, BLD). */
private const val TEN_SECOND_BANDS_BELOW = 120

/** How the Clips tab is grouped. Within a group the clips keep the Sort window's order. */
enum class ClipGroup(val label: String, val prefix: String) {
    NONE("None", ""),
    SESSION("Session", "s:"),
    PUZZLE("Puzzle", "p:"),
    TIME("Time", "t:");

    companion object {
        fun from(name: String?): ClipGroup = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** One group: [key] is unique among all groups of both tabs (it is how a folded group is remembered); [title] is its heading. */
class ListGroup<T>(val key: String, val title: String, val items: List<T>)

/** One row of a grouped grid or list: a group's heading, or an item of an open group. */
sealed interface GroupRow<out T> {
    val rowKey: String

    class Heading<T>(val group: ListGroup<T>, val folded: Boolean) : GroupRow<T> {
        override val rowKey: String get() = "g_" + group.key
    }

    /** [visibleIndex]: its place among the items shown (headings and folded groups left out), as hold-and-drag ticking counts. */
    class Item<T>(val item: T, val visibleIndex: Int, override val rowKey: String) : GroupRow<T>
}

/**
 * The rows to show: for each group of [groups] its heading, then its items unless its key is in [folded]. With [headings] false (not
 * grouped: one group) only the items. [keyOf] gives each item's own row key.
 */
fun <T> rowsOf(groups: List<ListGroup<T>>, folded: Set<String>, headings: Boolean, keyOf: (T) -> String): List<GroupRow<T>> {
    val rows = ArrayList<GroupRow<T>>()
    var n = 0
    for (g in groups) {
        val shut = headings && g.key in folded
        if (headings) rows.add(GroupRow.Heading(g, shut))
        if (!shut) for (item in g.items) rows.add(GroupRow.Item(item, n++, keyOf(item)))
    }
    return rows
}

/** The items shown by [rows], in order (index = [GroupRow.Item.visibleIndex]). */
fun <T> visibleItems(rows: List<GroupRow<T>>): List<T> = rows.mapNotNull { (it as? GroupRow.Item<T>)?.item }

/** Groups [items] by [keyOf], the groups in the order their first item comes (so they follow the sort), each keeping the items' order. */
fun <T> groupsInOrder(items: List<T>, keyOf: (T) -> String, titleOf: (T) -> String): List<ListGroup<T>> {
    val byKey = LinkedHashMap<String, MutableList<T>>()
    for (item in items) byKey.getOrPut(keyOf(item)) { ArrayList() }.add(item)
    return byKey.map { (k, list) -> ListGroup(k, titleOf(list[0]), list) }
}

/**
 * The upper end of the time band of [seconds], in whole seconds: 9.99 is in sub-10, 10.00 in sub-11 (a sub-10 is below 10). The bands
 * widen with the time (see the constants above), so one rule fits every puzzle.
 */
fun timeBandUpper(seconds: Double): Int {
    val s = if (seconds.isNaN() || seconds < 0) 0.0 else seconds
    val width = when {
        s < ONE_SECOND_BANDS_BELOW -> 1
        s < FIVE_SECOND_BANDS_BELOW -> 5
        s < TEN_SECOND_BANDS_BELOW -> 10
        else -> 60
    }
    return (floor(s / width).toInt() + 1) * width
}

/** "Sub-10", "Sub-25", "Sub-1:10", "Sub-3:00". */
fun timeBandTitle(upper: Int): String = if (upper < 60) "Sub-$upper" else "Sub-${upper / 60}:${"%02d".format(upper % 60)}"

/** The title of the group of clips with no time (a DNF, or a time CubeCut couldn't read). */
const val NO_TIME_GROUP = "No time"

/** Groups by time band, fastest first ([slowestFirst]: slowest first), clips with no time ([secondsOf] null) last. */
fun <T> groupByTime(items: List<T>, slowestFirst: Boolean, secondsOf: (T) -> Double?): List<ListGroup<T>> {
    val timed = items.filter { secondsOf(it) != null }.groupBy { timeBandUpper(secondsOf(it)!!) }
    val bands = if (slowestFirst) timed.keys.sortedDescending() else timed.keys.sorted()
    val groups = bands.map { ListGroup(ClipGroup.TIME.prefix + it, timeBandTitle(it), timed.getValue(it)) }
    val untimed = items.filter { secondsOf(it) == null }
    return if (untimed.isEmpty()) groups else groups + ListGroup(ClipGroup.TIME.prefix + "none", NO_TIME_GROUP, untimed)
}

/** Groups by puzzle: the puzzles in the tag window's order ([PUZZLES]), then typed ones A-Z, then the clips not tagged ([puzzleOf] ""). */
fun <T> groupByPuzzle(items: List<T>, puzzleOf: (T) -> String): List<ListGroup<T>> {
    val byPuzzle = items.groupBy(puzzleOf)
    val order = byPuzzle.keys.sortedWith(compareBy<String>({ it.isEmpty() }, { PUZZLES.indexOf(it).let { i -> if (i < 0) PUZZLES.size else i } }, { it.lowercase() }))
    return order.map { ListGroup(ClipGroup.PUZZLE.prefix + it, it.ifEmpty { "Not tagged" }, byPuzzle.getValue(it)) }
}

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"
)

/** The group key of the month of [ms] in [zone]: "m:2026-11". */
fun monthKey(ms: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
    return "m:%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
}

/** "November 2026" for "m:2026-11" (the app's text is English). */
fun monthTitle(key: String): String {
    val parts = key.removePrefix("m:").split('-')
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return key
    return "${MONTHS.getOrElse(month - 1) { "?" }} ${parts[0]}"
}
