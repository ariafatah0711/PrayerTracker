package com.prayertracker.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.prayertracker.app.R

@Preview(showBackground = false, widthDp = 512, heightDp = 512)
@Composable
fun AppLogoPreview() {
    Box(
        modifier = Modifier
            .size(512.dp)
            .background(Color(0xFF0B1320)), // Same as background vector color
        contentAlignment = Alignment.Center
    ) {
        // We can't easily layer the two vectors here if they are separate resources
        // but we can try to show the launcher icon which combines them
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(512.dp)
        )
    }
}
