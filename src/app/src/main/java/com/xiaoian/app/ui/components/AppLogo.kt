package com.xiaoian.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import com.xiaoian.app.R

/**
 * The launcher icon drawn in-app. painterResource cannot load the
 * adaptive-icon mipmap, so its two layers are stacked here, scaled like a
 * launcher does: only the inner 72dp of the 108dp layers is visible.
 */
@Composable
fun AppLogo(modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(percent = 24))) {
        val layer = Modifier.fillMaxSize().scale(108f / 72f)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = layer)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Xiaoian", modifier = layer)
    }
}
