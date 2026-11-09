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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------
// Version 3: the Gallery. It is the first screen of the app. It shows what the phone remembers (see SessionStore.kt):
//   Clips tab   - every solve found so far, from every session, as a grid of pictures or as a list
//   Footage tab - the raw session videos, as a grid or a list
// Rules for what the Clips tab shows:
//   - a session whose video can still be opened: ALL its found solves, each tagged Exported or not
//   - a session whose video is gone: only the clips that were exported (they play from the exported file)
// Tapping a clip opens the full-screen viewer (it plays just that clip; swipe up for its details, note and social media buttons).
// Tapping a footage card opens the video in the editor; its round three-dot button opens the video's options.
// Pinch with two fingers on a grid to change the number of columns (2 to 6), like the phone's own gallery.
// Long-press a clip to start choosing clips to export (a video's "Export clips..." option opens this too): tap clips to tick them, tap
// the arrows in a clip's corner to watch it full screen (it can be ticked and its scramble switched there too), then Export.
// ---------------------------------------------------------------------------------------

/** How tall a picture cell is compared to its width (3 wide : 4 tall). Change this one number to reshape the whole grid. */
private const val THUMB_ASPECT = 0.75f

/** The height in pixels of the still pictures shown in the full-screen viewer while swiping (bigger than the grid's). */
private const val VIEWER_PICTURE_HEIGHT = 900

private const val MIN_COLUMNS = 2
private const val MAX_COLUMNS = 6

private val AMBER = Color(0xFFFFB74D)
private val GREY = Color(0xFF757575)
private val GREEN = Color(0xFF2E7D32)
private val RED = Color(0xFFC62828)

/** A video or an exported clip that is about to be moved to the phone's trash (or brought back from it). */
class PendingTrash(val footage: Footage?, val export: ExportedClip?, val trashing: Boolean) {
    val uriString: String get() = footage?.uri ?: export?.uri ?: ""
    val name: String get() = footage?.title ?: export?.fileName ?: ""
}

/** What the Gallery should point out after loading: a video on the Footage tab, or a clip on the Clips tab. */
private class FlashTarget(val footageId: Long?, val clip: GalleryClip?)

/** What the editor is asked to open: [uri] (null = an empty editor) and optionally the solve to jump to ([seekSec] = its stop time). */
class EditorRequest(val uri: Uri?, val seekSec: Double? = null)

// ---------------------------------------------------------------------------------------
// sorting (clips can be sorted by up to three things at once: the first decides, the next one breaks ties)
// ---------------------------------------------------------------------------------------

/** One thing clips can be sorted by. "Date" means the calendar day of the session, so that sessions of the same day tie. */
enum class ClipKey(val label: String, val short: String) {
    DAY_NEWEST("Date (newest day first)", "newest day"),
    DAY_OLDEST("Date (oldest day first)", "oldest day"),
    TIME_FASTEST("Solve time (fastest first)", "fastest"),
    TIME_SLOWEST("Solve time (slowest first)", "slowest"),
    NAME("Session name A-Z", "session name"),
    ORDER("Order in the video", "video order"),
    CHECK("Needs check first", "needs check"),
    EXPORTED_FIRST("Exported first", "exported first"),
    NOT_EXPORTED_FIRST("Not exported first", "not exported first");

    companion object {
        fun parse(csv: String?): List<ClipKey> {
            val keys = (csv ?: "").split(',').mapNotNull { n -> ClipKey.entries.firstOrNull { it.name == n } }.take(3)
            return if (keys.isEmpty()) listOf(DAY_NEWEST) else keys
        }
    }
}

enum class FootageSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name A-Z"),
    MOST("Most solves"),
    BEST("Best time"),
    LONGEST("Longest video");

    companion object {
        fun from(name: String?): FootageSort = FootageSort.entries.firstOrNull { it.name == name } ?: NEWEST
    }
}

enum class ExportFilter(val label: String) {
    ALL("All clips"),
    EXPORTED("Exported only"),
    NOT_EXPORTED("Not exported only");

    companion object {
        fun from(name: String?): ExportFilter = ExportFilter.entries.firstOrNull { it.name == name } ?: ALL
    }
}

/** When a session was filmed (or, if the video doesn't say, when it was imported). */
private fun dateOf(f: Footage): Long = if (f.dateTakenMs > 0) f.dateTakenMs else f.addedMs

/** The calendar day of a session, counted in days (local time), so all sessions of one day share the same number. */
private fun dayOf(f: Footage): Long {
    val ms = dateOf(f)
    return (ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L
}

private fun comparatorFor(key: ClipKey): Comparator<GalleryClip> = when (key) {
    ClipKey.DAY_NEWEST -> compareBy<GalleryClip> { -dayOf(it.footage) }
    ClipKey.DAY_OLDEST -> compareBy<GalleryClip> { dayOf(it.footage) }
    ClipKey.TIME_FASTEST -> compareBy<GalleryClip> { it.seconds ?: Double.MAX_VALUE }
    ClipKey.TIME_SLOWEST -> compareBy<GalleryClip>({ if (it.seconds == null) 1 else 0 }, { -(it.seconds ?: 0.0) })
    ClipKey.NAME -> compareBy<GalleryClip> { it.footage.title.lowercase() }
    ClipKey.ORDER -> compareBy<GalleryClip> { it.index }
    ClipKey.CHECK -> compareBy<GalleryClip> { if (it.needsCheck) 0 else 1 }
    ClipKey.EXPORTED_FIRST -> compareBy<GalleryClip> { if (it.exported != null) 0 else 1 }
    ClipKey.NOT_EXPORTED_FIRST -> compareBy<GalleryClip> { if (it.exported == null) 0 else 1 }
}

fun sortClips(list: List<GalleryClip>, keys: List<ClipKey>): List<GalleryClip> {
    // whatever is still tied after your choices: keep each session together, newest first, and its solves in video order
    var order: Comparator<GalleryClip> = compareBy<GalleryClip>({ -dateOf(it.footage) }, { it.footage.id }, { it.index })
    for (k in keys.reversed()) {
        order = comparatorFor(k).then(order)
    }
    return list.sortedWith(order)
}

/** What a session card shows besides its title: the best time, how many solves are on, how many need a look. */
class FootageStats(val bestText: String, val best: Double?, val solves: Int, val check: Int)

fun statsOf(clips: List<GalleryClip>): FootageStats {
    val on = clips.filter { it.cut.included && !it.solveDeleted }        // (a file whose solve was deleted is not a solve any more)
    val best = on.filter { it.seconds != null }.minByOrNull { it.seconds ?: Double.MAX_VALUE }
    return FootageStats(best?.cut?.timeText ?: "", best?.seconds, on.size, clips.count { it.needsCheck })
}

fun sortFootage(list: List<Footage>, sort: FootageSort, stats: Map<Long, FootageStats>): List<Footage> = when (sort) {
    FootageSort.NEWEST -> list.sortedWith(compareBy<Footage>({ -dateOf(it) }, { it.id }))
    FootageSort.OLDEST -> list.sortedWith(compareBy<Footage>({ dateOf(it) }, { it.id }))
    FootageSort.NAME -> list.sortedWith(compareBy<Footage>({ it.title.lowercase() }, { it.id }))
    FootageSort.MOST -> list.sortedWith(compareBy<Footage>({ -(stats[it.id]?.solves ?: 0) }, { it.id }))
    FootageSort.BEST -> list.sortedWith(compareBy<Footage>({ stats[it.id]?.best ?: Double.MAX_VALUE }, { it.id }))
    FootageSort.LONGEST -> list.sortedWith(compareBy<Footage>({ -it.durationMs }, { it.id }))
}

// ---------------------------------------------------------------------------------------
// what the Gallery remembers about how you look at it
// ---------------------------------------------------------------------------------------

class GalleryUiState(private val prefs: SharedPreferences) {
    var tab by mutableStateOf(0)                                  // 0 = Clips, 1 = Footage
    var refresh by mutableStateOf(0)                              // add 1 to load the data again
    var detail by mutableStateOf<GalleryClip?>(null)              // the clip the viewer was opened on (null = viewer closed)
    var detailList by mutableStateOf<List<GalleryClip>>(emptyList())   // the clips the viewer can swipe through, in the order shown
    var skipSeconds by mutableStateOf(prefs.getInt("skip_seconds", 5).coerceIn(1, 60))
    var swipeHintSeen by mutableStateOf(prefs.getBoolean("swipe_hint_seen", false))
    var openClip by mutableStateOf<Pair<Long, Double>?>(null)     // (session id, stop time) of a clip to open as soon as the data has loaded
    var selecting by mutableStateOf(false)                        // choosing clips to export (long-press a clip, or a video's Export clips...)
    var selected by mutableStateOf<Set<String>>(emptySet())       // the chosen clips (their listKey)
    // "you were just here": once the data has loaded, the Gallery scrolls to this video or clip and lights it up briefly
    var flashFootage by mutableStateOf<Long?>(null)               // the video the editor was just closed on (Footage tab)
    var flashClip by mutableStateOf<GalleryClip?>(null)           // the clip the viewer was just closed on (Clips tab)
    var clipsGrid by mutableStateOf(prefs.getBoolean("clips_grid", true))
    var footageGrid by mutableStateOf(prefs.getBoolean("footage_grid", true))
    var clipsColumns by mutableStateOf(prefs.getInt("clips_columns", 3).coerceIn(MIN_COLUMNS, MAX_COLUMNS))
    var footageColumns by mutableStateOf(prefs.getInt("footage_columns", 2).coerceIn(MIN_COLUMNS, MAX_COLUMNS))
    var clipKeys by mutableStateOf(ClipKey.parse(prefs.getString("clip_keys", null)))
    var footageSort by mutableStateOf(FootageSort.from(prefs.getString("footage_sort", null)))
    var exportFilter by mutableStateOf(ExportFilter.from(prefs.getString("export_filter", null)))
    var checkOnly by mutableStateOf(prefs.getBoolean("check_only", false))   // show only clips that need a check
    var themeMode by mutableStateOf(ThemeMode.from(prefs.getString("theme", null)))  // light, dark or as the phone (the whole app)
    // sessions switched off in the Clips tab (everything else is shown, so a new session shows up by itself)
    var hidden by mutableStateOf(
        (prefs.getString("hidden", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()
    )

    /** Opens the viewer on [clip]; swiping left and right moves through [list]. */
    fun openViewer(list: List<GalleryClip>, clip: GalleryClip) {
        detailList = list
        detail = clip
    }

    val filterActive: Boolean get() = hidden.isNotEmpty() || exportFilter != ExportFilter.ALL || checkOnly

    /** Remember the view, sort and filter choices for the next time the app opens. */
    fun save() {
        prefs.edit()
            .putBoolean("clips_grid", clipsGrid)
            .putBoolean("footage_grid", footageGrid)
            .putInt("clips_columns", clipsColumns)
            .putInt("footage_columns", footageColumns)
            .putString("clip_keys", clipKeys.joinToString(",") { it.name })
            .putString("footage_sort", footageSort.name)
            .putString("export_filter", exportFilter.name)
            .putBoolean("check_only", checkOnly)
            .putString("theme", themeMode.name)
            .putString("hidden", hidden.joinToString(","))
            .putInt("skip_seconds", skipSeconds)
            .putBoolean("swipe_hint_seen", swipeHintSeen)
            .apply()
    }
}

/**
 * The activity a composable runs in (the screen is wrapped in other contexts on some phones). The one place this is worked out: the
 * Gallery, the editor and the theme all use it.
 */
fun Context.findActivity(): ComponentActivity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Pinch with two fingers to change the number of grid columns: fingers apart = bigger pictures (fewer columns), fingers together =
 * smaller pictures (more columns). It only reacts to two fingers; one finger still scrolls and taps as usual.
 * [onStep] is called with -1 (fewer columns) or +1 (more columns) each time the fingers have moved far enough. [onScale] follows the
 * fingers (1 = no change) so the grid can grow or shrink under them, and [onEnd] is called when they lift.
 */
private fun Modifier.pinchColumns(onStep: (Int) -> Unit, onScale: (Float) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var lastDistance = 0f
        var accumulated = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                if (lastDistance > 0f && distance > 0f) {
                    accumulated *= distance / lastDistance
                    if (accumulated > 1.3f) {
                        onStep(-1)
                        accumulated = 1f
                    } else if (accumulated < 0.77f) {
                        onStep(1)
                        accumulated = 1f
                    }
                    onScale(accumulated.coerceIn(0.8f, 1.25f))
                }
                lastDistance = distance
                event.changes.forEach { it.consume() }
            } else {
                lastDistance = 0f
                accumulated = 1f
            }
        } while (event.changes.any { it.pressed })
        onEnd()
    }
}

/** What a hold-and-drag selection is doing right now. Kept outside the screen state, because it changes with every finger move. */
private class DragSelect {
    var longPressed = false              // a long-press is in progress: the tap that Android sends when the finger lifts is ignored
    var anchor = -1                      // the clip (index in the shown list) the long-press landed on; -1 = not dragging
    var last = -1                        // the clip the finger is over now
    var ticking = true                   // true = the drag ticks clips; false = it unticks them (it started on a ticked clip)
    var base: Set<String> = emptySet()   // the ticked clips from before the drag
    var finger = Offset.Zero             // where the finger is, in the list's own coordinates
}

/**
 * Hold a clip, then drag over others to tick (or untick) all of them at once, like the phone's own gallery. Put on the list or grid
 * itself, not on the clips, so the drag can go on from one clip to the next. [indexAt] says which clip is under a point (null = none);
 * [onStart] gets the clip the long-press landed on and returns false if it can't be ticked (then the drag does nothing); [onOver]
 * gets the clip the finger has moved onto; [onSpeed] gets how fast to scroll (pixels per step; 0 = stop) while the finger is near
 * the top or bottom edge; [onEnd] is called when the finger lifts. [key] restarts the gesture when the list changes.
 */
private fun Modifier.dragToSelect(
    key: Any?,
    drag: DragSelect,
    indexAt: (Offset) -> Int?,
    viewportHeight: () -> Int,
    onStart: (Int) -> Boolean,
    onOver: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onEnd: () -> Unit
): Modifier = pointerInput(key) {
    val edge = 72.dp.toPx()
    fun finish() {
        drag.anchor = -1
        drag.longPressed = false
        onSpeed(0f)
        onEnd()
    }
    detectDragGesturesAfterLongPress(
        onDragStart = { pos ->
            drag.longPressed = true
            drag.finger = pos
            val i = indexAt(pos)
            if (i == null || !onStart(i)) drag.anchor = -1
        },
        onDrag = { change, _ ->
            if (drag.anchor >= 0) {
                change.consume()
                drag.finger = change.position
                val y = change.position.y
                val h = viewportHeight().toFloat()
                onSpeed(
                    when {
                        y > h - edge -> ((y - (h - edge)) / edge * 24f).coerceAtMost(24f)
                        y < edge -> -((edge - y) / edge * 24f).coerceAtMost(24f)
                        else -> 0f
                    }
                )
                val i = indexAt(change.position)
                if (i != null && i != drag.last) onOver(i)
            }
        },
        onDragEnd = { finish() },
        onDragCancel = { finish() }
    )
}

/**
 * Makes sure item [index] of a grid is fully on screen. If it has to scroll, the item ends up roughly in the middle. Waits (up to about a
 * second) for the grid to be laid out first, because it may only just have appeared.
 */
private suspend fun revealGridItem(state: LazyGridState, index: Int) {
    var tries = 0
    while (state.layoutInfo.totalItemsCount <= index && tries < 60) {
        delay(16)
        tries++
    }
    if (state.layoutInfo.totalItemsCount <= index) return
    val before = state.layoutInfo
    val seen = before.visibleItemsInfo.firstOrNull { it.index == index }
    if (seen != null && seen.offset.y >= before.viewportStartOffset && seen.offset.y + seen.size.height <= before.viewportEndOffset) return
    state.scrollToItem(index)
    tries = 0
    while (state.layoutInfo.visibleItemsInfo.none { it.index == index } && tries < 10) {
        delay(16)
        tries++
    }
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    // scrollToItem puts the item's row at the top: move it down to the middle (the grid stops by itself at its start)
    val shift = (info.viewportEndOffset - info.viewportStartOffset - item.size.height) / 2f - (item.offset.y - info.viewportStartOffset)
    if (shift > 1f) state.scrollBy(-shift)
}

/** The same as [revealGridItem], for a list. */
private suspend fun revealListItem(state: LazyListState, index: Int) {
    var tries = 0
    while (state.layoutInfo.totalItemsCount <= index && tries < 60) {
        delay(16)
        tries++
    }
    if (state.layoutInfo.totalItemsCount <= index) return
    val before = state.layoutInfo
    val seen = before.visibleItemsInfo.firstOrNull { it.index == index }
    if (seen != null && seen.offset >= before.viewportStartOffset && seen.offset + seen.size <= before.viewportEndOffset) return
    state.scrollToItem(index)
    tries = 0
    while (state.layoutInfo.visibleItemsInfo.none { it.index == index } && tries < 10) {
        delay(16)
        tries++
    }
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val shift = (info.viewportEndOffset - info.viewportStartOffset - item.size) / 2f - (item.offset - info.viewportStartOffset)
    if (shift > 1f) state.scrollBy(-shift)
}

// ---------------------------------------------------------------------------------------
// small pieces
// ---------------------------------------------------------------------------------------

@Composable
private fun Badge(text: String, background: Color, textColor: Color = Color.White, modifier: Modifier = Modifier) {
    Text(
        text,
        color = textColor,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Can this clip be ticked for an export? Only solves whose original video is here and that have a clip of their own. */
private fun selectable(c: GalleryClip, openable: Map<Long, Boolean>): Boolean =
    !c.exportOnly && c.index >= 0 && !c.noClip && openable[c.footage.id] == true

private fun notSelectableReason(c: GalleryClip, openable: Map<Long, Boolean>): String = when {
    c.solveDeleted -> "This solve was deleted in the editor, so it can't be exported again."
    c.exportOnly || openable[c.footage.id] != true -> "This clip's original video is missing, so it can't be exported again."
    c.noClip -> "This solve is too close to the one before. Fix it in the editor first."
    else -> "This clip can't be exported."
}

/** The round selection mark: an empty ring, or a filled circle with a tick. */
@Composable
private fun SelectMark(selected: Boolean, size: Dp) {
    val fill = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val r = w / 2f
        val c = Offset(w / 2f, h / 2f)
        if (selected) {
            drawCircle(fill, r, c)
            val tick = Path().apply {
                moveTo(w * 0.28f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.68f)
                lineTo(w * 0.74f, h * 0.36f)
            }
            drawPath(tick, Color.White, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(Color(0x55000000), r, c)
            drawCircle(Color.White, r - w * 0.06f, c, style = Stroke(width = w * 0.09f))
        }
    }
}

/** Two diagonal arrows pointing outwards: "watch this full screen". */
@Composable
private fun ExpandIcon(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.11f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(Color.White, Offset(w * x1, h * y1), Offset(w * x2, h * y2), strokeWidth = sw, cap = StrokeCap.Round)
        line(0.56f, 0.44f, 0.80f, 0.20f)       // up and right
        line(0.56f, 0.20f, 0.80f, 0.20f)
        line(0.80f, 0.20f, 0.80f, 0.44f)
        line(0.44f, 0.56f, 0.20f, 0.80f)       // down and left
        line(0.20f, 0.56f, 0.20f, 0.80f)
        line(0.20f, 0.80f, 0.44f, 0.80f)
    }
}

@Composable
private fun PreviewButton(size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x99000000))
            .clickable(onClick = onClick)
            .padding(size / 5)
    ) {
        ExpandIcon(size)
    }
}

/** What exporting the chosen solves of one session did. */
private class BatchResult(val made: Int, val skipped: Int, val problems: List<String>)

/**
 * Exports the chosen solves of one session ([indices] = their positions in the session) with the same maths as every other export, using
 * the cuts as they are saved right now (so scramble choices made while selecting count), and remembers the new files. Runs on a
 * background thread; [onClip] is told how many of this session's clips are done.
 */
private fun exportFromSession(context: Context, f: Footage, indices: Set<Int>, onClip: (Int) -> Unit): BatchResult {
    val cuts = SessionStore.loadCuts(context, f.id)
    val clips = computeClips(cuts, f.beforePad.toDouble(), f.afterPad.toDouble(), f.durationMs / 1000.0).filter { it.cutIndex in indices }
    val result = ClipExporter.export(context, Uri.parse(f.uri), clips, f.title) { i, _ -> onClip(i) }
    val now = System.currentTimeMillis()
    val records = result.files.mapNotNull { ef ->
        val cut = cuts.getOrNull(ef.clip.cutIndex)
        if (cut == null) null else ExportedClip(0L, f.id, cut.stopSec, cut.timeText, ef.uri, ef.fileName, max(0.0, cut.stopSec - ef.startSec), now)
    }
    if (records.isNotEmpty()) SessionStore.recordExports(context, f.id, records)
    return BatchResult(result.done, indices.size - clips.size, result.failed)
}

private fun timeBadgeText(clip: GalleryClip): String = if (clip.cut.timeText.isBlank()) "?" else clip.cut.timeText

/** Where the picture of a clip comes from: the exported file when the video is gone, else the video (null = nothing to take it from). */
private fun clipPictureUri(clip: GalleryClip, openable: Map<Long, Boolean>): Uri? = when {
    clip.exportOnly -> clip.exported?.let { Uri.parse(it.uri) }
    openable[clip.footage.id] == true -> Uri.parse(clip.footage.uri)
    else -> null
}

private fun clipPictureKey(clip: GalleryClip): String =
    if (clip.exportOnly && clip.exported != null) "x_${clip.exported.id}" else "c_${clip.key}"

private fun clipPictureMs(clip: GalleryClip): Long =
    if (clip.exportOnly && clip.exported != null) Math.round(clip.exported.offsetSec * 1000.0) else Math.round(clip.cut.stopSec * 1000.0)

/** "You were just here": a blue glow and border over a picture cell. [level] goes from 0 (nothing) to 1 (full). */
@Composable
private fun FlashOverlay(level: Float) {
    if (level <= 0f) return
    val blue = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .background(blue.copy(alpha = 0.22f * level))
            .border(3.dp, blue.copy(alpha = level), RoundedCornerShape(10.dp))
    )
}

/** The same "you were just here" light for a list row: a blue tint and border behind the row. */
private fun Modifier.flashRow(level: Float, blue: Color): Modifier =
    if (level <= 0f) this
    else this
        .background(blue.copy(alpha = 0.18f * level), RoundedCornerShape(8.dp))
        .border(2.dp, blue.copy(alpha = level), RoundedCornerShape(8.dp))

/**
 * One solve as a picture, with its time at the upper right. [columns] tells how crowded the grid is, so small cells show less text.
 * While clips are being chosen ([selecting]) the cell shows its selection mark and a button to watch it full screen ([onPreview]).
 * A long-press is not handled here but by the list around it (dragToSelect), so a drag can go on over the next clips.
 */
@Composable
private fun ClipCell(
    clip: GalleryClip, openable: Map<Long, Boolean>, columns: Int, selecting: Boolean, selected: Boolean, modifier: Modifier = Modifier,
    highlight: Float = 0f, onPreview: () -> Unit, onClick: () -> Unit
) {
    val canPick = selectable(clip, openable)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(THUMB_ASPECT)
            .clip(RoundedCornerShape(10.dp))
            .then(if (selecting && selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick)
    ) {
        Thumb(clipPictureKey(clip), clipPictureUri(clip, openable), clipPictureMs(clip), Modifier.fillMaxSize())
        if (!clip.cut.included || (selecting && !canPick)) Box(Modifier.fillMaxSize().background(Color(0x88000000)))
        Badge(timeBadgeText(clip), Color(0xCC000000), modifier = Modifier.align(Alignment.TopEnd).padding(if (columns >= 5) 3.dp else 6.dp))
        if (columns <= 4) {
            Column(Modifier.align(Alignment.TopStart).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (selecting && canPick) SelectMark(selected, 24.dp)
                // the clip itself still works (it plays from its exported file), so this tag is not red
                if (clip.solveDeleted) {
                    Badge("Exported", GREEN)
                    Badge("Solve deleted", GREY)
                } else if (clip.exportOnly) Badge("Export only", GREEN)
                else if (clip.exported != null) Badge("Exported", GREEN)
                if (clip.needsCheck) Badge("Check", AMBER, Color.Black)
                if (!clip.cut.included) Badge("Left over", GREY)
            }
        } else if (selecting && canPick) {
            Box(Modifier.align(Alignment.TopStart).padding(3.dp)) { SelectMark(selected, 16.dp) }
        } else if (clip.exported != null) {
            // very small cells: just a green corner mark for "exported"
            Box(Modifier.align(Alignment.TopStart).padding(3.dp).size(10.dp).background(GREEN, RoundedCornerShape(5.dp)))
        }
        if (columns <= 4) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(start = 6.dp, top = 3.dp, bottom = 3.dp, end = if (selecting) 34.dp else 6.dp)
            ) {
                Text(
                    if (clip.index >= 0) "Solve ${clip.index + 1} of ${clip.total}" else "Solve",
                    style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1
                )
                if (columns <= 3) {
                    Text(
                        clip.footage.title, style = MaterialTheme.typography.labelSmall, color = Color(0xFFDDDDDD),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    val shared = clip.sharedList
                    if (shared.isNotEmpty()) {
                        Text(
                            shared.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = Color(0xFF90CAF9),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (selecting) {
            PreviewButton(if (columns >= 5) 14.dp else 18.dp, Modifier.align(Alignment.BottomEnd).padding(4.dp), onPreview)
        }
        FlashOverlay(highlight)
    }
}

/**
 * One solve as a list row: small picture, title, a line about where it comes from, and its time. While choosing: mark and preview.
 * A long-press is handled by the list around it (dragToSelect), like in the grid.
 */
@Composable
private fun ClipRow(
    clip: GalleryClip, openable: Map<Long, Boolean>, selecting: Boolean, selected: Boolean,
    highlight: Float = 0f, onPreview: () -> Unit, onClick: () -> Unit
) {
    val canPick = selectable(clip, openable)
    Row(
        Modifier
            .fillMaxWidth()
            .flashRow(highlight, MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .alpha(if (selecting && !canPick) 0.45f else 1f)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) {
            Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) { if (canPick) SelectMark(selected, 24.dp) }
        }
        Thumb(
            clipPictureKey(clip), clipPictureUri(clip, openable), clipPictureMs(clip),
            Modifier.width(60.dp).aspectRatio(THUMB_ASPECT).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(clip.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatDate(dateOf(clip.footage)) +
                        (if (clip.index >= 0) "  |  Solve ${clip.index + 1} of ${clip.total}" else "") +
                        (if (clip.solveDeleted) "  |  exported, solve deleted" else if (clip.exportOnly) "  |  export only (raw footage gone)" else if (clip.exported != null) "  |  exported" else "  |  not exported") +
                        (if (!clip.cut.included) "  |  left over" else "") +
                        (if (clip.needsCheck) "  |  check" else "") +
                        (if (clip.sharedList.isNotEmpty()) "  |  " + clip.sharedList.joinToString(", ") else ""),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            timeBadgeText(clip),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (clip.needsCheck) AppColors.check else MaterialTheme.colorScheme.onSurface
        )
        if (selecting) {
            Spacer(Modifier.width(8.dp))
            PreviewButton(20.dp, onClick = onPreview)
        }
    }
}

private fun footageThumbKey(f: Footage): String = "f_${f.id}"

@Composable
private fun FootageCell(
    f: Footage, stats: FootageStats?, openable: Boolean, hidden: Boolean, columns: Int, modifier: Modifier = Modifier,
    highlight: Float = 0f, onMenu: () -> Unit, onClick: () -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(THUMB_ASPECT)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Thumb(footageThumbKey(f), if (openable) Uri.parse(f.uri) else null, f.durationMs / 10, Modifier.fillMaxSize())
        if (hidden) Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        Badge(
            "${stats?.solves ?: 0} " + (if (openable) "solves" else "exported"), Color(0xCC000000),
            modifier = Modifier.align(Alignment.TopEnd).padding(if (columns >= 5) 3.dp else 6.dp)
        )
        if (columns <= 4) {
            Column(Modifier.align(Alignment.TopStart).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (f.trashedMs > 0) Badge("In trash", RED) else if (!openable) Badge("Video deleted", RED)
                if ((stats?.check ?: 0) > 0) Badge("${stats?.check} to check", AMBER, Color.Black)
                if (f.lastScanMs == 0L && f.cutCount == 0) Badge("Not scanned", GREY)
                if (hidden) Badge("Not shown in Clips", GREY)
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(start = 6.dp, top = 3.dp, bottom = 3.dp, end = 38.dp)
            ) {
                Text(f.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (columns <= 3) {
                    Text(
                        formatDate(dateOf(f)) + "  |  " + formatTime(f.durationMs),
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFFDDDDDD), maxLines = 1
                    )
                }
            }
        }
        FlashOverlay(highlight)
        MoreButton(if (columns >= 5) 22.dp else 30.dp, Modifier.align(Alignment.BottomEnd).padding(4.dp), onMenu)
    }
}

@Composable
private fun FootageRow(
    f: Footage, stats: FootageStats?, openable: Boolean, hidden: Boolean, highlight: Float = 0f, onMenu: () -> Unit, onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .flashRow(highlight, MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(
            footageThumbKey(f), if (openable) Uri.parse(f.uri) else null, f.durationMs / 10,
            Modifier.width(60.dp).aspectRatio(THUMB_ASPECT).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatDate(dateOf(f)) + "  |  " + formatTime(f.durationMs) + "  |  ${stats?.solves ?: 0} " + (if (openable) "solves" else "exported") +
                        (if (stats?.best != null) "  |  best ${stats.bestText}" else "") +
                        (if ((stats?.check ?: 0) > 0) "  |  ${stats?.check} to check" else "") +
                        (if (f.trashedMs > 0) "  |  in trash" else if (!openable) "  |  video deleted" else "") +
                        (if (hidden) "  |  not shown in Clips" else ""),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
        Spacer(Modifier.width(6.dp))
        MoreButton(30.dp, onClick = onMenu)
    }
}

/** The round "more options" button with three dots, shown over a footage picture. */
@Composable
private fun MoreButton(size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(Color(0x99000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size * 0.6f)) {
            val r = this.size.width * 0.09f
            for (k in 0..2) drawCircle(Color.White, r, Offset(this.size.width / 2f, this.size.height * (0.2f + 0.3f * k)))
        }
    }
}

/** A button that opens a menu of sort choices (used for the Footage tab). */
@Composable
private fun <T> SortMenu(current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Sort  \u25BE") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, label) in options) {
                DropdownMenuItem(text = { Text(if (label == current) "\u2713 $label" else label) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

/** One line of the sort window: "Sort by" / "Then by" with a menu of things to sort by. */
@Composable
private fun LevelPicker(title: String, current: ClipKey?, allowNone: Boolean, enabled: Boolean, onPick: (ClipKey?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled) {
                Text((current?.label ?: "(none)") + "  \u25BE", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (allowNone) DropdownMenuItem(text = { Text("(none)") }, onClick = { open = false; onPick(null) })
                for (k in ClipKey.entries) {
                    DropdownMenuItem(text = { Text(k.label) }, onClick = { open = false; onPick(k) })
                }
            }
        }
    }
}

/** One active filter on the Clips tab, shown as a small rounded chip with a cross. Tapping it removes that filter. */
@Composable
private fun ActiveFilterChip(label: String, onClear: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClear)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text("\u2715", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
    }
}

/** The About window (author credit and licence notice; the licence needs this to stay in the app, see NOTICE.md). */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // the logo, about the height of the title text, then the app's name
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.cubecut_logo),
                    contentDescription = "CubeCut logo",
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("CubeCut")
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))      // a little room between the title and the credit
                // sizes from biggest to smallest: the credit and where to find the author, the description, the licence terms
                Text("Created by $AUTHOR_NAME", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(AUTHOR_FIND_ME, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Cuts a session video into one clip per solve by reading the timer. " +
                            "Everything runs on this phone. Nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                BulletLines(
                    listOf(
                        "Free software under the GNU General Public License, version 3, with no warranty.",
                        "If you share CubeCut or a changed version, you must also share the source code under the same licence.",
                        "Keep this credit in the app, and say clearly when a version is changed.",
                        "See LICENSE and NOTICE.md in the source code."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("OK") }
        }
    )
}

/** The author credit: one small line, tap it to open the About window. */
@Composable
fun AuthorCreditLine(onClick: () -> Unit) {
    Text(
        "CubeCut by $AUTHOR_NAME",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 4.dp, bottom = 2.dp)
    )
}

// ---------------------------------------------------------------------------------------
// the Gallery screen
// ---------------------------------------------------------------------------------------

@Composable
fun GalleryScreen(ui: GalleryUiState, onOpenEditor: (EditorRequest) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<GalleryData?>(null) }
    var loadError by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }                        // a short notice under the tabs (for example when something failed)
    var showAbout by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }                  // the "Recently deleted" window
    var trashData by remember { mutableStateOf<TrashData?>(null) }
    var pendingTrash by remember { mutableStateOf<PendingTrash?>(null) } // waiting for the system's confirmation
    var confirmTrashFootage by remember { mutableStateOf<Footage?>(null) }   // our own warning before moving a video to the trash
    var showFilter by remember { mutableStateOf(false) }
    var footageMenu by remember { mutableStateOf<Footage?>(null) }      // the session whose options window is open
    var renaming by remember { mutableStateOf<Footage?>(null) }
    var forgetting by remember { mutableStateOf<Footage?>(null) }
    var relinkFor by remember { mutableStateOf<Footage?>(null) }        // the session whose video is being picked again
    var askExport by remember { mutableStateOf(false) }                 // the "Export N clips?" window
    var batchDone by remember { mutableStateOf(-1) }                    // clips exported so far in a batch export (-1 = not exporting)
    var batchTotal by remember { mutableStateOf(0) }
    // ---- "you were just here": the video or clip you came back from lights up for a moment ----
    var flashTarget by remember { mutableStateOf<FlashTarget?>(null) }  // waiting to be shown (set once the data has loaded)
    var flashRun by remember { mutableStateOf(0) }                      // goes up for every new light-up
    var flashKey by remember { mutableStateOf("") }                     // which cell is lit: "c_<clip listKey>" or "f_<video id>"
    var flashLevel by remember { mutableStateOf(0f) }                   // how strongly (0 = not at all, 1 = fully)
    // ---- hold and drag to tick several clips (see dragToSelect) ----
    val drag = remember { DragSelect() }
    var dragging by remember { mutableStateOf(false) }                  // a drag is ticking clips: the list doesn't scroll by finger
    var autoScroll by remember { mutableStateOf(0f) }                   // the list scrolls by itself while the finger is near an edge
    val haptics = LocalHapticFeedback.current

    // keep the screen awake while a batch export runs
    val view = LocalView.current
    SideEffect { view.keepScreenOn = batchDone >= 0 }
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }

    // ---- choosing clips to export ----
    fun exitSelection() {
        ui.selecting = false
        ui.selected = emptySet()
    }
    fun toggleSelect(clip: GalleryClip, openable: Map<Long, Boolean>) {
        if (!selectable(clip, openable)) {
            message = notSelectableReason(clip, openable)
            return
        }
        ui.selected = if (clip.listKey in ui.selected) ui.selected - clip.listKey else ui.selected + clip.listKey
    }
    /** Shows only one session's clips and ticks the ones that aren't exported yet (the Export clips... option of a video on the Footage tab). */
    fun selectSession(id: Long, loaded: GalleryData) {
        ui.tab = 0
        ui.hidden = loaded.footage.map { it.id }.filter { it != id }.toSet()
        ui.exportFilter = ExportFilter.ALL
        ui.save()
        ui.selected = loaded.clips.filter { it.footage.id == id && it.exported == null && selectable(it, loaded.openable) }.map { it.listKey }.toSet()
        ui.selecting = true
        message = if (ui.selected.isEmpty()) "All clips of this video are already exported. Tap clips to pick them again."
        else "${ui.selected.size} new clips selected. Tap a clip to add or remove it. The corner arrows play it. Then Export."
    }
    fun runBatchExport(items: List<GalleryClip>) {
        if (batchDone >= 0 || items.isEmpty()) return
        batchDone = 0
        batchTotal = items.size
        scope.launch {
            var made = 0
            var skipped = 0
            val problems = ArrayList<String>()
            for (group in items.groupBy { it.footage.id }.values) {
                val f = group[0].footage
                val before = batchDone
                try {
                    val r = withContext(Dispatchers.IO) {
                        exportFromSession(context, f, group.map { it.index }.toSet()) { i -> batchDone = before + i }
                    }
                    made += r.made
                    skipped += r.skipped
                    problems.addAll(r.problems)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problems.add("${f.title}: ${e.message ?: e.javaClass.simpleName}")
                }
                batchDone = before + group.size
            }
            message = "Exported $made of ${items.size} clips to Movies/CubeCut." +
                    (if (skipped > 0) " $skipped had no clip (too close to the solve before)." else "") +
                    (if (problems.isNotEmpty()) " Problems: " + problems.take(3).joinToString("; ") + (if (problems.size > 3) " ..." else "") else "")
            batchDone = -1
            exitSelection()
            ui.refresh++
        }
    }
    /** Switches the scramble on or off for all the chosen clips at once (saved at once; the editor sees it too). */
    fun setScrambleForSelected(on: Boolean, chosen: List<GalleryClip>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                for (c in chosen) {
                    if (c.cut.includeScramble == on || c.index < 0) continue
                    try { SessionStore.updateCutScramble(context, c.footage.id, c.cut.stopSec, on) } catch (e: Exception) { }
                }
            }
            ui.refresh++                    // reload, so every clip shows its new setting
        }
    }
    BackHandler(enabled = ui.selecting) { exitSelection() }

    /** Tapping a video on the Footage tab opens it in the editor; a video that can't be opened shows its options instead (Reconnect). */
    fun openFootage(f: Footage, canOpen: Boolean) {
        if (canOpen) onOpenEditor(EditorRequest(Uri.parse(f.uri), null)) else footageMenu = f
    }

    // Android can't tell CubeCut when a video is deleted or restored while the app is open, so everything is checked again each time you
    // come back to the app (and the Refresh button does it on demand)
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        var firstEvent = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstEvent) firstEvent = false else ui.refresh++
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    // the two tabs are pages you can swipe between (tapping a tab moves the pager, swiping the pager moves the tab)
    val tabPager = rememberPagerState(initialPage = ui.tab) { 2 }
    LaunchedEffect(ui.tab) { if (tabPager.currentPage != ui.tab) tabPager.animateScrollToPage(ui.tab) }
    LaunchedEffect(tabPager.currentPage) { if (ui.tab != tabPager.currentPage) ui.tab = tabPager.currentPage }

    // pinching: the grid grows or shrinks under the fingers, then springs back as the columns change
    var pinchScale by remember { mutableStateOf(1f) }
    var pinchEnd by remember { mutableStateOf(0) }
    LaunchedEffect(pinchEnd) {
        if (pinchEnd > 0) animate(pinchScale, 1f, animationSpec = tween(180)) { v, _ -> pinchScale = v }
    }

    // scroll positions of the four lists (they jump back to the top whenever the order or the filter changes)
    val clipsGridState = rememberLazyGridState()
    val clipsListState = rememberLazyListState()
    val footageGridState = rememberLazyGridState()
    val footageListState = rememberLazyListState()
    LaunchedEffect(ui.clipKeys, ui.hidden, ui.exportFilter, ui.checkOnly) {
        clipsGridState.scrollToItem(0)
        clipsListState.scrollToItem(0)
    }
    LaunchedEffect(ui.footageSort) {
        footageGridState.scrollToItem(0)
        footageListState.scrollToItem(0)
    }

    // load (or reload) everything the phone remembers
    LaunchedEffect(ui.refresh) {
        try {
            val loaded = withContext(Dispatchers.IO) { SessionStore.loadGallery(context) }
            data = loaded
            loadError = ""
            // coming back from the editor with a solve to show: open its detail screen
            val request = ui.openClip
            if (request != null) {
                ui.openClip = null
                val found = loaded.clips.firstOrNull { it.footage.id == request.first && Math.abs(it.cut.stopSec - request.second) < 0.05 }
                if (found != null) {
                    ui.tab = 0
                    ui.openViewer(loaded.clips.filter { it.footage.id == found.footage.id }.sortedBy { it.index }, found)
                }
            }
            // coming back from the editor or from the clip viewer: point out where you were
            val backFromVideo = ui.flashFootage
            val backFromClip = ui.flashClip
            ui.flashFootage = null
            ui.flashClip = null
            if (backFromVideo != null && loaded.footage.any { it.id == backFromVideo }) {
                flashTarget = FlashTarget(backFromVideo, null)
                flashRun++
            } else if (backFromClip != null) {
                flashTarget = FlashTarget(null, backFromClip)
                flashRun++
            }
            // forget filter choices about sessions that no longer exist
            val known = loaded.footage.map { it.id }.toSet()
            if (ui.hidden.any { it !in known }) {
                ui.hidden = ui.hidden.filter { it in known }.toSet()
                ui.save()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "Couldn't load the saved sessions: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // Import video: pick a video, then open it in the editor (a video seen before gets its saved cuts back).
    // Reconnect video: the same picker, but the chosen video is attached to the session that lost its video, and you stay in the Gallery.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = relinkFor
        relinkFor = null
        if (uri != null) {
            SessionStore.keepAccess(context, uri)
            if (target == null) {
                onOpenEditor(EditorRequest(uri, null))
            } else {
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        try { SessionStore.relink(context, target, uri) } catch (e: Exception) { "Couldn't reconnect: ${e.message ?: e.javaClass.simpleName}" }
                    }
                    ui.refresh++
                }
            }
        }
    }

    // ---- the phone's trash: Android asks the user to confirm, then tells us the answer ----
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val p = pendingTrash
        pendingTrash = null
        if (p != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                scope.launch {
                    val stamp = if (p.trashing) System.currentTimeMillis() else 0L
                    withContext(Dispatchers.IO) {
                        try {
                            if (p.footage != null) SessionStore.setFootageTrashed(context, p.footage.id, stamp)
                            else if (p.export != null) SessionStore.setExportTrashed(context, p.export.id, stamp)
                        } catch (e: Exception) {
                        }
                    }
                    message = if (p.trashing) "Deleted \"${p.name}\". You can bring it back from Recently deleted."
                    else "Restored \"${p.name}\"."
                    ui.refresh++
                }
            } else {
                message = "Nothing was changed."
            }
        }
    }
    fun startTrash(p: PendingTrash) {
        if (Build.VERSION.SDK_INT < 30) {
            message = "Deleting from CubeCut needs Android 11 or newer."
            return
        }
        val media = SessionStore.mediaStoreUriOf(p.uriString)
        if (media == null) {
            message = "Can't delete \"${p.name}\" from here. It was picked from a folder view, and Android only allows this for videos " +
                    "picked from the Videos list. Delete it in your Files app instead."
            return
        }
        try {
            val request = MediaStore.createTrashRequest(context.contentResolver, listOf(media), p.trashing)
            pendingTrash = p
            trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            pendingTrash = null
            message = "Couldn't ask Android: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // the Recently deleted list is read again whenever it is open and something changed
    LaunchedEffect(showTrash, ui.refresh) {
        if (showTrash) {
            trashData = withContext(Dispatchers.IO) {
                try { SessionStore.loadTrash(context) } catch (e: Exception) { null }
            }
        }
    }

    val d = data
    val statsById: Map<Long, FootageStats> = remember(d) {
        if (d == null) emptyMap() else d.footage.associate { it.id to statsOf(d.clipsOf(it.id)) }
    }
    // the Clips tab's clips, filtered and sorted
    val shownClips: List<GalleryClip> = remember(d, ui.hidden, ui.exportFilter, ui.checkOnly, ui.clipKeys) {
        if (d == null) emptyList()
        else sortClips(
            d.clips.filter { c ->
                c.footage.id !in ui.hidden && (!ui.checkOnly || c.needsCheck) && when (ui.exportFilter) {
                    ExportFilter.ALL -> true
                    ExportFilter.EXPORTED -> c.exported != null
                    ExportFilter.NOT_EXPORTED -> c.exported == null
                }
            },
            ui.clipKeys
        )
    }

    // the light-up itself: scroll the video or clip into view (only if it isn't already fully on screen), then a blue glow and border
    // that comes up quickly, stays a moment and fades out (about 1.7 s in all)
    val latestData by rememberUpdatedState(d)
    val latestShown by rememberUpdatedState(shownClips)
    val latestStats by rememberUpdatedState(statsById)
    LaunchedEffect(flashRun) {
        val target = flashTarget ?: return@LaunchedEffect
        flashTarget = null
        val run = flashRun
        val loaded = latestData ?: return@LaunchedEffect
        val key: String
        val footageId = target.footageId
        val back = target.clip
        if (footageId != null) {
            val order = sortFootage(loaded.footage, ui.footageSort, latestStats)
            val i = order.indexOfFirst { it.id == footageId }
            if (i < 0) return@LaunchedEffect
            if (ui.footageGrid) revealGridItem(footageGridState, i) else revealListItem(footageListState, i)
            key = "f_$footageId"
        } else if (back != null) {
            val shown = latestShown
            // the same clip, found by its key, or else by its video and its place in the video (in case the key changed, e.g. after an export)
            var i = shown.indexOfFirst { it.listKey == back.listKey }
            if (i < 0) i = shown.indexOfFirst { it.footage.id == back.footage.id && Math.abs(it.cut.stopSec - back.cut.stopSec) < 0.05 }
            if (i < 0) return@LaunchedEffect          // filtered out of the Clips tab: nothing to point at
            if (ui.clipsGrid) revealGridItem(clipsGridState, i) else revealListItem(clipsListState, i)
            key = "c_" + shown[i].listKey
        } else {
            return@LaunchedEffect
        }
        flashKey = key
        try {
            animate(0f, 1f, animationSpec = tween(150)) { v, _ -> flashLevel = v }
            delay(650)
            animate(1f, 0f, animationSpec = tween(900)) { v, _ -> flashLevel = v }
        } finally {
            // a newer light-up may already have started: only clear this one
            if (flashRun == run) {
                flashLevel = 0f
                flashKey = ""
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 8.dp)
        ) {
            // ===== header (while choosing clips: how many are chosen, All / None, Export) =====
            if (ui.selecting && d != null) {
                val pickable = shownClips.filter { selectable(it, d.openable) }.map { it.listKey }.toSet()
                val allOn = pickable.isNotEmpty() && ui.selected.containsAll(pickable)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { exitSelection() }) { Text("\u2715", style = MaterialTheme.typography.titleLarge) }
                    Column(Modifier.weight(1f)) {
                        Text("${ui.selected.size} selected", style = MaterialTheme.typography.titleLarge)
                        // how many of the ticked clips were exported before (they would be exported again), else how to tick
                        val tickedBefore = d.clips.count { it.listKey in ui.selected && it.exported != null }
                        if (tickedBefore > 0) {
                            Text(
                                "$tickedBefore already exported",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = AppColors.check,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text("Tap or hold and drag to tick, arrows to watch", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TextButton(onClick = { ui.selected = if (allOn) ui.selected - pickable else ui.selected + pickable }) {
                        Text(if (allOn) "None" else "All")
                    }
                    Button(onClick = { askExport = true }, enabled = ui.selected.isNotEmpty() && batchDone < 0) { Text("Export") }
                }
                // one switch for the scramble of every chosen clip (each one can still differ: change it in the player)
                val chosenNow = d.clips.filter { it.listKey in ui.selected && selectable(it, d.openable) }
                if (chosenNow.isNotEmpty()) {
                    val onCount = chosenNow.count { it.cut.includeScramble }
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Include the scramble in the ${chosenNow.size} selected clips" +
                                    (if (onCount == 0 || onCount == chosenNow.size) "" else " (mixed: $onCount on)"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = onCount == chosenNow.size, onCheckedChange = { on -> setScrambleForSelected(on, chosenNow) })
                    }
                }
            } else Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.cubecut_logo),
                    contentDescription = "CubeCut logo",
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("CubeCut", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (d == null) "Loading..." else "${d.clips.size} clips in ${d.footage.size} sessions",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Box {
                    TextButton(onClick = { showMore = true }) { Text("\u22EE") }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(text = { Text("Refresh") }, onClick = { showMore = false; ui.refresh++ })
                        DropdownMenuItem(text = { Text("Recently deleted") }, onClick = { showMore = false; showTrash = true })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { showMore = false; showSettings = true })
                    }
                }
                Button(onClick = { relinkFor = null; picker.launch(arrayOf("video/*")) }) { Text("Import video") }
            }

            // ===== the two tabs =====
            TabRow(selectedTabIndex = ui.tab) {
                Tab(selected = ui.tab == 0, onClick = { ui.tab = 0 }, text = { Text("Clips") })
                Tab(selected = ui.tab == 1, onClick = { if (!ui.selecting) ui.tab = 1 }, text = { Text("Footage") })
            }

            // ===== view switch, sort and filter =====
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val grid = if (ui.tab == 0) ui.clipsGrid else ui.footageGrid
                OutlinedButton(onClick = {
                    if (ui.tab == 0) ui.clipsGrid = !ui.clipsGrid else ui.footageGrid = !ui.footageGrid
                    ui.save()
                }) { Text(if (grid) "List view" else "Grid view") }
                if (ui.tab == 0) {
                    OutlinedButton(onClick = { showSort = true }) { Text("Sort") }
                    OutlinedButton(onClick = { showFilter = true }) { Text(if (ui.filterActive) "Filter \u2022" else "Filter") }
                } else {
                    SortMenu(ui.footageSort.label, FootageSort.entries.map { it to it.label }) { ui.footageSort = it; ui.save() }
                }
            }
            if (ui.tab == 0) {
                Text(
                    "Sorted by " + ui.clipKeys.joinToString(", then ") { it.short } +
                            (if (ui.clipsGrid) "  |  pinch the grid to resize" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                // ===== what the Clips tab is filtered by: one chip per filter, tap a chip to remove that filter =====
                if (d != null && ui.filterActive) {
                    val known = d.footage.map { it.id }
                    val shownVideos = known.filter { it !in ui.hidden }
                    val videoLabel = when {
                        ui.hidden.isEmpty() -> null
                        shownVideos.isEmpty() -> "No videos"
                        shownVideos.size == 1 -> "Video: " + (d.footage.firstOrNull { it.id == shownVideos[0] }?.title ?: "1 video")
                        else -> "${shownVideos.size} of ${known.size} videos"
                    }
                    val exportLabel = when (ui.exportFilter) {
                        ExportFilter.ALL -> null
                        ExportFilter.EXPORTED -> "Exported only"
                        ExportFilter.NOT_EXPORTED -> "Not exported only"
                    }
                    val active = listOfNotNull(videoLabel, exportLabel, if (ui.checkOnly) "Needs a check" else null).size
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (videoLabel != null) ActiveFilterChip(videoLabel) { ui.hidden = emptySet(); ui.save() }
                        if (exportLabel != null) ActiveFilterChip(exportLabel) { ui.exportFilter = ExportFilter.ALL; ui.save() }
                        if (ui.checkOnly) ActiveFilterChip("Needs a check") { ui.checkOnly = false; ui.save() }
                        if (active >= 2) {
                            Text(
                                "Clear all",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        ui.hidden = emptySet()
                                        ui.exportFilter = ExportFilter.ALL
                                        ui.checkOnly = false
                                        ui.save()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
            if (message.isNotEmpty()) {
                InfoNote(
                    message,
                    modifier = Modifier.padding(bottom = 6.dp),
                    kind = kindOfMessage(message),
                    action = "OK",
                    onAction = { message = "" }
                )
            }

            // ===== the content: two pages (Clips, Footage) you can swipe between =====
            HorizontalPager(state = tabPager, userScrollEnabled = !ui.selecting, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .pinchColumns(
                            { step ->
                                if (page == 0 && ui.clipsGrid) {
                                    ui.clipsColumns = (ui.clipsColumns + step).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                                    ui.save()
                                } else if (page == 1 && ui.footageGrid) {
                                    ui.footageColumns = (ui.footageColumns + step).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                                    ui.save()
                                }
                            },
                            { scale -> pinchScale = scale },
                            { pinchEnd++ }
                        )
                        .graphicsLayer { scaleX = pinchScale; scaleY = pinchScale }
                ) {
                    if (loadError.isNotEmpty()) {
                        Text(loadError, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                    } else if (d == null) {
                        Text("Loading...", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                    } else if (d.footage.isEmpty()) {
                        InfoNote(
                            title = "Nothing here yet",
                            text = "Tap Import video, set up the timer and press Find solves. Every session you scan is kept here.",
                            modifier = Modifier.padding(12.dp)
                        )
                    } else if (page == 0) {
                        val shown = shownClips
                        // tap: watch it (or, while choosing, tick it); the corner arrows: watch it while choosing.
                        // Long-press (and drag) is handled by the grid or list itself: see dragToSelect.
                        // (the tap Android sends when the finger lifts after a long-press is ignored)
                        val onTap: (GalleryClip) -> Unit = { clip ->
                            if (!drag.longPressed) {
                                if (ui.selecting) toggleSelect(clip, d.openable) else ui.openViewer(shown, clip)
                            }
                        }
                        val onPreview: (GalleryClip) -> Unit = { clip -> if (!drag.longPressed) ui.openViewer(shown, clip) }
                        // which clip is under a point of the grid or list (null = none, e.g. the gap between two pictures)
                        fun indexAt(pos: Offset): Int? = if (ui.clipsGrid) {
                            clipsGridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                pos.x >= info.offset.x && pos.x < info.offset.x + info.size.width &&
                                        pos.y >= info.offset.y && pos.y < info.offset.y + info.size.height
                            }?.index
                        } else {
                            clipsListState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                pos.y >= info.offset && pos.y < info.offset + info.size
                            }?.index
                        }
                        fun viewportHeight(): Int =
                            if (ui.clipsGrid) clipsGridState.layoutInfo.viewportSize.height else clipsListState.layoutInfo.viewportSize.height
                        // the finger is over clip [to]: every clip from the first one to [to] is ticked (or unticked); going back undoes it
                        fun dragOver(to: Int) {
                            val a = drag.anchor
                            if (a < 0) return
                            drag.last = to
                            val range = (minOf(a, to)..maxOf(a, to)).mapNotNull { shown.getOrNull(it) }
                                .filter { selectable(it, d.openable) }
                                .map { it.listKey }.toSet()
                            ui.selected = if (drag.ticking) drag.base + range else drag.base - range
                        }
                        // a long-press on clip [i]: start choosing (or carry on), and tick it, or untick it if it was ticked
                        fun dragStart(i: Int): Boolean {
                            val clip = shown.getOrNull(i) ?: return false
                            if (!selectable(clip, d.openable)) {
                                message = notSelectableReason(clip, d.openable)
                                return false
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (!ui.selecting) {
                                ui.selected = emptySet()
                                ui.selecting = true
                            }
                            drag.base = ui.selected
                            drag.ticking = clip.listKey !in ui.selected
                            drag.anchor = i
                            dragging = true
                            dragOver(i)
                            return true
                        }
                        val dragModifier = Modifier.dragToSelect(
                            key = shown, drag = drag,
                            indexAt = { indexAt(it) }, viewportHeight = { viewportHeight() },
                            onStart = { dragStart(it) }, onOver = { dragOver(it) },
                            onSpeed = { autoScroll = it }, onEnd = { dragging = false }
                        )
                        // while the finger rests near the top or bottom edge, the list keeps scrolling and the ticked range follows it
                        LaunchedEffect(autoScroll) {
                            if (autoScroll != 0f) {
                                while (true) {
                                    if (ui.clipsGrid) clipsGridState.scrollBy(autoScroll) else clipsListState.scrollBy(autoScroll)
                                    val i = indexAt(drag.finger)
                                    if (i != null && i != drag.last) dragOver(i)
                                    delay(16)
                                }
                            }
                        }
                        if (shown.isEmpty()) {
                            Text(
                                if (ui.filterActive) "No clips match the filter. Remove a filter above, or tap Filter."
                                else "No clips yet. Open a session in the editor and press Find solves.",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)
                            )
                        } else if (ui.clipsGrid) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(ui.clipsColumns),
                                state = clipsGridState,
                                contentPadding = PaddingValues(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (ui.clipsColumns >= 5) 4.dp else 8.dp),
                                verticalArrangement = Arrangement.spacedBy(if (ui.clipsColumns >= 5) 4.dp else 8.dp),
                                userScrollEnabled = !dragging,
                                modifier = Modifier.fillMaxSize().then(dragModifier)
                            ) {
                                gridItems(shown, key = { it.listKey }) { clip ->
                                    ClipCell(
                                        clip, d.openable, ui.clipsColumns, ui.selecting, clip.listKey in ui.selected, Modifier.animateItem(),
                                        highlight = if (flashKey == "c_" + clip.listKey) flashLevel else 0f,
                                        onPreview = { onPreview(clip) }
                                    ) { onTap(clip) }
                                }
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize().then(dragModifier),
                                state = clipsListState,
                                contentPadding = PaddingValues(bottom = 8.dp),
                                userScrollEnabled = !dragging
                            ) {
                                listItems(shown, key = { it.listKey }) { clip ->
                                    ClipRow(
                                        clip, d.openable, ui.selecting, clip.listKey in ui.selected,
                                        highlight = if (flashKey == "c_" + clip.listKey) flashLevel else 0f,
                                        onPreview = { onPreview(clip) }
                                    ) { onTap(clip) }
                                }
                            }
                        }
                    } else {
                        val shown = remember(d, ui.footageSort) { sortFootage(d.footage, ui.footageSort, statsById) }
                        if (ui.footageGrid) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(ui.footageColumns),
                                state = footageGridState,
                                contentPadding = PaddingValues(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (ui.footageColumns >= 5) 4.dp else 8.dp),
                                verticalArrangement = Arrangement.spacedBy(if (ui.footageColumns >= 5) 4.dp else 8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                gridItems(shown, key = { it.id }) { f ->
                                    FootageCell(
                                        f, statsById[f.id], d.openable[f.id] == true, f.id in ui.hidden, ui.footageColumns, Modifier.animateItem(),
                                        highlight = if (flashKey == "f_${f.id}") flashLevel else 0f,
                                        onMenu = { footageMenu = f }
                                    ) { openFootage(f, d.openable[f.id] == true) }
                                }
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize(), state = footageListState, contentPadding = PaddingValues(bottom = 8.dp)) {
                                listItems(shown, key = { it.id }) { f ->
                                    FootageRow(
                                        f, statsById[f.id], d.openable[f.id] == true, f.id in ui.hidden,
                                        highlight = if (flashKey == "f_${f.id}") flashLevel else 0f,
                                        onMenu = { footageMenu = f }
                                    ) {
                                        openFootage(f, d.openable[f.id] == true)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AuthorCreditLine { showAbout = true }
        }

        // ===== the clip viewer, on top of the gallery =====
        val detail = ui.detail
        if (detail != null && d != null) {
            ClipViewer(
                list = ui.detailList,
                startKey = detail.listKey,
                allClips = d.clips,
                openable = d.openable,
                stats = statsById,
                skipSeconds = ui.skipSeconds,
                onSkipChange = { ui.skipSeconds = it; ui.save() },
                showHint = !ui.swipeHintSeen,
                onHintSeen = {
                    if (!ui.swipeHintSeen) {
                        ui.swipeHintSeen = true
                        ui.save()
                    }
                },
                onBack = { last ->
                    ui.detail = null
                    ui.flashClip = last     // point out the clip you were last on (it may not be the one you opened)
                    ui.refresh++            // pick up what was changed in the viewer (notes, switches, exports)
                },
                onOpenEditor = { c ->
                    ui.detail = null
                    onOpenEditor(EditorRequest(Uri.parse(c.footage.uri), c.cut.stopSec))
                },
                onReconnect = { f ->
                    relinkFor = f
                    picker.launch(arrayOf("video/*"))
                },
                onShowSession = { f ->
                    ui.detail = null
                    ui.refresh++
                    ui.hidden = d.footage.map { it.id }.filter { it != f.id }.toSet()
                    ui.save()
                    ui.tab = 0
                },
                onTrashExport = { e ->
                    ui.detail = null
                    startTrash(PendingTrash(null, e, true))
                },
                selection = if (ui.selecting) ui.selected else null,
                onToggleSelect = { c -> toggleSelect(c, d.openable) }
            )
        }

        // ===== "Export N clips?" =====
        if (askExport && d != null) {
            val chosen = shownClips.filter { it.listKey in ui.selected && selectable(it, d.openable) } +
                    d.clips.filter { it.listKey in ui.selected && selectable(it, d.openable) && it !in shownClips }
            val again = chosen.count { it.exported != null }
            val fresh = chosen.filter { it.exported == null }
            // Nothing exported before: a plain "Export N clips?". Some exported before: the title says so, a note says what exporting
            // again does, and the choices are stacked buttons, the new clips only first.
            AlertDialog(
                onDismissRequest = { askExport = false },
                title = {
                    Text(
                        when {
                            again == 0 -> "Export ${chosen.size} ${if (chosen.size == 1) "clip" else "clips"}?"
                            fresh.isEmpty() -> "${if (again == 1) "This clip was" else "All $again were"} exported before"
                            else -> "$again of ${chosen.size} were exported before"
                        }
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (again > 0) {
                            InfoNote(
                                kind = NoteKind.CHECK,
                                text = "Exporting again makes a second copy. The first files stay."
                            )
                        }
                        Text("Saved in Movies/CubeCut.", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                        if (again == 0) {
                            Button(onClick = { askExport = false; runBatchExport(chosen) }, enabled = chosen.isNotEmpty()) { Text("Export") }
                        } else {
                            if (fresh.isNotEmpty()) {
                                Button(onClick = { askExport = false; runBatchExport(fresh) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Export only the ${fresh.size} new")
                                }
                            }
                            OutlinedButton(onClick = { askExport = false; runBatchExport(chosen) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (fresh.isEmpty()) "Export again" else "Export all ${chosen.size}, $again again")
                            }
                        }
                        TextButton(onClick = { askExport = false }) { Text("Cancel") }
                    }
                }
            )
        }

        // ===== batch export progress (blocks the screen until it is done) =====
        if (batchDone >= 0) {
            AlertDialog(
                onDismissRequest = { },
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("Exporting clips") },
                text = {
                    Column {
                        Text("Clip ${min(batchDone + 1, batchTotal)} of $batchTotal", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { if (batchTotal > 0) batchDone.toFloat() / batchTotal else 0f },
                            modifier = Modifier.fillMaxWidth().height(10.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Keep CubeCut open until it is done.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { }
            )
        }

        // ===== settings =====
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Settings") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        Text("Appearance", style = MaterialTheme.typography.labelLarge)
                        for (m in ThemeMode.entries) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { ui.themeMode = m; ui.save() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = ui.themeMode == m, onClick = { ui.themeMode = m; ui.save() })
                                Text(m.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { ui.swipeHintSeen = false; ui.save(); showSettings = false }) {
                            Text("Show the \"swipe up for more\" hint again")
                        }
                    }
                },
                confirmButton = { Button(onClick = { showSettings = false }) { Text("Done") } }
            )
        }

        // ===== sort window (up to three levels) =====
        if (showSort) {
            val k1 = ui.clipKeys.getOrNull(0) ?: ClipKey.DAY_NEWEST
            val k2 = ui.clipKeys.getOrNull(1)
            val k3 = ui.clipKeys.getOrNull(2)
            AlertDialog(
                onDismissRequest = { showSort = false },
                title = { Text("Sort clips") },
                text = {
                    Column {
                        Text(
                            "Clips follow the first choice. Ties follow the next one. " +
                                    "Example: Date, then Solve time = each day's solves, fastest first.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LevelPicker("Sort by", k1, false, true) { pick ->
                            ui.clipKeys = listOfNotNull(pick, k2, k3); ui.save()
                        }
                        LevelPicker("Then by", k2, true, true) { pick ->
                            ui.clipKeys = if (pick == null) listOf(k1) else listOfNotNull(k1, pick, k3); ui.save()
                        }
                        LevelPicker("Then by", k3, true, k2 != null) { pick ->
                            ui.clipKeys = listOfNotNull(k1, k2, pick); ui.save()
                        }
                    }
                },
                confirmButton = { Button(onClick = { showSort = false }) { Text("Done") } }
            )
        }

        // ===== filter window: which sessions, exported or not =====
        if (showFilter && d != null) {
            AlertDialog(
                onDismissRequest = { showFilter = false },
                title = { Text("Filter clips") },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("Show clips that are", style = MaterialTheme.typography.labelLarge)
                        for (opt in ExportFilter.entries) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { ui.exportFilter = opt; ui.save() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = ui.exportFilter == opt, onClick = { ui.exportFilter = opt; ui.save() })
                                Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { ui.checkOnly = !ui.checkOnly; ui.save() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = ui.checkOnly, onCheckedChange = { ui.checkOnly = it; ui.save() })
                            Text("Only clips that need a check", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Show clips from these videos", style = MaterialTheme.typography.labelLarge)
                        Row {
                            TextButton(onClick = { ui.hidden = emptySet(); ui.save() }) { Text("All") }
                            TextButton(onClick = { ui.hidden = d.footage.map { it.id }.toSet(); ui.save() }) { Text("None") }
                        }
                        for (f in sortFootage(d.footage, FootageSort.NEWEST, statsById)) {
                            val on = f.id !in ui.hidden
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        ui.hidden = if (on) ui.hidden + f.id else ui.hidden - f.id
                                        ui.save()
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = on, onCheckedChange = {
                                    ui.hidden = if (on) ui.hidden + f.id else ui.hidden - f.id
                                    ui.save()
                                })
                                Column {
                                    Text(f.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        formatDate(dateOf(f)) + "  |  ${statsById[f.id]?.solves ?: 0} " +
                                                (if (d.openable[f.id] == true) "solves" else "exported"),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = { Button(onClick = { showFilter = false }) { Text("Done") } }
            )
        }

        // ===== options for one session =====
        val menuFootage = footageMenu
        if (menuFootage != null && d != null) {
            val canOpenIt = d.openable[menuFootage.id] == true
            val st = statsById[menuFootage.id]
            AlertDialog(
                onDismissRequest = { footageMenu = null },
                title = { Text(menuFootage.title) },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            formatDate(dateOf(menuFootage)) + "  |  " + formatTime(menuFootage.durationMs) + "\n" +
                                    (if (canOpenIt) "${st?.solves ?: 0} solves, ${menuFootage.cutCount} cuts saved" else "${st?.solves ?: 0} exported clips") +
                                    (if (st?.best != null) ", best ${st.bestText}" else "") + "\n" +
                                    (if (menuFootage.lastScanMs > 0) "Last scanned ${formatDate(menuFootage.lastScanMs)}" else "Not scanned yet") +
                                    "\nFile: ${menuFootage.displayName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (!canOpenIt) {
                            Spacer(Modifier.height(8.dp))
                            InfoNote(
                                kind = NoteKind.CHECK,
                                title = "Can't open the video",
                                text = "Only exported clips are shown. Your saved cuts are safe. Tap Reconnect video below and pick the original to bring every clip back."
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        // the Clips tab then shows only this video's clips; the "Video: ..." chip there undoes it
                        TextButton(onClick = {
                            ui.hidden = d.footage.map { it.id }.filter { it != menuFootage.id }.toSet()
                            ui.save()
                            ui.tab = 0
                            footageMenu = null
                        }) { Text("See its clips") }
                        if (!canOpenIt) {
                            TextButton(onClick = {
                                relinkFor = menuFootage
                                footageMenu = null
                                picker.launch(arrayOf("video/*"))
                            }) { Text("Reconnect video") }
                        }
                        TextButton(enabled = canOpenIt, onClick = {
                            footageMenu = null
                            selectSession(menuFootage.id, d)
                        }) { Text("Export clips...") }
                        if (menuFootage.trashedMs > 0) {
                            TextButton(onClick = { footageMenu = null; startTrash(PendingTrash(menuFootage, null, false)) }) { Text("Restore video") }
                        }
                        TextButton(onClick = { renaming = menuFootage; footageMenu = null }) { Text("Rename") }

                        // the two that remove things: apart, at the bottom, in red (each one warns first)
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))
                        if (menuFootage.trashedMs == 0L && canOpenIt && Build.VERSION.SDK_INT >= 30) {
                            DangerButton("Delete video") { footageMenu = null; confirmTrashFootage = menuFootage }
                            Spacer(Modifier.height(8.dp))
                        }
                        DangerButton("Forget this session") { forgetting = menuFootage; footageMenu = null }
                    }
                },
                confirmButton = { Button(onClick = { footageMenu = null }) { Text("Close") } }
            )
        }

        // ===== warning before the original video is deleted (moved to the phone's trash: it can be restored) =====
        val trashingFootage = confirmTrashFootage
        if (trashingFootage != null) {
            val its = d?.clipsOf(trashingFootage.id).orEmpty()
            val exportedN = its.count { it.exported != null }
            val notExportedN = its.count { it.cut.included && it.exported == null && !it.exportOnly }
            AlertDialog(
                onDismissRequest = { confirmTrashFootage = null },
                title = { Text("Delete the original video?") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        FootageHeader(trashingFootage, d?.openable?.get(trashingFootage.id) == true)
                        Spacer(Modifier.height(12.dp))
                        if (exportedN > 0) EffectLine(Effect.STAYS, "${clipsText(exportedN, "exported clip")} ${if (exportedN == 1) "stays and keeps" else "stay and keep"} playing")
                        if (notExportedN > 0) EffectLine(Effect.GOES, "${clipsText(notExportedN, "clip")} not exported ${if (notExportedN == 1) "leaves" else "leave"} the Gallery")
                        EffectLine(Effect.UNDO, "Restore it within about 30 days. After that it's gone for good.")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "It goes to the phone's trash. Android asks you to confirm next.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { confirmTrashFootage = null; startTrash(PendingTrash(trashingFootage, null, true)) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger, contentColor = Color.White)
                    ) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmTrashFootage = null }) { Text("Cancel") } }
            )
        }

        // ===== Recently deleted: what CubeCut moved to the trash, with a way back =====
        if (showTrash) {
            val td = trashData
            AlertDialog(
                onDismissRequest = { showTrash = false },
                title = { Text("Recently deleted") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        InfoNote(
                            "Android keeps trashed items about 30 days, then deletes them for good. " +
                                    "Restored one in another app? Tap Already restored to clear it here."
                        )
                        if (td == null) {
                            Text("Loading...", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        } else if (td.videos.isEmpty() && td.clips.isEmpty()) {
                            Text("Nothing here.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        } else {
                            if (td.videos.isNotEmpty()) {
                                Text("Original videos", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                                for (v in td.videos) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(v.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "Deleted ${formatDate(v.trashedMs)}  |  ${formatTime(v.durationMs)}",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Button(onClick = { startTrash(PendingTrash(v, null, false)) }) { Text("Restore") }
                                            TextButton(onClick = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { try { SessionStore.setFootageTrashed(context, v.id, 0L) } catch (e: Exception) { } }
                                                    ui.refresh++
                                                }
                                            }) { Text("Already restored", style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                            if (td.clips.isNotEmpty()) {
                                Text("Exported clips", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                                for (t in td.clips) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(t.clip.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "${t.footageTitle}  |  deleted ${formatDate(t.clip.trashedMs)}",
                                                style = MaterialTheme.typography.labelSmall, maxLines = 2
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Button(onClick = { startTrash(PendingTrash(null, t.clip, false)) }) { Text("Restore") }
                                            TextButton(onClick = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { try { SessionStore.setExportTrashed(context, t.clip.id, 0L) } catch (e: Exception) { } }
                                                    ui.refresh++
                                                }
                                            }) { Text("Already restored", style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { Button(onClick = { showTrash = false }) { Text("Close") } }
            )
        }

        val renameTarget = renaming
        if (renameTarget != null) {
            var text by remember(renameTarget.id) { mutableStateOf(renameTarget.title) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename session") },
                text = {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Title") })
                },
                confirmButton = {
                    Button(onClick = {
                        val newTitle = text
                        renaming = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try { SessionStore.renameFootage(context, renameTarget.id, newTitle) } catch (e: Exception) { }
                            }
                            ui.refresh++
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
            )
        }

        val forgetTarget = forgetting
        if (forgetTarget != null) {
            val its = d?.clipsOf(forgetTarget.id).orEmpty()
            val exportedN = its.count { it.exported != null }
            AlertDialog(
                onDismissRequest = { forgetting = null },
                title = { Text("Forget this session?") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        FootageHeader(forgetTarget, d?.openable?.get(forgetTarget.id) == true)
                        Spacer(Modifier.height(12.dp))
                        if (forgetTarget.cutCount > 0) EffectLine(Effect.GOES, "${clipsText(forgetTarget.cutCount, "saved solve")}, with your fixes and notes")
                        else EffectLine(Effect.GOES, "The session (it has no solves yet)")
                        if (exportedN > 0) EffectLine(Effect.GOES, "${clipsText(exportedN, "exported clip")} ${if (exportedN == 1) "leaves" else "leave"} the Clips list")
                        EffectLine(Effect.STAYS, "The video stays on your phone")
                        if (exportedN > 0) EffectLine(Effect.STAYS, "The exported files stay in Movies/CubeCut")
                        Spacer(Modifier.height(10.dp))
                        InfoNote(kind = NoteKind.PROBLEM, title = "Can't be undone", text = "Importing the video again starts from zero.")
                    }
                },
                confirmButton = {
                    Button(colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger, contentColor = Color.White), onClick = {
                        forgetting = null
                        scope.launch {
                            val problem = withContext(Dispatchers.IO) {
                                try {
                                    // (the exported files' ids are read first: deleting the session removes their records)
                                    val exportIds = SessionStore.loadExports(context, forgetTarget.id).map { it.id }
                                    SessionStore.delete(context, forgetTarget)
                                    ThumbCache.forgetFootage(context, forgetTarget.id, exportIds)
                                    ""
                                } catch (e: Exception) {
                                    "Couldn't forget \"${forgetTarget.title}\": ${e.message ?: e.javaClass.simpleName}"
                                }
                            }
                            message = if (problem.isEmpty()) "Forgot \"${forgetTarget.title}\"." else problem
                            ui.refresh++
                        }
                    }) { Text("Forget") }
                },
                dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } }
            )
        }

        if (showAbout) AboutDialog { showAbout = false }
    }
}

// ---------------------------------------------------------------------------------------
// the clip viewer: the clip fills the screen and plays at once; swipe left or right for the other clips, swipe up for the details
// ---------------------------------------------------------------------------------------

/** The part of the video a clip exports, worked out with the export's own maths (computeClips) from the given cuts. */
private fun clipWindow(clip: GalleryClip, cuts: List<CutItem>): Pair<Double, Double> {
    val f = clip.footage
    val durSec = f.durationMs / 1000.0
    val computed = computeClips(cuts, f.beforePad.toDouble(), f.afterPad.toDouble(), durSec).firstOrNull { it.cutIndex == clip.index }
    if (computed != null) return Pair(computed.startSec, computed.endSec)
    // a cut that is switched off has no exported clip; show the stretch leading up to it instead
    return Pair(max(0.0, clip.cut.stopSec - 20.0), min(durSec, clip.cut.stopSec + f.afterPad))
}

/** A round button for one social media platform; lit when the note already mentions it. */
@Composable
private fun SocialChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * Reacts to a mostly vertical swipe (and only to that: taps and sideways swipes still reach whatever is underneath).
 * [shouldStart] is asked once, when a swipe turns out to be vertical, with true = it moves down; answering false lets the swipe go on to
 * whatever is underneath (for example the sheet's own scrolling). [onDrag] gets the finger's vertical movement in pixels (negative = up),
 * [onEnd] is called when the finger lifts after a swipe that was taken.
 */
private fun Modifier.verticalSheetDrag(shouldStart: (Boolean) -> Boolean, onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var totalX = 0f
        var totalY = 0f
        var dragging = false
        var rejected = false
        val slop = viewConfiguration.touchSlop * 0.6f           // starts a little sooner than a normal scroll
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) break
            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y
            if (!dragging && !rejected && Math.abs(totalY) > slop && Math.abs(totalY) > Math.abs(totalX) * 1.2f) {
                if (shouldStart(totalY > 0f)) dragging = true else rejected = true
            }
            if (dragging) {
                onDrag(delta.y)
                change.consume()
            }
        } while (event.changes.any { it.pressed })
        if (dragging) onEnd()
    }
}

/** The first-time hint: an arrow that keeps floating upwards over the picture, to show that a swipe up works anywhere. */
@Composable
private fun SwipeUpHint(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500))
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "\u25B2",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.offset(y = (50f - 110f * travel).dp).alpha(1f - travel * 0.85f)
        )
        Spacer(Modifier.height(70.dp))
        Text(
            "Swipe up anywhere for more",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** A full-width red button, for the actions that remove something (they always warn first). Also used by the editor. */
@Composable
fun DangerButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) { Text(label) }
}

/** The video a warning is about: its picture, title and length, so it is clear at a glance which one it is. */
@Composable
private fun FootageHeader(f: Footage, openable: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Thumb(
            footageThumbKey(f), if (openable) Uri.parse(f.uri) else null, f.durationMs / 10,
            Modifier
                .width(96.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatTime(f.durationMs) + "  |  " + f.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** What a warning line says will happen: something stays (green tick), goes (red cross) or can be brought back (circle arrow). */
enum class Effect { STAYS, GOES, UNDO }

/** One line of a warning: a coloured round badge, then a short sentence. Also used by the editor. */
@Composable
fun EffectLine(effect: Effect, text: String) {
    val color = when (effect) {
        Effect.STAYS -> AppColors.keep
        Effect.GOES -> AppColors.danger
        Effect.UNDO -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when (effect) {
                    Effect.STAYS -> "\u2713"
                    Effect.GOES -> "\u2715"
                    Effect.UNDO -> "\u21BA"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "1 exported clip", "5 exported clips" */
private fun clipsText(n: Int, thing: String): String = if (n == 1) "1 $thing" else "$n ${thing}s"

/**
 * One button of the action row in a clip's details: an icon above a short label. Greyed out when [enabled] is false, so the row always
 * has the same three buttons in the same places; tapping a greyed-out button calls [onDisabledClick] (which says why it can't be used).
 */
@Composable
private fun SheetAction(
    label: String, background: Color, content: Color, enabled: Boolean, modifier: Modifier = Modifier,
    onDisabledClick: () -> Unit = {}, icon: @Composable (Color) -> Unit, onClick: () -> Unit
) {
    val fg = if (enabled) content else content.copy(alpha = 0.38f)
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) background else background.copy(alpha = 0.5f))
            .clickable { if (enabled) onClick() else onDisabledClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon(fg)
        Spacer(Modifier.height(4.dp))
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** An arrow going down into a tray: "export", drawn by CubeCut. */
@Composable
private fun ExportIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.09f
        drawLine(color, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.60f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.30f, h * 0.40f), Offset(w * 0.5f, h * 0.60f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.70f, h * 0.40f), Offset(w * 0.5f, h * 0.60f), strokeWidth = sw, cap = StrokeCap.Round)
        val tray = Path().apply {
            moveTo(w * 0.15f, h * 0.62f)
            lineTo(w * 0.15f, h * 0.86f)
            lineTo(w * 0.85f, h * 0.86f)
            lineTo(w * 0.85f, h * 0.62f)
        }
        drawPath(tray, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A pencil: "edit", drawn by CubeCut. */
@Composable
private fun EditIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.09f
        val body = Path().apply {
            moveTo(w * 0.68f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.32f)
            lineTo(w * 0.36f, h * 0.82f)
            lineTo(w * 0.14f, h * 0.86f)
            lineTo(w * 0.18f, h * 0.64f)
            close()
        }
        drawPath(body, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.58f, h * 0.24f), Offset(w * 0.76f, h * 0.42f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** A small trash can, drawn by CubeCut. */
@Composable
fun TrashIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.085f
        drawLine(color, Offset(w * 0.16f, h * 0.28f), Offset(w * 0.84f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.62f, h * 0.16f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.38f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.62f, h * 0.16f), Offset(w * 0.62f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
        val body = Path().apply {
            moveTo(w * 0.24f, h * 0.36f)
            lineTo(w * 0.31f, h * 0.88f)
            lineTo(w * 0.69f, h * 0.88f)
            lineTo(w * 0.76f, h * 0.36f)
        }
        drawPath(body, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.42f, h * 0.48f), Offset(w * 0.44f, h * 0.76f), strokeWidth = sw * 0.8f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.58f, h * 0.48f), Offset(w * 0.56f, h * 0.76f), strokeWidth = sw * 0.8f, cap = StrokeCap.Round)
    }
}

private enum class IconKind { PLAY, PAUSE, BACK, FORWARD }

/** The player's icons, drawn by CubeCut (no icon library needed): play, pause, and the round skip arrows with their seconds inside. */
@Composable
private fun PlayerIcon(kind: IconKind, seconds: Int, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            when (kind) {
                IconKind.PLAY -> {
                    val p = Path().apply {
                        moveTo(w * 0.30f, h * 0.18f)
                        lineTo(w * 0.82f, h * 0.50f)
                        lineTo(w * 0.30f, h * 0.82f)
                        close()
                    }
                    drawPath(p, Color.White)
                }
                IconKind.PAUSE -> {
                    drawRoundRect(Color.White, Offset(w * 0.24f, h * 0.18f), Size(w * 0.18f, h * 0.64f), CornerRadius(w * 0.04f))
                    drawRoundRect(Color.White, Offset(w * 0.58f, h * 0.18f), Size(w * 0.18f, h * 0.64f), CornerRadius(w * 0.04f))
                }
                IconKind.BACK, IconKind.FORWARD -> {
                    // a nearly closed circle with an arrow head; "forward" is the same picture mirrored
                    val flip = if (kind == IconKind.FORWARD) -1f else 1f
                    scale(scaleX = flip, scaleY = 1f, pivot = Offset(w / 2f, h / 2f)) {
                        val r = w * 0.36f
                        val cx = w / 2f
                        val cy = h / 2f
                        drawArc(
                            Color.White, startAngle = -60f, sweepAngle = -300f, useCenter = false,
                            topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r),
                            style = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
                        )
                        // the arrow head sits where the arc ends (at 3 o'clock) and points up, along the circle
                        val endX = cx + r
                        val endY = cy
                        val head = Path().apply {
                            moveTo(endX, endY - w * 0.17f)
                            lineTo(endX - w * 0.13f, endY + w * 0.02f)
                            lineTo(endX + w * 0.13f, endY + w * 0.02f)
                            close()
                        }
                        drawPath(head, Color.White)
                    }
                }
            }
        }
        if (kind == IconKind.BACK || kind == IconKind.FORWARD) {
            Text("$seconds", color = Color.White, fontSize = (size.value * 0.30f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OverlayIconButton(kind: IconKind, seconds: Int, big: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x99000000))
            .clickable(onClick = onClick)
            .padding(if (big) 16.dp else 10.dp)
    ) {
        PlayerIcon(kind, seconds, if (big) 44.dp else 34.dp)
    }
}

/**
 * The player controls drawn over the picture: skip back, play or pause, skip forward (by the number of seconds chosen in Settings or with
 * the small button above the seek bar) and a seek bar. They hide the moment the clip starts playing, a tap on the picture brings them back,
 * and they hide again 4 seconds after the last tap or skip. They only come back by themselves when the clip ends.
 */
@Composable
private fun PlayerOverlay(
    player: ExoPlayer, visible: Boolean, onVisibleChange: (Boolean) -> Unit, skipSeconds: Int, onSkipChange: (Int) -> Unit
) {
    var playing by remember { mutableStateOf(player.isPlaying) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var touched by remember { mutableStateOf(0) }       // goes up whenever a control is used, so the 4 second hide timer starts again
    var skipMenu by remember { mutableStateOf(false) }
    var lastUseMs by remember { mutableStateOf(0L) }       // when a control was last used

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                // a clip that starts playing hides the controls at once; but playback also stops and starts again by itself right after a
                // skip or a seek, and that must not hide them while you are using them
                if (isPlaying && System.currentTimeMillis() - lastUseMs > 1500L) onVisibleChange(false)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onVisibleChange(true)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = max(0L, player.currentPosition)
            durationMs = max(0L, player.duration)
            delay(100)
        }
    }
    LaunchedEffect(visible, playing, touched, skipMenu) {
        if (visible && playing && !skipMenu) {
            delay(4000)                                   // after the last tap or skip; not right away
            onVisibleChange(false)
        }
    }

    if (visible) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                OverlayIconButton(IconKind.BACK, skipSeconds, false) {
                    player.seekTo(max(0L, player.currentPosition - skipSeconds * 1000L))
                    lastUseMs = System.currentTimeMillis()
                    touched++
                }
                OverlayIconButton(if (playing) IconKind.PAUSE else IconKind.PLAY, 0, true) {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                        player.play()
                    }
                    lastUseMs = System.currentTimeMillis()
                    touched++
                }
                OverlayIconButton(IconKind.FORWARD, skipSeconds, false) {
                    val target = player.currentPosition + skipSeconds * 1000L
                    player.seekTo(if (durationMs > 0) min(target, durationMs) else target)
                    lastUseMs = System.currentTimeMillis()
                    touched++
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 44.dp)
            ) {
                val top = max(1f, durationMs.toFloat())
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, top),
                    onValueChange = {
                        player.seekTo(it.toLong())
                        positionMs = it.toLong()
                        lastUseMs = System.currentTimeMillis()
                        touched++
                    },
                    valueRange = 0f..top
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTime(positionMs) + " / " + formatTime(durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    // how far the skip buttons jump: tap to choose
                    Box {
                        Text(
                            "Skip ${skipSeconds}s \u25BE",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .background(Color(0x99000000), RoundedCornerShape(12.dp))
                                .clickable { skipMenu = true; lastUseMs = System.currentTimeMillis(); touched++ }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                        DropdownMenu(expanded = skipMenu, onDismissRequest = { skipMenu = false }) {
                            for (s in listOf(1, 2, 3, 5, 10, 15, 30)) {
                                DropdownMenuItem(
                                    text = { Text(if (s == skipSeconds) "\u2713 $s seconds" else "$s seconds") },
                                    onClick = { skipMenu = false; lastUseMs = System.currentTimeMillis(); touched++; onSkipChange(s) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ClipViewer(
    list: List<GalleryClip>,
    startKey: String,
    allClips: List<GalleryClip>,
    openable: Map<Long, Boolean>,
    stats: Map<Long, FootageStats>,
    skipSeconds: Int,
    onSkipChange: (Int) -> Unit,
    showHint: Boolean,
    onHintSeen: () -> Unit,
    onBack: (GalleryClip) -> Unit,             // told which clip was showing when the viewer closed
    onOpenEditor: (GalleryClip) -> Unit,
    onReconnect: (Footage) -> Unit,
    onShowSession: (Footage) -> Unit,
    onTrashExport: (ExportedClip) -> Unit,
    selection: Set<String>? = null,                 // not null while clips are being chosen for an export: the chosen ones
    onToggleSelect: (GalleryClip) -> Unit = { }
) {
    if (list.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp

    val startIndex = remember { max(0, list.indexOfFirst { it.listKey == startKey }) }
    val pagerState = rememberPagerState(initialPage = startIndex) { list.size }
    // The clip that plays (and whose details show) only changes once a swipe has come to rest. While a finger is still moving the pages
    // around, the playing clip stays where it is, so wobbling around the halfway point doesn't restart the player again and again.
    var activePage by remember { mutableStateOf(startIndex) }
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) activePage = pagerState.currentPage
    }
    val current = list[activePage.coerceIn(0, list.size - 1)]
    val footage = current.footage

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // leaving the app (home, another app, screen off) stops the sound: pause as soon as CubeCut is no longer in front
    DisposableEffect(player) {
        val lifecycle = context.findActivity()?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // changes made here are saved at once and also kept here, because the clip objects themselves never change
    val scrambleOv = remember { mutableStateMapOf<String, Boolean>() }
    val exportedNow = remember { mutableStateMapOf<String, ExportedClip>() }
    val noteDrafts = remember { mutableStateMapOf<String, String>() }
    val noteSaved = remember { mutableStateMapOf<String, String>() }
    val checkedNow = remember { mutableStateMapOf<String, Boolean>() }      // clips marked as checked here (saved at once)
    var noteVersion by remember { mutableStateOf(0) }
    var overrideVersion by remember { mutableStateOf(0) }
    var controlsVisible by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf("") }
    var actionHint by remember { mutableStateOf("") }             // why a greyed-out button can't be used (shown for a few seconds)
    var exporting by remember { mutableStateOf(false) }
    var confirmReexport by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var loadingKey by remember { mutableStateOf("") }            // the clip that was last handed to the player
    var renderedKey by remember { mutableStateOf("") }           // the clip whose first picture the player has drawn

    fun withScramble(c: GalleryClip): Boolean = scrambleOv[c.listKey] ?: c.cut.includeScramble
    fun exportedFor(c: GalleryClip): ExportedClip? = exportedNow[c.listKey] ?: c.exported
    /**
     * All cuts of one session as they are right now (the editor's list, with the scramble choices made here). The cut of [clip] itself is
     * counted as switched on even if it is off in the editor: asking for this clip here is an explicit request for it.
     */
    fun cutsFor(clip: GalleryClip): List<CutItem> =
        allClips.filter { it.footage.id == clip.footage.id && !it.exportOnly && it.index >= 0 }
            .sortedBy { it.index }
            .map { c -> c.cut.copy(included = c.cut.included || c.index == clip.index, includeScramble = withScramble(c)) }

    /** The moment of the video a clip starts at (what the player shows first), so the still and the first frame are the same picture. */
    fun stillMsFor(c: GalleryClip): Long =
        if (c.exportOnly || c.index < 0) 0L else Math.round(clipWindow(c, cutsFor(c)).first * 1000.0)
    fun stillKeyFor(c: GalleryClip): String {
        val e = c.exported
        return if (c.exportOnly && e != null) "xv_${e.id}" else "v_${c.key}_${stillMsFor(c)}"
    }

    // The clip's still picture covers the player until the player has drawn its own first picture, then fades away. While the player has
    // not drawn it, the still is fully visible from the very first frame of the new page (no animation involved, so there is no gap).
    var stillFade by remember { mutableStateOf(1f) }
    val stillAlpha = if (renderedKey == current.listKey) stillFade else 1f
    LaunchedEffect(renderedKey, current.listKey) {
        if (renderedKey == current.listKey) {
            delay(150)                       // let the player's first picture really reach the screen before the still starts to fade
            animate(stillFade, 0f, animationSpec = tween(220)) { v, _ -> stillFade = v }
        } else {
            stillFade = 1f
        }
    }
    // make the still pictures of the neighbouring clips ready before they are swiped to
    LaunchedEffect(activePage) {
        for (offset in listOf(-1, 1)) {
            val neighbour = list.getOrNull(activePage + offset) ?: continue
            ThumbCache.get(
                context, stillKeyFor(neighbour), clipPictureUri(neighbour, openable), stillMsFor(neighbour), VIEWER_PICTURE_HEIGHT
            )
        }
    }
    val sourceOk = openable[footage.id] == true
    val playsExport = current.exportOnly && current.exported != null          // the video is gone: play the exported file instead
    val editable = sourceOk && !current.exportOnly && current.index >= 0
    val playable = playsExport || editable

    val window: Pair<Double, Double>? = remember(current.listKey, overrideVersion, allClips, sourceOk) {
        if (editable) clipWindow(current, cutsFor(current)) else null
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedKey = loadingKey
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // load the clip into the player and start it as soon as it is chosen
    LaunchedEffect(current.listKey, window, playsExport) {
        loadingKey = current.listKey
        renderedKey = ""
        val exportedFile = current.exported
        if (playsExport && exportedFile != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(exportedFile.uri)))
            player.prepare()
            player.playWhenReady = true
        } else if (window != null) {
            val range = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((window.first * 1000.0).toLong())
                .setEndPositionMs((window.second * 1000.0).toLong())
                .build()
            player.setMediaItem(MediaItem.Builder().setUri(Uri.parse(footage.uri)).setClippingConfiguration(range).build())
            player.prepare()
            player.playWhenReady = true
        } else {
            player.clearMediaItems()
        }
    }
    LaunchedEffect(current.listKey) {
        exportMessage = ""
        actionHint = ""
        confirmReexport = false
        confirmTrash = false
        controlsVisible = false          // a new clip starts without the controls on top of it
    }

    // ---- notes: kept as drafts, saved a moment after typing, when swiping to another clip, and when leaving ----
    val byKey = remember(list) { list.associateBy { it.listKey } }
    suspend fun flushNotes() {
        val pending = noteDrafts.entries
            .filter { e -> e.value != (noteSaved[e.key] ?: byKey[e.key]?.cut?.userNote ?: "") }
            .map { e -> e.key to e.value }
        for ((key, text) in pending) {
            val c = byKey[key] ?: continue
            if (c.index < 0) continue
            withContext(Dispatchers.IO) {
                try { SessionStore.updateCutNote(context, c.footage.id, c.cut.stopSec, text) } catch (e: Exception) { }
            }
            noteSaved[key] = text
        }
    }
    LaunchedEffect(noteVersion) {
        delay(500)
        flushNotes()
    }
    LaunchedEffect(activePage) { flushNotes() }
    fun leave() {
        val last = current                      // the clip on screen now (the Gallery points it out)
        scope.launch {
            flushNotes()
            onBack(last)
        }
    }

    // ---- the sheet that slides up from the bottom ----
    val sheetHeightDp = (screenHeightDp * 0.66f).dp
    val peekDp = 30.dp
    val travelPx = with(density) { (sheetHeightDp - peekDp).toPx() }
    var sheetP by remember { mutableStateOf(0f) }          // 0 = only the handle shows, 1 = fully open
    var settleTo by remember { mutableStateOf(0f) }
    var settleCount by remember { mutableStateOf(0) }
    var draggedUp by remember { mutableStateOf(true) }     // which way the finger was last moving
    val sheetScroll = rememberScrollState()
    LaunchedEffect(settleCount) {
        if (settleCount > 0) animate(sheetP, settleTo, animationSpec = tween(220)) { v, _ -> sheetP = v }
    }
    // the sheet follows the finger a little faster than the finger moves, and a short swipe is enough to open or close it
    fun dragSheet(dy: Float) {
        if (Math.abs(dy) > 0.5f) draggedUp = dy < 0f
        sheetP = (sheetP - dy * 1.6f / travelPx).coerceIn(0f, 1f)
    }
    fun releaseSheet() {
        settleTo = if (draggedUp) (if (sheetP > 0.08f) 1f else 0f) else (if (sheetP < 0.92f) 0f else 1f)
        settleCount++
    }
    fun setSheet(open: Boolean) { settleTo = if (open) 1f else 0f; settleCount++ }
    // a swipe on the picture: up opens the sheet, down closes it
    val startOnPicture: (Boolean) -> Boolean = { down -> if (down) sheetP > 0.02f else sheetP < 0.999f }
    // a swipe on the sheet itself: down closes it (only when its contents are scrolled to the top), up opens it further
    val startOnSheet: (Boolean) -> Boolean = { down -> if (down) sheetScroll.value == 0 && sheetP > 0.02f else sheetP < 0.999f }
    LaunchedEffect(sheetP > 0.3f) {
        if (sheetP > 0.3f) onHintSeen()
    }
    BackHandler { if (sheetP > 0.5f) setSheet(false) else leave() }

    fun setScramble(c: GalleryClip, scramble: Boolean) {
        scrambleOv[c.listKey] = scramble
        overrideVersion++
        scope.launch {
            withContext(Dispatchers.IO) {
                try { SessionStore.updateCutScramble(context, c.footage.id, c.cut.stopSec, scramble) } catch (e: Exception) { }
            }
        }
    }

    // ---- export this one clip (the same maths and rules as the batch export) ----
    fun doExport() {
        if (exporting) return
        val c = current
        val f = c.footage
        val cuts = cutsFor(c)
        val clip = computeClips(cuts, f.beforePad.toDouble(), f.afterPad.toDouble(), f.durationMs / 1000.0).firstOrNull { it.cutIndex == c.index }
        if (clip == null) {
            exportMessage = "This solve is too close to the one before to make a clip."
            return
        }
        val cut = cuts.getOrNull(c.index) ?: return
        exporting = true
        exportMessage = "Exporting..."
        scope.launch {
            // Writing the file and recording it are ONE background step: if you leave the player while it exports, this screen's work is
            // cancelled, but the background step still finishes, so the new file is always recorded (and shows as Exported).
            val done: Pair<ExportResult, ExportedClip?>? = withContext(Dispatchers.IO) {
                try {
                    val result = ClipExporter.export(context, Uri.parse(f.uri), listOf(clip), f.title) { _, _ -> }
                    val now = System.currentTimeMillis()
                    val records = result.files.map { ef ->
                        ExportedClip(0L, f.id, cut.stopSec, cut.timeText, ef.uri, ef.fileName, max(0.0, cut.stopSec - ef.startSec), now)
                    }
                    if (records.isNotEmpty()) {
                        try { SessionStore.recordExports(context, f.id, records) } catch (e: Exception) { }
                    }
                    Pair(result, records.firstOrNull())
                } catch (e: Exception) {
                    null
                }
            }
            if (done == null) {
                exportMessage = "The export failed."
            } else {
                exportMessage = done.first.message
                val record = done.second
                if (record != null) exportedNow[c.listKey] = record
            }
            exporting = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ===== the picture area: swipe sideways for other clips, up for the details, tap for the controls =====
        Box(
            Modifier
                .fillMaxSize()
                .verticalSheetDrag(startOnPicture, { dy -> dragSheet(dy) }, { releaseSheet() })
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val c = list[page]
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
                    contentAlignment = Alignment.Center
                ) {
                    val playing = page == activePage && playable
                    if (playing) {
                        AndroidView(
                            factory = { ctx ->
                                val pv = LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null) as PlayerView
                                pv.useController = false          // the controls are drawn by CubeCut (PlayerOverlay), not by the player
                                pv.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)    // no black cover while the player starts
                                pv.setKeepContentOnPlayerReset(true)
                                pv.player = player
                                pv
                            },
                            modifier = Modifier.fillMaxSize(),
                            // a swipe to another clip removes this view: unhook it from the player, or the player keeps it alive
                            // (and keeps telling it about every event) until the viewer closes
                            onRelease = { it.player = null }
                        )
                    }
                    // the clip's still picture. It is the SAME picture before and after the page becomes the playing one (it is never
                    // removed and made again), so it never has to load again and there is no empty (black) frame when the swipe ends.
                    // Over the player it stays until the player has drawn its first picture, then fades out.
                    // (it is kept even when fully faded, see-through, so it is not made again when the page stops playing)
                    Thumb(
                        stillKeyFor(c), clipPictureUri(c, openable), stillMsFor(c),
                        Modifier.fillMaxSize().alpha(if (playing) stillAlpha else 1f),
                        ContentScale.Fit, VIEWER_PICTURE_HEIGHT, if (playing) Color.Transparent else Color.Black,
                        placeholderKey = clipPictureKey(c)
                    )
                    if (page == activePage && !playable) {
                        Text(
                            "The video can't be opened right now.",
                            color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.background(Color(0xAA000000)).padding(8.dp)
                        )
                    }
                }
            }

            if (playable) PlayerOverlay(player, controlsVisible, { controlsVisible = it }, skipSeconds, onSkipChange)

            if (controlsVisible) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Color(0x66000000))
                        .systemBarsPadding()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { leave() }) { Text("\u2039 Back", color = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(current.title, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${activePage + 1} / ${list.size}",
                            color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // ===== while choosing clips to export: tick this one, and choose its scramble, without leaving the player =====
        if (selection != null && sheetP < 0.5f) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(top = if (controlsVisible) 56.dp else 8.dp, end = 10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (editable && !current.noClip) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onToggleSelect(current) }
                            .padding(4.dp)
                    ) {
                        SelectMark(current.listKey in selection, 34.dp)
                    }
                    val on = withScramble(current)
                    Text(
                        if (on) "Scramble: on" else "Scramble: off",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) Color(0xCC2E7D32) else Color(0xCC000000))
                            .clickable { setScramble(current, !on) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                } else {
                    Text(
                        if (current.noClip) "No clip: too close to the solve before" else "Can't be exported",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        if (showHint && sheetP < 0.05f && !controlsVisible) {
            SwipeUpHint(Modifier.align(Alignment.Center).padding(top = 120.dp))
        }

        // ===== the sheet: switches, note, export, where the clip comes from =====
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetHeightDp)
                .offset { IntOffset(0, ((1f - sheetP) * travelPx).roundToInt()) },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize().verticalSheetDrag(startOnSheet, { dy -> dragSheet(dy) }, { releaseSheet() })) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(peekDp)
                        .pointerInput(Unit) { detectTapGestures(onTap = { setSheet(sheetP < 0.5f) }) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(5.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(sheetScroll)
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 28.dp)
                ) {
                    val exportedFile = exportedFor(current)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (current.cut.timeText.isBlank()) "time unknown" else "${current.cut.timeText} s",
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            (if (current.index >= 0) "Solve ${current.index + 1} of ${current.total}  |  " else "") +
                                    "at ${formatTime(Math.round(current.cut.stopSec * 1000.0))} in the video",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(current.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)

                    // ===== what you can do with this clip: export it, open it in the editor, delete its exported file =====
                    // (all three always show, greyed out when they can't be used, so the row never changes shape)
                    val canDeleteFile = exportedFile != null && exportedFile.trashedMs == 0L && Build.VERSION.SDK_INT >= 30
                    // what a tap on a greyed-out button says (short, and says what to do instead)
                    val noVideoHint = if (current.solveDeleted) "This solve was deleted in the editor. Only its exported file is left."
                    else if (!sourceOk) "The original video is missing. Tap Reconnect video below to use it again."
                    else "This clip no longer matches a solve. Open the video in the editor from the Footage tab."
                    val noFileHint = if (exportedFile == null) "Not exported yet, so there's no file to delete.\nWrong solve? Tap Editor to fix or delete it."
                    else "Deleting files needs Android 11 or newer."
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetAction(
                            if (exporting) "Exporting..." else if (exportedFile != null) "Export again" else "Export",
                            MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary,
                            enabled = editable && !exporting, modifier = Modifier.weight(1f),
                            onDisabledClick = { if (!exporting) actionHint = noVideoHint },
                            icon = { c -> ExportIcon(c, 22.dp) }
                        ) { if (exportedFile != null) confirmReexport = true else doExport() }
                        SheetAction(
                            "Editor",
                            MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface,
                            enabled = editable, modifier = Modifier.weight(1f),
                            onDisabledClick = { actionHint = noVideoHint },
                            icon = { c -> EditIcon(c, 22.dp) }
                        ) { scope.launch { flushNotes(); onOpenEditor(current) } }
                        SheetAction(
                            "Delete file",
                            MaterialTheme.colorScheme.surfaceContainerHighest, AppColors.danger,
                            enabled = canDeleteFile, modifier = Modifier.weight(1f),
                            onDisabledClick = { actionHint = noFileHint },
                            icon = { c -> TrashIcon(c, 22.dp) }
                        ) { confirmTrash = true }
                    }
                    if (actionHint.isNotEmpty()) {
                        InfoNote(actionHint, modifier = Modifier.padding(top = 8.dp))
                        LaunchedEffect(actionHint) {
                            delay(5000)                     // the hint goes away by itself
                            actionHint = ""
                        }
                    }
                    if (exportMessage.isNotEmpty()) {
                        Text(exportMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))

                    Text(
                        if (exportedFile != null) "Exported as ${exportedFile.fileName} on ${formatDate(exportedFile.exportedMs)}"
                        else "Not exported yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (exportedFile != null) GREEN else MaterialTheme.colorScheme.onSurface
                    )
                    if (playsExport) {
                        Text("The original video is gone, so this is the exported file.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (current.needsCheck && checkedNow[current.listKey] == true) {
                        Text("Marked as checked.", style = MaterialTheme.typography.bodySmall)
                    } else if (current.needsCheck) {
                        Text(highlightCheck("check: " + current.checkText), style = MaterialTheme.typography.bodyMedium)
                        if (editable && !current.cut.reviewed) {
                            TextButton(onClick = {
                                val c = current
                                checkedNow[c.listKey] = true
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        try { SessionStore.markCutReviewed(context, c.footage.id, c.cut.stopSec) } catch (e: Exception) { }
                                    }
                                }
                            }) { Text("It's fine: mark as checked", color = AppColors.check) }
                        }
                    } else if (current.cut.note.isNotEmpty()) {
                        Text(current.cut.note, style = MaterialTheme.typography.bodySmall)
                    }

                    // ===== what the clip contains (changes here show up in the editor too) =====
                    if (editable) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Include the scramble", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = withScramble(current), onCheckedChange = { setScramble(current, it) })
                        }
                        if (window != null) {
                            Text(
                                "Clip: ${formatTime(Math.round(window.first * 1000.0))} to ${formatTime(Math.round(window.second * 1000.0))} " +
                                        "(${Math.round(window.second - window.first)} s)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (exportedFile != null) {
                            Text(
                                "Your exported file stays as it is. Exporting again makes a new file.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    // ===== note and social media buttons =====
                    Spacer(Modifier.height(10.dp))
                    val canNote = current.index >= 0
                    val note = noteDrafts[current.listKey] ?: current.cut.userNote
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Note", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (canNote) {
                            TextButton(onClick = { noteDrafts[current.listKey] = ""; noteVersion++ }, enabled = note.isNotEmpty()) { Text("Clear") }
                        }
                    }
                    if (canNote) {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { noteDrafts[current.listKey] = it; noteVersion++ },
                            placeholder = { Text("Write a note, or press a button below") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (p in SOCIAL_PLATFORMS) {
                                SocialChip(p, noteMentions(note, p)) {
                                    noteDrafts[current.listKey] = togglePlatform(note, p)
                                    noteVersion++
                                }
                            }
                        }
                    } else {
                        Text(
                            "This clip no longer matches a solve, so it can't take a note.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // ===== where it comes from =====
                    Spacer(Modifier.height(10.dp))
                    Text("From this video", style = MaterialTheme.typography.labelLarge)
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clickable(onClick = { scope.launch { flushNotes(); onShowSession(footage) } })
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Thumb(
                                footageThumbKey(footage), if (sourceOk) Uri.parse(footage.uri) else null, footage.durationMs / 10,
                                Modifier.width(54.dp).aspectRatio(THUMB_ASPECT).clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(footage.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    formatDate(dateOf(footage)) + "  |  " + formatTime(footage.durationMs) + "  |  ${stats[footage.id]?.solves ?: 0} " +
                                            (if (sourceOk) "solves" else "exported"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "File: ${footage.displayName}" + (if (!sourceOk) " (deleted or moved)" else ""),
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text("Tap to see only this video's clips", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    if (!sourceOk) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { scope.launch { flushNotes(); onReconnect(footage) } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Reconnect video") }
                        Text(
                            "Your saved cuts are safe. Pick the original video to bring every clip back.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        val trashTarget = exportedFor(current)
        if (confirmTrash && trashTarget != null) {
            AlertDialog(
                onDismissRequest = { confirmTrash = false },
                title = { Text("Delete this exported file?") },
                text = {
                    BulletLines(
                        listOf(
                            "${trashTarget.fileName} (in Movies/CubeCut) goes to the phone's trash and leaves your gallery apps. Android asks you to confirm next.",
                            "Restore it from Recently deleted within about 30 days. After that it's gone for good.",
                            "The solve, its cut and your note stay in CubeCut."
                        )
                    )
                },
                confirmButton = { Button(onClick = { confirmTrash = false; onTrashExport(trashTarget) }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } }
            )
        }

        if (confirmReexport) {
            AlertDialog(
                onDismissRequest = { confirmReexport = false },
                title = { Text("Export again?") },
                text = {
                    Text(
                        "This solve was already exported. Exporting again makes a second file in Movies/CubeCut. The first one stays.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = { Button(onClick = { confirmReexport = false; doExport() }) { Text("Export again") } },
                dismissButton = { TextButton(onClick = { confirmReexport = false }) { Text("Cancel") } }
            )
        }
    }
}