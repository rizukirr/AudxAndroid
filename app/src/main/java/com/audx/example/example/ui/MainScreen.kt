package com.audx.example.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audx.example.example.MainViewModel
import com.audx.example.example.audio.AudioRecorder
import com.audx.example.example.domain.RecordingMode
import com.audx.example.example.domain.RecordingState

/**
 * Main screen for the Audx Example app.
 *
 * Demonstrates real-time audio denoising capabilities using the Audx library.
 * The screen provides controls for:
 * - Recording audio with simultaneous raw and denoised capture
 * - Viewing real-time Voice Activity Detection (VAD) probability during recording
 * - Playing back both raw and denoised audio for comparison
 * - Viewing detailed buffer information (sample count, duration, frame count)
 * - Managing microphone permissions
 *
 * The UI uses Material 3 design with a responsive layout that adapts to different states
 * (idle, recording, playing). Recording captures both raw and denoised audio simultaneously,
 * allowing users to compare the effectiveness of the denoising algorithm.
 *
 * @param viewModel The MainViewModel managing audio recording and playback state
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

            // Recording Button
            Text(
                "Recording",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            RecordingButton(
                mode = RecordingMode.DENOISED,
                isRecording = state.recordingState is RecordingState.Recording &&
                    (state.recordingState as RecordingState.Recording).mode == RecordingMode.DENOISED,
                isEnabled = state.hasRecordPermission && state.recordingState is RecordingState.Idle,
                onStartRecording = { viewModel.startRecording(RecordingMode.DENOISED) },
                onStopRecording = { viewModel.stopRecording() },
                modifier = Modifier.fillMaxWidth()
            )

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
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
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
 * Card displaying audio format configuration used by the AudioRecorder.
 *
 * Shows the sample rate and frame size constants that define the audio recording parameters.
 * These values are used throughout the app for recording, processing, and playback.
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
                InfoItem("Sample Rate", "${AudioRecorder.SAMPLE_RATE} Hz")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Frame Size", "${AudioRecorder.FRAME_SIZE} samples")
            }
        }
    }
}

/**
 * Card displaying the current recording/playback state and Voice Activity Detection information.
 *
 * Shows:
 * - Current state (Idle, Recording, or Playing)
 * - VAD probability (0.0-1.0) when recording with denoising
 * - Speech detection indicator (checkmark when VAD > 0.5)
 *
 * The VAD information is only visible during denoised recording, as it's generated by the
 * Audx denoiser in real-time.
 *
 * @param recordingState The current state (Idle, Recording, or Playing)
 * @param vadProbability Voice Activity Detection probability (0.0 to 1.0)
 * @param isSpeechDetected Whether speech is detected (vadProbability > 0.5)
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
                        recordingState.mode == RecordingMode.DENOISED
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
 * Displays a label-value pair with consistent styling.
 *
 * Used in the AudioFormatCard to show audio configuration parameters.
 *
 * @param label The descriptive label (e.g., "Sample Rate")
 * @param value The value to display (e.g., "16000 Hz")
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
