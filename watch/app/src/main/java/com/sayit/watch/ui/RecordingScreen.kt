package com.sayit.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.sayit.watch.recording.RecordingSession
import com.sayit.watch.settings.SettingsStore
import com.sayit.watch.settings.DestinationValidator
import kotlinx.coroutines.delay

/**
 * Main Wear OS screen. Shows configuration state, recording duration, and
 * Start/Stop/Send controls. Displays "Uploaded / transport verified" only on
 * 201 Created — never "Transcribed".
 */
@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel,
    settings: SettingsStore,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sampleCount by viewModel.sampleCount.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val canSend by viewModel.canSend.collectAsState()
    var ipText by remember { mutableStateOf(settings.receiverIp) }
    var portText by remember { mutableStateOf(settings.receiverPort) }
    var tokenText by remember { mutableStateOf(settings.devToken) }
    var elapsedSec by remember { mutableStateOf(0) }

    LaunchedEffect(state) {
        while (state == RecordingSession.State.RECORDING) {
            elapsedSec = viewModel.durationMs.toInt() / 1000
            delay(250)
        }
    }

    MaterialTheme {
        TimeText()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "SayIt Watch",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))

            // Destination + token settings (debug only)
            SettingsField("PC IP (RFC1918)", ipText) { ipText = it; settings.receiverIp = it }
            SettingsField("Port", portText) { portText = it; settings.receiverPort = it }
            SettingsField("Dev Token", tokenText) { tokenText = it; settings.devToken = it }

            val dest = DestinationValidator.validate(ipText, portText)
            val destOk = dest is DestinationValidator.ValidationResult.Valid
            val tokenOk = tokenText.length >= 32
            val canStart = hasPermission && destOk && tokenOk

            Spacer(Modifier.height(8.dp))
            if (!hasPermission) {
                Button(onClick = onRequestPermission) { Text("Grant Mic") }
            } else {
                when (state) {
                    RecordingSession.State.IDLE,
                    RecordingSession.State.READY,
                    RecordingSession.State.RECORDED,
                    RecordingSession.State.FAILURE,
                    -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { viewModel.startRecording() },
                                enabled = canStart,
                                colors = ButtonDefaults.buttonColors(),
                            ) { Text("Start") }
                            Spacer(Modifier.width(8.dp))
                            if (canSend) {
                                Button(onClick = { viewModel.send() }) { Text("Send") }
                            }
                        }
                    }
                    RecordingSession.State.RECORDING -> {
                        Button(
                            onClick = { viewModel.stopRecording() },
                            colors = ButtonDefaults.buttonColors(),
                        ) { Text("Stop") }
                    }
                    RecordingSession.State.UPLOADING -> {
                        Text("Uploading…")
                    }
                    RecordingSession.State.TRANSPORT_SUCCESS -> {
                        Text("Uploaded / transport verified")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.reset() }) { Text("New") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            StatusLine(viewModel, state, sampleCount, lastError)
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colors.onSurfaceVariant)
        // Minimal editable row; text entry uses the Wear keyboard via a focused field.
        Text(
            text = if (value.isEmpty()) "—" else value,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusLine(
    viewModel: RecordingViewModel,
    state: RecordingSession.State,
    sampleCount: Int,
    lastError: String?,
) {
    val text = when (state) {
        RecordingSession.State.IDLE -> "Idle"
        RecordingSession.State.READY -> "Ready — 16 kHz verified"
        RecordingSession.State.RECORDING -> "Recording ${viewModel.durationMs} ms"
        RecordingSession.State.RECORDED -> "Recorded ($sampleCount samples)"
        RecordingSession.State.UPLOADING -> "Uploading…"
        RecordingSession.State.TRANSPORT_SUCCESS -> "Uploaded / transport verified"
        RecordingSession.State.FAILURE -> lastError ?: "Failure"
    }
    Text(
        text = text,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
