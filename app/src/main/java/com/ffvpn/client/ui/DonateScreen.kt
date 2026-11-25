package com.ffvpn.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ffvpn.client.R
import com.ffvpn.client.Texts

/** The XMR address shown under the QR code, and copied by the button. */
private const val MONERO_ADDRESS =
    "43QRPZJCEVZcyRouhmyKrNi7faNs5RiaC3CZMh32F8tgha6bDz1GPwGJjyVzajY1udKyYfWL6W5q8i6J9TYNYeB2GMXmCsh"

/** A quiet thank-you page: the donated QR image, the raw address, and a copy button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val gold = MaterialTheme.colorScheme.tertiary

    BackHandler { onBack() }

    Box(modifier = Modifier.fillMaxSize().background(Lotus.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            TopAppBar(
                title = { Text(Texts.donateTitle, fontWeight = FontWeight.SemiBold) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = Texts.back
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                DiamondIcon(width = 116.dp)
                Text(
                    text = Texts.donateTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = gold,
                    textAlign = TextAlign.Center
                )
                HeartBadge(gold)
                Text(
                    text = Texts.donateThanks,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                // The uploaded QR code sits on white: scanners need the quiet zone.
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.donate_monero_qr),
                        contentDescription = Texts.donateQr,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(212.dp).padding(12.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, gold.copy(alpha = 0.38f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = Texts.donateAddressLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = gold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        SelectionContainer {
                            Text(
                                text = MONERO_ADDRESS,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { copyAddress(context) }) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(Texts.donateCopy)
                        }
                    }
                }
                Text(
                    text = Texts.donateHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun HeartBadge(gold: Color) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(50))
            .background(gold.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Favorite,
            contentDescription = null,
            tint = gold,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun copyAddress(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Monero address", MONERO_ADDRESS))
    Toast.makeText(context, Texts.donateCopied, Toast.LENGTH_SHORT).show()
}
