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

import android.content.SharedPreferences
import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------
// Version 3: the Gallery. It is the first screen of the app. It shows what the phone remembers (see SessionStore.kt):
//   Clips tab   - every solve found so far, from every session, as a grid of pictures or as a list
//   Footage tab - the raw session videos, as a grid or a list
// Tapping a clip opens its detail screen (it plays just that clip and says which video it came from); tapping a footage card offers
// "show its clips", "open in editor", "rename" and "forget".
// ---------------------------------------------------------------------------------------

/** How tall a picture cell is compared to its width (3 wide : 4 tall). Change this one number to reshape the whole grid. */
private const val THUMB_ASPECT = 0.75f

private val AMBER = Color(0xFFFFB74D)
private val GREY = Color(0xFF757575)

/** What the editor is asked to open: [uri] (null = an empty editor) and optionally the solve to jump to ([seekSec] = its stop time). */
class EditorRequest(val uri: Uri?, val seekSec: Double? = null)

// ---------------------------------------------------------------------------------------
// sorting
// ---------------------------------------------------------------------------------------

enum class ClipSort(val label: String) {
    NEWEST("Newest session first"),
    OLDEST("Oldest session first"),
    NAME("Name A-Z"),
    FASTEST("Fastest time"),
    SLOWEST("Slowest time"),
    CHECK("Needs check first");

    companion object {
        fun from(name: String?): ClipSort = values().firstOrNull { it.name == name } ?: NEWEST
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

/** When a session was filmed (or, if the video doesn't say, when it was imported). */
private fun dateOf(f: Footage): Long = if (f.dateTakenMs > 0) f.dateTakenMs else f.addedMs

fun sortClips(list: List<GalleryClip>, sort: ClipSort): List<GalleryClip> = when (sort) {
    ClipSort.NEWEST -> list.sortedWith(compareBy<GalleryClip>({ -dateOf(it.footage) }, { it.footage.id }, { it.index }))
    ClipSort.OLDEST -> list.sortedWith(compareBy<GalleryClip>({ dateOf(it.footage) }, { it.footage.id }, { it.index }))
    ClipSort.NAME -> list.sortedWith(compareBy<GalleryClip>({ it.footage.title.lowercase() }, { it.footage.id }, { it.index }))
    ClipSort.FASTEST -> list.sortedWith(compareBy<GalleryClip>({ it.seconds ?: Double.MAX_VALUE }, { it.footage.id }, { it.index }))
    ClipSort.SLOWEST -> list.sortedWith(
        compareBy<GalleryClip>({ if (it.seconds == null) 1 else 0 }, { -(it.seconds ?: 0.0) }, { it.footage.id }, { it.index })
    )
    ClipSort.CHECK -> list.sortedWith(
        compareBy<GalleryClip>({ if (it.needsCheck) 0 else 1 }, { -dateOf(it.footage) }, { it.footage.id }, { it.index })
    )
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
    var filterFootageId by mutableStateOf<Long?>(null)            // show only the clips of this session
    var refresh by mutableStateOf(0)                              // add 1 to load the data again
    var detail by mutableStateOf<GalleryClip?>(null)              // the clip whose detail screen is open
    var clipsGrid by mutableStateOf(prefs.getBoolean("clips_grid", true))
    var footageGrid by mutableStateOf(prefs.getBoolean("footage_grid", true))
    var clipSort by mutableStateOf(ClipSort.from(prefs.getString("clip_sort", null)))
    var footageSort by mutableStateOf(FootageSort.from(prefs.getString("footage_sort", null)))

    /** Remember the view and sort choices for the next time the app opens. */
    fun save() {
        prefs.edit()
            .putBoolean("clips_grid", clipsGrid)
            .putBoolean("footage_grid", footageGrid)
            .putString("clip_sort", clipSort.name)
            .putString("footage_sort", footageSort.name)
            .apply()
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

private fun clipUri(clip: GalleryClip, openable: Map<Long, Boolean>): Uri? =
    if (openable[clip.footage.id] == true) Uri.parse(clip.footage.uri) else null

private fun clipThumbKey(clip: GalleryClip): String = "c_${clip.key}"

/** One solve as a picture, with its time at the upper right. */
@Composable
private fun ClipCell(clip: GalleryClip, openable: Map<Long, Boolean>, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(THUMB_ASPECT)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Thumb(clipThumbKey(clip), clipUri(clip, openable), Math.round(clip.cut.stopSec * 1000.0), Modifier.fillMaxSize())
        if (!clip.cut.included) Box(Modifier.fillMaxSize().background(Color(0x88000000)))
        Badge(timeBadgeText(clip), Color(0xCC000000), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        Column(Modifier.align(Alignment.TopStart).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (clip.needsCheck) Badge("Check", AMBER, Color.Black)
            if (!clip.cut.included) Badge("Left out", GREY)
            if (openable[clip.footage.id] != true) Badge("No video", Color(0xFFC62828))
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text("Solve ${clip.index + 1} of ${clip.total}", style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
            Text(
                clip.footage.title, style = MaterialTheme.typography.labelSmall, color = Color(0xFFDDDDDD),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
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
            clipThumbKey(clip), clipUri(clip, openable), Math.round(clip.cut.stopSec * 1000.0),
            Modifier.width(60.dp).aspectRatio(THUMB_ASPECT).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(clip.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatDate(dateOf(clip.footage)) + "  |  Solve ${clip.index + 1} of ${clip.total}" +
                        (if (!clip.cut.included) "  |  left out" else "") +
                        (if (clip.needsCheck) "  |  check" else "") +
                        (if (openable[clip.footage.id] != true) "  |  no video" else ""),
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
private fun FootageCell(f: Footage, stats: FootageStats?, openable: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(THUMB_ASPECT)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Thumb(footageThumbKey(f), if (openable) Uri.parse(f.uri) else null, f.durationMs / 10, Modifier.fillMaxSize())
        Badge(
            "${stats?.solves ?: 0} solves", Color(0xCC000000),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
        )
        Column(Modifier.align(Alignment.TopStart).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (!openable) Badge("No video", Color(0xFFC62828))
            if ((stats?.check ?: 0) > 0) Badge("${stats?.check} to check", AMBER, Color.Black)
            if (f.lastScanMs == 0L && f.cutCount == 0) Badge("Not scanned", GREY)
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(f.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatDate(dateOf(f)) + "  |  " + formatTime(f.durationMs),
                style = MaterialTheme.typography.labelSmall, color = Color(0xFFDDDDDD), maxLines = 1
            )
        }
    }
}

@Composable
private fun FootageRow(f: Footage, stats: FootageStats?, openable: Boolean, onClick: () -> Unit) {
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
                formatDate(dateOf(f)) + "  |  " + formatTime(f.durationMs) + "  |  ${stats?.solves ?: 0} solves" +
                        (if (stats?.best != null) "  |  best ${stats.bestText}" else "") +
                        (if ((stats?.check ?: 0) > 0) "  |  ${stats?.check} to check" else "") +
                        (if (!openable) "  |  no video" else ""),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}

/** A button that opens a menu of sort choices. */
@Composable
private fun <T> SortMenu(current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Sort: $current  \u25BE") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, label) in options) {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(value) })
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
    var showAbout by remember { mutableStateOf(false) }
    var footageMenu by remember { mutableStateOf<Footage?>(null) }      // the session whose options window is open
    var renaming by remember { mutableStateOf<Footage?>(null) }
    var forgetting by remember { mutableStateOf<Footage?>(null) }

    // load (or reload) everything the phone remembers
    LaunchedEffect(ui.refresh) {
        try {
            data = withContext(Dispatchers.IO) { SessionStore.loadGallery(context) }
            loadError = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "Couldn't load the saved sessions: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // Import video: pick a video, then open it in the editor (a video seen before gets its saved cuts back; this also
    // reconnects a session whose video was moved)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            SessionStore.keepAccess(context, uri)
            onOpenEditor(EditorRequest(uri, null))
        }
    }

    // Back closes the clip detail screen first
    BackHandler(enabled = ui.detail != null) { ui.detail = null }

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
                        if (d == null) "Loading..." else "${d.clips.size} solves in ${d.footage.size} sessions",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(onClick = { picker.launch(arrayOf("video/*")) }) { Text("Import video") }
            }

            // ===== the two tabs =====
            TabRow(selectedTabIndex = ui.tab) {
                Tab(selected = ui.tab == 0, onClick = { ui.tab = 0 }, text = { Text("Clips") })
                Tab(selected = ui.tab == 1, onClick = { ui.tab = 1 }, text = { Text("Footage") })
            }

            // ===== view switch and sort =====
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
                    SortMenu(ui.clipSort.label, ClipSort.values().map { it to it.label }) { ui.clipSort = it; ui.save() }
                } else {
                    SortMenu(ui.footageSort.label, FootageSort.values().map { it to it.label }) { ui.footageSort = it; ui.save() }
                }
            }

            // "showing only one session" bar
            val filterId = ui.filterFootageId
            val filterFootage = d?.footage?.firstOrNull { it.id == filterId }
            if (ui.tab == 0 && filterFootage != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Only: ${filterFootage.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { ui.filterFootageId = null }) { Text("Show all") }
                }
            }

            // ===== the content =====
            Box(Modifier.weight(1f).fillMaxWidth()) {
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
                    val shown = remember(d, ui.filterFootageId, ui.clipSort) {
                        sortClips(d.clips.filter { ui.filterFootageId == null || it.footage.id == ui.filterFootageId }, ui.clipSort)
                    }
                    if (shown.isEmpty()) {
                        Text(
                            "No solves saved for this selection yet. Open the session in the editor and press Find solves.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)
                        )
                    } else if (ui.clipsGrid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            contentPadding = PaddingValues(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(shown, key = { it.listKey }) { clip -> ClipCell(clip, d.openable) { ui.detail = clip } }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                            listItems(shown, key = { it.listKey }) { clip -> ClipRow(clip, d.openable) { ui.detail = clip } }
                        }
                    }
                } else {
                    val shown = remember(d, ui.footageSort) { sortFootage(d.footage, ui.footageSort, statsById) }
                    if (ui.footageGrid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            contentPadding = PaddingValues(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(shown, key = { it.id }) { f ->
                                FootageCell(f, statsById[f.id], d.openable[f.id] == true) { footageMenu = f }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                            listItems(shown, key = { it.id }) { f ->
                                FootageRow(f, statsById[f.id], d.openable[f.id] == true) { footageMenu = f }
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
                onBack = { ui.detail = null },
                onOpenEditor = {
                    ui.detail = null
                    onOpenEditor(EditorRequest(Uri.parse(detail.footage.uri), detail.cut.stopSec))
                },
                onShowSession = {
                    ui.detail = null
                    ui.filterFootageId = detail.footage.id
                    ui.tab = 0
                }
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
                    Column {
                        Text(
                            formatDate(dateOf(menuFootage)) + "  |  " + formatTime(menuFootage.durationMs) + "\n" +
                                    "${st?.solves ?: 0} solves on, ${menuFootage.cutCount} cuts saved" +
                                    (if (st?.best != null) ", best ${st.bestText}" else "") + "\n" +
                                    (if (menuFootage.lastScanMs > 0) "Find solves last ran on ${formatDate(menuFootage.lastScanMs)}" else "Find solves hasn't run on it yet") +
                                    "\nFile: ${menuFootage.displayName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (!canOpenIt) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The video can't be opened right now (moved or deleted). Your saved cuts are safe. If you still have the video, " +
                                        "tap Import video and pick it again: CubeCut recognises it and reconnects it.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            ui.filterFootageId = menuFootage.id
                            ui.tab = 0
                            footageMenu = null
                        }) { Text("Show its clips") }
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
                        "\"${forgetTarget.title}\" and its ${forgetTarget.cutCount} saved cuts are removed from CubeCut. " +
                                "The video itself and any clips you exported are not touched.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        forgetting = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    SessionStore.delete(context, forgetTarget)
                                    ThumbCache.forgetFootage(context, forgetTarget.id)
                                } catch (e: Exception) {
                                }
                            }
                            if (ui.filterFootageId == forgetTarget.id) ui.filterFootageId = null
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

@Composable
fun ClipDetailScreen(
    clip: GalleryClip,
    sessionClips: List<GalleryClip>,
    openable: Boolean,
    stats: FootageStats?,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onShowSession: () -> Unit
) {
    val context = LocalContext.current
    val footage = clip.footage
    val clipPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { clipPlayer.release() } }

    val window = remember(clip.key, sessionClips) { clipWindow(clip, sessionClips) }

    // load just this clip into the player (it plays only its own part of the video)
    LaunchedEffect(clip.key, openable) {
        if (openable) {
            val range = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((window.first * 1000.0).toLong())
                .setEndPositionMs((window.second * 1000.0).toLong())
                .build()
            clipPlayer.setMediaItem(MediaItem.Builder().setUri(Uri.parse(footage.uri)).setClippingConfiguration(range).build())
            clipPlayer.prepare()
            clipPlayer.pause()
        }
    }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2039 Back") }
                Text(clip.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            // the clip itself
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((screenHeightDp * 0.42f).dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (openable) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null) as PlayerView).apply {
                                this.player = clipPlayer
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Thumb(clipThumbKey(clip), null, 0L, Modifier.fillMaxSize())
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
                        "Solve ${clip.index + 1} of ${clip.total}  |  at ${formatTime(Math.round(clip.cut.stopSec * 1000.0))} in the video",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    (if (clip.cut.included) "Will be exported" else "Left out of the export") +
                            (if (clip.cut.includeScramble) "  |  with scramble" else "  |  solve only"),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (clip.needsCheck) {
                    Text(highlightCheck("check: " + clip.cut.note), style = MaterialTheme.typography.bodyMedium)
                } else if (clip.cut.note.isNotEmpty()) {
                    Text(clip.cut.note, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(10.dp))
                Text("From this video", style = MaterialTheme.typography.labelLarge)
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clickable(onClick = onShowSession)
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
                                formatDate(dateOf(footage)) + "  |  " + formatTime(footage.durationMs) + "  |  ${stats?.solves ?: 0} solves",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text("File: ${footage.displayName}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Tap to see all its clips", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenEditor, enabled = openable, modifier = Modifier.fillMaxWidth()) { Text("Open in editor at this solve") }
                if (!openable) {
                    Text(
                        "Your saved cuts are safe. If you still have the video, tap Import video in the Gallery and pick it again: CubeCut recognises it and reconnects it.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}