package com.ffvpn.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ffvpn.client.R

/** The uploaded diamond, shown as-is — no spin, no extra decoration. */
@Composable
fun DiamondIcon(width: Dp = 34.dp) {
    Image(
        painter = painterResource(R.drawable.donate_diamond),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .width(width)
            .height(width * 382f / 448f)
    )
}
