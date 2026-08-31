package com.sayit.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.sayit.watch.R
import com.sayit.watch.recording.WavWriter
import com.sayit.watch.settings.DestinationValidator
import com.sayit.watch.settings.DevTokenValidator
import com.sayit.watch.settings.SettingsStore

/** 0.2.0-dev.3 Watch presentation layer; recording and transport logic stay untouched. */
private val SayItBlue = Color(0xFF1976E9)
private val RecordingRed = Color(0xFFE14B52)
private val FieldSurface = Color(0xFF252A34)
private val PanelSurface = Color(0xFF20252E)
private val MutedText = Color(0xFFB5BFCC)

/** Responsive metrics: fixed dp is only used for touch targets and icon sizes. */
object WatchUiMetrics {
    val WideChipHeightDp = 52.dp
    val RowMinHeightDp = 48.dp
    val MainActionSizeDp = 92.dp
    val ScreenSidePaddingDp = 22.dp
    const val WideChipWidthFraction = 1f
    const val HalfChipWeight = 1f
    val HalfChipGapDp = 10.dp
}

/** Duration is derived by the ViewModel from captured audio data, never wall time. */
fun formatRecordingDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}

/** Keeps the visible timer byte-for-byte aligned with the recorder's WAV duration rule. */
fun formatRecordingDurationFromSamples(sampleCount: Int): String =
    formatRecordingDuration(WavWriter.durationMs(sampleCount))

fun maskToken(token: String): String =
    if (token.length <= 8) "••••••••" else token.take(4) + "••••••••" + token.takeLast(4)

@Composable
private fun PillAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, background: Color = SayItBlue, enabled: Boolean = true) {
    Box(
        modifier = modifier.height(WatchUiMetrics.WideChipHeightDp)
            .background(if (enabled) background else FieldSurface, RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else MutedText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))
    }
}

@Composable
private fun SmallIconAction(label: String, icon: IconType, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(width = 58.dp, height = 48.dp).clickable(onClick = onClick)) {
        Canvas(Modifier.size(24.dp)) { drawIcon(icon, SayItBlue) }
        Text(label, fontSize = 10.sp, color = MutedText)
    }
}

private enum class IconType { MICROPHONE, REFRESH, SETTINGS, CLOSE }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIcon(type: IconType, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * .10f, cap = StrokeCap.Round)
    when (type) {
        IconType.MICROPHONE -> {
            drawRoundRect(color, Offset(w * .34f, h * .08f), Size(w * .32f, h * .48f), CornerRadius(w * .16f, w * .16f))
            drawArc(color, 0f, 180f, false, Offset(w * .20f, h * .28f), Size(w * .60f, h * .48f), style = stroke)
            drawLine(color, Offset(w * .50f, h * .76f), Offset(w * .50f, h * .94f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .30f, h * .94f), Offset(w * .70f, h * .94f), stroke.width, StrokeCap.Round)
        }
        IconType.REFRESH -> {
            drawArc(color, 35f, 285f, false, Offset(w * .12f, h * .12f), Size(w * .76f, h * .76f), style = stroke)
            drawLine(color, Offset(w * .80f, h * .12f), Offset(w * .88f, h * .36f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .80f, h * .12f), Offset(w * .58f, h * .17f), stroke.width, StrokeCap.Round)
        }
        IconType.SETTINGS -> {
            drawCircle(color, w * .17f, Offset(w * .50f, h * .50f), style = stroke)
            repeat(4) { index ->
                val x = if (index % 2 == 0) w * .50f else if (index == 1) w * .84f else w * .16f
                val y = if (index % 2 == 1) h * .50f else if (index == 0) h * .16f else h * .84f
                drawLine(color, Offset(w * .50f, h * .50f), Offset(x, y), stroke.width, StrokeCap.Round)
            }
            drawCircle(color, w * .09f, Offset(w * .50f, h * .50f))
        }
        IconType.CLOSE -> {
            drawLine(color, Offset(w * .25f, h * .25f), Offset(w * .75f, h * .75f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * .75f, h * .25f), Offset(w * .25f, h * .75f), stroke.width, StrokeCap.Round)
        }
    }
}

@Composable
fun RecordingScreen(viewModel: RecordingViewModel, settings: SettingsStore, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (ui.screen) {
                WatchUiState.Screen.CONFIG -> ConfigScreen(viewModel, settings)
                WatchUiState.Screen.READY -> ReadyScreen(viewModel, ui, hasPermission, onRequestPermission)
                WatchUiState.Screen.RECORDING -> RecordingActiveScreen(viewModel)
            }
        }
    }
}

@Composable
private fun ConfigScreen(viewModel: RecordingViewModel, settings: SettingsStore) {
    var ipText by remember { mutableStateOf(settings.receiverIp) }
    var portText by remember { mutableStateOf(settings.receiverPort) }
    var tokenText by remember { mutableStateOf(settings.devToken) }
    var tokenRevealed by remember { mutableStateOf(false) }
    var editingToken by remember { mutableStateOf(false) }
    val canApply = DestinationValidator.validate(ipText, portText) is DestinationValidator.ValidationResult.Valid && DevTokenValidator.isValid(tokenText)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = WatchUiMetrics.ScreenSidePaddingDp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.screen_config_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        SettingsField(stringResource(R.string.config_pc_ip), ipText) { ipText = it }
        Spacer(Modifier.height(8.dp))
        SettingsField(stringResource(R.string.config_port), portText) { portText = it }
        Spacer(Modifier.height(8.dp))
        FieldCard(stringResource(R.string.config_dev_token), if (tokenText.isEmpty()) stringResource(R.string.config_tap_to_edit) else if (tokenRevealed) tokenText else maskToken(tokenText), if (tokenRevealed) stringResource(R.string.config_hide_token) else stringResource(R.string.config_show_token), { editingToken = true }) { tokenRevealed = !tokenRevealed }
        Spacer(Modifier.height(14.dp))
        PillAction(stringResource(R.string.config_save_apply), { viewModel.applySettings(ipText, portText, tokenText) }, Modifier.fillMaxWidth(), enabled = canApply)
        if (!canApply) { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.config_validation_hint), fontSize = 10.sp, color = MutedText, textAlign = TextAlign.Center) }
    }
    if (editingToken) WearTextInputDialog(stringResource(R.string.config_dev_token), tokenText, { tokenText = it; editingToken = false }, { editingToken = false })
}

@Composable
private fun ReadyScreen(viewModel: RecordingViewModel, ui: WatchUiState, hasPermission: Boolean, onRequestPermission: () -> Unit) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val micSize = if (maxHeight < 230.dp) 88.dp else 96.dp
    Column(Modifier.fillMaxSize().padding(horizontal = WatchUiMetrics.ScreenSidePaddingDp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Text(stringResource(R.string.screen_ready_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            val health = when (ui.transportAvailable) { true -> stringResource(R.string.health_available); false -> stringResource(R.string.health_unavailable); null -> stringResource(R.string.health_unchecked) }
            Text(health, fontSize = 11.sp, color = if (ui.transportAvailable == false) RecordingRed else MutedText, modifier = Modifier.weight(1f))
            SmallIconAction(stringResource(R.string.health_check_action), IconType.REFRESH) { viewModel.checkHealth() }
            SmallIconAction(stringResource(R.string.ready_open_config), IconType.SETTINGS) { viewModel.openConfig() }
        }
        if (!hasPermission) PillAction(stringResource(R.string.ready_grant_mic), onRequestPermission, Modifier.fillMaxWidth()) else {
            Box(Modifier.size(micSize).background(SayItBlue, CircleShape).clickable { viewModel.recordButtonPressed() }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(micSize * .48f)) { drawIcon(IconType.MICROPHONE, Color.White) }
            }
            Text(stringResource(R.string.ready_record), fontSize = 12.sp, color = MutedText)
        }
    }
}

@Composable
private fun RecordingActiveScreen(viewModel: RecordingViewModel) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < 230.dp
    // Subscribe to the StateFlow so each captured-sample update recomposes the timer.
    val sampleCount by viewModel.sampleCount.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = WatchUiMetrics.ScreenSidePaddingDp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(RecordingRed, CircleShape)); Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.recording_title), fontSize = 15.sp, color = RecordingRed, fontWeight = FontWeight.SemiBold)
        }
        Text(formatRecordingDurationFromSamples(sampleCount), fontSize = if (compact) 38.sp else 44.sp, fontWeight = FontWeight.Bold)
        PillAction(stringResource(R.string.recording_stop), { viewModel.stopRecording() }, Modifier.fillMaxWidth(), background = RecordingRed)
        Row(Modifier.height(WatchUiMetrics.RowMinHeightDp).clickable { viewModel.cancelRecording() }, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(18.dp)) { drawIcon(IconType.CLOSE, MutedText) }; Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.recording_cancel_hint), fontSize = 12.sp, color = MutedText)
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    FieldCard(label, if (value.isEmpty()) stringResource(R.string.config_tap_to_edit) else value, onClick = { editing = true })
    if (editing) WearTextInputDialog(label, value, { onChange(it); editing = false }, { editing = false })
}

@Composable
private fun FieldCard(label: String, value: String, trailing: String? = null, onClick: () -> Unit, onTrailingClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).background(FieldSurface, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 10.sp, color = MutedText); Text(value, fontSize = 13.sp, maxLines = 1) }
        if (trailing != null && onTrailingClick != null) Text(trailing, fontSize = 11.sp, color = SayItBlue, modifier = Modifier.padding(start = 8.dp).clickable(onClick = onTrailingClick))
    }
}

@Composable
private fun WearTextInputDialog(label: String, initialValue: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(PanelSurface, RoundedCornerShape(18.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 13.sp, color = MutedText); Spacer(Modifier.height(8.dp))
            BasicTextField(value = text, onValueChange = { text = it }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center, color = Color.White), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onConfirm(text) }), modifier = Modifier.fillMaxWidth().background(FieldSurface, RoundedCornerShape(10.dp)).padding(10.dp).focusRequester(focusRequester))
            Spacer(Modifier.height(12.dp))
            PillAction(stringResource(R.string.action_ok), { keyboard?.hide(); onConfirm(text) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PillAction(stringResource(R.string.action_cancel), { keyboard?.hide(); onDismiss() }, Modifier.fillMaxWidth(), background = FieldSurface)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus(); keyboard?.show() }
}
