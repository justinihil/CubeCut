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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class GroupingTest {
    @Test
    fun timeBandsWidenWithTheTime() {
        assertEquals(10, timeBandUpper(9.99))
        assertEquals(11, timeBandUpper(10.0))         // 10.00 is not a sub-10
        assertEquals(20, timeBandUpper(19.5))
        assertEquals(25, timeBandUpper(20.0))
        assertEquals(60, timeBandUpper(59.9))
        assertEquals(70, timeBandUpper(60.0))
        assertEquals(120, timeBandUpper(119.0))
        assertEquals(180, timeBandUpper(120.0))
        assertEquals(1, timeBandUpper(-1.0))
    }

    @Test
    fun timeBandTitles() {
        assertEquals("Sub-10", timeBandTitle(10))
        assertEquals("Sub-1:00", timeBandTitle(60))
        assertEquals("Sub-1:10", timeBandTitle(70))
        assertEquals("Sub-3:00", timeBandTitle(180))
    }

    @Test
    fun timeGroupsFastestFirstAndNoTimeLast() {
        val times = listOf(11.2, null, 9.5, 10.4, 9.1)
        val g = groupByTime(times, slowestFirst = false) { it }
        assertEquals(listOf("Sub-10", "Sub-11", "Sub-12", NO_TIME_GROUP), g.map { it.title })
        assertEquals(listOf(9.5, 9.1), g[0].items)      // the sort order stays within a group
        val slow = groupByTime(times, slowestFirst = true) { it }
        assertEquals(listOf("Sub-12", "Sub-11", "Sub-10", NO_TIME_GROUP), slow.map { it.title })
    }

    @Test
    fun puzzlesInTagOrderThenTypedThenNotTagged() {
        val g = groupByPuzzle(listOf("4x4", "", "Mirror", "3x3", "4x4", "Axis")) { it }
        assertEquals(listOf("3x3", "4x4", "Axis", "Mirror", "Not tagged"), g.map { it.title })
        assertEquals(2, g[1].items.size)
    }

    @Test
    fun groupsFollowTheFirstItem() {
        val g = groupsInOrder(listOf("b1", "a1", "b2", "c1", "a2"), { it.take(1) }, { it.take(1).uppercase() })
        assertEquals(listOf("B", "A", "C"), g.map { it.title })
        assertEquals(listOf("b1", "b2"), g[0].items)
    }

    @Test
    fun foldedGroupsShowOnlyTheirHeading() {
        val groups = listOf(ListGroup("p:a", "A", listOf("a1", "a2")), ListGroup("p:b", "B", listOf("b1")), ListGroup("p:c", "C", listOf("c1")))
        val rows = rowsOf(groups, setOf("p:b"), headings = true) { it }
        assertEquals(listOf("g_p:a", "a1", "a2", "g_p:b", "g_p:c", "c1"), rows.map { it.rowKey })
        assertEquals(listOf("a1", "a2", "c1"), visibleItems(rows))
        assertEquals(2, (rows.last() as GroupRow.Item).visibleIndex)
        // not grouped: no headings, and a folded key does nothing
        assertEquals(listOf("a1", "a2", "b1", "c1"), rowsOf(groups, setOf("p:b"), headings = false) { it }.map { it.rowKey })
    }

    @Test
    fun months() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("m:2026-11", monthKey(1_795_000_000_000L, utc))     // 2026-11-17
        assertEquals("November 2026", monthTitle("m:2026-11"))
        assertEquals("January 2027", monthTitle("m:2027-01"))
    }
}
