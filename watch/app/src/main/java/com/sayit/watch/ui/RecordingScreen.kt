package com.sayit.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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
import com.sayit.watch.R
import com.sayit.watch.recording.RecordingSession
import com.sayit.watch.settings.DestinationValidator
import com.sayit.watch.settings.DevTokenValidator
import com.sayit.watch.settings.SettingsStore

/**
 * 0.2.0-dev.2 Wear OS UI (docs/WATCH-UI-Z-HANDOFF.md).
 *
 * Three screens plus inline upload states — no separate Send page, no carousel
 * dots, no transcription-success wording. "Uploaded to PC" is transport-only.
 * This is a Wear OS app on a 480x480 round display: primary controls stay
 * inside the circular safe area (centered column, generous horizontal padding).
 */

/** Masked token display: first 4 + dots + last 4 (e.g. A1B2••••••••7890). */
fun maskToken(token: String): String {
    if (token.length <= 8) return "••••••••"
    return token.take(4) + "••••••••" + token.takeLast(4)
}

@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel,
    settings: SettingsStore,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val state by viewModel.state.collectAsState()
    val sampleCount by viewModel.sampleCount.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            TimeText()

            when (ui.screen) {
                WatchUiState.Screen.CONFIG -> ConfigScreen(viewModel, settings)
                WatchUiState.Screen.READY -> ReadyScreen(
                    viewModel = viewModel,
                    settings = settings,
                    ui = ui,
                    hasPermission = hasPermission,
                    onRequestPermission = onRequestPermission,
                    state = state,
                    sampleCount = sampleCount,
                    lastError = lastError,
                )
                WatchUiState.Screen.RECORDING -> RecordingActiveScreen(viewModel)
            }

            // Inline upload overlays sit above the screen content.
            when (ui.overlay) {
                WatchUiState.Overlay.UPLOADING -> UploadingOverlay()
                WatchUiState.Overlay.UPLOAD_FAILED -> UploadFailedOverlay(viewModel, ui)
                WatchUiState.Overlay.UPLOADED -> UploadedOverlay(viewModel)
                WatchUiState.Overlay.NONE -> {}
            }

            if (ui.discardPrompt) {
                DiscardPromptDialog(viewModel)
            }
        }
    }
}

// ─── Screen 1: developer configuration ───

@Composable
private fun ConfigScreen(viewModel: RecordingViewModel, settings: SettingsStore) {
    var ipText by remember { mutableStateOf(settings.receiverIp) }
    var portText by remember { mutableStateOf(settings.receiverPort) }
    var tokenText by remember { mutableStateOf(settings.devToken) }
    var tokenRevealed by remember { mutableStateOf(false) }

    var editingToken by remember { mutableStateOf(false) }

    val dest = DestinationValidator.validate(ipText, portText)
    val destOk = dest is DestinationValidator.ValidationResult.Valid
    val tokenOk = DevTokenValidator.isValid(tokenText)
    val canApply = destOk && tokenOk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 34.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.screen_config_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        SettingsField(stringResource(R.string.config_pc_ip), ipText) { ipText = it }
        SettingsField(stringResource(R.string.config_port), portText) { portText = it }

        // Token row: masked by default with an explicit temporary reveal action.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.config_dev_token),
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurfaceVariant,
                )
                Text(
                    text = if (tokenText.isEmpty()) {
                        stringResource(R.string.config_tap_to_edit)
                    } else if (tokenRevealed) {
                        tokenText
                    } else {
                        maskToken(tokenText)
                    },
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingToken = true },
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = if (tokenRevealed) {
                    stringResource(R.string.config_hide_token)
                } else {
                    stringResource(R.string.config_show_token)
                },
                fontSize = 11.sp,
                color = MaterialTheme.colors.primary,
                modifier = Modifier
                    .padding(start = 8.dp, top = 14.dp)
                    .clickable { tokenRevealed = !tokenRevealed },
            )
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { viewModel.applySettings(ipText, portText, tokenText) },
            enabled = canApply,
        ) {
            Text(stringResource(R.string.config_save_apply), fontSize = 13.sp)
        }
        if (!canApply) {
            Text(
                text = stringResource(R.string.config_validation_hint),
                fontSize = 10.sp,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
    }

    if (editingToken) {
        WearTextInputDialog(
            label = stringResource(R.string.config_dev_token),
            initialValue = tokenText,
            onConfirm = { newValue -> tokenText = newValue; editingToken = false },
            onDismiss = { editingToken = false },
        )
    }
}

// ─── Screen 2: Ready (health check + Record + Pending upload badge) ───

@Composable
private fun ReadyScreen(
    viewModel: RecordingViewModel,
    settings: SettingsStore,
    ui: WatchUiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    state: RecordingSession.State,
    sampleCount: Int,
    lastError: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 34.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.screen_ready_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))

        val transportText = when (ui.transportAvailable) {
            null -> stringResource(R.string.health_unchecked)
            true -> stringResource(R.string.health_available)
            false -> stringResource(R.string.health_unavailable)
        }
        Text(
            text = transportText,
            fontSize = 11.sp,
            color = if (ui.transportAvailable == true) {
                MaterialTheme.colors.primary
            } else {
                MaterialTheme.colors.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { viewModel.checkHealth() }) {
            Text(stringResource(R.string.health_check_action), fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.ready_open_config),
            fontSize = 11.sp,
            color = MaterialTheme.colors.primary,
            modifier = Modifier.clickable { viewModel.openConfig() },
        )

        if (ui.showsPendingUploadBadge) {
            Spacer(Modifier.height(6.dp))
            PendingUploadBadge()
        }

        Spacer(Modifier.height(10.dp))
        if (!hasPermission) {
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.ready_grant_mic), fontSize = 13.sp)
            }
        } else {
            Button(
                onClick = { viewModel.recordButtonPressed() },
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(stringResource(R.string.ready_record), fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        StatusLine(state, sampleCount, lastError)
    }

    // A pending WAV from a failed upload: explicit Retry is one tap away.
    if (ui.showsPendingUploadBadge) {
        LaunchedEffect(Unit) { /* badge only; retry lives in the failure overlay */ }
    }
}

@Composable
private fun PendingUploadBadge() {
    Text(
        text = stringResource(R.string.pending_upload_badge),
        fontSize = 11.sp,
        color = Color.Black,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(MaterialTheme.colors.secondary, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        textAlign = TextAlign.Center,
    )
}

// ─── Screen 3: Recording (sample-derived duration, Stop, Cancel) ───

@Composable
private fun RecordingActiveScreen(viewModel: RecordingViewModel) {
    val sampleCount by viewModel.sampleCount.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.recording_title),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        // Sample-derived duration — never a wall clock.
        Text(text = "${viewModel.durationMs} ms", fontSize = 22.sp)
        Text(
            text = stringResource(R.string.recording_sample_count, sampleCount),
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = { viewModel.stopRecording() }, colors = ButtonDefaults.buttonColors()) {
            Text(stringResource(R.string.recording_stop), fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.recording_cancel_hint),
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.cancelRecording() },
        )
    }
}

// ─── Inline overlay: Uploading (keep the screen on; no user action needed) ───

@Composable
private fun UploadingOverlay() {
    OverlayScaffold {
        Text(
            text = stringResource(R.string.uploading_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.uploading_body),
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Inline overlay: Upload failed (retain WAV; Retry / Later) ───

@Composable
private fun UploadFailedOverlay(viewModel: RecordingViewModel, ui: WatchUiState) {
    OverlayScaffold {
        Text(
            text = stringResource(R.string.upload_failed_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = ui.failureReason ?: stringResource(R.string.upload_failed_generic),
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.upload_failed_retained),
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.retryUpload() }) {
                Text(stringResource(R.string.upload_failed_retry), fontSize = 12.sp)
            }
            Button(onClick = { viewModel.laterPressed() }) {
                Text(stringResource(R.string.upload_failed_later), fontSize = 12.sp)
            }
        }
    }
}

// ─── Inline overlay: Uploaded to PC (transport-only, brief, auto-dismiss) ───

@Composable
private fun UploadedOverlay(viewModel: RecordingViewModel) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(RecordingViewModel.UPLOADED_BRIEF_MS)
        viewModel.uploadedBriefDismissed()
    }
    OverlayScaffold {
        Text(
            text = stringResource(R.string.uploaded_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.uploaded_body),
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Dialog: explicit discard decision before a new recording ───

@Composable
private fun DiscardPromptDialog(viewModel: RecordingViewModel) {
    Dialog(onDismissRequest = { viewModel.discardPromptDismissed() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(MaterialTheme.colors.surface, RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.discard_prompt_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.discard_prompt_body),
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.discardPromptDismissed() }) {
                    Text(stringResource(R.string.discard_keep), fontSize = 12.sp)
                }
                Button(onClick = { viewModel.discardConfirmed() }) {
                    Text(stringResource(R.string.discard_confirm), fontSize = 12.sp)
                }
            }
        }
    }
}

// ─── Shared overlay scaffold (circular safe area) ───

@Composable
private fun OverlayScaffold(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}

// ─── Status line (kept from 0.2.0-dev.1, transport-only wording) ───

@Composable
private fun StatusLine(state: RecordingSession.State, sampleCount: Int, lastError: String?) {
    val text = when (state) {
        RecordingSession.State.IDLE -> stringResource(R.string.status_idle)
        RecordingSession.State.READY -> stringResource(R.string.status_ready)
        RecordingSession.State.RECORDING -> stringResource(R.string.status_recording)
        RecordingSession.State.RECORDED -> stringResource(R.string.status_recorded, sampleCount)
        RecordingSession.State.UPLOADING -> stringResource(R.string.uploading_title)
        RecordingSession.State.TRANSPORT_SUCCESS -> stringResource(R.string.uploaded_title)
        RecordingSession.State.FAILURE -> lastError ?: stringResource(R.string.status_failure)
    }
    Text(
        text = text,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ─── Settings field + Wear keyboard dialog (kept from 0.2.0-dev.1) ───

@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colors.onSurfaceVariant)
        Text(
            text = if (value.isEmpty()) stringResource(R.string.config_tap_to_edit) else value,
            fontSize = 12.sp,
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
            Text(label, fontSize = 13.sp, color = MaterialTheme.colors.onSurfaceVariant)
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
                ) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { keyboard?.hide(); onConfirm(text) },
                    colors = ButtonDefaults.buttonColors(),
                ) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}
