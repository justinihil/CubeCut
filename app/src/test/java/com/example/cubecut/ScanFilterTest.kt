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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** (4.0) The Sessions tab's scan filter: which videos count as not scanned, timer not found, or with places to check. */
class ScanFilterTest {
    private fun video(lastScanMs: Long = 0L, scanState: String = "", kind: String = KIND_SESSION) = Footage(
        id = 1L, uri = "", displayName = "a.mp4", sizeBytes = 1L, durationMs = 60_000L, title = "a", dateTakenMs = 0L, addedMs = 0L,
        lastScanMs = lastScanMs, beforePad = 2.5f, afterPad = 2.5f, scrambleAll = true, cutCount = 0, timerFrameMs = -1L,
        timerCorners = "", timerTyped = "", trashedMs = 0L, kind = kind, scanState = scanState
    )

    @Test
    fun notScanned_isNeverScanned_andNotTimerNotFound_norASingleSolve() {
        assertTrue(scanFilterMatches(ScanFilter.NOT_SCANNED, video(), null, 0))
        assertFalse(scanFilterMatches(ScanFilter.NOT_SCANNED, video(lastScanMs = 5L), null, 0))
        assertFalse(scanFilterMatches(ScanFilter.NOT_SCANNED, video(scanState = SCAN_NO_TIMER), null, 0))
        assertFalse(scanFilterMatches(ScanFilter.NOT_SCANNED, video(kind = KIND_SINGLE), null, 0))
    }

    @Test
    fun timerNotFound_andToCheck() {
        assertTrue(scanFilterMatches(ScanFilter.NO_TIMER, video(scanState = SCAN_NO_TIMER), null, 0))
        assertFalse(scanFilterMatches(ScanFilter.NO_TIMER, video(), null, 0))
        assertTrue(scanFilterMatches(ScanFilter.TO_CHECK, video(lastScanMs = 5L), null, 2))
        assertFalse(scanFilterMatches(ScanFilter.TO_CHECK, video(lastScanMs = 5L), null, 0))
        // (owner, 2026-11-27) a solve to check counts as much as a place to check
        assertTrue(scanFilterMatches(ScanFilter.TO_CHECK, video(lastScanMs = 5L), null, 0, checks = 1))
        assertTrue(scanFilterMatches(ScanFilter.ANY, video(), null, 0))
    }
}
