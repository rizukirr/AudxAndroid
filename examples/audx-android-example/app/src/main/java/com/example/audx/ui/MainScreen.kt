package com.example.audx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.audx.AudxDenoiser
import com.example.audx.MainViewModel
import com.example.audx.domain.RecordingMode
import com.example.audx.domain.RecordingState

/**
 * Main screen for the Audx Example app
 *
 * Displays audio format constants from AudxDenoiser and provides controls for:
 * - Recording raw audio
 * - Recording with denoising
 * - Playing back both recordings
 * - Viewing buffer information
 * - VAD (Voice Activity Detection) status
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Audx Example", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Real-time Audio Denoising",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = {
            // Show error messages
            state.error?.let { error ->
                Snackbar(
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    },
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(error)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Audio Format Info Card (using AudxDenoiser constants)
            AudioFormatCard()

            // Status Card
            StatusCard(
                recordingState = state.recordingState,
                vadProbability = state.vadProbability,
                isSpeechDetected = state.isSpeechDetected
            )

            // Recording Buttons
            Text(
                "Recording",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RecordingButton(
                    mode = RecordingMode.RAW,
                    isRecording = state.recordingState is RecordingState.Recording &&
                            (state.recordingState as RecordingState.Recording).mode == RecordingMode.RAW,
                    isEnabled = state.hasRecordPermission && state.recordingState is RecordingState.Idle,
                    onStartRecording = { viewModel.startRecording(RecordingMode.RAW) },
                    onStopRecording = { viewModel.stopRecording() },
                    modifier = Modifier.weight(1f)
                )

                RecordingButton(
                    mode = RecordingMode.DENOISED,
                    isRecording = state.recordingState is RecordingState.Recording &&
                            (state.recordingState as RecordingState.Recording).mode == RecordingMode.DENOISED,
                    isEnabled = state.hasRecordPermission && state.recordingState is RecordingState.Idle,
                    onStartRecording = { viewModel.startRecording(RecordingMode.DENOISED) },
                    onStopRecording = { viewModel.stopRecording() },
                    modifier = Modifier.weight(1f)
                )
            }

            // Buffer Info Cards
            Text(
                "Audio Buffers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            AudioBufferCard(
                title = "Raw Audio",
                sampleCount = state.rawAudioBuffer.size,
                durationMs = state.rawDurationMs,
                frameCount = state.rawFrameCount
            )

            AudioBufferCard(
                title = "Denoised Audio",
                sampleCount = state.denoisedAudioBuffer.size,
                durationMs = state.denoisedDurationMs,
                frameCount = state.denoisedFrameCount
            )

            // Playback Buttons
            Text(
                "Playback",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlaybackButton(
                    mode = RecordingMode.RAW,
                    isPlaying = state.recordingState is RecordingState.Playing &&
                            (state.recordingState as RecordingState.Playing).mode == RecordingMode.RAW,
                    isEnabled = state.rawAudioBuffer.isNotEmpty() && state.recordingState is RecordingState.Idle,
                    onPlay = { viewModel.playAudio(RecordingMode.RAW) },
                    onStop = { viewModel.stopPlayback() },
                    modifier = Modifier.weight(1f)
                )

                PlaybackButton(
                    mode = RecordingMode.DENOISED,
                    isPlaying = state.recordingState is RecordingState.Playing &&
                            (state.recordingState as RecordingState.Playing).mode == RecordingMode.DENOISED,
                    isEnabled = state.denoisedAudioBuffer.isNotEmpty() && state.recordingState is RecordingState.Idle,
                    onPlay = { viewModel.playAudio(RecordingMode.DENOISED) },
                    onStop = { viewModel.stopPlayback() },
                    modifier = Modifier.weight(1f)
                )
            }

            // Clear Buffers Button
            OutlinedButton(
                onClick = { viewModel.clearBuffers() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.recordingState is RecordingState.Idle &&
                        (state.rawAudioBuffer.isNotEmpty() || state.denoisedAudioBuffer.isNotEmpty())
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Clear All Buffers")
            }

            // Permission warning
            if (!state.hasRecordPermission) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Microphone permission required for recording",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card displaying audio format information from AudxDenoiser constants
 */
@Composable
fun AudioFormatCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Audio Format (from AudxDenoiser)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Sample Rate", "${AudxDenoiser.SAMPLE_RATE} Hz")
                InfoItem("Channels", "${AudxDenoiser.CHANNELS} (Mono)")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Bit Depth", "${AudxDenoiser.BIT_DEPTH}-bit PCM")
                InfoItem("Frame Size", "${AudxDenoiser.FRAME_SIZE} samples")
            }

            InfoItem("Frame Duration", "${AudxDenoiser.getFrameDurationMs()}ms")
        }
    }
}

/**
 * Status card showing recording/playback state and VAD info
 */
@Composable
fun StatusCard(
    recordingState: RecordingState,
    vadProbability: Float,
    isSpeechDetected: Boolean
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    when (recordingState) {
                        is RecordingState.Idle -> Icons.Default.Info
                        is RecordingState.Recording -> Icons.Default.Mic
                        is RecordingState.Playing -> Icons.Default.PlayArrow
                    },
                    contentDescription = null,
                    tint = when (recordingState) {
                        is RecordingState.Idle -> MaterialTheme.colorScheme.onSurface
                        is RecordingState.Recording -> MaterialTheme.colorScheme.error
                        is RecordingState.Playing -> MaterialTheme.colorScheme.primary
                    }
                )

                Text(
                    text = when (recordingState) {
                        is RecordingState.Idle -> "Idle"
                        is RecordingState.Recording -> "Recording (${recordingState.mode.name})"
                        is RecordingState.Playing -> "Playing (${recordingState.mode.name})"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Show VAD info when recording with denoiser
            AnimatedVisibility(
                visible = recordingState is RecordingState.Recording &&
                        (recordingState as RecordingState.Recording).mode == RecordingMode.DENOISED
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("VAD Probability", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "%.2f".format(vadProbability),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Speech Detected", style = MaterialTheme.typography.bodyMedium)
                        Icon(
                            if (isSpeechDetected) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (isSpeechDetected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

/**
 * Info item for displaying label-value pairs
 */
@Composable
fun InfoItem(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
