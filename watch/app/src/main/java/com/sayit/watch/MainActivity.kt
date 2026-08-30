package com.sayit.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.sayit.watch.settings.SettingsStore
import com.sayit.watch.ui.RecordingScreen
import com.sayit.watch.ui.RecordingViewModel
import com.sayit.watch.ui.vibratePattern

/**
 * Delivery 1A debug application: record 16 kHz/16-bit/mono audio and transport
 * it to the SayIt Windows debug receiver over LAN HTTP. No ASR integration.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: RecordingViewModel by viewModels {
        val store = SettingsStore(applicationContext)
        RecordingViewModel.Factory(store)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind the vibrator to the ViewModel (needs Context; ViewModel has none).
        viewModel.onVibrate = { pattern -> vibratePattern(this, pattern) }

        setContent {
            var hasPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> hasPermission = granted }

            LaunchedEffect(Unit) {
                viewModel.prepare()
            }

            RecordingScreen(
                viewModel = viewModel,
                settings = SettingsStore(applicationContext),
                hasPermission = hasPermission,
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
        }
    }
}
