package com.audx.example.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audx.example.example.domain.RecordingMode

/**
 * Interactive button for starting and stopping audio recording.
 *
 * The button appearance changes based on the recording state:
 * - When idle: Primary color with "Record" label and recording mode icon
 * - When recording: Error color (red) with "Stop" label
 *
 * The button displays different icons based on the recording mode:
 * - RAW mode: Microphone icon
 * - DENOISED mode: Auto Awesome (sparkle) icon
 *
 * Note: In the current UI implementation, only DENOISED mode is exposed to users,
 * which captures both raw and denoised audio simultaneously.
 *
 * @param mode The recording mode (RAW or DENOISED)
 * @param isRecording Whether recording is currently active
 * @param isEnabled Whether the button should be enabled (requires microphone permission)
 * @param onStartRecording Callback invoked when the user taps to start recording
 * @param onStopRecording Callback invoked when the user taps to stop recording
 * @param modifier Optional modifier for the button
 */
@Composable
fun RecordingButton(
    mode: RecordingMode,
    isRecording: Boolean,
    isEnabled: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier
) {

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Button(
            onClick = {
                if (isRecording) {
                    onStopRecording()
                } else {
                    onStartRecording()
                }
            },
            enabled = isEnabled || isRecording,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
                contentColor = if (isRecording)
                    MaterialTheme.colorScheme.onError
                else
                    MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (mode == RecordingMode.RAW)
                        Icons.Default.Mic
                    else
                        Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (isRecording) "Stop" else "Record",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = if (mode == RecordingMode.RAW) "Raw" else "Denoised",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

            }
        }
    }
}

/**
 * Interactive button for playing and stopping recorded audio.
 *
 * The button appearance changes based on the playback state:
 * - When idle: Primary color with "Play" label and play icon
 * - When playing: Tertiary color with "Stop" label and stop icon
 *
 * The button shows the audio type (Raw or Denoised) and is only enabled when
 * audio has been recorded and the app is in idle state (not recording or playing other audio).
 *
 * This allows users to compare the original raw audio with the denoised version to
 * evaluate the effectiveness of the Audx denoiser.
 *
 * @param mode The audio mode to play (RAW or DENOISED)
 * @param isPlaying Whether this audio is currently playing
 * @param isEnabled Whether the button should be enabled (requires recorded audio)
 * @param onPlay Callback invoked when the user taps to start playback
 * @param onStop Callback invoked when the user taps to stop playback
 * @param modifier Optional modifier for the button
 */
@Composable
fun PlaybackButton(
    mode: RecordingMode,
    isPlaying: Boolean,
    isEnabled: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Button(
            onClick = {
                if (isPlaying) {
                    onStop()
                } else {
                    onPlay()
                }
            },
            enabled = isEnabled || isPlaying,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPlaying)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.primary,
                contentColor = if (isPlaying)
                    MaterialTheme.colorScheme.onTertiary
                else
                    MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isPlaying)
                        Icons.Default.Stop
                    else
                        Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(Modifier.width(8.dp))

                Column {
                    Text(
                        text = if (isPlaying) "Stop" else "Play",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = if (mode == RecordingMode.RAW) "Raw" else "Denoised",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/**
 * Card displaying detailed information about an audio buffer.
 *
 * Shows comprehensive statistics about recorded audio:
 * - Sample count: Total number of PCM16 samples
 * - Duration: Audio length in seconds (calculated from sample count and rate)
 * - Frame count: Number of audio frames processed
 * - Frame size: Fixed at 480 samples (10ms at 48kHz internal processing rate)
 *
 * The card's appearance changes based on whether audio has been recorded:
 * - With audio: Tertiary container color with detailed statistics
 * - Empty: Surface variant color with "No audio recorded" message
 *
 * @param title The buffer title (e.g., "Raw Audio" or "Denoised Audio")
 * @param sampleCount Total number of audio samples in the buffer
 * @param durationMs Duration of the audio in milliseconds
 * @param frameCount Number of audio frames that were processed
 */
@Composable
fun AudioBufferCard(
    title: String,
    sampleCount: Int,
    durationMs: Int,
    frameCount: Int
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (sampleCount > 0)
                MaterialTheme.colorScheme.tertiaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AudioFile,
                    contentDescription = null,
                    tint = if (sampleCount > 0)
                        MaterialTheme.colorScheme.onTertiaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (sampleCount > 0)
                        MaterialTheme.colorScheme.onTertiaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (sampleCount > 0) {
                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Samples",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            sampleCount.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Duration",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            "%.1fs".format(durationMs / 1000.0),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Frames",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            "$frameCount frames",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Frame Size",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            "480 samples",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            } else {
                Text(
                    "No audio recorded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
