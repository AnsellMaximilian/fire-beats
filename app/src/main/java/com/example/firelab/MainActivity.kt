package com.example.firelab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.example.firelab.ui.theme.FireLabTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private lateinit var receiver: ReceiverController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        receiver = ReceiverController(applicationContext, BuildConfig.SERVER_URL)

        setContent {
            FireLabTheme {
                ReceiverScreen(receiver)
            }
        }
    }

    override fun onDestroy() {
        receiver.close()
        super.onDestroy()
    }
}

@Composable
private fun ReceiverScreen(receiver: ReceiverController) {
    val state by receiver.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (state.poseChannelOpen) {
            PoseScreen(state)
        } else if (state.remoteTrackReceived) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setZOrderOnTop(true)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setEnableHardwareScaler(true)
                        receiver.attachRenderer(this)
                    }
                },
                onRelease = receiver::detachRenderer
            )
        } else {
            PairingScreen(state, receiver::retrySignalingNow)
        }
    }
}

@Composable
private fun PoseScreen(state: ReceiverUiState) {
    val context = LocalContext.current
    val incomingFrame = state.poseFrame
    val currentFrame by rememberUpdatedState(incomingFrame)
    val selectedTrack = state.selectedTrack
    val currentTrack by rememberUpdatedState(selectedTrack)
    val generatedChart = remember(selectedTrack?.id) {
        selectedTrack?.let(::generateBeatChart).orEmpty()
    }
    val currentChart by rememberUpdatedState(generatedChart)
    val currentInferenceMs by rememberUpdatedState(state.poseInferenceMs)
    var displayedFrame by remember { mutableStateOf<PoseFrame?>(null) }
    val frame = displayedFrame ?: incomingFrame
    var activeNotes by remember { mutableStateOf(emptyList<BeatNote>()) }
    var score by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var combo by remember { mutableIntStateOf(0) }
    var nextBeatIndex by remember { mutableIntStateOf(0) }
    var songPositionSeconds by remember { mutableFloatStateOf(0f) }
    var preRollRemainingSeconds by remember { mutableFloatStateOf(NOTE_TRAVEL_SECONDS) }
    var audioReady by remember { mutableStateOf(false) }
    var playbackStarted by remember { mutableStateOf(false) }
    var audioError by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var calibration by remember { mutableStateOf<PoseBounds?>(null) }
    var calibrationStage by remember { mutableStateOf(CalibrationStage.TOP_CORNERS) }
    var topCornerHands by remember { mutableStateOf<CalibrationHands?>(null) }
    var bottomCornerHands by remember { mutableStateOf<CalibrationHands?>(null) }
    var calibrationProgress by remember { mutableFloatStateOf(0f) }
    var readyProgress by remember { mutableFloatStateOf(0f) }
    var hitEffects by remember { mutableStateOf(emptyList<HitEffect>()) }
    var scorePopup by remember { mutableStateOf<ScorePopup?>(null) }
    val avatarBitmap = remember(state.avatarDataUrl) {
        decodeAvatarBitmap(state.avatarDataUrl)?.asImageBitmap()
    }
    val redFireBitmap = remember {
        BitmapFactory.decodeResource(
            context.resources,
            R.drawable.fire_note_red,
            BitmapFactory.Options().apply { inSampleSize = 4 }
        ).asImageBitmap()
    }
    val blueFireBitmap = remember {
        BitmapFactory.decodeResource(
            context.resources,
            R.drawable.fire_note_blue,
            BitmapFactory.Options().apply { inSampleSize = 4 }
        ).asImageBitmap()
    }
    val sleepingTargetBitmap = remember {
        BitmapFactory.decodeResource(
            context.resources,
            R.drawable.stone_target_sleeping,
            BitmapFactory.Options().apply { inSampleSize = 4 }
        ).asImageBitmap()
    }
    val awakeTargetBitmap = remember {
        BitmapFactory.decodeResource(
            context.resources,
            R.drawable.stone_target_awake,
            BitmapFactory.Options().apply { inSampleSize = 4 }
        ).asImageBitmap()
    }
    val scorePopupPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val popSoundPlayer = remember(context) {
        PopSoundPlayer(context.applicationContext)
    }
    val beatAudioPlayer = remember { BeatAudioPlayer() }

    DisposableEffect(popSoundPlayer, beatAudioPlayer) {
        onDispose {
            popSoundPlayer.release()
            beatAudioPlayer.release()
        }
    }

    LaunchedEffect(selectedTrack?.id) {
        activeNotes = emptyList()
        score = 0
        misses = 0
        combo = 0
        nextBeatIndex = 0
        songPositionSeconds = 0f
        preRollRemainingSeconds = NOTE_TRAVEL_SECONDS
        audioReady = false
        playbackStarted = false
        audioError = null
        hitEffects = emptyList()
        scorePopup = null
        selectedTrack?.let { track ->
            Log.i(
                "FireTV",
                "Gameplay chart generated; sourceBeats=${track.beats.size} notes=${generatedChart.size}"
            )
            beatAudioPlayer.load(
                url = track.audioUrl,
                onReady = { audioReady = true },
                onError = { message -> audioError = message }
            )
        } ?: beatAudioPlayer.reset()
    }

    LaunchedEffect(Unit) {
        var lastFrameTime = withFrameNanos { it }
        var lastIncomingTimestamp = Long.MIN_VALUE
        var interpolationStart: PoseFrame? = null
        var interpolationTarget: PoseFrame? = null
        var interpolationElapsedSeconds = 0f

        while (isActive) {
            val frameTime = withFrameNanos { it }
            val elapsedSeconds =
                ((frameTime - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrameTime = frameTime
            hitEffects = hitEffects
                .map { it.copy(ageSeconds = it.ageSeconds + elapsedSeconds) }
                .filter { it.ageSeconds < HIT_EFFECT_SECONDS }
            scorePopup = scorePopup?.let { popup ->
                popup.copy(ageSeconds = popup.ageSeconds + elapsedSeconds)
                    .takeIf { it.ageSeconds < SCORE_POPUP_SECONDS }
            }

            val incomingPose = currentFrame
            if (incomingPose != null && incomingPose.timestamp != lastIncomingTimestamp) {
                interpolationStart = displayedFrame ?: incomingPose
                interpolationTarget = incomingPose
                interpolationElapsedSeconds = 0f
                lastIncomingTimestamp = incomingPose.timestamp
            }
            interpolationTarget?.let { target ->
                interpolationElapsedSeconds += elapsedSeconds
                val progress = (
                    interpolationElapsedSeconds / POSE_INTERPOLATION_SECONDS
                    ).coerceIn(0f, 1f)
                displayedFrame = interpolationStart?.interpolateTo(target, progress) ?: target
            }

            val pose = displayedFrame ?: incomingPose
            if (calibration == null) {
                activeNotes = emptyList()
                if (pose != null && canvasSize != IntSize.Zero) {
                    when (calibrationStage) {
                        CalibrationStage.TOP_CORNERS,
                        CalibrationStage.BOTTOM_CORNERS -> {
                            val direction = if (calibrationStage == CalibrationStage.TOP_CORNERS) {
                                DiagonalDirection.UP
                            } else {
                                DiagonalDirection.DOWN
                            }
                            val detectedHands = pose.diagonalCalibrationHands(direction)
                            if (detectedHands != null) {
                                if (direction == DiagonalDirection.UP) {
                                    topCornerHands = topCornerHands
                                        ?.smoothedToward(detectedHands)
                                        ?: detectedHands
                                } else {
                                    bottomCornerHands = bottomCornerHands
                                        ?.smoothedToward(detectedHands)
                                        ?: detectedHands
                                }
                                calibrationProgress = (
                                    calibrationProgress +
                                        elapsedSeconds / CALIBRATION_HOLD_SECONDS
                                    ).coerceAtMost(1f)
                            } else {
                                calibrationProgress = (
                                    calibrationProgress - elapsedSeconds * 1.5f
                                    ).coerceAtLeast(0f)
                            }

                            if (calibrationProgress >= 1f) {
                                calibrationProgress = 0f
                                calibrationStage = if (direction == DiagonalDirection.UP) {
                                    Log.i("FireTV", "Top reach corners captured; hands=$topCornerHands")
                                    CalibrationStage.BOTTOM_CORNERS
                                } else {
                                    Log.i("FireTV", "Bottom reach corners captured; hands=$bottomCornerHands")
                                    CalibrationStage.READY
                                }
                            }
                        }

                        CalibrationStage.READY -> {
                            val readyTarget = pose.readyTarget(
                                canvasSize.width.toFloat(),
                                canvasSize.height.toFloat()
                            )
                            val leftWrist = pose.screenPoint(
                                TargetHand.LEFT.wristIndex,
                                canvasSize.width.toFloat(),
                                canvasSize.height.toFloat()
                            )
                            val rightWrist = pose.screenPoint(
                                TargetHand.RIGHT.wristIndex,
                                canvasSize.width.toFloat(),
                                canvasSize.height.toFloat()
                            )
                            val bothHandsReady = readyTarget != null &&
                                leftWrist != null &&
                                rightWrist != null &&
                                (leftWrist - readyTarget.center).getDistance() <= readyTarget.radius &&
                                (rightWrist - readyTarget.center).getDistance() <= readyTarget.radius

                            readyProgress = if (bothHandsReady) {
                                (readyProgress + elapsedSeconds / READY_HOLD_SECONDS)
                                    .coerceAtMost(1f)
                            } else {
                                (readyProgress - elapsedSeconds * 1.5f).coerceAtLeast(0f)
                            }

                            if (readyProgress >= 1f) {
                                calibration = calibrationBounds(topCornerHands, bottomCornerHands)
                                    ?.withPadding(CALIBRATION_PADDING)
                                    ?.matchingAspect(
                                        canvasSize.width.toFloat() / canvasSize.height.toFloat()
                                    )
                                readyProgress = 0f
                                score = 0
                                combo = 0
                                Log.i("FireTV", "Four-corner calibration locked; bounds=$calibration")
                            }
                        }
                    }
                }
                continue
            }

            val track = currentTrack
            if (track == null || !audioReady) {
                activeNotes = emptyList()
                continue
            }
            val songTime = if (!playbackStarted) {
                preRollRemainingSeconds = (
                    preRollRemainingSeconds - elapsedSeconds
                    ).coerceAtLeast(0f)
                if (preRollRemainingSeconds == 0f && beatAudioPlayer.playFromStart()) {
                    playbackStarted = true
                    Log.i("FireTV", "Beat game started; track=${track.name}")
                    0f
                } else {
                    -preRollRemainingSeconds
                }
            } else {
                beatAudioPlayer.positionSeconds()
            }
            songPositionSeconds = songTime
            val notes = activeNotes.toMutableList()
            val chart = currentChart
            val lateHitWindowSeconds = noteLateHitWindowSeconds(currentInferenceMs)
            var beatIndex = nextBeatIndex
            while (
                beatIndex < chart.size &&
                chart[beatIndex].beatTimeSeconds - NOTE_TRAVEL_SECONDS <= songTime
            ) {
                val chartNote = chart[beatIndex]
                if (chartNote.beatTimeSeconds + lateHitWindowSeconds >= songTime) {
                    notes += chartNote
                }
                beatIndex += 1
            }
            nextBeatIndex = beatIndex

            val missedApproaches = notes.count { note ->
                songTime > note.beatTimeSeconds + lateHitWindowSeconds
            }
            val playableNotes = notes.filter { note ->
                songTime <= note.beatTimeSeconds + lateHitWindowSeconds
            }

            val survivors = mutableListOf<BeatNote>()
            val newHitEffects = mutableListOf<HitEffect>()
            playableNotes.forEach { note ->
                val hit = pose != null && canvasSize != IntSize.Zero && noteHitsFist(
                    note = note,
                    songTime = songTime,
                    frame = pose,
                    canvasSize = canvasSize,
                    calibration = calibration,
                    inferenceMs = currentInferenceMs
                )
                if (hit) {
                    newHitEffects += HitEffect(
                        laneIndex = note.laneIndex,
                        hand = note.hand
                    )
                } else {
                    survivors += note
                }
            }

            activeNotes = survivors
            if (missedApproaches > 0) {
                misses += missedApproaches
                combo = 0
            }
            val scoringEvents = newHitEffects.size
            if (scoringEvents > 0) {
                var pointsAwarded = 0
                repeat(scoringEvents) {
                    combo += 1
                    pointsAwarded += comboMultiplier(combo)
                }
                score += pointsAwarded
                hitEffects = hitEffects + newHitEffects
                scorePopup = ScorePopup(
                    points = pointsAwarded,
                    multiplier = comboMultiplier(combo)
                )
                repeat(scoringEvents.coerceAtMost(3)) { popSoundPlayer.play() }
                Log.i(
                    "FireTV",
                    "Gameplay score event; events=$scoringEvents score=$score combo=$combo " +
                        "multiplier=${comboMultiplier(combo)} misses=$misses"
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080B12))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
        ) {
            val activeBounds = calibration
            if (activeBounds != null && selectedTrack != null) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val targetHalfSize = HIT_TARGET_HALF_SIZE * min(size.width, size.height)
                beatLanes.forEachIndexed { laneIndex, lane ->
                    val targetCenter = lane.position(size.width, size.height)
                    val highlighted = activeNotes.any { note ->
                        note.laneIndex == laneIndex && note.isInsideHitWindow(
                            songTime = songPositionSeconds,
                            inferenceMs = state.poseInferenceMs
                        )
                    }
                    val scale = if (highlighted) 1.08f else 1f
                    val halfSize = targetHalfSize * scale
                    val targetSize = (halfSize * 2f).roundToInt()
                    drawLine(
                        color = Color.White.copy(alpha = 0.08f),
                        start = center,
                        end = targetCenter,
                        strokeWidth = 4f
                    )
                    drawImage(
                        image = if (highlighted) awakeTargetBitmap else sleepingTargetBitmap,
                        dstOffset = IntOffset(
                            x = (targetCenter.x - halfSize).roundToInt(),
                            y = (targetCenter.y - halfSize).roundToInt()
                        ),
                        dstSize = IntSize(targetSize, targetSize)
                    )
                    if (highlighted) {
                        drawRect(
                            color = Color.White.copy(alpha = 0.72f),
                            topLeft = Offset(
                                targetCenter.x - targetHalfSize,
                                targetCenter.y - targetHalfSize
                            ),
                            size = Size(targetHalfSize * 2f, targetHalfSize * 2f),
                            style = Stroke(width = 6f)
                        )
                    }
                }
            }

            activeNotes.forEach { note ->
                val center = note.position(songPositionSeconds, size.width, size.height)
                val spriteHalfSize = NOTE_SPRITE_HALF_SIZE * min(size.width, size.height)
                val spriteSize = (spriteHalfSize * 2f).roundToInt()
                val fireBitmap = if (note.hand == TargetHand.LEFT) {
                    redFireBitmap
                } else {
                    blueFireBitmap
                }
                drawCircle(
                    color = note.hand.color.copy(alpha = 0.18f),
                    radius = spriteHalfSize * 0.72f,
                    center = center
                )
                drawImage(
                    image = fireBitmap,
                    dstOffset = IntOffset(
                        x = (center.x - spriteHalfSize).roundToInt(),
                        y = (center.y - spriteHalfSize).roundToInt()
                    ),
                    dstSize = IntSize(spriteSize, spriteSize)
                )
            }

            hitEffects.forEach { effect ->
                val progress = (effect.ageSeconds / HIT_EFFECT_SECONDS).coerceIn(0f, 1f)
                val lane = beatLanes[effect.laneIndex]
                val center = lane.position(size.width, size.height)
                val baseRadius = HIT_TARGET_HALF_SIZE * 0.82f * min(size.width, size.height)
                val radius = baseRadius * (1f + progress * 0.9f)
                val alpha = (1f - progress) * 0.75f
                drawCircle(
                    color = effect.hand.color.copy(alpha = alpha * 0.35f),
                    radius = radius,
                    center = center
                )
                drawCircle(
                    color = Color.White.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 9f * (1f - progress).coerceAtLeast(0.25f))
                )
            }

            if (frame != null && frame.landmarks.size >= 33) {
                if (activeBounds == null) {
                    val capturedCorners = listOfNotNull(
                        topCornerHands?.left?.let { it to TargetHand.LEFT },
                        topCornerHands?.right?.let { it to TargetHand.RIGHT },
                        bottomCornerHands?.left?.let { it to TargetHand.LEFT },
                        bottomCornerHands?.right?.let { it to TargetHand.RIGHT }
                    )
                    val cornerOutline = if (topCornerHands != null && bottomCornerHands != null) {
                        listOf(
                            topCornerHands!!.left,
                            topCornerHands!!.right,
                            bottomCornerHands!!.right,
                            bottomCornerHands!!.left,
                            topCornerHands!!.left
                        )
                    } else {
                        emptyList()
                    }
                    cornerOutline.zipWithNext().forEach { (start, end) ->
                        drawLine(
                            color = Color(0xFF38BDF8).copy(alpha = 0.6f),
                            start = frame.worldToScreen(start, size.width, size.height),
                            end = frame.worldToScreen(end, size.width, size.height),
                            strokeWidth = 5f
                        )
                    }
                    capturedCorners.forEach { (point, hand) ->
                        val center = frame.worldToScreen(point, size.width, size.height)
                        drawCircle(
                            color = hand.color.copy(alpha = 0.25f),
                            radius = 34f,
                            center = center
                        )
                        drawCircle(
                            color = hand.color,
                            radius = 34f,
                            center = center,
                            style = Stroke(width = 6f)
                        )
                    }

                    val currentDirection = when (calibrationStage) {
                        CalibrationStage.TOP_CORNERS -> DiagonalDirection.UP
                        CalibrationStage.BOTTOM_CORNERS -> DiagonalDirection.DOWN
                        CalibrationStage.READY -> null
                    }
                    currentDirection
                        ?.let(frame::diagonalCalibrationHands)
                        ?.let { hands ->
                            listOf(hands.left, hands.right).forEach { point ->
                                val center = frame.worldToScreen(point, size.width, size.height)
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.22f),
                                    radius = 48f,
                                    center = center
                                )
                                drawCircle(
                                    color = Color(0xFF34D399),
                                    radius = 48f * calibrationProgress.coerceAtLeast(0.08f),
                                    center = center,
                                    style = Stroke(width = 8f)
                                )
                            }
                        }

                    if (calibrationStage == CalibrationStage.READY) {
                        frame.readyTarget(size.width, size.height)?.let { target ->
                            drawCircle(
                                color = Color.White.copy(alpha = 0.12f),
                                radius = target.radius,
                                center = target.center
                            )
                            drawCircle(
                                color = Color(0xFF34D399),
                                radius = target.radius,
                                center = target.center,
                                style = Stroke(width = 10f)
                            )
                            if (readyProgress > 0f) {
                                drawCircle(
                                    color = Color(0xFF34D399).copy(alpha = 0.5f),
                                    radius = target.radius * readyProgress,
                                    center = target.center
                                )
                            }
                        }
                    }
                }

                poseConnections.forEach { (startIndex, endIndex) ->
                    val start = frame.screenPoint(
                        startIndex,
                        size.width,
                        size.height,
                        activeBounds
                    )
                    val end = frame.screenPoint(
                        endIndex,
                        size.width,
                        size.height,
                        activeBounds
                    )
                    if (start != null && end != null) {
                        drawLine(
                            color = Color(0xFFF97316),
                            start = start,
                            end = end,
                            strokeWidth = 12f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                poseJointIndices.forEach { index ->
                    frame.screenPoint(
                        index,
                        size.width,
                        size.height,
                        activeBounds
                    )?.let { center ->
                        drawCircle(color = Color.White, radius = 8f, center = center)
                    }
                }

                val leftEar = frame.screenPoint(7, size.width, size.height, activeBounds)
                val rightEar = frame.screenPoint(8, size.width, size.height, activeBounds)
                val nose = frame.screenPoint(0, size.width, size.height, activeBounds)
                if (leftEar != null && rightEar != null && nose != null) {
                    val center = Offset(
                        x = (leftEar.x + rightEar.x + nose.x) / 3f,
                        y = (leftEar.y + rightEar.y + nose.y) / 3f
                    )
                    val radius = ((leftEar - rightEar).getDistance() * 0.75f)
                        .coerceAtLeast(22f)
                    avatarBitmap?.let { image ->
                        val avatarBounds = Rect(
                            left = center.x - radius,
                            top = center.y - radius,
                            right = center.x + radius,
                            bottom = center.y + radius
                        )
                        val avatarClip = Path().apply { addOval(avatarBounds) }
                        clipPath(avatarClip) {
                            drawImage(
                                image = image,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(image.width, image.height),
                                dstOffset = IntOffset(
                                    avatarBounds.left.roundToInt(),
                                    avatarBounds.top.roundToInt()
                                ),
                                dstSize = IntSize(
                                    avatarBounds.width.roundToInt(),
                                    avatarBounds.height.roundToInt()
                                )
                            )
                        }
                    }
                    drawCircle(
                        color = if (avatarBitmap == null) Color(0xFFF97316) else Color.White,
                        radius = radius,
                        center = center,
                        style = Stroke(width = 10f)
                    )
                }

                val fistRadius = FIST_RADIUS * min(size.width, size.height)
                targetHands.forEach { hand ->
                    frame.screenPoint(
                        hand.wristIndex,
                        size.width,
                        size.height,
                        activeBounds
                    )?.let { center ->
                        drawCircle(
                            color = hand.color.copy(alpha = 0.82f),
                            radius = fistRadius,
                            center = center
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.9f),
                            radius = fistRadius,
                            center = center,
                            style = Stroke(width = 5f)
                        )
                    }
                }
            }

            scorePopup?.let { popup ->
                val progress = (popup.ageSeconds / SCORE_POPUP_SECONDS).coerceIn(0f, 1f)
                val easedScale = 1f + (1f - progress) * 0.35f
                scorePopupPaint.textSize = 92f * easedScale
                scorePopupPaint.alpha = ((1f - progress) * 255).roundToInt().coerceIn(0, 255)
                drawContext.canvas.nativeCanvas.drawText(
                    "+${popup.points}",
                    size.width / 2f,
                    size.height / 2f - progress * 90f,
                    scorePopupPaint
                )
                if (popup.multiplier > 1) {
                    scorePopupPaint.textSize = 38f * easedScale
                    drawContext.canvas.nativeCanvas.drawText(
                        "x${popup.multiplier} COMBO",
                        size.width / 2f,
                        size.height / 2f + 52f - progress * 90f,
                        scorePopupPaint
                    )
                }
            }
        }

        Text(
            text = state.status,
            color = Color(0xFFFDBA74),
            fontSize = 20.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
        )

        if (state.poseChannelOpen) {
            val fpsText = if (state.poseFps > 0f) {
                String.format(java.util.Locale.US, "%.1f", state.poseFps)
            } else {
                "--"
            }
            Text(
                text = "Pose $fpsText FPS  •  ${state.poseDelegate}  •  " +
                    "inference ${state.poseInferenceMs.toInt()} ms",
                color = Color(0xFF94A3B8),
                fontSize = 17.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 24.dp, top = 56.dp)
            )
        }

        if (calibration != null) {
            val multiplier = comboMultiplier(combo)
            Text(
                text = "Score  $score   •   Combo  $combo  x$multiplier   •   Miss  $misses",
                color = Color.White,
                fontSize = 32.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
            )
        }

        if (frame == null || frame.landmarks.size < 33) {
            Text(
                text = "Keep your head, elbows, and both hands visible",
                color = Color(0xFF94A3B8),
                fontSize = 30.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (calibration == null) {
            Text(
                text = when (calibrationStage) {
                    CalibrationStage.TOP_CORNERS -> {
                        if (calibrationProgress > 0f) {
                            val secondsRemaining = ceil(
                                (1f - calibrationProgress) * CALIBRATION_HOLD_SECONDS
                            ).toInt().coerceAtLeast(1)
                            "Hold both arms diagonally UP — capturing in $secondsRemaining"
                        } else {
                            "Point both arms diagonally UP toward the top corners"
                        }
                    }
                    CalibrationStage.BOTTOM_CORNERS -> {
                        if (calibrationProgress > 0f) {
                            val secondsRemaining = ceil(
                                (1f - calibrationProgress) * CALIBRATION_HOLD_SECONDS
                            ).toInt().coerceAtLeast(1)
                            "Hold both arms diagonally DOWN — capturing in $secondsRemaining"
                        } else {
                            "Now point both arms diagonally DOWN toward the bottom corners"
                        }
                    }
                    CalibrationStage.READY ->
                        "Corners captured — hold both hands in the circle to start"
                },
                color = Color.White,
                fontSize = 30.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(48.dp)
            )
        }

        if (calibration != null) {
            val gameStatus = when {
                audioError != null -> audioError
                selectedTrack == null -> "Choose or upload a beat track on your phone"
                !audioReady -> "Loading ${selectedTrack.name}…"
                !playbackStarted -> "Get ready — ${ceil(preRollRemainingSeconds).toInt()}"
                else -> "${selectedTrack.name}  •  ${selectedTrack.bpm.toInt()} BPM  •  " +
                    "${songPositionSeconds.coerceAtLeast(0f).toInt()} / " +
                    "${selectedTrack.duration.toInt()} sec"
            }
            Text(
                text = gameStatus.orEmpty(),
                color = if (audioError != null) Color(0xFFFCA5A5) else Color(0xFFCBD5E1),
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            )
        }
    }
}

private data class BeatNote(
    val beatIndex: Int,
    val beatTimeSeconds: Float,
    val laneIndex: Int,
    val hand: TargetHand
)

private data class HitEffect(
    val laneIndex: Int,
    val hand: TargetHand,
    val ageSeconds: Float = 0f
)

private data class ScorePopup(
    val points: Int,
    val multiplier: Int,
    val ageSeconds: Float = 0f
)

private data class BeatLane(
    val x: Float,
    val y: Float
) {
    fun position(width: Float, height: Float): Offset = Offset(x * width, y * height)
}

private fun BeatNote.position(songTime: Float, width: Float, height: Float): Offset {
    val start = Offset(width / 2f, height / 2f)
    val destination = beatLanes[laneIndex].position(width, height)
    val progress = (
        songTime - (beatTimeSeconds - NOTE_TRAVEL_SECONDS)
        ) / NOTE_TRAVEL_SECONDS
    return start + (destination - start) * progress
}

private enum class TargetHand(
    val color: Color,
    val wristIndex: Int
) {
    LEFT(Color(0xFFFF3218), 15),
    RIGHT(Color(0xFF00BFF3), 16)
}

private enum class CalibrationStage {
    TOP_CORNERS,
    BOTTOM_CORNERS,
    READY
}

private enum class DiagonalDirection {
    UP,
    DOWN
}

private data class CalibrationHands(
    val left: Offset,
    val right: Offset
) {
    fun smoothedToward(next: CalibrationHands): CalibrationHands = CalibrationHands(
        left = left + (next.left - left) * CALIBRATION_SMOOTHING,
        right = right + (next.right - right) * CALIBRATION_SMOOTHING
    )
}

private data class PoseBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float
) {
    val width: Float get() = (maxX - minX).coerceAtLeast(0.001f)
    val height: Float get() = (maxY - minY).coerceAtLeast(0.001f)

    fun withPadding(fraction: Float): PoseBounds {
        val horizontalPadding = width * fraction
        val verticalPadding = height * fraction
        return PoseBounds(
            minX = minX - horizontalPadding,
            maxX = maxX + horizontalPadding,
            minY = minY - verticalPadding,
            maxY = maxY + verticalPadding
        )
    }

    fun matchingAspect(targetAspect: Float): PoseBounds {
        val currentAspect = width / height
        return if (currentAspect < targetAspect) {
            val expandedWidth = height * targetAspect
            val centerX = (minX + maxX) / 2f
            copy(
                minX = centerX - expandedWidth / 2f,
                maxX = centerX + expandedWidth / 2f
            )
        } else {
            val expandedHeight = width / targetAspect
            val centerY = (minY + maxY) / 2f
            copy(
                minY = centerY - expandedHeight / 2f,
                maxY = centerY + expandedHeight / 2f
            )
        }
    }
}

private data class ReadyTarget(
    val center: Offset,
    val radius: Float
)

private const val CALIBRATION_HOLD_SECONDS = 1.4f
private const val CALIBRATION_PADDING = 0.10f
private const val CALIBRATION_SMOOTHING = 0.22f
private const val READY_HOLD_SECONDS = 0.7f
private const val POSE_INTERPOLATION_SECONDS = 0.06f
private const val NOTE_TRAVEL_SECONDS = 1.6f
private const val NOTE_EARLY_HIT_WINDOW_SECONDS = 0.18f
private const val NOTE_BASE_LATE_HIT_WINDOW_SECONDS = 0.34f
private const val NOTE_MAX_INFERENCE_COMPENSATION_SECONDS = 0.16f
private const val NOTE_DENSITY_WINDOW_SECONDS = 1.25f
private const val NOTE_MIN_GAP_SECONDS = 0.32f
private const val MAX_NOTES_PER_DENSITY_WINDOW = 2
private const val NOTE_SPRITE_HALF_SIZE = 0.060f
private const val HIT_TARGET_HALF_SIZE = 0.145f
private const val FIST_RADIUS = 0.03f
private const val HIT_EFFECT_SECONDS = 0.48f
private const val SCORE_POPUP_SECONDS = 0.72f
private const val HITS_PER_MULTIPLIER = 5
private const val MAX_COMBO_MULTIPLIER = 4
private val targetHands = TargetHand.values().toList()
private val beatLanes = listOf(
    BeatLane(0.15f, 0.20f),
    BeatLane(0.85f, 0.20f),
    BeatLane(0.15f, 0.80f),
    BeatLane(0.85f, 0.80f)
)

private fun generateBeatChart(track: BeatTrack): List<BeatNote> {
    val random = Random(track.id.hashCode())
    val recentNotes = mutableListOf<Float>()
    var lastAcceptedTime = Float.NEGATIVE_INFINITY
    var lastLaneIndex = -1
    var lastHand: TargetHand? = null
    var sameHandStreak = 0

    return buildList {
        track.beats.forEachIndexed { sourceIndex, beatTime ->
            recentNotes.removeAll { previous ->
                beatTime - previous > NOTE_DENSITY_WINDOW_SECONDS
            }
            if (
                beatTime - lastAcceptedTime < NOTE_MIN_GAP_SECONDS ||
                recentNotes.size >= MAX_NOTES_PER_DENSITY_WINDOW
            ) {
                return@forEachIndexed
            }

            val candidateLanes = beatLanes.indices.filter { it != lastLaneIndex }
            val laneIndex = candidateLanes[random.nextInt(candidateLanes.size)]
            val candidateHands = targetHands.filter { hand ->
                sameHandStreak < 2 || hand != lastHand
            }
            val hand = candidateHands[random.nextInt(candidateHands.size)]
            sameHandStreak = if (hand == lastHand) sameHandStreak + 1 else 1
            lastHand = hand
            lastLaneIndex = laneIndex
            lastAcceptedTime = beatTime
            recentNotes += beatTime
            add(
                BeatNote(
                    beatIndex = sourceIndex,
                    beatTimeSeconds = beatTime,
                    laneIndex = laneIndex,
                    hand = hand
                )
            )
        }
    }
}

private fun noteLateHitWindowSeconds(inferenceMs: Float): Float =
    NOTE_BASE_LATE_HIT_WINDOW_SECONDS +
        (inferenceMs / 1000f).coerceIn(0f, NOTE_MAX_INFERENCE_COMPENSATION_SECONDS)

private fun BeatNote.isInsideHitWindow(songTime: Float, inferenceMs: Float): Boolean {
    val difference = songTime - beatTimeSeconds
    return difference >= -NOTE_EARLY_HIT_WINDOW_SECONDS &&
        difference <= noteLateHitWindowSeconds(inferenceMs)
}

private fun comboMultiplier(combo: Int): Int =
    (1 + combo / HITS_PER_MULTIPLIER).coerceAtMost(MAX_COMBO_MULTIPLIER)

private fun noteHitsFist(
    note: BeatNote,
    songTime: Float,
    frame: PoseFrame,
    canvasSize: IntSize,
    calibration: PoseBounds?,
    inferenceMs: Float
): Boolean {
    if (!note.isInsideHitWindow(songTime, inferenceMs)) {
        return false
    }
    val width = canvasSize.width.toFloat()
    val height = canvasSize.height.toFloat()
    val lane = beatLanes[note.laneIndex]
    val center = lane.position(width, height)
    val fist = frame.screenPoint(
        note.hand.wristIndex,
        width,
        height,
        calibration
    ) ?: return false
    val halfSize = (HIT_TARGET_HALF_SIZE + FIST_RADIUS) * min(width, height)
    return fist.isInsideSquare(center, halfSize)
}

private fun Offset.isInsideSquare(center: Offset, halfSize: Float): Boolean =
    kotlin.math.abs(x - center.x) <= halfSize && kotlin.math.abs(y - center.y) <= halfSize

private fun calibrationBounds(
    top: CalibrationHands?,
    bottom: CalibrationHands?
): PoseBounds? {
    if (top == null || bottom == null) return null
    val points = listOf(top.left, top.right, bottom.left, bottom.right)
    return PoseBounds(
        minX = points.minOf { it.x },
        maxX = points.maxOf { it.x },
        minY = points.minOf { it.y },
        maxY = points.maxOf { it.y }
    )
}

private fun PoseFrame.diagonalCalibrationHands(
    direction: DiagonalDirection
): CalibrationHands? {
    val leftShoulder = worldPoint(11) ?: return null
    val rightShoulder = worldPoint(12) ?: return null
    val leftElbow = worldPoint(13) ?: return null
    val rightElbow = worldPoint(14) ?: return null
    val leftWrist = worldPoint(15) ?: return null
    val rightWrist = worldPoint(16) ?: return null
    val shoulderSpan = (leftShoulder - rightShoulder).getDistance()
    if (shoulderSpan <= 0f) return null

    fun armIsStraight(shoulder: Offset, elbow: Offset, wrist: Offset): Boolean {
        val fullReach = (shoulder - elbow).getDistance() + (elbow - wrist).getDistance()
        return fullReach > 0f &&
            (shoulder - wrist).getDistance() >= fullReach * 0.84f
    }

    val minimumHorizontalReach = shoulderSpan * 0.42f
    val minimumVerticalReach = shoulderSpan * 0.32f
    val leftPointsOutward = leftWrist.x <= leftShoulder.x - minimumHorizontalReach
    val rightPointsOutward = rightWrist.x >= rightShoulder.x + minimumHorizontalReach
    val verticalDirectionMatches = when (direction) {
        DiagonalDirection.UP ->
            leftWrist.y <= leftShoulder.y - minimumVerticalReach &&
                rightWrist.y <= rightShoulder.y - minimumVerticalReach
        DiagonalDirection.DOWN ->
            leftWrist.y >= leftShoulder.y + minimumVerticalReach &&
                rightWrist.y >= rightShoulder.y + minimumVerticalReach
    }
    val armsAreStraight =
        armIsStraight(leftShoulder, leftElbow, leftWrist) &&
            armIsStraight(rightShoulder, rightElbow, rightWrist)

    return if (leftPointsOutward && rightPointsOutward && verticalDirectionMatches && armsAreStraight) {
        CalibrationHands(left = leftWrist, right = rightWrist)
    } else {
        null
    }
}

private fun PoseFrame.interpolateTo(target: PoseFrame, progress: Float): PoseFrame {
    if (landmarks.size != target.landmarks.size || landmarks.isEmpty()) return target
    val amount = progress.coerceIn(0f, 1f)
    fun lerp(start: Float, end: Float): Float = start + (end - start) * amount

    return target.copy(
        landmarks = List(landmarks.size) { index ->
            val start = landmarks[index]
            val end = target.landmarks[index]
            PoseLandmark(
                x = lerp(start.x, end.x),
                y = lerp(start.y, end.y),
                z = lerp(start.z, end.z),
                visibility = lerp(start.visibility, end.visibility)
            )
        }
    )
}

private fun PoseFrame.worldPoint(index: Int): Offset? {
    val landmark = landmarks.getOrNull(index) ?: return null
    if (landmark.visibility < 0.35f) return null

    val normalizedX = if (mirrored) 1f - landmark.x else landmark.x
    return Offset(
        x = normalizedX * (width.toFloat() / height.toFloat()),
        y = landmark.y
    )
}

private fun PoseFrame.worldToScreen(
    point: Offset,
    targetWidth: Float,
    targetHeight: Float,
    viewBounds: PoseBounds? = null
): Offset {
    val sourceAspect = width.toFloat() / height.toFloat()
    val bounds = viewBounds ?: PoseBounds(0f, sourceAspect, 0f, 1f)
    val scale = min(targetWidth / bounds.width, targetHeight / bounds.height)
    val drawingWidth = bounds.width * scale
    val drawingHeight = bounds.height * scale
    val originX = (targetWidth - drawingWidth) / 2f
    val originY = (targetHeight - drawingHeight) / 2f
    return Offset(
        x = originX + (point.x - bounds.minX) * scale,
        y = originY + (point.y - bounds.minY) * scale
    )
}

private fun PoseFrame.screenPoint(
    index: Int,
    targetWidth: Float,
    targetHeight: Float,
    viewBounds: PoseBounds? = null
): Offset? {
    val point = worldPoint(index) ?: return null
    return worldToScreen(point, targetWidth, targetHeight, viewBounds)
}

private fun PoseFrame.readyTarget(
    targetWidth: Float,
    targetHeight: Float
): ReadyTarget? {
    val head = screenPoint(0, targetWidth, targetHeight) ?: return null
    val leftShoulder = screenPoint(11, targetWidth, targetHeight) ?: return null
    val rightShoulder = screenPoint(12, targetWidth, targetHeight) ?: return null
    val shoulderSpan = (leftShoulder - rightShoulder).getDistance()
    if (shoulderSpan <= 0f) return null

    val radius = (shoulderSpan * 0.42f).coerceIn(54f, 110f)
    return ReadyTarget(
        center = Offset(
            x = head.x.coerceIn(radius + 24f, targetWidth - radius - 24f),
            y = (head.y - radius * 1.35f).coerceAtLeast(radius + 24f)
        ),
        radius = radius
    )
}

private val poseConnections = listOf(
    11 to 12,
    11 to 13,
    13 to 15,
    12 to 14,
    14 to 16,
    11 to 23,
    12 to 24,
    23 to 24
)

private val poseJointIndices = listOf(
    11, 12, 13, 14, 15, 16,
    23, 24
)

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun PairingScreen(
    state: ReceiverUiState,
    onRetry: () -> Unit
) {
    val qrCode = remember(state.phoneUrl) {
        state.phoneUrl?.let(::createQrCode)
    }
    val statusPulse by rememberInfiniteTransition(label = "pairing-status").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pairing-status-pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080B12)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(48.dp)
        ) {
            if (qrCode != null) {
                Image(
                    bitmap = qrCode.asImageBitmap(),
                    contentDescription = "Pair phone QR code",
                    modifier = Modifier
                        .size(300.dp)
                        .background(Color.White)
                        .padding(12.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .background(Color(0xFF172033)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("…", color = Color.White, fontSize = 64.sp)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val statusColor =
                    if (state.error == null) Color(0xFFFDBA74) else Color(0xFFFCA5A5)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size((12f + statusPulse * 6f).dp)
                            .background(
                                statusColor.copy(alpha = 0.35f + statusPulse * 0.65f),
                                CircleShape
                            )
                    )
                    Text(
                        text = state.status,
                        color = statusColor,
                        fontSize = 22.sp
                    )
                }
                Text(
                    text = "Scan QR to start pose play",
                    color = Color.White,
                    fontSize = 40.sp,
                    lineHeight = 48.sp
                )
                Text(
                    text = "Open the QR code with your phone, then tap Start pose tracking.",
                    color = Color(0xFF94A3B8),
                    fontSize = 24.sp,
                    lineHeight = 30.sp
                )
                state.room?.let { room ->
                    Text(
                        text = "Room $room",
                        color = Color(0xFFFDBA74),
                        fontSize = 20.sp
                    )
                }
                state.error?.let { error ->
                    Text(
                        text = error,
                        color = Color(0xFFFCA5A5),
                        fontSize = 18.sp
                    )
                }
                if (!state.signalingConnected && state.room != null) {
                    Button(onClick = onRetry) {
                        Text(
                            text = if (state.signalingRetryAttempt > 0) {
                                "Retry now"
                            } else {
                                "Connect again"
                            },
                            fontSize = 20.sp
                        )
                    }
                }
            }
        }
    }
}

private fun decodeAvatarBitmap(dataUrl: String?): Bitmap? {
    if (dataUrl.isNullOrBlank()) return null
    return runCatching {
        val encoded = dataUrl.substringAfter(',', missingDelimiterValue = "")
        if (encoded.isBlank()) return null
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.onFailure { error ->
        Log.w("FireTV", "Could not decode player avatar", error)
    }.getOrNull()
}

private fun createQrCode(contents: String): Bitmap {
    val size = 720
    val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
