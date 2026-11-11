package com.ffvpn.client.ui

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ffvpn.client.BuildConfig
import com.ffvpn.client.R
import com.ffvpn.client.vpn.Mode
import com.ffvpn.client.vpn.Phase
import com.ffvpn.client.vpn.VpnUi

@Composable
fun HomeScreen(
    ui: VpnUi,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val accent = when (ui.phase) {
        Phase.Off -> Color(0xFF8A90A2)
        Phase.Connecting -> MaterialTheme.colorScheme.secondary
        Phase.On -> MaterialTheme.colorScheme.primary
        Phase.Error -> MaterialTheme.colorScheme.error
    }
    val glow by animateColorAsState(accent, label = "glow")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0C0E16), Color(0xFF0A0B10), Color(0xFF100A16))
                )
            )
    ) {
        // Soft halo behind the orb, so the screen has depth without an image.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.95f)
                .aspectRatio(1f)
                .background(
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = 0.16f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopBar(onOpenSettings)
            Spacer(Modifier.weight(1f))
            Orb(phase = ui.phase, accent = accent, onClick = onToggle)
            Spacer(Modifier.height(26.dp))
            StatusBlock(ui = ui, accent = accent)
            Spacer(Modifier.weight(1f))
            StatsCard(ui)
            Spacer(Modifier.height(14.dp))
            if (!ui.signedIn) {
                AccountChip(onClick = onOpenSettings)
            }
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun TopBar(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.free_tier),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp)),
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

@Composable
private fun Orb(phase: Phase, accent: Color, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(if (phase == Phase.Connecting) 2_600 else 11_000, easing = LinearEasing)
        ),
        label = "spin"
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
            .size(286.dp)
            .graphicsLayer {
                val press = if (pressed) 0.965f else 1f
                val breath = if (phase == Phase.On) pulse else 1f
                scaleX = press * breath
                scaleY = press * breath
            }
            .clickable(
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

            // Two counter-rotating arcs: only while the tunnel is not up. Once
            // connected they are gone and the disc alone shows the live state.
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
                rotate(-spin * 1.6f) {
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

            // Inner disc with a faint accent wash at the top.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.22f), Color(0xFF14161F), Color(0xFF0D0F16)),
                    center = Offset(center.x, center.y - radius * 0.35f),
                    radius = radius * 1.05f
                ),
                radius = radius * 0.72f,
                center = center
            )
            drawCircle(
                color = accent.copy(alpha = 0.30f),
                radius = radius * 0.72f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        Icon(
            imageVector = Icons.Rounded.PowerSettingsNew,
            contentDescription = stringResource(R.string.cd_power),
            tint = accent,
            modifier = Modifier.size(58.dp)
        )
    }
}

@Composable
private fun StatusBlock(ui: VpnUi, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(
                when {
                    ui.phase == Phase.Off -> R.string.status_off
                    ui.phase == Phase.Connecting -> R.string.status_connecting
                    ui.phase == Phase.On && ui.mode == Mode.Proxy -> R.string.status_proxy_on
                    ui.phase == Phase.On -> R.string.status_on
                    else -> R.string.status_error
                }
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
        Spacer(Modifier.height(8.dp))
        val detail = when {
            ui.phase == Phase.Error && ui.detail.isNotBlank() -> ui.detail
            ui.phase == Phase.Connecting && ui.step.isNotBlank() -> ui.step
            ui.phase == Phase.On && ui.share && ui.address.isNotBlank() ->
                stringResource(R.string.share_ready, ui.address) + " · " +
                    stringResource(R.string.share_ports, "1080", "1081")
            ui.phase == Phase.On && ui.mode == Mode.Proxy -> stringResource(R.string.hint_proxy_on)
            ui.phase == Phase.On -> stringResource(R.string.hint_on)
            else -> stringResource(R.string.hint_off)
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp)
        )
    }
}

@Composable
private fun StatsCard(ui: VpnUi) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 18.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Stat(
                label = stringResource(R.string.exit_label),
                value = ui.country.ifBlank { stringResource(R.string.exit_unknown) },
                modifier = Modifier.weight(1.15f)
            )
            Divider()
            Stat(
                label = stringResource(R.string.latency_label),
                value = if (ui.phase == Phase.On && ui.latencyMs > 0) {
                    stringResource(R.string.latency_format, ui.latencyMs)
                } else {
                    "—"
                },
                modifier = Modifier.weight(0.85f)
            )
            Divider()
            Stat(
                label = stringResource(R.string.quota_label),
                value = ui.quota.ifBlank { "—" },
                modifier = Modifier.weight(1.1f)
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    )
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
