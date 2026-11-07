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

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

// ---------------------------------------------------------------------------------------
// Version 3: the Gallery. It is the first screen of the app. It shows what the phone remembers (see SessionStore.kt):
//   Clips tab   - every solve found so far, from every session, as a grid of pictures or as a list
//   Footage tab - the raw session videos, as a grid or a list
// Rules for what the Clips tab shows:
//   - a session whose video can still be opened: ALL its found solves, each tagged Exported or not
//   - a session whose video is gone: only the clips that were exported (they play from the exported file)
// Tapping a clip opens its detail screen (it plays just that clip, says which video it came from, and keeps your note and social
// media buttons); tapping a footage card offers "show only its clips", "open in editor", "rename" and "forget".
// Pinch with two fingers on a grid to change the number of columns (2 to 6), like the phone's own gallery.
// ---------------------------------------------------------------------------------------

/** How tall a picture cell is compared to its width (3 wide : 4 tall). Change this one number to reshape the whole grid. */
private const val THUMB_ASPECT = 0.75f

private const val MIN_COLUMNS = 2
private const val MAX_COLUMNS = 6

private val AMBER = Color(0xFFFFB74D)
private val GREY = Color(0xFF757575)
private val GREEN = Color(0xFF2E7D32)
private val RED = Color(0xFFC62828)

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
            val keys = (csv ?: "").split(',').mapNotNull { n -> values().firstOrNull { it.name == n } }.take(3)
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
        fun from(name: String?): FootageSort = values().firstOrNull { it.name == name } ?: NEWEST
    }
}

enum class ExportFilter(val label: String) {
    ALL("All clips"),
    EXPORTED("Exported only"),
    NOT_EXPORTED("Not exported only");

    companion object {
        fun from(name: String?): ExportFilter = values().firstOrNull { it.name == name } ?: ALL
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
    val on = clips.filter { it.cut.included }
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
    var detail by mutableStateOf<GalleryClip?>(null)              // the clip whose detail screen is open
    var openClip by mutableStateOf<Pair<Long, Double>?>(null)     // (session id, stop time) of a clip to open as soon as the data has loaded
    var clipsGrid by mutableStateOf(prefs.getBoolean("clips_grid", true))
    var footageGrid by mutableStateOf(prefs.getBoolean("footage_grid", true))
    var clipsColumns by mutableStateOf(prefs.getInt("clips_columns", 3).coerceIn(MIN_COLUMNS, MAX_COLUMNS))
    var footageColumns by mutableStateOf(prefs.getInt("footage_columns", 2).coerceIn(MIN_COLUMNS, MAX_COLUMNS))
    var clipKeys by mutableStateOf(ClipKey.parse(prefs.getString("clip_keys", null)))
    var footageSort by mutableStateOf(FootageSort.from(prefs.getString("footage_sort", null)))
    var exportFilter by mutableStateOf(ExportFilter.from(prefs.getString("export_filter", null)))
    // sessions switched off in the Clips tab (everything else is shown, so a new session shows up by itself)
    var hidden by mutableStateOf(
        (prefs.getString("hidden", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()
    )

    val filterActive: Boolean get() = hidden.isNotEmpty() || exportFilter != ExportFilter.ALL

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
            .putString("hidden", hidden.joinToString(","))
            .apply()
    }
}

/** The activity a composable runs in (the screen is wrapped in other contexts on some phones). */
private fun Context.findActivity(): ComponentActivity? {
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
 * [onStep] is called with -1 (fewer columns) or +1 (more columns) each time the fingers have moved far enough.
 */
private fun Modifier.pinchColumns(onStep: (Int) -> Unit): Modifier = pointerInput(Unit) {
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
                }
                lastDistance = distance
                event.changes.forEach { it.consume() }
            } else {
                lastDistance = 0f
                accumulated = 1f
            }
        } while (event.changes.any { it.pressed })
    }
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

/** One solve as a picture, with its time at the upper right. [columns] tells how crowded the grid is, so small cells show less text. */
@Composable
private fun ClipCell(clip: GalleryClip, openable: Map<Long, Boolean>, columns: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(THUMB_ASPECT)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Thumb(clipPictureKey(clip), clipPictureUri(clip, openable), clipPictureMs(clip), Modifier.fillMaxSize())
        if (!clip.cut.included) Box(Modifier.fillMaxSize().background(Color(0x88000000)))
        Badge(timeBadgeText(clip), Color(0xCC000000), modifier = Modifier.align(Alignment.TopEnd).padding(if (columns >= 5) 3.dp else 6.dp))
        if (columns <= 4) {
            Column(Modifier.align(Alignment.TopStart).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (clip.exportOnly) Badge("Video deleted", RED)
                else if (clip.exported != null) Badge("Exported", GREEN)
                if (clip.needsCheck) Badge("Check", AMBER, Color.Black)
                if (!clip.cut.included) Badge("Left out", GREY)
            }
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
                    .padding(horizontal = 6.dp, vertical = 3.dp)
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
    }
}

/** One solve as a list row: small picture, title, a line about where it comes from, and its time. */
@Composable
private fun ClipRow(clip: GalleryClip, openable: Map<Long, Boolean>, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                        (if (clip.exportOnly) "  |  video deleted, exported file" else if (clip.exported != null) "  |  exported" else "  |  not exported") +
                        (if (!clip.cut.included) "  |  left out" else "") +
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
            color = if (clip.needsCheck) AMBER else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun footageThumbKey(f: Footage): String = "f_${f.id}"

@Composable
private fun FootageCell(f: Footage, stats: FootageStats?, openable: Boolean, hidden: Boolean, columns: Int, onClick: () -> Unit) {
    Box(
        Modifier
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
                if (!openable) Badge("Video deleted", RED)
                if ((stats?.check ?: 0) > 0) Badge("${stats?.check} to check", AMBER, Color.Black)
                if (f.lastScanMs == 0L && f.cutCount == 0) Badge("Not scanned", GREY)
                if (hidden) Badge("Hidden in Clips", GREY)
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
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
    }
}

@Composable
private fun FootageRow(f: Footage, stats: FootageStats?, openable: Boolean, hidden: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
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
                        (if (!openable) "  |  video deleted" else "") +
                        (if (hidden) "  |  hidden in Clips" else ""),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
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
                for (k in ClipKey.values()) {
                    DropdownMenuItem(text = { Text(k.label) }, onClick = { open = false; onPick(k) })
                }
            }
        }
    }
}

/** The About window (author credit and licence notice; the licence needs this to stay in the app, see NOTICE.md). */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CubeCut") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Created by $AUTHOR_NAME", style = MaterialTheme.typography.bodyLarge)
                Text(AUTHOR_FIND_ME, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cuts a speedcubing session video into one clip per solve by reading the timer. " +
                            "Everything runs on this phone and nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Free software under the GNU General Public License, version 3, with no warranty. " +
                            "Anyone who shares CubeCut or a changed version of it must also share the source code under the same licence, " +
                            "keep this credit in the app, and say clearly that a changed version is changed. " +
                            "See the LICENSE and NOTICE.md files in the source code.",
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
    var showFilter by remember { mutableStateOf(false) }
    var footageMenu by remember { mutableStateOf<Footage?>(null) }      // the session whose options window is open
    var renaming by remember { mutableStateOf<Footage?>(null) }
    var forgetting by remember { mutableStateOf<Footage?>(null) }
    var relinkFor by remember { mutableStateOf<Footage?>(null) }        // the session whose video is being picked again

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

    // scroll positions of the four lists (they jump back to the top whenever the order or the filter changes)
    val clipsGridState = rememberLazyGridState()
    val clipsListState = rememberLazyListState()
    val footageGridState = rememberLazyGridState()
    val footageListState = rememberLazyListState()
    LaunchedEffect(ui.clipKeys, ui.hidden, ui.exportFilter) {
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
                    ui.detail = found
                }
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

    val d = data
    val statsById: Map<Long, FootageStats> = remember(d) {
        if (d == null) emptyMap() else d.footage.associate { it.id to statsOf(d.clipsOf(it.id)) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 8.dp)
        ) {
            // ===== header =====
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                TextButton(onClick = { ui.refresh++ }) { Text("Refresh") }
                Button(onClick = { relinkFor = null; picker.launch(arrayOf("video/*")) }) { Text("Import video") }
            }

            // ===== the two tabs =====
            TabRow(selectedTabIndex = ui.tab) {
                Tab(selected = ui.tab == 0, onClick = { ui.tab = 0 }, text = { Text("Clips") })
                Tab(selected = ui.tab == 1, onClick = { ui.tab = 1 }, text = { Text("Footage") })
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
                    SortMenu(ui.footageSort.label, FootageSort.values().map { it to it.label }) { ui.footageSort = it; ui.save() }
                }
            }
            if (ui.tab == 0) {
                Text(
                    "Sorted by " + ui.clipKeys.joinToString(", then ") { it.short } +
                            (if (ui.filterActive) "  |  filtered" else "") +
                            (if (ui.clipsGrid) "  |  pinch the grid to resize" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (message.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { message = "" }) { Text("OK") }
                }
            }

            // ===== the content =====
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pinchColumns { step ->
                        if (ui.tab == 0 && ui.clipsGrid) {
                            ui.clipsColumns = (ui.clipsColumns + step).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                            ui.save()
                        } else if (ui.tab == 1 && ui.footageGrid) {
                            ui.footageColumns = (ui.footageColumns + step).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                            ui.save()
                        }
                    }
            ) {
                if (loadError.isNotEmpty()) {
                    Text(loadError, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                } else if (d == null) {
                    Text("Loading...", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                } else if (d.footage.isEmpty()) {
                    Text(
                        "Nothing here yet.\n\nTap Import video, set up the timer and press Find solves. Every session you scan is kept here, " +
                                "with all its solves, the next time you open CubeCut.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                } else if (ui.tab == 0) {
                    val shown = remember(d, ui.hidden, ui.exportFilter, ui.clipKeys) {
                        sortClips(
                            d.clips.filter { c ->
                                c.footage.id !in ui.hidden && when (ui.exportFilter) {
                                    ExportFilter.ALL -> true
                                    ExportFilter.EXPORTED -> c.exported != null
                                    ExportFilter.NOT_EXPORTED -> c.exported == null
                                }
                            },
                            ui.clipKeys
                        )
                    }
                    if (shown.isEmpty()) {
                        Text(
                            if (ui.filterActive) "No clips match the filter. Tap Filter to change it."
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
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(shown, key = { it.listKey }) { clip -> ClipCell(clip, d.openable, ui.clipsColumns) { ui.detail = clip } }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), state = clipsListState, contentPadding = PaddingValues(bottom = 8.dp)) {
                            listItems(shown, key = { it.listKey }) { clip -> ClipRow(clip, d.openable) { ui.detail = clip } }
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
                                FootageCell(f, statsById[f.id], d.openable[f.id] == true, f.id in ui.hidden, ui.footageColumns) { footageMenu = f }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), state = footageListState, contentPadding = PaddingValues(bottom = 8.dp)) {
                            listItems(shown, key = { it.id }) { f ->
                                FootageRow(f, statsById[f.id], d.openable[f.id] == true, f.id in ui.hidden) { footageMenu = f }
                            }
                        }
                    }
                }
            }

            AuthorCreditLine { showAbout = true }
        }

        // ===== clip detail screen, on top of the gallery =====
        val detail = ui.detail
        if (detail != null && d != null) {
            ClipDetailScreen(
                clip = detail,
                sessionClips = d.clipsOf(detail.footage.id),
                openable = d.openable[detail.footage.id] == true,
                stats = statsById[detail.footage.id],
                onBack = {
                    ui.detail = null
                    ui.refresh++            // pick up the note that may have just been written
                },
                onOpenEditor = {
                    ui.detail = null
                    onOpenEditor(EditorRequest(Uri.parse(detail.footage.uri), detail.cut.stopSec))
                },
                onReconnect = {
                    relinkFor = detail.footage
                    picker.launch(arrayOf("video/*"))
                },
                onShowSession = {
                    ui.detail = null
                    ui.refresh++
                    ui.hidden = d.footage.map { it.id }.filter { it != detail.footage.id }.toSet()
                    ui.save()
                    ui.tab = 0
                }
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
                            "Clips are ordered by the first choice. Clips that tie are ordered by the next one. " +
                                    "For example: Date, then Solve time = each day's solves from fastest to slowest.",
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
                        for (opt in ExportFilter.values()) {
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
                        Spacer(Modifier.height(10.dp))
                        Text("From these videos", style = MaterialTheme.typography.labelLarge)
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
            val isHidden = menuFootage.id in ui.hidden
            AlertDialog(
                onDismissRequest = { footageMenu = null },
                title = { Text(menuFootage.title) },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            formatDate(dateOf(menuFootage)) + "  |  " + formatTime(menuFootage.durationMs) + "\n" +
                                    (if (canOpenIt) "${st?.solves ?: 0} solves on, ${menuFootage.cutCount} cuts saved" else "${st?.solves ?: 0} exported clips") +
                                    (if (st?.best != null) ", best ${st.bestText}" else "") + "\n" +
                                    (if (menuFootage.lastScanMs > 0) "Find solves last ran on ${formatDate(menuFootage.lastScanMs)}" else "Find solves hasn't run on it yet") +
                                    "\nFile: ${menuFootage.displayName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (!canOpenIt) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The video can't be opened right now (moved, renamed, deleted, or restored under a new link), so only the clips you exported are shown. " +
                                        "Your saved cuts are safe: if you still have the video, tap Reconnect video below and pick it, " +
                                        "and every clip is back.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            ui.hidden = d.footage.map { it.id }.filter { it != menuFootage.id }.toSet()
                            ui.save()
                            ui.tab = 0
                            footageMenu = null
                        }) { Text("Show only its clips") }
                        TextButton(onClick = {
                            ui.hidden = if (isHidden) ui.hidden - menuFootage.id else ui.hidden + menuFootage.id
                            ui.save()
                            footageMenu = null
                        }) { Text(if (isHidden) "Show its clips in the Clips tab" else "Hide its clips in the Clips tab") }
                        if (!canOpenIt) {
                            TextButton(onClick = {
                                relinkFor = menuFootage
                                footageMenu = null
                                picker.launch(arrayOf("video/*"))
                            }) { Text("Reconnect video") }
                        }
                        TextButton(enabled = canOpenIt, onClick = {
                            footageMenu = null
                            onOpenEditor(EditorRequest(Uri.parse(menuFootage.uri), null))
                        }) { Text("Open in editor") }
                        TextButton(onClick = { renaming = menuFootage; footageMenu = null }) { Text("Rename") }
                        TextButton(onClick = { forgetting = menuFootage; footageMenu = null }) { Text("Forget this session") }
                    }
                },
                confirmButton = { Button(onClick = { footageMenu = null }) { Text("Close") } }
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
            AlertDialog(
                onDismissRequest = { forgetting = null },
                title = { Text("Forget this session?") },
                text = {
                    Text(
                        "\"${forgetTarget.title}\" and its ${forgetTarget.cutCount} saved cuts are removed from CubeCut, including the list of " +
                                "clips you exported from it. The video itself and the exported clip files are not touched.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        forgetting = null
                        scope.launch {
                            val problem = withContext(Dispatchers.IO) {
                                try {
                                    SessionStore.delete(context, forgetTarget)
                                    ThumbCache.forgetFootage(context, forgetTarget.id)
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
// the clip detail screen
// ---------------------------------------------------------------------------------------

/** The part of the video this clip would export: the same maths as the export (computeClips), so what you see is what you get. */
private fun clipWindow(clip: GalleryClip, sessionClips: List<GalleryClip>): Pair<Double, Double> {
    val f = clip.footage
    val durSec = f.durationMs / 1000.0
    val cuts = sessionClips.sortedBy { it.index }.map { it.cut }
    val computed = computeClips(cuts, f.beforePad.toDouble(), f.afterPad.toDouble(), durSec).firstOrNull { it.cutIndex == clip.index }
    if (computed != null) return Pair(computed.startSec, computed.endSec)
    // a cut that is left out has no exported clip; show the stretch leading up to it instead
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

@Composable
fun ClipDetailScreen(
    clip: GalleryClip,
    sessionClips: List<GalleryClip>,
    openable: Boolean,
    stats: FootageStats?,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onReconnect: () -> Unit,
    onShowSession: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val footage = clip.footage
    val exportedFile = clip.exported
    val playsExport = clip.exportOnly && exportedFile != null          // the video is gone: play the exported file instead
    val clipPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { clipPlayer.release() } }

    val window = remember(clip.listKey, sessionClips) { if (playsExport) Pair(0.0, 0.0) else clipWindow(clip, sessionClips) }

    // load just this clip into the player (it plays only its own part of the video, or the whole exported file)
    LaunchedEffect(clip.listKey, openable) {
        if (playsExport) {
            clipPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(exportedFile!!.uri)))
            clipPlayer.prepare()
            clipPlayer.pause()
        } else if (openable) {
            val range = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((window.first * 1000.0).toLong())
                .setEndPositionMs((window.second * 1000.0).toLong())
                .build()
            clipPlayer.setMediaItem(MediaItem.Builder().setUri(Uri.parse(footage.uri)).setClippingConfiguration(range).build())
            clipPlayer.prepare()
            clipPlayer.pause()
        }
    }

    // your note (the social media buttons write into it); saved a moment after you stop typing, and when you leave
    var note by remember(clip.listKey) { mutableStateOf(clip.cut.userNote) }
    val canSaveNote = clip.index >= 0           // the solve still exists as a cut in its session
    var savedNote by remember(clip.listKey) { mutableStateOf(clip.cut.userNote) }
    suspend fun saveNote() {
        if (canSaveNote && note != savedNote) {
            val text = note
            withContext(Dispatchers.IO) {
                try { SessionStore.updateCutNote(context, footage.id, clip.cut.stopSec, text) } catch (e: Exception) { }
            }
            savedNote = text
        }
    }
    LaunchedEffect(note) {
        delay(500)
        saveNote()
    }
    fun leave() {
        scope.launch {
            saveNote()
            onBack()
        }
    }
    BackHandler { leave() }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { leave() }) { Text("\u2039 Back") }
                Text(clip.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            // the clip itself
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((screenHeightDp * 0.38f).dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (openable || playsExport) {
                    AndroidView(
                        factory = { ctx ->
                            val pv = LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null) as PlayerView
                            pv.player = clipPlayer
                            pv.useController = true
                            pv.controllerShowTimeoutMs = 2000        // after a tap the controls show for 2 seconds
                            pv.hideController()                      // hidden from the start: tap the picture to show them
                            clipPlayer.addListener(object : Player.Listener {
                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    if (isPlaying) pv.hideController()   // as soon as it plays, the controls go away
                                }
                            })
                            pv
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Thumb(clipPictureKey(clip), null, 0L, Modifier.fillMaxSize())
                    Text(
                        "The video can't be opened right now.",
                        color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.background(Color(0xAA000000)).padding(8.dp)
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (clip.cut.timeText.isBlank()) "time unknown" else "${clip.cut.timeText} s",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        (if (clip.index >= 0) "Solve ${clip.index + 1} of ${clip.total}  |  " else "") +
                                "at ${formatTime(Math.round(clip.cut.stopSec * 1000.0))} in the video",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (exportedFile != null) "Exported as ${exportedFile.fileName} on ${formatDate(exportedFile.exportedMs)}"
                    else if (clip.cut.included) "Not exported yet" else "Left out of the export",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (exportedFile != null) GREEN else MaterialTheme.colorScheme.onSurface
                )
                if (playsExport) {
                    Text(
                        "The original video is gone, so this is the exported file.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (clip.needsCheck) {
                    Text(highlightCheck("check: " + clip.cut.note), style = MaterialTheme.typography.bodyMedium)
                } else if (clip.cut.note.isNotEmpty()) {
                    Text(clip.cut.note, style = MaterialTheme.typography.bodySmall)
                }

                // ===== note and social media buttons =====
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Note", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (canSaveNote) {
                        TextButton(onClick = { note = "" }, enabled = note.isNotEmpty()) { Text("Clear") }
                    }
                }
                if (canSaveNote) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
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
                            SocialChip(p, noteMentions(note, p)) { note = togglePlatform(note, p) }
                        }
                    }
                } else {
                    Text(
                        "This exported clip no longer matches a solve in the session (its cut was moved or removed), so it can't take a note.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text("From this video", style = MaterialTheme.typography.labelLarge)
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clickable(onClick = { scope.launch { saveNote(); onShowSession() } })
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(
                            footageThumbKey(footage), if (openable) Uri.parse(footage.uri) else null, footage.durationMs / 10,
                            Modifier.width(54.dp).aspectRatio(THUMB_ASPECT).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(footage.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                formatDate(dateOf(footage)) + "  |  " + formatTime(footage.durationMs) + "  |  ${stats?.solves ?: 0} " +
                                        (if (openable) "solves" else "exported"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text("File: ${footage.displayName}" + (if (!openable) " (deleted or moved)" else ""),
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Tap to see only this video's clips", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { scope.launch { saveNote(); onOpenEditor() } },
                    enabled = openable && !playsExport,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open in editor at this solve") }
                if (!openable) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { scope.launch { saveNote(); onReconnect() } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reconnect video")
                    }
                    Text(
                        "Your saved cuts are safe. Pick the original session video and every clip of it is back.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}