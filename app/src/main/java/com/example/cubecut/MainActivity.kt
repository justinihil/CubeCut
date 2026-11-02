package com.example.cubecut

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

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
                    EditorScreen()
                }
            }
        }
    }
}

// words that mean "look at this one" - they are shown in bold amber in the solves list
private val CHECK_COLOR = Color(0xFFFFB74D)
private val CHECK_KEYWORDS = listOf(
    "check", "couldn't read", "not steady", "shows zero", "repeated noise",
    "time unknown", "no time yet", "too close", "will be skipped"
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
fun EditorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- the video ----
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var durationMs by remember { mutableStateOf(0L) }
    var thumbs by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var waveform by remember { mutableStateOf<FloatArray?>(null) }
    var loudness by remember { mutableStateOf<FloatArray?>(null) }   // loudness of every 10 ms of the video
    var positionMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Import a video to begin") }

    // ---- settings ----
    var spikeRatio by remember { mutableStateOf(DEFAULT_SPIKE_RATIO) }
    var endPad by remember { mutableStateOf(2.5f) }              // seconds kept after the stop sound
    var zoom by remember { mutableStateOf(1f) }
    val timelineScroll = rememberScrollState()

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
    var showFindConfirm by remember { mutableStateOf(false) }    // the "this may take a couple of minutes" window
    var analysisDone by remember { mutableStateOf(0) }           // progress of Find solves
    var analysisTotal by remember { mutableStateOf(0) }
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

    // the system video picker (needs no storage permission)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) videoUri = uri
    }

    // when a video is chosen: load it into the player, then build the film strip and the loudness data
    LaunchedEffect(videoUri) {
        val uri = videoUri ?: return@LaunchedEffect
        // the timer setup belongs to one video: a new video needs a new setup
        timerEngine = null
        timerDebug = null
        timerMessage = ""
        thumbs = emptyList()
        waveform = null
        loudness = null
        cuts.clear()
        selectedCut = -1
        analysisStatus = ""
        exportStatus = ""
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

            importStage = "Building film strip..."
            status = "Building film strip..."
            thumbs = withContext(Dispatchers.IO) {
                buildThumbnails(context, uri, dur, THUMB_COUNT) { p -> importProgress = 0.05f + 0.20f * p }
            }

            importStage = "Analysing audio..."
            status = "Analysing audio..."
            val levels = withContext(Dispatchers.IO) {
                decodeLoudness(context, uri, dur) { p -> importProgress = 0.25f + 0.75f * p }
            }
            loudness = levels
            waveform = makeWaveform(levels, WAVE_BUCKETS)
            importProgress = 1f

            status = "Ready"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Couldn't import this video: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            importing = false
        }
    }

    // the timer setup is not kept between runs any more; remove the files an older version left behind
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { TimerStore.clear(context) }
    }

    // all loud sounds found in the audio (re-computed instantly when the sensitivity slider moves)
    val sounds: List<Double> = remember(loudness, spikeRatio) {
        val levels = loudness
        if (levels == null) emptyList() else findSpikes(levels, spikeRatio)
    }
    // the clips that would be exported right now
    val clips = computeClips(cuts, endPad.toDouble(), durationMs / 1000.0)

    fun seekToSec(sec: Double) {
        val ms = (sec * 1000).toLong().coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        exoPlayer.seekTo(ms)
        positionMs = ms
    }

    // the real work behind the "Find solves" button (only called after the user pressed Continue)
    fun runFindSolves() {
        val uri = videoUri
        val engine = timerEngine
        val soundList = sounds.toList()
        val dur = durationMs
        if (uri != null && engine != null && soundList.isNotEmpty() && !analyzing) {
            exoPlayer.pause()
            scope.launch {
                analyzing = true
                analysisDone = 0
                analysisTotal = soundList.size
                analysisStatus = "Starting..."
                AnalysisService.start(context)          // keeps the work alive while you use other apps
                var finishedText: String? = null
                try {
                    val started = System.currentTimeMillis()
                    val found = withContext(Dispatchers.Default) {
                        SolveAnalyzer.analyze(context, uri, soundList, dur, engine) { done, total ->
                            analysisDone = done
                            analysisTotal = total
                            analysisStatus = "Checking the timer after sound ${minOf(done + 1, total)} of $total..."
                            AnalysisService.update(context, done, total)
                        }
                    }
                    cuts.clear()
                    selectedCut = -1
                    for (r in found) {
                        cuts.add(CutItem(r.soundSec, if (r.digits != null) r.timeText else "", r.isSolve, r.reason, false))
                    }
                    val seconds = (System.currentTimeMillis() - started) / 1000
                    analysisStatus = "Done in ${seconds}s: ${found.count { it.isSolve }} solves found, " +
                            "${found.count { !it.isSolve }} to check"
                    finishedText = analysisStatus
                    setupExpanded = false                // fold the Setup box away now that it has been used
                } catch (e: CancellationException) {
                    throw e
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
        seekToSec(cuts[i].soundSec - 0.5)
    }

    // the video's height follows the screen height: clear picture, but room left for the list
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val videoHeight = (screenHeightDp * 0.30f).coerceIn(180f, 320f).dp
    // when the Setup box is open it may use at most this much height (it scrolls inside itself beyond that)
    val setupMaxHeight = (screenHeightDp * 0.35f).dp

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
                    val statusText = if (status == "Ready") "Ready - ${sounds.size} loud sounds found" else status
                    Text(statusText, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }) { Text("Import video") }
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

            // ===== the timeline (film strip + audio waveform), half the old height =====
            Timeline(
                sounds = if (cuts.isEmpty()) sounds else cuts.map { it.soundSec },
                markerStates = if (cuts.isEmpty()) sounds.map { 0 } else cuts.map { if (it.included) 1 else 2 },
                selectedIndex = if (cuts.isEmpty()) -1 else selectedCut,
                thumbs = thumbs,
                waveform = waveform,
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
                                            (if (cuts[sel].note.isNotEmpty() && cuts[sel].note != "added by you") "  |  check this one" else "")
                                } else "tap Next or a row below"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { goToCut(if (selValid) sel + 1 else 0) }) { Text("Next \u203A") }
                    Switch(
                        checked = selValid && cuts[sel].included,
                        onCheckedChange = { on -> if (selValid) cuts[sel] = cuts[sel].copy(included = on) },
                        enabled = selValid
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // ################ MIDDLE: Setup box + action buttons (fixed, they do not scroll) ################

            // ===== Setup box: timer, sound sensitivity, clip length (folds away) =====
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
                            if (setupExpanded) "Setup: timer, sensitivity, clip length"
                            else (if (engineNow != null) "Timer ready" else "Timer not set up") +
                                    " | ${"%.1f".format(spikeRatio)}x | ${sounds.size} sounds | clip ${"%.1f".format(endPad)} s",
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
                        Column(
                            Modifier
                                .heightIn(max = setupMaxHeight)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Text("Timer", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (engineNow != null) "Timer is set up (${engineNow.decimals} decimals) - ready to use"
                                else "No timer set up yet",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (engineNow == null) {
                                Text(
                                    "Import a video, pause it on a sharp view of the timer display (hands away), then tap Set up timer.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val uri = videoUri
                                        val atMs = positionMs
                                        if (uri != null) {
                                            exoPlayer.pause()
                                            scope.launch {
                                                val bmp = withContext(Dispatchers.IO) { grabFrame(context, uri, atMs) }
                                                if (bmp != null) {
                                                    setupMessage = ""
                                                    setupFrame = bmp
                                                } else {
                                                    timerMessage = "Couldn't grab a picture from the video here"
                                                }
                                            }
                                        }
                                    },
                                    enabled = videoUri != null && openCvReady
                                ) { Text(if (engineNow != null) "Set up again" else "Set up timer") }
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
                                    enabled = videoUri != null && timerEngine != null
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
                            Text("Sound sensitivity", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "A sound must be ${"%.1f".format(spikeRatio)}x louder than the second before it " +
                                        "(lower = finds more). Loud sounds found: ${sounds.size}. Applies the next time you press Find solves.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(value = spikeRatio, onValueChange = { spikeRatio = it }, valueRange = 2f..8f)

                            Text("Clip length after the stop sound", style = MaterialTheme.typography.titleSmall)
                            Text("${"%.1f".format(endPad)} s", style = MaterialTheme.typography.bodySmall)
                            Slider(value = endPad, onValueChange = { endPad = it }, valueRange = 1f..6f)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // ===== Find solves + add a cut =====
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showFindConfirm = true },
                    enabled = videoUri != null && timerEngine != null && sounds.isNotEmpty() && !analyzing,
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
                                var text = ""
                                var note = "added by you"
                                if (engine != null) {
                                    val candidate = withContext(Dispatchers.Default) {
                                        SolveAnalyzer.analyzeOne(context, uri, at, dur, engine)
                                    }
                                    if (candidate.digits != null) text = candidate.timeText
                                    if (!candidate.isSolve) note = "added by you - ${candidate.reason}"
                                }
                                var position = cuts.indexOfFirst { it.soundSec > at }
                                if (position < 0) position = cuts.size
                                cuts.add(position, CutItem(at, text, true, note, true))
                                selectedCut = position
                                analysisStatus = "Cut added. Use Edit to fix its time or move it."
                            }
                        }
                    },
                    enabled = videoUri != null && !analyzing,
                    modifier = Modifier.weight(1f)
                ) { Text("+ Cut at playhead") }
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
                        Text(
                            "Checking sound ${minOf(analysisDone + 1, analysisTotal)} of $analysisTotal. " +
                                    "You can switch to another app - a notification shows the progress.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (analysisStatus.isNotEmpty() && !analyzing) Text(analysisStatus, style = MaterialTheme.typography.bodySmall, maxLines = 2)

            // ################ THE SOLVES LIST: scrolls on its own, nothing else moves ################
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (timerEngine == null) {
                    Text("First set up your timer (the Setup box above).", style = MaterialTheme.typography.bodySmall)
                }
                if (cuts.isEmpty()) {
                    // before "Find solves": one button per loud sound, tap to jump to just before it
                    LazyRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(sounds) { index, t ->
                            OutlinedButton(onClick = { seekToSec(t - 0.5) }) {
                                Text("${index + 1}: ${formatTime((t * 1000).toLong())}")
                            }
                        }
                    }
                } else {
                    Text(
                        "Green = will be exported, grey = left out. Tap a row to jump there. " +
                                "To add a missed cut: put the playhead on the stop sound, then tap + Cut at playhead.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    // one row per cut: switch = include in the export, tap the row to jump there, Edit to fix it
                    cuts.forEachIndexed { index, item ->
                        val clip = clips.firstOrNull { it.cutIndex == index }
                        val isSelected = index == selectedCut
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = item.included,
                                onCheckedChange = { on -> cuts[index] = cuts[index].copy(included = on) }
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable {
                                        selectedCut = index
                                        seekToSec(item.soundSec - 0.5)
                                    }
                            ) {
                                Text(
                                    highlightCheck(
                                        "${index + 1}.  " + (if (item.timeText.isNotEmpty()) "${item.timeText} s" else "time unknown") +
                                                "   (sound at ${formatTime((item.soundSec * 1000).toLong())})"
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val unknownHint = if (item.timeText.isEmpty()) " - no time yet: tap Add time to type it" else ""
                                val line = when {
                                    item.included && clip != null ->
                                        "clip ${clip.number}: ${formatTime((clip.startSec * 1000).toLong())} - ${formatTime((clip.endSec * 1000).toLong())}" + unknownHint
                                    item.included -> "too close to the previous cut - will be skipped"
                                    item.note.isNotEmpty() -> "check: ${item.note}"
                                    else -> "left out"
                                }
                                Text(highlightCheck(line), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                selectedCut = index
                                editIndex = index
                            }) {
                                Text(if (item.timeText.isEmpty()) "Add time" else "Edit")
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

        // ===== the "Find solves may take a while" window =====
        if (showFindConfirm) {
            val estMin = maxOf(1, (sounds.size * 6 + 30) / 60)
            AlertDialog(
                onDismissRequest = { showFindConfirm = false },
                title = { Text("Find solves?") },
                text = {
                    Column {
                        Text(
                            "This may take a couple of minutes (roughly $estMin min for ${sounds.size} loud sounds - " +
                                    "this is only an estimate). You can switch to another app while it works - a notification will show the progress. " +
                                    "Keep CubeCut in your recent apps (don't swipe it away) until it is done."
                        )
                        if (cuts.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "You already have a list of solves. Continuing replaces it, including any edits you made.",
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
                            "Cut point (the stop sound): ${formatTime((item.soundSec * 1000).toLong())}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                cuts[editIndex] = cuts[editIndex].copy(soundSec = (cuts[editIndex].soundSec - 0.5).coerceAtLeast(0.0))
                                seekToSec(cuts[editIndex].soundSec - 0.5)
                            }) { Text("-0.5 s") }
                            OutlinedButton(onClick = {
                                cuts[editIndex] = cuts[editIndex].copy(soundSec = cuts[editIndex].soundSec + 0.5)
                                seekToSec(cuts[editIndex].soundSec - 0.5)
                            }) { Text("+0.5 s") }
                            OutlinedButton(onClick = { seekToSec(cuts[editIndex].soundSec - 0.5) }) { Text("Jump") }
                        }
                        if (item.note.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Note: ${item.note}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        cuts[editIndex] = cuts[editIndex].copy(timeText = text.trim())
                        editIndex = -1
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            cuts.removeAt(editIndex)
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
                            timerEngine = withContext(Dispatchers.Default) { TimerEngine(setup, ref) }
                            timerMessage = res.message
                            timerDebug = null
                            setupFrame = null
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