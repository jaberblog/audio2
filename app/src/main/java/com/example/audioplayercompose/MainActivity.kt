package com.example.audioplayercompose

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen edge-to-edge display (transparent status & navigation bars)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFFFFFFFF) // Frame 1 background: #FFFFFF full screen
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AudioWaveformPlayer(
                        audioUrl = "https://irsv.upmusics.com/singletracks/2026/11/Ai%20-%20Kash%20Zoodtar%20Baroon%20Miomad%20UpMusics.mp3",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AudioWaveformPlayer(
    audioUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Figma Exact Colors & Bar Specs
    val activeGreen = Color(0xFF44B678)
    val inactiveGray = Color(0xFFF0F0F0)
    val barWidth = 4.dp
    val barSpacing = 4.dp
    val maxBands = 64

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0.4f) } // Matches 40% initial green bars in Figma
    var audioSessionId by remember { mutableIntStateOf(0) }

    // Real-time FFT amplitudes for up to 64 bars (0f..1f)
    val barAmplitudes = remember {
        mutableStateListOf<Float>().apply {
            repeat(maxBands) { add(0f) }
        }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    // Initialize ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(audioUrl))
            prepare()
        }
    }

    // Listen to ExoPlayer state & audioSessionId
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onAudioSessionIdChanged(sessionId: Int) {
                audioSessionId = sessionId
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                    exoPlayer.pause()
                    progress = 0f
                }
            }
        }
        exoPlayer.addListener(listener)
        audioSessionId = exoPlayer.audioSessionId

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Real-time Audio Visualizer bound to ExoPlayer's audioSessionId
    DisposableEffect(audioSessionId, hasAudioPermission, isPlaying) {
        var visualizer: Visualizer? = null
        if (hasAudioPermission && audioSessionId != 0 && isPlaying) {
            try {
                visualizer = Visualizer(audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                vis: Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int
                            ) {
                            }

                            override fun onFftDataCapture(
                                vis: Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int
                            ) {
                                if (fft != null && fft.size >= 4) {
                                    val availableBins = (fft.size / 2) - 1
                                    for (i in 0 until maxBands) {
                                        // Map bar index across musical low-to-high frequency bins
                                        val mappedBin = ((i % 32) + 1).coerceAtMost(availableBins)
                                        val index = mappedBin * 2
                                        if (index + 1 < fft.size) {
                                            val rfk = fft[index].toFloat()
                                            val ifk = fft[index + 1].toFloat()
                                            val magnitude = hypot(rfk, ifk)
                                            val normalized = min(1f, max(0f, magnitude / 45f))
                                            barAmplitudes[i] =
                                                barAmplitudes[i] * 0.35f + normalized * 0.65f
                                        }
                                    }
                                }
                            }
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        false,
                        true
                    )
                    enabled = true
                }
            } catch (_: Exception) {
                visualizer = null
            }
        }

        onDispose {
            try {
                visualizer?.enabled = false
                visualizer?.release()
            } catch (_: Exception) {
            }
        }
    }

    // Track playback progress & fallback animation if Visualizer permission wasn't granted
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val duration = exoPlayer.duration
            val current = exoPlayer.currentPosition
            if (duration > 0) {
                progress = (current.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            }
            if (!hasAudioPermission || audioSessionId == 0) {
                for (i in 0 until maxBands) {
                    val target = Random.nextFloat() * 0.85f + 0.15f
                    barAmplitudes[i] = barAmplitudes[i] * 0.4f + target * 0.6f
                }
            }
            delay(60L)
        }
        if (!isPlaying) {
            for (i in 0 until maxBands) {
                barAmplitudes[i] = 0f
            }
        }
    }

    // Full-width Group 1 layout: Play button (40dp) + 8dp gap + Equalizer filling remaining screen width
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Frame 2: Circular Play/Pause Button (40x40dp, cornerRadius 100, #44B678)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(activeGreen)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (!hasAudioPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Exact SVG Path & Pause bars inside 24x24dp Frame
            Canvas(modifier = Modifier.size(24.dp)) {
                val scaleX = size.width / 24f
                val scaleY = size.height / 24f

                if (!isPlaying) {
                    val offsetX = 1f * scaleX
                    val path = Path().apply {
                        moveTo(offsetX + 3.91795f * scaleX, 0.529181f * scaleY)
                        cubicTo(
                            offsetX + 1.75422f * scaleX, -0.711968f * scaleY,
                            offsetX + 0f * scaleX, 0.304796f * scaleY,
                            offsetX + 0f * scaleX, 2.79837f * scaleY
                        )
                        lineTo(offsetX + 0f * scaleX, 21.1999f * scaleY)
                        cubicTo(
                            offsetX + 0f * scaleX, 23.6959f * scaleY,
                            offsetX + 1.75422f * scaleX, 24.7114f * scaleY,
                            offsetX + 3.91795f * scaleX, 23.4714f * scaleY
                        )
                        lineTo(offsetX + 20.0018f * scaleX, 14.2474f * scaleY)
                        cubicTo(
                            offsetX + 22.1663f * scaleX, 13.0059f * scaleY,
                            offsetX + 22.1663f * scaleX, 10.9943f * scaleY,
                            offsetX + 20.0018f * scaleX, 9.753f * scaleY
                        )
                        lineTo(offsetX + 3.91795f * scaleX, 0.529181f * scaleY)
                        close()
                    }
                    drawPath(path = path, color = Color.White)
                } else {
                    val barW = 4.5f * scaleX
                    val barH = 16f * scaleY
                    val topY = (size.height - barH) / 2f
                    val radius = CornerRadius(2f * scaleX, 2f * scaleY)

                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(5.5f * scaleX, topY),
                        size = Size(barW, barH),
                        cornerRadius = radius
                    )
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(14f * scaleX, topY),
                        size = Size(barW, barH),
                        cornerRadius = radius
                    )
                }
            }
        }

        // Exact 8dp spacing between Play button and Equalizer
        Spacer(modifier = Modifier.width(8.dp))

        // Full-width Equalizer Bars filling the remaining width of the screen
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                        progress = newProgress
                        val duration = exoPlayer.duration
                        if (duration > 0) {
                            exoPlayer.seekTo((duration * newProgress).toLong())
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val newProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        progress = newProgress
                        val duration = exoPlayer.duration
                        if (duration > 0) {
                            exoPlayer.seekTo((duration * newProgress).toLong())
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // Dynamically fit exact 4dp bars with 4dp spacing across the entire available width
            val dynamicBarCount = remember(maxWidth) {
                ((maxWidth + barSpacing) / (barWidth + barSpacing))
                    .toInt()
                    .coerceIn(10, maxBands)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (index in 0 until dynamicBarCount) {
                    // Alternating 32dp and 16dp base heights matching Figma pattern
                    val baseH = if (index % 2 == 0) 32f else 16f
                    val amp = barAmplitudes[index % maxBands]

                    val targetHeightDp = if (isPlaying) {
                        (8f + (baseH * 0.35f) + (amp * 22f)).coerceIn(8f, 34f).dp
                    } else {
                        baseH.dp
                    }

                    val animatedHeight by animateDpAsState(
                        targetValue = targetHeightDp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "eq_bar_$index"
                    )

                    val barThreshold = (index + 0.5f) / dynamicBarCount.toFloat()
                    val barColor = if (progress >= barThreshold) activeGreen else inactiveGray

                    Box(
                        modifier = Modifier
                            .width(barWidth)
                            .height(animatedHeight)
                            .clip(RoundedCornerShape(10.dp))
                            .background(barColor)
                    )
                }
            }
        }
    }
}
