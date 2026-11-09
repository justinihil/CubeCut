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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

// should a new cut include the part before the solve (the scramble)? true = clip runs from the previous clip's end
const val DEFAULT_INCLUDE_SCRAMBLE = true

// The author credit: a small line at the bottom of the main screen, and the About window it opens. The licence (NOTICE.md)
// requires the credit to stay in the app, so anyone who shares a version of CubeCut has to keep it (changed versions
// add their own name next to it). AUTHOR_FIND_ME is only shown in the About window; change it freely when more
// platforms are added, it is not part of the licence terms.
const val AUTHOR_NAME = "DadCuber"
const val AUTHOR_FIND_ME = "Find me on TikTok, Instagram and Facebook: DadCuber"

// true once the image library (OpenCV) has loaded
var openCvReady = false

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openCvReady = OpenCVLoader.initLocal()
        setContent {
            val ui = remember { GalleryUiState(getSharedPreferences("cubecut_ui", Context.MODE_PRIVATE)) }
            CubeCutTheme(ui.themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(ui)
                }
            }
        }
    }
}

/**
 * The first screen is the Gallery (everything the phone remembers). Opening a session, a clip or a new video from there opens the
 * editor; closing the editor returns to the Gallery and reloads it.
 */
@Composable
fun AppRoot(ui: GalleryUiState) {
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    val request = editor
    if (request != null) {
        EditorScreen(request = request, onClose = { footageId, solveSec ->
            editor = null
            if (footageId != null && solveSec != null) ui.openClip = Pair(footageId, solveSec)         // View on a solve card
            else if (footageId != null) {
                // Gallery button or Back: open the Footage tab and point out the video you were just in
                ui.tab = 1
                ui.flashFootage = footageId
            }
            ui.refresh++
        })
    } else {
        GalleryScreen(ui = ui, onOpenEditor = {
            ui.selecting = false            // a selection in progress doesn't survive a trip to the editor
            ui.selected = emptySet()
            editor = it
        })
    }
}

// words that mean "look at this one" - they are shown in bold amber in the solves list
private val CHECK_KEYWORDS = listOf(
    "check", "couldn't read", "couldn't see", "not steady", "already on the timer", "time unknown",
    "no time", "too close", "will be skipped"
)

fun highlightCheck(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val lower = text.lowercase()
    for (word in CHECK_KEYWORDS) {
        var from = 0
        while (true) {
            val i = lower.indexOf(word, from)
            if (i < 0) break
            addStyle(SpanStyle(color = AppColors.check, fontWeight = FontWeight.Bold), i, i + word.length)
            from = i + word.length
        }
    }
}

/** A small filled button for the solve rows (Edit, View). */
@Composable
private fun RowButton(text: String, background: Color, textColor: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = textColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The red delete button with a trash icon. */
@Composable
private fun DeleteButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) AppColors.danger else AppColors.danger.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        TrashIcon(Color.White, 20.dp)
    }
}

/**
 * The scramble button on a solve card: crossing "shuffle" arrows. On (the clip includes the scramble): blue arrows and a blue border.
 * Off (only the solve): grey arrows, crossed out. The button itself stays grey like Edit, so it isn't confused with the blue View button.
 */
@Composable
private fun ScrambleButton(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val back = MaterialTheme.colorScheme.surfaceContainerHighest
    val tint = (if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        .let { if (enabled) it else it.copy(alpha = 0.4f) }
    Box(
        Modifier
            .size(34.dp)
            .clip(shape)
            .background(if (enabled) back else back.copy(alpha = 0.4f))
            .then(if (on) Modifier.border(1.5.dp, tint, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = this.size.width
            val h = this.size.height
            val sw = w * 0.1f
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(tint, Offset(w * x1, h * y1), Offset(w * x2, h * y2), strokeWidth = sw, cap = StrokeCap.Round)
            // top left to bottom right, arrow head at the end
            line(0.10f, 0.30f, 0.32f, 0.30f)
            line(0.32f, 0.30f, 0.62f, 0.70f)
            line(0.62f, 0.70f, 0.88f, 0.70f)
            line(0.88f, 0.70f, 0.76f, 0.58f)
            line(0.88f, 0.70f, 0.76f, 0.82f)
            // bottom left to top right, arrow head at the end
            line(0.10f, 0.70f, 0.32f, 0.70f)
            line(0.32f, 0.70f, 0.62f, 0.30f)
            line(0.62f, 0.30f, 0.88f, 0.30f)
            line(0.88f, 0.30f, 0.76f, 0.18f)
            line(0.88f, 0.30f, 0.76f, 0.42f)
            if (!on) line(0.08f, 0.92f, 0.92f, 0.08f)          // crossed out: the scramble is left out
        }
    }
}

/** The previous / next solve buttons in the review bar: a grey square with a bold arrow pointing the way it goes. */
@Composable
private fun ArrowButton(left: Boolean, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (left) "Previous solve" else "Next solve" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = this.size.width
            val h = this.size.height
            val sw = w * 0.13f
            // drawn pointing right; mirrored for the left one
            fun x(f: Float) = if (left) w * (1f - f) else w * f
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(tint, Offset(x(x1), h * y1), Offset(x(x2), h * y2), strokeWidth = sw, cap = StrokeCap.Round)
            line(0.14f, 0.50f, 0.84f, 0.50f)
            line(0.84f, 0.50f, 0.54f, 0.20f)
            line(0.84f, 0.50f, 0.54f, 0.80f)
        }
    }
}

/**
 * "1:23.4 / 5:00.0" next to Play. It reads the playhead itself (position is a function), so while the video plays only this
 * text is redrawn, not the editor around it.
 */
@Composable
private fun PositionText(position: () -> Long, durationMs: Long) {
    Text("${formatTime(position())} / ${formatTime(durationMs)}", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun EditorScreen(request: EditorRequest, onClose: (Long?, Double?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- the video ----
    var videoUri by remember { mutableStateOf<Uri?>(request.uri) }     // the Gallery can hand over the video to open
    var durationMs by remember { mutableStateOf(0L) }
    var thumbs by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var positionMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Import a video to begin") }

    // ---- saved sessions (version 3) ----
    var footageId by remember { mutableStateOf<Long?>(null) }    // this video's saved record (null = none yet, or still loading it)
    var pendingSeek by remember { mutableStateOf(request.seekSec) }
    var scrollToSelected by remember { mutableStateOf(false) }          // after opening from a Gallery clip: bring its row into view     // a solve to jump to once the video has loaded (from a Gallery clip)

    // ---- settings ----
    var beforePad by remember { mutableStateOf(2.5f) }           // seconds kept BEFORE the solve starts (used when the scramble is left out)
    var afterPad by remember { mutableStateOf(2.5f) }            // seconds kept AFTER the first frame that shows the final time
    var scrambleAll by remember { mutableStateOf(DEFAULT_INCLUDE_SCRAMBLE) }   // the "all clips" scramble switch (new cuts follow it)
    var zoom by remember { mutableStateOf(1f) }
    val timelineScroll = rememberScrollState()
    val listScroll = rememberScrollState()                       // the scrolling area under the buttons (setup box + list)
    val listBox = remember { arrayOfNulls<LayoutCoordinates>(1) } // where that area is on the screen
    val rowBoxes = remember { HashMap<Int, LayoutCoordinates>() } // where each solve row is on the screen

    // ---- the cuts (one per solve) ----
    val cuts = remember { mutableStateListOf<CutItem>() }
    var analyzing by remember { mutableStateOf(false) }
    var analysisStatus by remember { mutableStateOf("") }
    var editIndex by remember { mutableStateOf(-1) }             // which cut is open in the edit window (-1 = none)
    // cuts moved since the last save: where each was saved -> where it is now (the save moves their export records along)
    val pendingMoves = remember { HashMap<Double, Double>() }
    // where this video's exported clips are (to say "Already exported" in the Edit window); moves follow them
    var exportedAt by remember { mutableStateOf<List<Double>>(emptyList()) }
    var selectedCut by remember { mutableStateOf(-1) }           // the cut you are looking at right now (-1 = none)

    // ---- video zoom (pinch the video) ----
    var videoScale by remember { mutableStateOf(1f) }
    var videoOffX by remember { mutableStateOf(0f) }
    var videoOffY by remember { mutableStateOf(0f) }

    // ---- import progress window ----
    var importing by remember { mutableStateOf(false) }
    var importStage by remember { mutableStateOf("") }
    var importProgress by remember { mutableStateOf(0f) }

    var showAbout by remember { mutableStateOf(false) }          // the About window (author credit and licence notice)
    var showClearChecks by remember { mutableStateOf(false) }    // the "Clear all checks?" window
    var showFindConfirm by remember { mutableStateOf(false) }    // the "this may take a couple of minutes" window
    var addCutAt by remember { mutableStateOf<Double?>(null) }    // "+ Cut at playhead": where the new solve stops (its time window is open)
    var cutBlockedBy by remember { mutableStateOf(-1) }          // "+ Cut at playhead" on a spot inside this solve: the warning window
    var cutBlockedAt by remember { mutableStateOf(0.0) }         // ...and where that spot was
    var analysisDone by remember { mutableStateOf(0) }           // progress of Find solves
    var analysisTotal by remember { mutableStateOf(0) }
    var cancelScan by remember { mutableStateOf(false) }         // the Cancel button of Find solves
    var scanReport by remember { mutableStateOf<ScanResult?>(null) }   // what the last scan learned (for the warnings)
    var setupExpanded by remember { mutableStateOf(true) }       // the Setup box: open at first, closes after Find solves
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // keep the screen awake while a long job is running (so the phone doesn't go to sleep halfway)
    val view = LocalView.current
    SideEffect { view.keepScreenOn = analyzing }
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }

    // ---- timer reading ----
    var timerEngine by remember { mutableStateOf<TimerEngine?>(null) }
    var timerMessage by remember {
        mutableStateOf(if (openCvReady) "" else "The image library failed to load")
    }
    var timerDebug by remember { mutableStateOf<Bitmap?>(null) }
    var setupFrame by remember { mutableStateOf<Bitmap?>(null) }
    var setupAtMs by remember { mutableStateOf(0L) }                    // where in the video the setup picture was taken (saved with the session)
    var setupBusy by remember { mutableStateOf(false) }
    var setupMessage by remember { mutableStateOf("") }

    // The timer engine holds OpenCV memory that the garbage collector can't see, so it is freed by hand when the editor closes.
    // A background job that is still reading with it (Find solves, Read timer here) must finish first, or it
    // would read with freed memory: each one counts itself in and out here, and the last one out frees the engine.
    // (plain arrays, not screen state: only the main thread touches them, and changing them must not redraw anything)
    val engineUsers = remember { IntArray(1) }
    val editorClosed = remember { BooleanArray(1) }
    fun startUsing() {
        engineUsers[0]++
    }
    fun stopUsing(engine: TimerEngine) {
        engineUsers[0]--
        if (editorClosed[0] && engineUsers[0] == 0) engine.release()
    }
    DisposableEffect(Unit) {
        onDispose {
            editorClosed[0] = true
            if (engineUsers[0] == 0) timerEngine?.release()
        }
    }

    // ---- the video player ----
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    // leaving the app (home, another app, screen off) stops the sound: pause as soon as CubeCut is no longer in front
    DisposableEffect(exoPlayer) {
        val lifecycle = context.findActivity()?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // the system file picker (needs no storage permission). It is used instead of the photo picker because Android lets the app
    // keep its permission to read the chosen video, so a saved session can be opened again after the app or the phone restarted.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            SessionStore.keepAccess(context, uri)
            videoUri = uri
        }
    }

    // when a video is chosen: load it into the player, then build the film strip
    LaunchedEffect(videoUri) {
        val uri = videoUri ?: return@LaunchedEffect
        footageId = null        // first of all: stop saving into the previous video's record while the lists below are cleared
        pendingMoves.clear()
        exportedAt = emptyList()
        // the timer setup belongs to one video: a new video needs a new setup
        timerEngine?.release()
        timerEngine = null
        timerDebug = null
        timerMessage = ""
        thumbs = emptyList()
        cuts.clear()
        rowBoxes.clear()
        selectedCut = -1
        analysisStatus = ""
        scanReport = null
        zoom = 1f
        videoScale = 1f
        videoOffX = 0f
        videoOffY = 0f
        setupExpanded = true
        positionMs = 0L
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.pause()

        importing = true
        importProgress = 0f
        importStage = "Reading video..."
        try {
            status = "Reading video..."
            val dur = withContext(Dispatchers.IO) { readDurationMs(context, uri) }
            durationMs = dur
            importProgress = 0.05f

            // is this a video the app has seen before? If so, bring back its saved cuts and settings
            var openedNote: String? = null
            val opened = try {
                withContext(Dispatchers.IO) { SessionStore.openOrCreate(context, uri, dur, beforePad, afterPad, scrambleAll) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null        // the session could not be saved or loaded; the editor still works, it just won't remember this video
            }
            if (opened != null) {
                if (!opened.isNew) {
                    beforePad = opened.footage.beforePad
                    afterPad = opened.footage.afterPad
                    scrambleAll = opened.footage.scrambleAll
                    cuts.addAll(opened.cuts)
                    if (opened.cuts.isNotEmpty()) {
                        setupExpanded = false
                        openedNote = "Saved session: ${opened.cuts.size} cuts" +
                                (if (opened.footage.lastScanMs > 0) ", found ${formatDate(opened.footage.lastScanMs)}" else "") +
                                ". Set up the timer only to scan again."
                        analysisStatus = openedNote
                    }
                }
                footageId = opened.footage.id       // set last: from now on every change is saved
            }

            importStage = "Building film strip..."
            status = "Building film strip..."
            thumbs = withContext(Dispatchers.IO) {
                buildThumbnails(context, uri, dur, THUMB_COUNT) { p -> importProgress = 0.05f + 0.95f * p }
            }
            importProgress = 1f

            status = openedNote ?: "Ready"

            // opened from a Gallery clip: select that solve and put the playhead just before it
            val seekTo = pendingSeek
            if (seekTo != null) {
                pendingSeek = null
                val nearest = cuts.indices.minByOrNull { Math.abs(cuts[it].stopSec - seekTo) }
                if (nearest != null && Math.abs(cuts[nearest].stopSec - seekTo) < 0.5) selectedCut = nearest
                val ms = ((seekTo - 0.5).coerceAtLeast(0.0) * 1000.0).toLong().coerceIn(0L, maxOf(0L, dur))
                exoPlayer.seekTo(ms)
                positionMs = ms
                scrollToSelected = true
            }

            // the timer was set up on this video before: set it up again from the saved corners, so Find solves is ready
            val savedSession = opened?.footage
            val savedCorners = if (savedSession != null) SessionStore.timerCornersOf(savedSession) else null
            if (savedSession != null && savedCorners != null && timerEngine == null) {
                try {
                    val frame = withContext(Dispatchers.IO) { grabFrame(context, uri, savedSession.timerFrameMs) }
                    if (frame != null) {
                        val res = withContext(Dispatchers.Default) { TimerVision.createSetup(frame, savedCorners, savedSession.timerTyped) }
                        val restoredSetup = res.setup
                        val restoredRef = res.reference
                        if (restoredSetup != null && restoredRef != null) {
                            timerEngine = withContext(Dispatchers.Default) { TimerEngine(restoredSetup, restoredRef) }
                            timerMessage = "Saved timer setup restored. " + res.message
                            timerDebug = null
                            status = (openedNote ?: "Ready") + " Timer restored."
                        } else {
                            timerMessage = "Couldn't use the saved timer setup (${res.message}). Set it up again."
                            status = (openedNote ?: "Ready") + " Set up the timer again."
                        }
                    } else {
                        timerMessage = "Couldn't restore the saved timer setup (no picture there). Set it up again."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    timerMessage = "Couldn't restore the saved timer setup: ${e.message ?: e.javaClass.simpleName}. Set it up again."
                }
            } else if (savedSession != null && savedCorners == null && savedSession.timerFrameMs >= 0) {
                timerMessage = "The saved timer setup couldn't be read. Set it up again."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Couldn't open this video: ${e.message ?: e.javaClass.simpleName}. " +
                    "If it moved, import it again. Your saved cuts come back."
        } finally {
            importing = false
        }
    }

    // the timer setup is not kept between runs any more; remove the files an older version left behind
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { TimerStore.clear(context) }
    }

    // version 3: every change to the cuts or the settings is saved on the phone (after a short pause, so a burst of edits is one write)
    LaunchedEffect(footageId, cuts.toList(), beforePad, afterPad, scrambleAll) {
        val id = footageId ?: return@LaunchedEffect
        delay(400)
        val snapshot = cuts.toList()
        val before = beforePad
        val after = afterPad
        val scramble = scrambleAll
        val moves = HashMap(pendingMoves)
        pendingMoves.clear()
        val saved = withContext(Dispatchers.IO) {
            try {
                SessionStore.saveSession(context, id, snapshot, before, after, scramble, moves)
                true
            } catch (e: Exception) {
                false          // saving is a bonus: if it fails the editor keeps working
            }
        }
        if (!saved) {
            // not saved: keep the moves for the next save (joined with any move of the same cut made in the meantime)
            for ((from, to) in moves) {
                val later = pendingMoves.keys.firstOrNull { Math.abs(it - to) < 0.0005 }
                if (later != null) pendingMoves[from] = pendingMoves.remove(later)!! else pendingMoves[from] = to
            }
        }
    }

    // where this video's exported clips are (read once when the video's record is open)
    LaunchedEffect(footageId) {
        val id = footageId ?: return@LaunchedEffect
        exportedAt = withContext(Dispatchers.IO) {
            try {
                SessionStore.loadExports(context, id).filter { it.trashedMs == 0L }.map { it.stopSec }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    fun isExported(i: Int): Boolean {
        val c = cuts.getOrNull(i) ?: return false
        return exportedAt.any { Math.abs(it - c.stopSec) < 0.05 }
    }

    // the clips that would be exported right now
    // (remembered: the clips only change when the cuts or buffers do, not every time the screen is redrawn)
    val clips = remember(cuts.toList(), beforePad, afterPad, durationMs) {
        computeClips(cuts, beforePad.toDouble(), afterPad.toDouble(), durationMs / 1000.0)
    }
    val clipByCut = remember(clips) { clips.associateBy { it.cutIndex } }
    val checks = remember(clips) { checkCuts(cuts, clips) }      // too close / overlapping solves (shown as "check")
    var onlyCheck by remember { mutableStateOf(false) }          // the list shows only the solves that need a check
    /** Does this cut need a look? (a scan note you haven't marked as checked, or a timing problem) */
    fun needsCheck(i: Int): Boolean {
        val c = cuts.getOrNull(i) ?: return false
        return (!c.reviewed && c.note.isNotEmpty() && c.note != "added by you") || checks.warnings.containsKey(i)
    }
    fun markChecked(i: Int) {
        if (i in cuts.indices) cuts[i] = cuts[i].copy(reviewed = true, edited = true)
    }
    // rough time Find solves needs: about 0.3 s of work per second of video (an estimate)
    val estMin = maxOf(1, Math.round(durationMs / 1000.0 * 0.3 / 60.0).toInt())

    // When the timeline is zoomed in, keep the playhead on screen: if it is near or past an edge, the timeline scrolls so the
    // playhead is in the middle. Runs on every playhead tick while playing, after every jump (a solve tapped, Prev / Next) and
    // when the zoom changes. Not after a tap on the timeline itself: that spot is on screen already.
    val timelineWidth = remember { IntArray(1) }                 // the timeline's visible width in pixels
    fun keepTimelineOn(ms: Long) {
        val viewPx = timelineWidth[0]
        if (zoom <= 1.01f || viewPx <= 0 || durationMs <= 0) return
        val x = (viewPx * zoom * (ms.toDouble() / durationMs)).toInt()
        val left = timelineScroll.value
        if (x < left + viewPx * 0.05f || x > left + viewPx * 0.95f) {
            scope.launch { timelineScroll.scrollTo(maxOf(0, x - viewPx / 2)) }
        }
    }

    fun seekToSec(sec: Double) {
        val ms = (sec * 1000).toLong().coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        exoPlayer.seekTo(ms)
        positionMs = ms
        keepTimelineOn(ms)
    }

    // opens the full-screen "Set up timer" helper on the picture under the playhead
    fun openTimerSetup() {
        val uri = videoUri
        val atMs = positionMs
        if (uri != null) {
            exoPlayer.pause()
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { grabFrame(context, uri, atMs) }
                if (bmp != null) {
                    setupMessage = ""
                    setupAtMs = atMs
                    setupFrame = bmp
                } else {
                    timerMessage = "Couldn't read a picture here"
                }
            }
        }
    }

    // the real work behind the "Find solves" button (only called after the user pressed Continue)
    fun runFindSolves() {
        val uri = videoUri
        val engine = timerEngine
        val dur = durationMs
        if (uri != null && engine != null && dur > 0 && !analyzing) {
            exoPlayer.pause()
            scope.launch {
                startUsing()                            // the scan reads with the timer engine (see stopUsing in finally)
                analyzing = true
                setupExpanded = false                   // fold the Setup box away as soon as the scan starts
                cancelScan = false
                analysisDone = 0
                analysisTotal = 100
                analysisStatus = "Starting..."
                AnalysisService.start(context)          // keeps the work alive while you use other apps
                var finishedText: String? = null
                try {
                    val started = System.currentTimeMillis()
                    val report = withContext(Dispatchers.Default) {
                        SolveAnalyzer.analyze(
                            context, uri, dur, engine,
                            onProgress = { done, total ->
                                analysisDone = done
                                analysisTotal = total
                                analysisStatus = "Reading the timer along the video: ${done * 100 / maxOf(total, 1)}%"
                                AnalysisService.update(context, done, total)
                            },
                            isCancelled = { cancelScan || !isActive }
                        )
                    }
                    // a re-scan keeps what you changed: see mergeRescan (RescanMerge.kt)
                    val saved = cuts.toList()
                    val found = report.solves.map { r -> normalizeIncluded(CutItem(r.stopSec, r.timeText, r.include, r.note, scrambleAll)) }
                    val exportedStops: List<Double> = footageId?.let { id ->
                        withContext(Dispatchers.IO) {
                            try { SessionStore.loadExports(context, id).map { it.stopSec } } catch (e: Exception) { emptyList() }
                        }
                    } ?: emptyList()
                    val merged = mergeRescan(saved, found, exportedStops)
                    cuts.clear()
                    rowBoxes.clear()
                    selectedCut = -1
                    cuts.addAll(merged.cuts)
                    scanReport = report
                    footageId?.let { id ->
                        withContext(Dispatchers.IO) { try { SessionStore.markScanned(context, id) } catch (e: Exception) { } }
                    }
                    val seconds = (System.currentTimeMillis() - started) / 1000
                    analysisStatus = if (saved.isEmpty()) {
                        "Done in ${seconds}s: ${report.solves.count { it.include && it.note.isEmpty() }} solves, " +
                                "${report.solves.count { it.note.isNotEmpty() }} to check"
                    } else {
                        "Re-scan done in ${seconds}s: " + merged.summary()
                    }
                    finishedText = analysisStatus
                    setupExpanded = cuts.isEmpty()      // fold the Setup box away once it has done its job
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ScanCancelled) {
                    analysisStatus = "Cancelled."
                } catch (e: Exception) {
                    analysisStatus = "Find solves stopped: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    analyzing = false
                    AnalysisService.stop(context)
                    stopUsing(engine)                   // the scan has really stopped reading by now (also when the editor was closed)
                }
                finishedText?.let { AnalysisService.notifyDone(context, it) }
            }
        }
    }

    // ---- delete a cut straight from its row; the last deletion can be undone ----
    var undoCut by remember { mutableStateOf<Pair<Int, CutItem>?>(null) }      // (where it was, the cut) of the last deleted cut
    var undoVideo by remember { mutableStateOf<Uri?>(null) }                   // the video it was deleted from (another video: no Undo)
    LaunchedEffect(undoCut) {
        if (undoCut != null) {
            delay(8000)                         // the Undo bar goes away by itself after a while
            undoCut = null
        }
    }
    // after a delete (or an undo) the solve now in that place is selected and the playhead jumps to it, so you can see where you are
    fun selectAfterChange(i: Int) {
        if (i !in cuts.indices) return
        selectedCut = i
        seekToSec(cuts[i].stopSec - 0.5)
    }
    /** The first row the list shows at or after [start] (or, if there is none, the last one it shows); -1 = it shows none. */
    fun shownFrom(start: Int): Int {
        val shown = cuts.indices.filter { !onlyCheck || needsCheck(it) }
        return shown.firstOrNull { it >= start } ?: shown.lastOrNull() ?: -1
    }
    // a row being removed: it slides away first (about a quarter of a second), then the change is made
    var leavingCut by remember { mutableStateOf<CutItem?>(null) }
    fun slideOut(c: CutItem, then: () -> Unit) {
        if (leavingCut != null) return
        leavingCut = c
        scope.launch {
            delay(280)
            leavingCut = null
            then()
        }
    }
    fun deleteNow(index: Int) {
        if (index !in cuts.indices) return
        undoCut = Pair(index, cuts[index])
        undoVideo = videoUri
        cuts.removeAt(index)
        rowBoxes.clear()
        selectedCut = -1
        selectAfterChange(shownFrom(index))       // the next solve has moved up into this place
    }
    /** Delete button: the row slides away, then the solve after it is selected. */
    fun deleteCut(index: Int) {
        val c = cuts.getOrNull(index) ?: return
        editIndex = -1
        slideOut(c) { deleteNow(cuts.indexOf(c)) }
    }
    /** Checked button: the flag goes, and the next solve in the list is selected (the row slides away if only solves to check are shown). */
    fun checkCut(index: Int) {
        val c = cuts.getOrNull(index) ?: return
        val done = {
            val i = cuts.indexOf(c)
            if (i >= 0) {
                markChecked(i)
                selectAfterChange(shownFrom(i + 1))
            }
        }
        if (onlyCheck) slideOut(c, done) else done()
    }
    fun undoDelete() {
        val u = undoCut ?: return
        undoCut = null
        if (undoVideo != videoUri) return
        val at = u.first.coerceIn(0, cuts.size)
        cuts.add(at, u.second)
        rowBoxes.clear()
        selectAfterChange(at)
    }

    // scroll the list so solve [i]'s card is visible (only when it is not already fully in view)
    fun scrollListTo(i: Int) {
        val rowBox = rowBoxes[i]
        val areaBox = listBox[0]
        if (rowBox != null && areaBox != null && rowBox.isAttached && areaBox.isAttached) {
            val rowTop = rowBox.positionInRoot().y - areaBox.positionInRoot().y     // relative to the visible area
            val rowBottom = rowTop + rowBox.size.height
            if (rowTop < 0f || rowBottom > areaBox.size.height) {
                val target = listScroll.value + rowTop.toInt() - 4
                scope.launch { listScroll.animateScrollTo(maxOf(0, target)) }
            }
        }
    }

    // jump to cut number [target] (used by the Prev / Next buttons)
    fun goToCut(target: Int) {
        if (cuts.isEmpty()) return
        val i = target.coerceIn(0, cuts.size - 1)
        selectedCut = i
        seekToSec(cuts[i].stopSec - 0.5)
        scrollListTo(i)
    }

    // Prev / Next: step through the rows the list shows (only the ones to check while that filter is on)
    fun stepCut(dir: Int) {
        val shown = cuts.indices.filter { !onlyCheck || needsCheck(it) }
        if (shown.isEmpty()) return
        val sel = selectedCut
        // nothing selected (the playhead is between clips): go to the next / previous solve from where the playhead is
        val at = exoPlayer.currentPosition / 1000.0
        val target = when {
            sel !in cuts.indices ->
                if (dir > 0) shown.firstOrNull { cuts[it].stopSec > at } ?: shown.last()
                else shown.lastOrNull { cuts[it].stopSec < at } ?: shown.first()
            dir > 0 -> shown.firstOrNull { it > sel } ?: shown.last()
            else -> shown.lastOrNull { it < sel } ?: shown.first()
        }
        goToCut(target)
    }

    /** Selects solve [i] and jumps to it (switching the "only show solves to check" filter off if it would hide that solve). */
    fun showSolve(i: Int) {
        if (i !in cuts.indices) return
        if (onlyCheck && !needsCheck(i)) {
            onlyCheck = false
            rowBoxes.clear()
        }
        goToCut(i)
    }

    /**
     * The "+ Cut at playhead" button. Nothing is read from the video: the playhead is where the solve stopped (the first frame that
     * shows the final time), and the window that opens asks for the solve time. A playhead inside an existing solve gets a warning
     * instead, because two solves can't overlap (see solveAtPlayhead in RescanMerge.kt).
     */
    fun startManualCut() {
        exoPlayer.pause()
        val at = exoPlayer.currentPosition.coerceAtLeast(0L) / 1000.0
        val inside = solveAtPlayhead(cuts, at)
        if (inside >= 0) {
            cutBlockedAt = at
            cutBlockedBy = inside
        } else {
            addCutAt = at
        }
    }

    // ---- the selected solve follows the playhead while the video plays or you tap / scrub the timeline ----
    // Each solve owns the stretch from the end of the clip before it to the end of its own clip: its scramble and its solve,
    // even when its clip leaves the scramble out. While "Only solves to check" is on, a solve the list hides is never selected.
    // When the selection changes, the list scrolls so its card is in view. Tapping a card, Prev / Next and Undo choose the
    // solve themselves and are not overruled.
    fun solveUnder(ms: Long): Int {
        val t = ms / 1000.0
        var zoneStart = 0.0
        for (c in clips) {                                       // clips are in time order
            if (t >= minOf(zoneStart, c.startSec) && t < c.endSec) {
                return if (!onlyCheck || needsCheck(c.cutIndex)) c.cutIndex else -1
            }
            zoneStart = c.endSec
        }
        return -1
    }
    fun followPlayhead(ms: Long) {
        val i = solveUnder(ms)
        if (i == selectedCut) return
        // a leftover cut (from before the video began) makes no clip, so the playhead is never "in" it: keep it selected
        // until the playhead reaches a real clip, instead of dropping it straight after Prev / Next chose it
        if (i < 0 && selectedCut in cuts.indices && clips.none { it.cutIndex == selectedCut }) return
        selectedCut = i
        if (i >= 0) scrollListTo(i)
    }
    // the playhead loop below keeps running across screen updates, so it calls the newest version of these (with the newest cuts)
    val followNow = rememberUpdatedState<(Long) -> Unit> { ms -> followPlayhead(ms) }
    val keepTimelineNow = rememberUpdatedState<(Long) -> Unit> { ms -> keepTimelineOn(ms) }

    // zooming the timeline keeps the playhead on screen
    LaunchedEffect(zoom) { keepTimelineNow.value(positionMs) }

    // move the red playhead along while the video plays. Paused, nothing moves, so nothing runs (the phone isn't woken 30 times a
    // second for nothing). Only the timeline and the time text read the position, so only they redraw, not the whole screen.
    LaunchedEffect(exoPlayer, isPlaying) {
        positionMs = exoPlayer.currentPosition
        if (isPlaying) {
            while (true) {
                delay(33)
                val ms = exoPlayer.currentPosition
                positionMs = ms
                followNow.value(ms)
                keepTimelineNow.value(ms)
            }
        }
    }

    // opened from a Gallery clip: once the list has been laid out, scroll so the selected row is in view
    LaunchedEffect(scrollToSelected) {
        if (!scrollToSelected) return@LaunchedEffect
        delay(250)                                  // let the screen settle (the Setup box folds away)
        var tries = 0
        while (tries < 30) {
            val i = selectedCut
            val rowBox = rowBoxes[i]
            val areaBox = listBox[0]
            if (i >= 0 && rowBox != null && areaBox != null && rowBox.isAttached && areaBox.isAttached) {
                val rowTop = rowBox.positionInRoot().y - areaBox.positionInRoot().y
                listScroll.animateScrollTo(maxOf(0, listScroll.value + rowTop.toInt() - 4))
                break
            }
            delay(100)
            tries++
        }
        scrollToSelected = false
    }

    // back to the Gallery: save the latest edits first (the autosave waits a moment), then close the editor
    // [showSolve]: the Gallery opens that solve's clip in the viewer (the View button); null = the Footage tab, pointing out this video
    fun leaveToGallery(showSolve: Int? = null) {
        if (analyzing) {
            status = "Find solves is running. Let it finish or cancel it first."
            return
        }
        val openSec = if (showSolve != null) cuts.getOrNull(showSolve)?.stopSec else null     // the solve to show in the Gallery
        scope.launch {
            val id = footageId
            if (id != null) {
                val snapshot = cuts.toList()
                val before = beforePad
                val after = afterPad
                val scramble = scrambleAll
                val moves = HashMap(pendingMoves)
                pendingMoves.clear()
                withContext(Dispatchers.IO) {
                    try {
                        SessionStore.saveSession(context, id, snapshot, before, after, scramble, moves)
                    } catch (e: Exception) {
                    }
                }
            }
            exoPlayer.pause()
            onClose(id, openSec)
        }
    }
    BackHandler { leaveToGallery() }

    // the video's height follows the screen height: clear picture, but room left for the list
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val videoHeight = (screenHeightDp * 0.27f).coerceIn(170f, 300f).dp

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(8.dp)
        ) {
            // ################ PINNED PART (always visible): header, video, timeline, review bar ################

            // ===== header: logo + title on the left, Import on the right =====
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.cubecut_logo),
                    contentDescription = "CubeCut logo",
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("CubeCut", style = MaterialTheme.typography.titleLarge)
                    val statusText = if (status == "Ready") (if (timerEngine == null) "Ready - first, set up the timer" else "Ready") else status
                    Text(statusText, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { leaveToGallery() }, enabled = !analyzing) { Text("Gallery") }
                Button(onClick = {
                    picker.launch(arrayOf("video/*"))
                }, enabled = !analyzing) { Text("Import video") }
            }
            Spacer(Modifier.height(6.dp))

            // ===== the video (pinch to zoom, drag to move, double-tap to reset) =====
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(videoHeight)
                    .background(Color.Black)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            videoScale = 1f
                            videoOffX = 0f
                            videoOffY = 0f
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val newScale = (videoScale * zoomChange).coerceIn(1f, 8f)
                            val cx = centroid.x - size.width / 2f
                            val cy = centroid.y - size.height / 2f
                            val ratio = newScale / videoScale
                            // keep the spot under your fingers where it is, then add the drag
                            val ox = cx - (cx - videoOffX) * ratio + pan.x
                            val oy = cy - (cy - videoOffY) * ratio + pan.y
                            val maxX = (newScale - 1f) * size.width / 2f
                            val maxY = (newScale - 1f) * size.height / 2f
                            videoScale = newScale
                            videoOffX = ox.coerceIn(-maxX, maxX)
                            videoOffY = oy.coerceIn(-maxY, maxY)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        // the player view comes from res/layout/video_player_view.xml (it uses a TextureView, which can be zoomed)
                        (LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null) as PlayerView).apply {
                            this.player = exoPlayer
                            useController = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = videoScale
                            scaleY = videoScale
                            translationX = videoOffX
                            translationY = videoOffY
                        }
                )
                if (videoScale > 1.01f) {
                    Text(
                        "${"%.1f".format(videoScale)}x  (double-tap to reset)",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(Color(0x99000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // ===== play button, time and timeline zoom, all on one line =====
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    enabled = videoUri != null
                ) { Text(if (isPlaying) "Pause" else "Play") }
                Spacer(Modifier.width(8.dp))
                PositionText({ positionMs }, durationMs)
                Spacer(Modifier.width(8.dp))
                Text("Zoom", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = zoom,
                    onValueChange = { zoom = it },
                    valueRange = 1f..10f,
                    modifier = Modifier.weight(1f)
                )
            }

            // ===== the timeline (film strip + what each clip will contain) =====
            // (the box measures how wide the timeline is on screen, for keepTimelineOn)
            Box(Modifier.fillMaxWidth().onGloballyPositioned { timelineWidth[0] = it.size.width }) {
                Timeline(
                    stops = cuts.map { it.stopSec },
                    markerStates = cuts.map { if (it.included) 1 else 2 },
                    selectedIndex = selectedCut,
                    clipBars = clips.map { ClipBar(it.startSec, it.solveStartSec, it.endSec) },
                    thumbs = thumbs,
                    position = { positionMs },
                    durationMs = durationMs,
                    zoom = zoom,
                    scrollState = timelineScroll,
                    onSeek = { ms ->
                        exoPlayer.seekTo(ms)
                        positionMs = ms
                        followPlayhead(ms)          // scrubbing or tapping the timeline selects the solve under the playhead
                    }
                )
            }

            // ===== review bar: step through the solves and switch each one on/off without scrolling =====
            if (cuts.isNotEmpty()) {
                val sel = selectedCut
                val selValid = sel in cuts.indices
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArrowButton(left = true) { stepCut(-1) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (selValid) "Solve ${sel + 1} of ${cuts.size}" else "${cuts.size} solves",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            highlightCheck(
                                if (selValid) {
                                    (if (cuts[sel].timeText.isNotEmpty()) "${cuts[sel].timeText} s" else "time unknown") +
                                            (if (cuts[sel].includeScramble) "  |  with scramble" else "  |  solve only") +
                                            (if (needsCheck(sel)) "  |  check this one" else "")
                                } else "tap an arrow or a solve below"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    ArrowButton(left = false) { stepCut(1) }
                }
            }
            val undone = undoCut
            if (undone != null && undoVideo == videoUri) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Deleted " + (if (undone.second.timeText.isNotEmpty()) "the ${undone.second.timeText} s solve" else "a cut") +
                                " at ${formatTime((undone.second.stopSec * 1000).toLong())}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { undoDelete() }) { Text("Undo") }
                }
            }
            Spacer(Modifier.height(4.dp))

            // ################ always at hand: + Cut at playhead and the two switches ################

            OutlinedButton(
                onClick = { startManualCut() },
                enabled = videoUri != null && durationMs > 0 && !analyzing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("+ Cut at playhead") }

            // the scramble for every clip (a card's own scramble button can then change one clip again), and the check filter
            if (videoUri != null) {
                val allScrambleOn = if (cuts.isEmpty()) scrambleAll else cuts.all { it.includeScramble }
                val mixedScramble = cuts.isNotEmpty() && cuts.any { it.includeScramble } && cuts.any { !it.includeScramble }
                val toCheck = cuts.indices.count { needsCheck(it) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Scramble in all clips", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(
                            if (mixedScramble) "mixed" else if (allScrambleOn) "on" else "off",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = allScrambleOn,
                        enabled = !analyzing,
                        onCheckedChange = { on ->
                            scrambleAll = on
                            for (i in cuts.indices) cuts[i] = cuts[i].copy(includeScramble = on)
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(highlightCheck("Only solves to check"), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(
                            if (toCheck == 0) "none flagged" else "$toCheck flagged",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = onlyCheck,
                        onCheckedChange = { onlyCheck = it; rowBoxes.clear() },
                        enabled = toCheck > 0 || onlyCheck
                    )
                }
            }

            // ################ EVERYTHING BELOW SCROLLS TOGETHER: setup box, Find solves, status, the list ################
            // (only the video, timeline, Prev/Next bar, + Cut at playhead and the two switches above stay put)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { listBox[0] = it }
                    .verticalScroll(listScroll)
            ) {
                val report = scanReport

                // ===== Setup box: the timer (the heart of the app now) and the clip buffers; folds away =====
                val engineNow = timerEngine
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        // the header row is always visible; tap it to open or close the box
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { setupExpanded = !setupExpanded },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(if (engineNow != null) Color(0xFF4CAF50) else Color(0xFFFF9800), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (setupExpanded) "Setup: timer and clip buffers"
                                else (if (engineNow != null) "Timer ready" else "Timer not set up") +
                                        " \u00B7 ${"%.1f".format(beforePad)} s before \u00B7 ${"%.1f".format(afterPad)} s after",
                                style = if (setupExpanded) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (setupExpanded) "Hide \u25B2" else "Change \u25BC", style = MaterialTheme.typography.labelLarge)
                        }

                        if (setupExpanded) {
                            // the open box scrolls inside itself if it is taller than its allowed height
                            Column {
                                Spacer(Modifier.height(8.dp))
                                Text("1. Timer", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (engineNow != null) "Ready (${engineNow.decimals} decimals)" else "Not set up yet",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(6.dp))
                                InfoNote(
                                    title = "Before you set up",
                                    text = "Pause on a finished time, like 12.412. Not 0.000, and no hand in front of it."
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (engineNow == null) {
                                        Button(
                                            onClick = { openTimerSetup() },
                                            enabled = videoUri != null && openCvReady && !analyzing
                                        ) { Text("Set up timer") }
                                    } else {
                                        OutlinedButton(
                                            onClick = { openTimerSetup() },
                                            enabled = videoUri != null && openCvReady && !analyzing
                                        ) { Text("Set up again") }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val uri = videoUri
                                            val engine = timerEngine
                                            val atMs = positionMs
                                            if (uri != null && engine != null) {
                                                scope.launch {
                                                    timerMessage = "Reading..."
                                                    val bmp = withContext(Dispatchers.IO) { grabFrame(context, uri, atMs) }
                                                    if (bmp == null) {
                                                        timerMessage = "Couldn't read a picture here"
                                                    } else {
                                                        startUsing()
                                                        val res = try {
                                                            withContext(Dispatchers.Default) { engine.readFrame(bmp) }
                                                        } finally {
                                                            stopUsing(engine)
                                                        }
                                                        timerMessage = res.message
                                                        timerDebug = res.debug
                                                    }
                                                }
                                            }
                                        },
                                        enabled = videoUri != null && timerEngine != null && !analyzing
                                    ) { Text("Read timer here") }
                                }
                                if (timerMessage.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    InfoNote(timerMessage, kind = kindOfMessage(timerMessage))
                                }
                                val debugPicture = timerDebug
                                if (debugPicture != null) {
                                    Image(
                                        bitmap = debugPicture.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.height(56.dp)
                                    )
                                }

                                Spacer(Modifier.height(12.dp))
                                Text("2. Buffers around the solve", style = MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Before the solve: ${"%.1f".format(beforePad)} s", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    InfoButton(
                                        "Before the solve",
                                        listOf("Used when the scramble is left out.", "The clip starts this long before the timer started.")
                                    )
                                }
                                Slider(value = beforePad, onValueChange = { beforePad = it }, valueRange = 0f..8f, steps = 15)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("After the solve: ${"%.1f".format(afterPad)} s", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    InfoButton(
                                        "After the solve",
                                        listOf("Every clip ends this long after the final time first shows.")
                                    )
                                }
                                Slider(value = afterPad, onValueChange = { afterPad = it }, valueRange = 0f..8f, steps = 15)
                                // (the scramble switch for all clips is not here: it sits under + Cut at playhead, next to the check filter)
                            }
                        }
                    }
                }

                // ===== Find solves: right under the box it depends on (a re-run doesn't need the box opened) =====
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { showFindConfirm = true },
                    enabled = videoUri != null && timerEngine != null && durationMs > 0 && !analyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (analyzing) "Working... ${if (analysisTotal > 0) analysisDone * 100 / analysisTotal else 0}%"
                        else if (cuts.isEmpty()) "Find solves" else "Find solves again"
                    )
                }

                if (analyzing) {
                    val fraction = if (analysisTotal > 0) analysisDone.toFloat() / analysisTotal else 0f
                    val shown by animateFloatAsState(targetValue = fraction, label = "findSolvesProgress")
                    Spacer(Modifier.height(6.dp))
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Finding solves...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${(shown * 100).toInt()}%",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { shown },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(14.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "You can switch apps. A notification shows progress.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { cancelScan = true }) { Text("Cancel") }
                            }
                        }
                    }
                }
                if (analysisStatus.isNotEmpty() && !analyzing) Text(analysisStatus, style = MaterialTheme.typography.bodySmall, maxLines = 2)


                if (videoUri == null) {
                    InfoNote("Import a video to begin.", Modifier.padding(vertical = 6.dp))
                } else if (timerEngine == null && cuts.isEmpty() && !analyzing) {
                    InfoNote(
                        title = "Set up the timer",
                        text = "Use the box above, then press Find solves.",
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else if (cuts.isEmpty() && !analyzing && report == null) {
                    InfoNote(
                        title = "Timer ready",
                        text = "Press Find solves. About $estMin min for this video.",
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }

                // warnings after a scan: never a dead end, always something to try
                if (report != null && !analyzing) {
                    val readablePct = (report.readableFraction * 100).toInt()
                    if (report.solves.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            InfoNote(
                                kind = NoteKind.CHECK,
                                title = "No solves found",
                                text = "The timer was readable on $readablePct% of the frames."
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("What to try", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            BulletLines(
                                listOf(
                                    "Set up again on a frame right after a solve.",
                                    "Use Read timer here on a few moments after solves.",
                                    "Or put the playhead on the first frame of a final time and tap + Cut at playhead."
                                )
                            )
                        }
                    } else if (report.readableFraction < 0.25) {
                        InfoNote(
                            kind = NoteKind.CHECK,
                            title = "Some solves may be missing",
                            text = "The timer was readable on only $readablePct% of the frames. Keep it in view and set up on a clear frame.",
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    if (report.solves.isNotEmpty()) {
                        InfoNote(
                            text = "Missing a solve? Put the playhead on the first frame of its final time and tap + Cut at playhead.",
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                if (cuts.isNotEmpty()) {
                    // every check marked as looked at in one go (on its own line, apart from the filter switch)
                    val canClear = cuts.indices.count { needsCheck(it) && !cuts[it].reviewed }
                    if (canClear > 0) {
                        OutlinedButton(onClick = { showClearChecks = true }) { Text("Mark all $canClear as checked") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Tap a solve to jump to it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        InfoButton(
                            "Solve cards",
                            listOf(
                                "Arrows: put the scramble in or out of this clip (blue = in).",
                                "Edit: fix the time or move the cut.",
                                "View: open the clip in the Gallery.",
                                "Red bin: delete a cut that isn't a solve. Undo brings it back.",
                                "Check: needs a look. Fix it, or tap It's fine.",
                                "To export, choose clips in the Gallery."
                            )
                        )
                    }
                    // one row per cut: tap the row to jump there, Edit to fix or delete it, Gallery to see its clip
                    cuts.forEachIndexed { index, item ->
                        if (onlyCheck && !needsCheck(index)) return@forEachIndexed
                        val clip = clipByCut[index]
                        val isSelected = index == selectedCut
                        // the row slides away when it is deleted, or when it is marked as checked while only solves to check are shown
                        key(item) {
                            AnimatedVisibility(
                                visible = leavingCut != item,
                                exit = shrinkVertically(tween(260)) + fadeOut(tween(200))
                            ) {
                                // one card per solve: grey border (blue when selected); tap it to jump there
                                val shape = RoundedCornerShape(10.dp)
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .onGloballyPositioned { rowBoxes[index] = it }
                                        .clip(shape)
                                        .border(
                                            if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape
                                        )
                                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface)
                                        .clickable {
                                            selectedCut = index
                                            seekToSec(item.stopSec - 0.5)
                                        }
                                        .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.Bottom) {
                                                Text(
                                                    "${index + 1}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(end = 8.dp, bottom = 2.dp)
                                                )
                                                Text(
                                                    if (item.timeText.isNotEmpty()) "${item.timeText} s" else "time unknown",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (item.timeText.isEmpty()) AppColors.check else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            val detail = "at ${formatTime((item.stopSec * 1000).toLong())}" + when {
                                                clip != null -> "  \u00B7  clip ${formatTime((clip.startSec * 1000).toLong())}\u2013${formatTime((clip.endSec * 1000).toLong())}" +
                                                        "  \u00B7  " + when {
                                                    clip.noTime -> "full clip"
                                                    clip.withScramble -> "with scramble"
                                                    else -> "solve only"
                                                }
                                                isLeftover(item) -> "  \u00B7  not a clip"
                                                else -> ""
                                            }
                                            Text(
                                                detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            // this clip's scramble: in (blue) or out (grey, crossed out). Counts as an edit, so a re-scan keeps it
                                            ScrambleButton(on = item.includeScramble, enabled = !analyzing && !isLeftover(item)) {
                                                if (index in cuts.indices) {
                                                    cuts[index] = cuts[index].copy(includeScramble = !cuts[index].includeScramble, edited = true)
                                                }
                                            }
                                            RowButton(
                                                if (item.timeText.isEmpty()) "Add time" else "Edit",
                                                MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface
                                            ) {
                                                selectedCut = index
                                                editIndex = index
                                            }
                                            // this solve's clip in the Gallery (your edits are saved first)
                                            RowButton(
                                                "View", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary,
                                                enabled = !analyzing && footageId != null
                                            ) { leaveToGallery(index) }
                                            // not a solve: delete it at once (the Undo bar at the top brings it back)
                                            DeleteButton(enabled = !analyzing) { deleteCut(index) }
                                        }
                                    }
                                    // what needs a look, under the line, full width
                                    if (item.timeText.isEmpty()) {
                                        Text(highlightCheck("No time yet. Tap Add time."), style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (isLeftover(item)) {
                                        Text("Left over from before the video. Not a clip.", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val warning = checks.warnings[index]
                                    if (warning != null) Text(highlightCheck("check: $warning"), style = MaterialTheme.typography.bodySmall)
                                    if (!item.reviewed && item.note.isNotEmpty() && item.note != "added by you") {
                                        Text(highlightCheck("check: ${item.note}"), style = MaterialTheme.typography.bodySmall)
                                    }
                                    // it was looked at and is fine: stop flagging it
                                    if (needsCheck(index) && !item.reviewed) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "\u2713  It's fine",
                                            color = AppColors.check,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .border(1.dp, AppColors.check, RoundedCornerShape(16.dp))
                                                .clickable { checkCut(index) }
                                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val unknownCount = clips.count { it.timeText.isBlank() }
                    if (unknownCount > 0) {
                        Text(
                            "$unknownCount solves have no time. Use Add time to name their clips.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ===== the author credit: one small line at the very bottom; tap it to open the About window (the same line as the Gallery's) =====
            AuthorCreditLine { showAbout = true }
        }

        // ===== the import progress window: blocks all touches until the video is fully loaded =====
        if (importing) {
            AlertDialog(
                onDismissRequest = { },
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("Importing video") },
                text = {
                    Column {
                        Text(importStage, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { importProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(importProgress * 100).toInt()}%. Long videos can take a minute or two.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = { }
            )
        }

        // ===== mark every check as looked at =====
        if (showClearChecks) {
            val n = cuts.indices.count { needsCheck(it) && !cuts[it].reviewed }
            AlertDialog(
                onDismissRequest = { showClearChecks = false },
                title = { Text("Mark all as checked?") },
                text = {
                    BulletLines(
                        listOf(
                            "$n solves stop being flagged, here and in the Gallery.",
                            "A solve too close to the one before stays flagged. It has no clip until you delete or move one of them."
                        )
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showClearChecks = false
                        for (i in cuts.indices) if (needsCheck(i) && !cuts[i].reviewed) markChecked(i)
                        onlyCheck = false
                    }) { Text("Mark as checked") }
                },
                dismissButton = { TextButton(onClick = { showClearChecks = false }) { Text("Cancel") } }
            )
        }

        // ===== the About window (shared with the Gallery, see AboutDialog in Gallery.kt) =====
        if (showAbout) AboutDialog { showAbout = false }

        // ===== the "Find solves may take a while" window =====
        if (showFindConfirm) {
            AlertDialog(
                onDismissRequest = { showFindConfirm = false },
                title = { Text("Find solves?") },
                text = {
                    Column {
                        LeadAndBullets(
                            "CubeCut reads the timer along the whole video. About $estMin min (an estimate).",
                            listOf(
                                "You can switch apps. A notification shows progress.",
                                "Don't swipe CubeCut away until it's done.",
                                "You can cancel any time."
                            )
                        )
                        if (cuts.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            InfoNote("Solves you changed or exported stay as they are. The rest are updated, and new ones are added.")
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showFindConfirm = false
                        // Android 13+ asks once whether CubeCut may show its progress notification
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        runFindSolves()
                    }) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = { showFindConfirm = false }) { Text("Cancel") }
                }
            )
        }

        // ===== "+ Cut at playhead" on a spot inside an existing solve: no cut, look at that solve first =====
        if (cutBlockedBy in cuts.indices) {
            val n = cutBlockedBy + 1
            val other = cuts[cutBlockedBy]
            val otherTime = parseTimeSeconds(other.timeText)
            fun at(sec: Double) = formatTime((sec * 1000.0).toLong())
            AlertDialog(
                onDismissRequest = { cutBlockedBy = -1 },
                title = { Text("This spot is inside solve $n") },
                text = {
                    BulletLines(
                        listOf(
                            if (otherTime != null) "Solve $n (${other.timeText} s) runs from ${at(other.stopSec - otherTime)} to ${at(other.stopSec)}."
                            else "Solve $n stops at ${at(other.stopSec)}.",
                            "Two solves can't overlap, so no cut was added at ${at(cutBlockedAt)}.",
                            "If solve $n isn't a real solve, delete it first. Then add your cut."
                        )
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val i = cutBlockedBy
                        cutBlockedBy = -1
                        showSolve(i)
                    }) { Text("Go to solve $n") }
                },
                dismissButton = { TextButton(onClick = { cutBlockedBy = -1 }) { Text("Cancel") } }
            )
        }

        // ===== "+ Cut at playhead": type the solve time; a time that would overlap an earlier solve is refused =====
        val newCutAt = addCutAt
        if (newCutAt != null) {
            var text by remember(newCutAt) { mutableStateOf("") }
            var overlapWith by remember(newCutAt) { mutableStateOf(-1) }      // the solve the typed time overlaps (-1 = none)
            val typed = text.trim().trimEnd('.')
            val seconds = parseTimeSeconds(typed)
            fun at(sec: Double) = formatTime((sec * 1000.0).toLong())
            AlertDialog(
                onDismissRequest = { addCutAt = null },
                title = { Text("Add a solve at ${at(newCutAt)}") },
                text = {
                    Column {
                        Text(
                            "The playhead should be on the first frame that shows the final time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { v ->
                                text = v.filter { c -> c.isDigit() || c == '.' || c == ':' }
                                overlapWith = -1
                            },
                            label = { Text("Solve time, e.g. 12.412") },
                            supportingText = { Text("Needed: it sets where the solve starts") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        val o = overlapWith
                        if (o in cuts.indices && seconds != null) {
                            val other = cuts[o]
                            Spacer(Modifier.height(8.dp))
                            InfoNote(
                                kind = NoteKind.PROBLEM,
                                title = "Overlaps solve ${o + 1}",
                                text = "A $typed s solve ending here would start at ${at(newCutAt - seconds)}, before solve ${o + 1} " +
                                        "stopped at ${at(other.stopSec)}. Check the time. If solve ${o + 1} isn't a real solve, delete it first.",
                                action = "Go to solve ${o + 1}",
                                onAction = {
                                    addCutAt = null
                                    showSolve(o)
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (seconds != null) {
                                val o = solveOverlappedBy(cuts, newCutAt, seconds)
                                if (o >= 0) {
                                    overlapWith = o
                                } else {
                                    var position = cuts.indexOfFirst { it.stopSec > newCutAt }
                                    if (position < 0) position = cuts.size
                                    cuts.add(position, CutItem(newCutAt, typed, true, "added by you", scrambleAll, edited = true))
                                    rowBoxes.clear()
                                    addCutAt = null
                                    selectedCut = position
                                    analysisStatus = "Solve added at ${at(newCutAt)}: $typed s."
                                }
                            }
                        },
                        enabled = seconds != null
                    ) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { addCutAt = null }) { Text("Cancel") } }
            )
        }

        // ===== the edit window for one cut =====
        if (editIndex in cuts.indices) {
            val item = cuts[editIndex]
            var text by remember(editIndex) { mutableStateOf(item.timeText) }
            // moves the cut (not the solve time) and shows the frame it now points at
            fun nudge(delta: Double) {
                val from = cuts[editIndex].stopSec
                val to = (from + delta).coerceAtLeast(0.0)
                cuts[editIndex] = cuts[editIndex].copy(stopSec = to, edited = true)
                // remember the move for the save (a second move of the same cut continues the first one)
                val earlier = pendingMoves.entries.firstOrNull { Math.abs(it.value - from) < 0.0005 }
                if (earlier != null) earlier.setValue(to) else pendingMoves[from] = to
                // the "Already exported" note follows the cut too
                exportedAt = exportedAt.map { if (Math.abs(it - from) < 0.05) to else it }
                seekToSec(to)
            }
            // the frame the cut is on, shown in this window (made again after each move, a moment after the last tap)
            val cutMs = Math.round(item.stopSec * 1000.0)
            val cutFrame by produceState<ImageBitmap?>(initialValue = null, cutMs, videoUri) {
                val uri = videoUri ?: return@produceState
                delay(150)
                value = withContext(Dispatchers.IO) {
                    try { ThumbCache.frame(context, uri, cutMs, 540)?.asImageBitmap() } catch (e: Exception) { null }
                }
            }
            AlertDialog(
                onDismissRequest = { editIndex = -1 },
                title = { Text("Edit solve ${editIndex + 1}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (isExported(editIndex)) {
                            InfoNote(
                                kind = NoteKind.CHECK,
                                title = "Already exported",
                                text = "Changes here don't change the file. Export it again to update it."
                            )
                            Spacer(Modifier.height(10.dp))
                        }

                        // ---- 1. the solve time ----
                        Text("Solve time", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ':' } },
                            label = { Text("Seconds, e.g. 12.412") },
                            supportingText = { Text("What the timer showed. Leave empty if unknown.") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))

                        // ---- 2. where the cut is in the video (the buttons move this, not the time above) ----
                        Text("Where the cut is", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "On the first frame where the timer shows the final time. If it's off, move it:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            val f = cutFrame
                            if (f != null) {
                                Image(bitmap = f, contentDescription = "The frame the cut is on", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            } else {
                                Text("Loading the frame...", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                formatTime(cutMs),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .background(Color(0x99000000))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val tight = PaddingValues(horizontal = 6.dp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(onClick = { nudge(-0.5) }, contentPadding = tight) { Text("\u25C2 0.5s") }
                                    OutlinedButton(onClick = { nudge(-0.1) }, contentPadding = tight) { Text("\u25C2 0.1s") }
                                }
                                Text("earlier", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(onClick = { nudge(0.1) }, contentPadding = tight) { Text("0.1s \u25B8") }
                                    OutlinedButton(onClick = { nudge(0.5) }, contentPadding = tight) { Text("0.5s \u25B8") }
                                }
                                Text("later", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedButton(onClick = { seekToSec(cuts[editIndex].stopSec) }) { Text("Show it in the video") }
                        if (item.note.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Note: ${item.note}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val newText = text.trim()
                        if (newText != cuts[editIndex].timeText) cuts[editIndex] = cuts[editIndex].copy(timeText = newText, edited = true)
                        editIndex = -1
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { deleteCut(editIndex) }) { Text("Delete cut") }
                        TextButton(onClick = { editIndex = -1 }) { Text("Close") }
                    }
                }
            )
        }

        // ===== the "Set up timer" screen sits on top of everything while it is open =====
        val frameForSetup = setupFrame
        if (frameForSetup != null) {
            TimerSetupScreen(
                frame = frameForSetup,
                busy = setupBusy,
                message = setupMessage,
                onCancel = { setupFrame = null },
                onSave = { corners, typed ->
                    scope.launch {
                        setupBusy = true
                        setupMessage = "Learning your timer..."
                        val res = withContext(Dispatchers.Default) { TimerVision.createSetup(frameForSetup, corners, typed) }
                        val setup = res.setup
                        val ref = res.reference
                        if (setup != null && ref != null) {
                            val newEngine = withContext(Dispatchers.Default) { TimerEngine(setup, ref) }
                            timerEngine?.release()
                            timerEngine = newEngine
                            val zeroWarning = if (typed.any { it.isDigit() } && typed.all { !it.isDigit() || it == '0' })
                                "\nA zero doesn't teach the timer much. Set up again on a frame that shows a finished time."
                            else ""
                            timerMessage = res.message + zeroWarning
                            timerDebug = null
                            setupFrame = null
                            // remember this setup for this video, so it is brought back the next time the session is opened
                            val savedId = footageId
                            if (savedId != null) {
                                val problem = withContext(Dispatchers.IO) {
                                    try {
                                        SessionStore.saveTimerSetup(context, savedId, setupAtMs, corners, typed)
                                        ""
                                    } catch (e: Exception) {
                                        e.message ?: e.javaClass.simpleName
                                    }
                                }
                                timerMessage += if (problem.isEmpty()) "\nSaved for this video." else "\nCouldn't save this setup: $problem"
                            } else {
                                timerMessage += "\nNot saved: this video has no saved session yet."
                            }
                        } else {
                            setupMessage = res.message
                        }
                        setupBusy = false
                    }
                }
            )
        }
    }
}