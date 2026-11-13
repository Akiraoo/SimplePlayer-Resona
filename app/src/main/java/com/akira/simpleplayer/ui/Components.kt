package com.akira.simpleplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.akira.simpleplayer.core.Lib
import java.io.File

fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "FLAC · 44.1 kHz · 2ch" (rate and channels once the player knows them). */
fun formatLine(track: Int, rate: Int, channels: Int, bitrate: Int): String {
    val parts = mutableListOf<String>()
    Lib.format(track).takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
    if (rate > 0) {
        val k = rate / 1000.0
        parts += (if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(k)) + " kHz"
    }
    if (bitrate > 0 && Lib.format(track).lowercase() in setOf("mp3", "aac", "m4a", "ogg", "opus")) {
        parts += "${bitrate / 1000} kbps"
    }
    if (channels > 0) parts += if (channels == 1) tr("單聲道", "mono") else "${channels}ch"
    return parts.joinToString(" · ")
}

private val LOSSLESS = setOf("flac", "wav", "alac", "ape", "aiff", "aif", "wv", "dsf", "dff")

/** Small format tag in song rows (lossless formats in the accent colour, as the desktop). */
@Composable
fun FormatBadge(format: String) {
    if (format.isBlank()) return
    val p = LocalPalette.current
    val lossless = format.lowercase() in LOSSLESS
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, if (lossless) p.accentLine else p.line, RoundedCornerShape(4.dp))
            .background(if (lossless) p.accentSoft else p.surface)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            format.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (lossless) p.accent else p.muted,
            maxLines = 1
        )
    }
}

/** Cover art from the local cache, or the desktop's gradient placeholder. */
@Composable
fun Cover(track: Int, size: Dp, corner: Dp = 6.dp, modifier: Modifier = Modifier, full: Boolean = false) {
    val p = LocalPalette.current
    val path = if (track >= 0) Lib.cover(track) else ""
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(p.artA, p.artB))),
        contentAlignment = Alignment.Center
    ) {
        if (path.isEmpty()) {
            Icon(Icons.Filled.MusicNote, null, tint = p.artIcon, modifier = Modifier.size(size * 0.42f))
        } else {
            val request = remember(path, px, full) {
                ImageRequest.Builder(context)
                    .data(File(path))
                    .apply { if (!full) size(px, px) }
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) p.accent else p.raised)
            .border(1.dp, if (selected) p.accent else p.line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) p.accentText else p.text, maxLines = 1)
    }
}
