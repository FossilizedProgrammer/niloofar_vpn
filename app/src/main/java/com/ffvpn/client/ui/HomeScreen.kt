package com.ffvpn.client.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ffvpn.client.BuildConfig
import com.ffvpn.client.R
import com.ffvpn.client.Texts
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.CoreRegistry
import com.ffvpn.client.core.GithubUpdates
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.core.StatusText
import com.ffvpn.client.vpn.Store
import kotlinx.coroutines.delay

private enum class Phase { Off, Connecting, Disconnecting, On, Error }

@Composable
fun HomeScreen(
    state: AppState.State,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTools: () -> Unit
) {
    val context = LocalContext.current
    val phase = phaseOf(state)
    val accent = when (phase) {
        Phase.Off -> Lotus.idle
        Phase.Connecting, Phase.Disconnecting -> MaterialTheme.colorScheme.secondary
        Phase.On -> MaterialTheme.colorScheme.primary
        Phase.Error -> MaterialTheme.colorScheme.error
    }
    val glow by animateColorAsState(accent, label = "glow")

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Lotus.background)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.95f)
                .aspectRatio(1f)
                .background(
                    Brush.radialGradient(listOf(glow.copy(alpha = 0.15f), Color.Transparent)),
                    CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            TopBar(onOpenTools, onOpenSettings)
            Spacer(Modifier.height(14.dp))
            CorePicker(state, enabled = !state.running && !state.busy && !state.disconnecting)
            Spacer(Modifier.height(20.dp))
            Orb(phase = phase, accent = accent, onClick = onToggle)
            Spacer(Modifier.height(20.dp))
            StatusBlock(state, phase, accent)
            if (state.coreId == "firefox" && !Store.hasSession(context)) {
                Spacer(Modifier.height(10.dp))
                AccountChip(onClick = onOpenSettings)
            }
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
            )
            state.update?.let { release -> UpdateBadge(release) }
        }
    }
}

/** A quiet notice that a newer release exists; tapping it opens that release. */
@Composable
private fun UpdateBadge(release: GithubUpdates.Release) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)),
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.update_available, release.version),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

private fun phaseOf(state: AppState.State): Phase = when {
    state.disconnecting -> Phase.Disconnecting
    state.connected -> Phase.On
    state.busy || state.running -> Phase.Connecting
    state.statusCode >= 2000 && state.statusCode != StatusText.STOPPED -> Phase.Error
    else -> Phase.Off
}

@Composable
private fun TopBar(onOpenTools: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark()
        Spacer(Modifier.width(12.dp))
        Text(
            text = Texts.appName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpenTools) {
            Icon(
                imageVector = Icons.Rounded.Widgets,
                contentDescription = Texts.tools,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = stringResource(R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BrandMark() {
    // Same artwork as the launcher icon: the rosette is cropped out of the
    // adaptive-icon foreground, whose sandstone plate fills 62% of its canvas.
    Box(
        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(61.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CorePicker(state: AppState.State, enabled: Boolean) {
    val cores = CoreRegistry.all()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cores.forEach { core ->
            val selected = core.id == state.coreId
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                },
                border = BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                ),
                modifier = Modifier.clickable(enabled = enabled) {
                    Prefs.coreId = core.id
                    AppState.update { it.copy(coreId = core.id) }
                }
            ) {
                Text(
                    text = Texts.coreName(core.id),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun Orb(phase: Phase, accent: Color, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "orb")
    val ringMillis = if (phase == Phase.Connecting || phase == Phase.Disconnecting) 2_600 else 11_000
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(ringMillis, easing = LinearEasing)
        ),
        label = "spin"
    )
    // The inner arc counter-rotates 1.6x faster on its own 0..360 cycle. Scaling
    // the outer angle by a fraction instead leaves a gap where the cycle wraps,
    // which reads as a jump once per revolution.
    val counterSpin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween((ringMillis / 1.6f).toInt(), easing = LinearEasing)
        ),
        label = "counterSpin"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            tween(1_900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val idle = phase == Phase.Off || phase == Phase.Error

    Box(
        modifier = Modifier
            .size(276.dp)
            .graphicsLayer {
                val press = if (pressed) 0.965f else 1f
                val breath = if (phase == Phase.On) pulse else 1f
                scaleX = press * breath
                scaleY = press * breath
            }
            .clickable(
                enabled = phase != Phase.Disconnecting,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val alpha = if (idle) 0.5f else 0.95f

            if (phase != Phase.On) {
                rotate(spin) {
                    drawArc(
                        color = accent.copy(alpha = alpha),
                        startAngle = -90f,
                        sweepAngle = 108f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                rotate(-counterSpin) {
                    drawArc(
                        color = accent.copy(alpha = alpha * 0.55f),
                        startAngle = 40f,
                        sweepAngle = 62f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius * 0.86f, center.y - radius * 0.86f),
                        size = Size(radius * 1.72f, radius * 1.72f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.24f),
                        Color(0xFF121A24),
                        Color(0xFF0A0E15)
                    ),
                    center = Offset(center.x, center.y - radius * 0.35f),
                    radius = radius * 1.05f
                ),
                radius = radius * 0.72f,
                center = center
            )
            drawCircle(
                color = accent.copy(alpha = 0.32f),
                radius = radius * 0.72f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        Icon(
            imageVector = Icons.Rounded.PowerSettingsNew,
            contentDescription = stringResource(R.string.cd_power),
            tint = accent,
            modifier = Modifier.size(56.dp)
        )
    }
}

@Composable
private fun StatusBlock(state: AppState.State, phase: Phase, accent: Color) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.connectedAt) {
        while (state.connectedAt > 0L) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when (phase) {
                Phase.Off -> Texts.ready
                Phase.Connecting -> Texts.connecting
                Phase.Disconnecting -> Texts.disconnecting
                Phase.On -> Texts.connected
                Phase.Error -> Texts.failed
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = Texts.coreName(state.coreId).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(8.dp))
        val detail = when {
            phase == Phase.Disconnecting -> Texts.disconnectingHint
            phase == Phase.Error ->
                StatusText.describe(state.statusCode, state.statusDetail) +
                    if (state.diagnosis.isNotBlank()) " · ${state.diagnosis}" else ""
            phase == Phase.Connecting -> Texts.progress(state.coreId, state.statusDetail)
            phase == Phase.On && state.shareLan && state.lan.isNotBlank() ->
                Texts.lanDetail(state.lan, state.lanHttp)
            phase == Phase.On && state.mode == AppState.Mode.PROXY ->
                Texts.proxyDetail(Prefs.socksAddress, Prefs.httpAddress)
            phase == Phase.On -> Texts.coreActiveDetail(Texts.coreName(state.coreId))
            else -> Texts.detailIdle
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp)
        )
        if (phase == Phase.On) {
            val endpoint = listOf(state.exitIp, state.exitPlace)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (endpoint.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            val session = buildList {
                if (state.connectMillis > 0L) {
                    add(Texts.connectTook(Texts.duration(state.connectMillis)))
                }
                if (state.connectedAt > 0L) {
                    add(
                        Texts.sessionUptime(
                            Texts.duration((now - state.connectedAt).coerceAtLeast(0L))
                        )
                    )
                }
            }
            if (session.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = session.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun AccountChip(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = stringResource(R.string.account_title) + " · " + stringResource(R.string.action_sign_in),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}
