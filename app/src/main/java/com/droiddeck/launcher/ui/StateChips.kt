package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StateChip(text: String, tone: ChipTone, small: Boolean = false) {
    val pal = LocalPalette.current
    val (fill, ink) = when (tone) {
        ChipTone.OK -> Color(0xFF16321F) to pal.good
        ChipTone.BUSY -> pal.signal.copy(alpha = 0.18f) to pal.signal
        ChipTone.WARN -> Color(0xFF3A2E10) to Color(0xFFFFC24D)
        ChipTone.DEAL -> Color(0xFF1E3A1A) to Color(0xFF9BE27A)
        ChipTone.PLAIN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text, fontSize = if (small) 9.sp else 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, color = ink, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(fill).padding(horizontal = if (small) 5.dp else 8.dp, vertical = if (small) 1.dp else 2.dp),
    )
}

internal enum class ChipTone { OK, BUSY, WARN, DEAL, PLAIN }
