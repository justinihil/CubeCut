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
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
const val AUTHOR_FIND_ME = "Find me on TikTok and Facebook: DadCuber"

// true once the image library (OpenCV) has loaded
var openCvReady = false

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openCvReady = OpenCVLoader.initLocal()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
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
fun AppRoot() {
    val context = LocalContext.current
    val ui = remember { GalleryUiState(context.getSharedPreferences("cubecut_ui", Context.MODE_PRIVATE)) }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    val request = editor
    if (request != null) {
        EditorScreen(request = request, onClose = { footageId, solveSec ->
            editor = null
            if (footageId != null && solveSec != null) ui.openClip = Pair(footageId, solveSec)    // "show in Gallery" on a solve
            ui.refresh++
        })
    } else {
        GalleryScreen(ui = ui, onOpenEditor = { editor = it })
    }
}

// words that mean "look at this one" - they are shown in bold amber in the solves list
private val CHECK_COLOR = Color(0xFFFFB74D)
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
            addStyle(SpanStyle(color = CHECK_COLOR, fontWeight = FontWeight.Bold), i, i + word.length)
            from = i + word.length
        }
    }
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
    var selectedCut by remember { mutableStateOf(-1) }           // the cut you are looking at right now (-1 = none)

    // ---- video zoom (pinch the video) ----
    var videoScale by remember { mutableStateOf(1f) }
    var videoOffX by remember { mutableStateOf(0f) }
    var videoOffY by remember { mutableStateOf(0f) }

    // ---- import progress window ----
    var importing by remember { mutableStateOf(false) }
    var importStage by remember { mutableStateOf("") }
    var importProgress by remember { mutableStateOf(0f) }

    // ---- "export finished" window ----
    var exportDialogTitle by remember { mutableStateOf("") }
    var exportDialogText by remember { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }          // the About window (author credit and licence notice)
    var showFindConfirm by remember { mutableStateOf(false) }    // the "this may take a couple of minutes" window
    var analysisDone by remember { mutableStateOf(0) }           // progress of Find solves
    var analysisTotal by remember { mutableStateOf(0) }
    var cancelScan by remember { mutableStateOf(false) }         // the Cancel button of Find solves
    var scanReport by remember { mutableStateOf<ScanResult?>(null) }   // what the last scan learned (for the warnings)
    var setupExpanded by remember { mutableStateOf(true) }       // the Setup box: open at first, closes after Find solves
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ---- export ----
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }

    // keep the screen awake while a long job is running (so the phone doesn't go to sleep halfway)
    val view = LocalView.current
    SideEffect { view.keepScreenOn = analyzing || exporting }
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

    // ---- the video player ----
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // move the red playhead along as the video plays
    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition
            delay(33)
        }
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
        exportStatus = ""
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
                        openedNote = "Opened your saved session: ${opened.cuts.size} cuts" +
                                (if (opened.footage.lastScanMs > 0) ", found on ${formatDate(opened.footage.lastScanMs)}" else "") +
                                ". Set up the timer only to run Find solves again."
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
                            timerMessage = "Timer setup brought back from the last time this video was open. " + res.message
                            timerDebug = null
                            status = (openedNote ?: "Ready") + " Timer set up again from your saved setup."
                        } else {
                            timerMessage = "Couldn't bring back the saved timer setup (${res.message}). Please set it up again."
                            status = (openedNote ?: "Ready") + " The saved timer setup couldn't be used: set it up again."
                        }
                    } else {
                        timerMessage = "Couldn't bring back the saved timer setup (no picture at that point of the video). Please set it up again."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    timerMessage = "Couldn't bring back the saved timer setup: ${e.message ?: e.javaClass.simpleName}. Please set it up again."
                }
            } else if (savedSession != null && savedCorners == null && savedSession.timerFrameMs >= 0) {
                timerMessage = "A timer setup was saved for this video but it couldn't be read. Please set it up again."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Couldn't open this video: ${e.message ?: e.javaClass.simpleName}. " +
                    "If it was moved, import it again: CubeCut recognises it and brings back its saved cuts."
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
        withContext(Dispatchers.IO) {
            try {
                SessionStore.saveSession(context, id, snapshot, before, after, scramble)
            } catch (e: Exception) {
                // saving is a bonus: if it fails the editor keeps working
            }
        }
    }

    // the clips that would be exported right now
    // (remembered: the playhead moves the screen 30 times a second, but the clips only change when the cuts or buffers do)
    val clips = remember(cuts.toList(), beforePad, afterPad, durationMs) {
        computeClips(cuts, beforePad.toDouble(), afterPad.toDouble(), durationMs / 1000.0)
    }
    val clipByCut = remember(clips) { clips.associateBy { it.cutIndex } }
    // rough time Find solves needs: about 0.3 s of work per second of video (an estimate)
    val estMin = maxOf(1, Math.round(durationMs / 1000.0 * 0.3 / 60.0).toInt())

    fun seekToSec(sec: Double) {
        val ms = (sec * 1000).toLong().coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        exoPlayer.seekTo(ms)
        positionMs = ms
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
                    timerMessage = "Couldn't grab a picture from the video here"
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
                    val found = report.solves.map { r -> CutItem(r.stopSec, r.timeText, r.include, r.note, scrambleAll) }
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
                        "Done in ${seconds}s: ${report.solves.count { it.include && it.note.isEmpty() }} solves found, " +
                                "${report.solves.count { it.note.isNotEmpty() }} to check"
                    } else {
                        "Re-scan done in ${seconds}s: " + merged.summary()
                    }
                    finishedText = analysisStatus
                    setupExpanded = cuts.isEmpty()      // fold the Setup box away once it has done its job
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ScanCancelled) {
                    analysisStatus = "Find solves cancelled."
                } catch (e: Exception) {
                    analysisStatus = "Find solves stopped: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    analyzing = false
                    AnalysisService.stop(context)
                }
                finishedText?.let { AnalysisService.notifyDone(context, it) }
            }
        }
    }

    // jump to cut number [target] (used by the Prev / Next buttons)
    fun goToCut(target: Int) {
        if (cuts.isEmpty()) return
        val i = target.coerceIn(0, cuts.size - 1)
        selectedCut = i
        seekToSec(cuts[i].stopSec - 0.5)
        // scroll the list so the selected row is visible (only when it is not already fully in view)
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
    fun leaveToGallery(showSolve: Int? = null) {
        if (analyzing || exporting) {
            status = if (analyzing) "Find solves is still running: let it finish or press Cancel first" else "An export is running: wait until it finishes"
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
                withContext(Dispatchers.IO) {
                    try {
                        SessionStore.saveSession(context, id, snapshot, before, after, scramble)
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
                TextButton(onClick = { leaveToGallery() }, enabled = !analyzing && !exporting) { Text("Gallery") }
                Button(onClick = {
                    picker.launch(arrayOf("video/*"))
                }, enabled = !analyzing && !exporting) { Text("Import video") }
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
                Text("${formatTime(positionMs)} / ${formatTime(durationMs)}", style = MaterialTheme.typography.bodySmall)
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
            Timeline(
                stops = cuts.map { it.stopSec },
                markerStates = cuts.map { if (it.included) 1 else 2 },
                selectedIndex = selectedCut,
                clipBars = clips.map { ClipBar(it.startSec, it.solveStartSec, it.endSec) },
                thumbs = thumbs,
                positionMs = positionMs,
                durationMs = durationMs,
                zoom = zoom,
                scrollState = timelineScroll,
                onSeek = { ms ->
                    exoPlayer.seekTo(ms)
                    positionMs = ms
                }
            )

            // ===== review bar: step through the solves and switch each one on/off without scrolling =====
            if (cuts.isNotEmpty()) {
                val sel = selectedCut
                val selValid = sel in cuts.indices
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { goToCut(if (selValid) sel - 1 else cuts.size - 1) }) { Text("\u2039 Prev") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (selValid) "Solve ${sel + 1} of ${cuts.size}" else "${cuts.size} solves",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            highlightCheck(
                                if (selValid) {
                                    (if (cuts[sel].timeText.isNotEmpty()) "${cuts[sel].timeText} s" else "time unknown") +
                                            (if (cuts[sel].included) "  |  included" else "  |  left out") +
                                            (if (cuts[sel].includeScramble) "  |  with scramble" else "  |  solve only") +
                                            (if (cuts[sel].note.isNotEmpty() && cuts[sel].note != "added by you") "  |  check this one" else "")
                                } else "tap Next or a row below"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { goToCut(if (selValid) sel + 1 else 0) }) { Text("Next \u203A") }
                    Switch(
                        checked = selValid && cuts[sel].included,
                        onCheckedChange = { on -> if (selValid) cuts[sel] = cuts[sel].copy(included = on, edited = true) },
                        enabled = selValid
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // ################ the two main buttons stay pinned ################

            // ===== Find solves + add a cut =====
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showFindConfirm = true },
                    enabled = videoUri != null && timerEngine != null && durationMs > 0 && !analyzing,
                    modifier = Modifier.weight(1f)
                ) { Text(if (analyzing) "Working... ${if (analysisTotal > 0) analysisDone * 100 / analysisTotal else 0}%" else "Find solves") }
                OutlinedButton(
                    onClick = {
                        val uri = videoUri
                        val engine = timerEngine
                        val at = positionMs / 1000.0
                        val dur = durationMs
                        if (uri != null) {
                            scope.launch {
                                analysisStatus = "Adding a cut at ${formatTime(positionMs)}..."
                                var stop = at
                                var text = ""
                                var note = "added by you"
                                if (engine != null) {
                                    val found = withContext(Dispatchers.Default) {
                                        SolveAnalyzer.analyzeOne(context, uri, at, dur, engine)
                                    }
                                    stop = found.stopSec
                                    text = found.timeText
                                    note = found.note
                                }
                                var position = cuts.indexOfFirst { it.stopSec > stop }
                                if (position < 0) position = cuts.size
                                cuts.add(position, CutItem(stop, text, true, note, scrambleAll, edited = true))
                                selectedCut = position
                                analysisStatus = if (text.isNotEmpty()) "Cut added at the first frame that shows $text. Use Edit to adjust it."
                                else "Cut added. Use Edit to fix its time or move it."
                            }
                        }
                    },
                    enabled = videoUri != null && !analyzing,
                    modifier = Modifier.weight(1f)
                ) { Text("+ Cut at playhead") }
            }

            // ===== one switch for the scramble of ALL clips - pinned, so it stays visible while the list scrolls =====
            // (each clip also has its own tick box in its row and can differ afterwards)
            val allScrambleOn = if (cuts.isEmpty()) scrambleAll else cuts.all { it.includeScramble }
            val mixedScramble = cuts.isNotEmpty() && cuts.any { it.includeScramble } && cuts.any { !it.includeScramble }
            if (cuts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Include scramble in all clips" + if (mixedScramble) " (mixed)" else if (allScrambleOn) " (on)" else " (off)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = allScrambleOn,
                        onCheckedChange = { on ->
                            scrambleAll = on
                            for (i in cuts.indices) cuts[i] = cuts[i].copy(includeScramble = on)
                        }
                    )
                }
            }

            // ################ EVERYTHING BELOW SCROLLS TOGETHER: setup box, status, scramble switch, the list ################
            // (only the video, timeline, Prev/Next bar and the two buttons above stay put, so the list gets the rest of the screen)
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
                                        " | ${"%.1f".format(beforePad)} s before | ${"%.1f".format(afterPad)} s after",
                                style = if (setupExpanded) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (setupExpanded) "Hide \u25B2" else "Show \u25BC", style = MaterialTheme.typography.labelLarge)
                        }

                        if (setupExpanded) {
                            // the open box scrolls inside itself if it is taller than its allowed height
                            Column {
                                Spacer(Modifier.height(8.dp))
                                Text("1. Timer", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (engineNow != null) "Timer is set up (${engineNow.decimals} decimals) - ready to use"
                                    else "Timer not set up yet",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Use a frame that shows an actual time after a solve: the timer has stopped and shows the final time " +
                                            "(for example 12.412), with no hand in front of it. Do NOT use a frame that shows 0.000. " +
                                            "Pause the video on that frame, then tap " + (if (engineNow == null) "Set up timer." else "Set up again."),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
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
                                                        timerMessage = "Couldn't grab a picture from the video here"
                                                    } else {
                                                        val res = withContext(Dispatchers.Default) { engine.readFrame(bmp) }
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
                                    Text(timerMessage, style = MaterialTheme.typography.bodySmall)
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
                                Text(
                                    "Before the solve: ${"%.1f".format(beforePad)} s  (used when the scramble is left out: the clip starts this long " +
                                            "before the timer started, worked out from the time on the timer)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Slider(value = beforePad, onValueChange = { beforePad = it }, valueRange = 0f..8f, steps = 15)
                                Text(
                                    "After the solve: ${"%.1f".format(afterPad)} s  (every clip ends this long after the first frame that shows the time)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Slider(value = afterPad, onValueChange = { afterPad = it }, valueRange = 0f..8f, steps = 15)
                            }
                        }
                    }
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
                                    "Reading the timer along the video. You can switch to another app - a notification shows the progress.",
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
                    Text("Import a video to begin.", style = MaterialTheme.typography.bodySmall)
                } else if (timerEngine == null && cuts.isEmpty() && !analyzing) {
                    Text(
                        "Set up the timer in the box above (use a frame that shows an actual time after a solve, not 0.000), then press Find solves.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else if (cuts.isEmpty() && !analyzing && report == null) {
                    Text(
                        "Timer ready. Press Find solves: CubeCut reads the timer along the whole video (about $estMin min for this video) " +
                                "and cuts at the first frame of each final time.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // warnings after a scan: never a dead end, always something to try
                if (report != null && !analyzing) {
                    val readablePct = (report.readableFraction * 100).toInt()
                    if (report.solves.isEmpty()) {
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    highlightCheck(
                                        "check: no stopped time was found, so nothing was cut. The timer could be read on $readablePct% of the frames looked at."
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "What to try: Set up again on a frame right after a solve (the stopped time showing, no hand in front), " +
                                            "then use Read timer here on a few moments after solves to see whether it reads them. " +
                                            "Or cut by hand: put the playhead on a frame where a final time is showing and tap + Cut at playhead.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    } else if (report.readableFraction < 0.25) {
                        Text(
                            highlightCheck(
                                "check: the timer could be read on only $readablePct% of the frames looked at, so some solves may be missing. " +
                                        "Make sure it stays in view and that the setup was done on a clear frame."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    if (report.solves.isNotEmpty()) {
                        Text(
                            highlightCheck(
                                "check: make sure every solve was captured. If one is missing, put the playhead on the END of that solve " +
                                        "(the first frame that shows the final time) and tap + Cut at playhead."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                if (cuts.isNotEmpty()) {
                    Text(
                        "Green = will be exported, grey = left out. Tap a row to jump to just before the time first appears. " +
                                "Tick \"Include scramble\" to keep the part before the solve, untick it for the solve only.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    // one row per cut: switch = include in the export, box = include the scramble, tap the row to jump there, Edit to fix it
                    cuts.forEachIndexed { index, item ->
                        val clip = clipByCut[index]
                        val isSelected = index == selectedCut
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .padding(vertical = 2.dp)
                                .onGloballyPositioned { rowBoxes[index] = it },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = item.included,
                                onCheckedChange = { on -> cuts[index] = cuts[index].copy(included = on, edited = true) }
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Column(
                                    Modifier.clickable {
                                        selectedCut = index
                                        seekToSec(item.stopSec - 0.5)
                                    }
                                ) {
                                    Text(
                                        highlightCheck(
                                            "${index + 1}.  " + (if (item.timeText.isNotEmpty()) "${item.timeText} s" else "time unknown") +
                                                    "   (time first shows at ${formatTime((item.stopSec * 1000).toLong())})"
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    val unknownHint = if (item.timeText.isEmpty()) " - no time yet: tap Add time to type it" else ""
                                    val line = when {
                                        item.included && clip != null -> {
                                            val mode = when {
                                                clip.noTime -> "full clip (no time yet, so the scramble can't be cut out)"
                                                clip.withScramble -> "with scramble"
                                                else -> "solve only"
                                            }
                                            "clip ${clip.number}: ${formatTime((clip.startSec * 1000).toLong())} - ${formatTime((clip.endSec * 1000).toLong())}  |  $mode" + unknownHint
                                        }
                                        item.included -> "too close to the previous cut - will be skipped"
                                        item.note.isNotEmpty() -> "check: ${item.note}"
                                        else -> "left out"
                                    }
                                    Text(highlightCheck(line), style = MaterialTheme.typography.bodySmall)
                                    if (item.included && item.note.isNotEmpty() && item.note != "added by you") {
                                        Text(highlightCheck("check: ${item.note}"), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = item.includeScramble,
                                        onCheckedChange = { on -> cuts[index] = cuts[index].copy(includeScramble = on, edited = true) }
                                    )
                                    Text("Include scramble", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = {
                                    selectedCut = index
                                    editIndex = index
                                }) {
                                    Text(if (item.timeText.isEmpty()) "Add time" else "Edit")
                                }
                                // go straight to this solve's page in the Gallery (your edits are saved first)
                                TextButton(onClick = { leaveToGallery(index) }, enabled = !analyzing && !exporting && footageId != null) {
                                    Text("Gallery")
                                }
                            }
                        }
                    }
                    // ===== export: only at the very end of the list =====
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val uri = videoUri
                            if (uri != null && clips.isNotEmpty() && !exporting) {
                                val toExport = clips.toList()
                                val cutsAtExport = cuts.toList()
                                val footageAtExport = footageId
                                exoPlayer.pause()
                                scope.launch {
                                    exporting = true
                                    exportStatus = "Starting export..."
                                    val result = withContext(Dispatchers.IO) {
                                        ClipExporter.export(context, uri, toExport) { done, total ->
                                            exportStatus = "Exporting clip ${minOf(done + 1, total)} of $total..."
                                        }
                                    }
                                    exportStatus = result.message
                                    exporting = false
                                    // remember which solves were exported and where the files are (the Gallery shows them as Exported)
                                    if (footageAtExport != null && result.files.isNotEmpty()) {
                                        val now = System.currentTimeMillis()
                                        val records = result.files.mapNotNull { f ->
                                            val cut = cutsAtExport.getOrNull(f.clip.cutIndex)
                                            if (cut == null) null
                                            else ExportedClip(0L, footageAtExport, cut.stopSec, cut.timeText, f.uri, f.fileName, Math.max(0.0, cut.stopSec - f.startSec), now)
                                        }
                                        withContext(Dispatchers.IO) {
                                            try { SessionStore.recordExports(context, footageAtExport, records) } catch (e: Exception) { }
                                        }
                                    }
                                    val folder = if (Build.VERSION.SDK_INT >= 29) "Movies/CubeCut"
                                    else "Android/data/${context.packageName}/files/Movies/CubeCut"
                                    exportDialogTitle = if (result.failed.isEmpty()) "Export complete" else "Export finished with problems"
                                    exportDialogText =
                                        (if (result.failed.isEmpty()) "All ${result.done} clips were saved."
                                        else "${result.done} of ${toExport.size} clips were saved.\n\nProblems:\n" + result.failed.joinToString("\n")) +
                                                "\n\nSaved in the folder: $folder\n\nYou can find them in your Gallery app, or in the Files app under that folder."
                                }
                            }
                        },
                        enabled = videoUri != null && clips.isNotEmpty() && !exporting,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (exporting) "Exporting..." else "Export ${clips.size} clips") }
                    val unknownCount = clips.count { it.timeText.isBlank() }
                    if (unknownCount > 0) {
                        Text(
                            "$unknownCount clips have no time yet (they export as \"unknown\"; use Add time).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (exportStatus.isNotEmpty()) Text(exportStatus, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            }

            // ===== the author credit: one small line at the very bottom; tap it to open the About window =====
            Text(
                "CubeCut by $AUTHOR_NAME",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAbout = true }
                    .padding(top = 4.dp)
            )
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
                            "${(importProgress * 100).toInt()}%  -  please wait, a long video can take a minute or two.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = { }
            )
        }

        // ===== the "export finished" window =====
        val exportText = exportDialogText
        if (exportText != null) {
            AlertDialog(
                onDismissRequest = { exportDialogText = null },
                title = { Text(exportDialogTitle) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(exportText, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    Button(onClick = { exportDialogText = null }) { Text("OK") }
                }
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
                        Text(
                            "CubeCut reads the timer along the whole video and cuts at the first frame where each final time appears. " +
                                    "This may take a few minutes (roughly $estMin min for this video - only an estimate). " +
                                    "You can switch to another app while it works - a notification will show the progress, and there is a Cancel button. " +
                                    "Keep CubeCut in your recent apps (don't swipe it away) until it is done."
                        )
                        if (cuts.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "You already have a saved list of solves for this video. Solves you changed or exported stay exactly as they are; " +
                                        "solves you haven't touched are updated, and any solves found for the first time are added.",
                                style = MaterialTheme.typography.bodySmall
                            )
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

        // ===== the edit window for one cut =====
        if (editIndex in cuts.indices) {
            val item = cuts[editIndex]
            var text by remember(editIndex) { mutableStateOf(item.timeText) }
            // moves the cut and shows the frame it now points at
            fun nudge(delta: Double) {
                cuts[editIndex] = cuts[editIndex].copy(stopSec = (cuts[editIndex].stopSec + delta).coerceAtLeast(0.0), edited = true)
                seekToSec(cuts[editIndex].stopSec)
            }
            AlertDialog(
                onDismissRequest = { editIndex = -1 },
                title = { Text("Edit solve ${editIndex + 1}") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ':' } },
                            label = { Text("Time on the timer, e.g. 12.412 (empty = unknown)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Cut point (first frame that shows the time): ${"%.2f".format(item.stopSec)} s  (${formatTime((item.stopSec * 1000).toLong())})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val tight = PaddingValues(horizontal = 8.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { nudge(-0.5) }, contentPadding = tight) { Text("-0.5") }
                            OutlinedButton(onClick = { nudge(-0.1) }, contentPadding = tight) { Text("-0.1") }
                            OutlinedButton(onClick = { nudge(0.1) }, contentPadding = tight) { Text("+0.1") }
                            OutlinedButton(onClick = { nudge(0.5) }, contentPadding = tight) { Text("+0.5") }
                        }
                        OutlinedButton(onClick = { seekToSec(cuts[editIndex].stopSec) }) { Text("Jump to this frame") }
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
                        TextButton(onClick = {
                            cuts.removeAt(editIndex)
                            rowBoxes.clear()
                            selectedCut = -1
                            editIndex = -1
                        }) { Text("Delete cut") }
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
                                "\nWarning: a zero doesn't teach the timer much. For best results use Set up again on a frame right after a solve, when the stopped time is showing."
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
                                timerMessage += if (problem.isEmpty()) "\nThis setup is remembered for this video." else "\nCouldn't remember this setup: $problem"
                            } else {
                                timerMessage += "\nThis setup can't be remembered: the video has no saved session yet."
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