package com.audx.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.audx.example.example.MainViewModel
import com.audx.example.example.ui.MainScreen
import com.audx.example.example.ui.theme.MyApplicationTheme

/**
 * Main activity for the Audx Example application.
 *
 * This activity demonstrates best practices for building an Android app with real-time
 * audio processing using the Audx library. Key features include:
 *
 * - Runtime permission handling for RECORD_AUDIO with proper rationale flow
 * - Jetpack Compose UI with Material 3 design
 * - ViewModel architecture for state management
 * - Real-time audio denoising with the Audx library
 * - Edge-to-edge display with modern Android UI patterns
 *
 * The activity manages the lifecycle of audio recording operations through the MainViewModel,
 * ensuring proper resource cleanup and permission state synchronization.
 */
class MainActivity : ComponentActivity() {

    /**
     * ViewModel managing audio recording, denoising, and playback state.
     *
     * Uses the `by viewModels()` delegate for automatic lifecycle-aware initialization.
     */
    private val viewModel: MainViewModel by viewModels()

    /**
     * Permission launcher for requesting RECORD_AUDIO permission.
     *
     * Uses the Activity Result API to handle permission requests in a lifecycle-aware manner.
     * Updates the ViewModel state when the user grants or denies the permission.
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        viewModel.updatePermission(isGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check and request permission
        checkAndRequestPermission()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    /**
     * Checks if RECORD_AUDIO permission is granted and requests it if necessary.
     *
     * Follows Android permission best practices:
     * 1. If permission is already granted, updates ViewModel immediately
     * 2. If rationale should be shown, launches permission request (a real app might show a dialog)
     * 3. Otherwise, directly launches the permission request
     *
     * Called during onCreate to ensure permission state is checked when the app starts.
     */
    private fun checkAndRequestPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                viewModel.updatePermission(true)
            }

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // Show rationale and request permission
                // In a real app, you might want to show a dialog explaining why
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            else -> {
                // Request permission directly
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check permission again when returning to the app
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.updatePermission(hasPermission)
    }
}
