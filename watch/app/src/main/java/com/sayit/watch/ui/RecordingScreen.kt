package com.sayit.watch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.sayit.watch.recording.RecordingSession
import com.sayit.watch.settings.DevTokenValidator
import com.sayit.watch.settings.SettingsStore
import com.sayit.watch.settings.DestinationValidator

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

            // Destination + token settings (debug only). Each field is a real
            // on-device text input: tapping it opens the Wear keyboard and the
            // typed value is committed immediately to settings.
            SettingsField("PC IP (RFC1918)", ipText) { ipText = it; settings.receiverIp = it }
            SettingsField("Port", portText) { portText = it; settings.receiverPort = it }
            SettingsField("Dev Token (64 hex)", tokenText) { tokenText = it; settings.devToken = it }

            val dest = DestinationValidator.validate(ipText, portText)
            val destOk = dest is DestinationValidator.ValidationResult.Valid
            val tokenOk = DevTokenValidator.isValid(tokenText)
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

/**
 * A genuinely editable Wear field. Tapping the row opens a Wear dialog with a
 * [BasicTextField] that raises the on-device keyboard; the dialog auto-shifts
 * above the keyboard so its OK/Cancel buttons stay tappable. The value is
 * committed to [onChange] when the user confirms.
 */
@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colors.onSurfaceVariant)
        Text(
            text = if (value.isEmpty()) "— tap to edit" else value,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = true },
            textAlign = TextAlign.Center,
        )
    }

    if (editing) {
        WearTextInputDialog(
            label = label,
            initialValue = value,
            onConfirm = { newValue -> onChange(newValue); editing = false },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun WearTextInputDialog(
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colors.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurface,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { keyboard?.hide(); onConfirm(text) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { keyboard?.hide(); onDismiss() },
                    colors = ButtonDefaults.buttonColors(),
                ) { Text("Cancel") }
                Button(
                    onClick = { keyboard?.hide(); onConfirm(text) },
                    colors = ButtonDefaults.buttonColors(),
                ) { Text("OK") }
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
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
