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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlin.math.cos
import kotlin.math.sin

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
private fun ReadyScreen(viewModel: RecordingViewModel, ui: WatchUiState, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    WatchDial {
        if (!hasPermission) {
            PillAction(stringResource(R.string.ready_grant_mic), onRequestPermission, Modifier.fillMaxWidth().padding(horizontal = 40.dp))
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Title: small, gray, slightly higher.
                Text(stringResource(R.string.dial_title_ready), fontSize = 9.sp, color = DialMuted, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.Center).offset(y = -56.dp))
                // Line microphone: largest element, dead center.
                Box(Modifier.size(84.dp).align(Alignment.Center).clickable { viewModel.recordButtonPressed() }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(72.dp)) { drawIcon(IconType.MICROPHONE, DialIcon) }
                }
                // Dim, low-key settings entry (needed for first-time config).
                Text(stringResource(R.string.ready_open_config), fontSize = 8.sp, color = DialMuted, modifier = Modifier.align(Alignment.Center).offset(y = 66.dp).clickable { viewModel.openConfig() })
            }
        }
    }
}

@Composable
private fun RecordingActiveScreen(viewModel: RecordingViewModel) {
    val sampleCount by viewModel.sampleCount.collectAsState()
    WatchDial {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.dial_title_recording), fontSize = 9.sp, color = DialMuted, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.Center).offset(y = -56.dp))
            RecordingWaveform(SayItBlue, Modifier.size(width = 156.dp, height = 46.dp).align(Alignment.Center).clickable { viewModel.stopRecording() })
            Text(formatRecordingDurationFromSamples(sampleCount), fontSize = 20.sp, color = DialIcon, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).offset(y = 52.dp))
            Text(stringResource(R.string.recording_cancel_hint), fontSize = 8.sp, color = DialMuted, modifier = Modifier.align(Alignment.Center).offset(y = 84.dp).clickable { viewModel.cancelRecording() })
        }
    }
}

// ─── Watch dial (card) presentation ───────────────────────────────────────────

private val DialFace = Color(0xFFF6F7F9)
private val DialTick = Color(0xFF1C1E22)
private val DialIcon = Color(0xFF1C1E22)
private val DialMuted = Color(0xFF9AA3AE)

@Composable
private fun WatchDial(content: @Composable BoxScope.() -> Unit) {
    // Galaxy Watch 7 (SM-L310): 480×480 px @ density 340 → dial ≈ 226dp, radius ≈ 113dp.
    // The dial face fills the round screen; ticks sit just inside the bezel.
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) / 2f
            drawCircle(DialFace, radius, center)
            drawDialTicks(center, radius)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

private fun DrawScope.drawDialTicks(center: Offset, radius: Float) {
    // 60 uniform minute ticks in gray, hugging the bezel; 4 longer/thicker blue
    // marks at 12/3/6/9 o'clock.
    val outer = radius * 0.99f
    val minuteInner = radius * 0.95f
    val majorInner = radius * 0.89f
    repeat(60) { i ->
        val angle = i * 6.0 * Math.PI / 180.0
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val major = i % 15 == 0
        val end = center + dir * (if (major) majorInner else minuteInner)
        drawLine(
            color = if (major) SayItBlue else DialMuted,
            start = center + dir * outer,
            end = end,
            strokeWidth = if (major) radius * 0.030f else radius * 0.008f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun RecordingWaveform(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
    )
    Canvas(modifier) {
        // 11 symmetric bars: center tallest, tapering to the sides, thin round caps.
        val barCount = 11
        val maxAmp = size.height * 0.42f
        val midY = size.height / 2f
        val gap = size.width / barCount
        val barWidth = gap * 0.30f
        val phaseRad = phase.toDouble() * Math.PI
        for (i in 0 until barCount) {
            val envelope = (1.0 - kotlin.math.abs(i - (barCount - 1) / 2.0) / ((barCount - 1) / 2.0)).toFloat()
            val wave = (sin(phaseRad + i.toDouble() * 0.7) * 0.5 + 0.5).toFloat()
            val amp = maxAmp * (0.18f + 0.82f * envelope) * (0.55f + 0.45f * wave)
            val x = gap * i + gap / 2f
            drawLine(color, Offset(x, midY - amp), Offset(x, midY + amp), barWidth, StrokeCap.Round)
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
