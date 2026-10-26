package com.nxdump

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF3F51B5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF), onPrimaryContainer = Color(0xFF001258),
    secondary = Color(0xFF5B5D72), tertiary = Color(0xFF00696C),
    background = Color(0xFFFBF8FF), surface = Color(0xFFFBF8FF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB9C3FF), onPrimary = Color(0xFF0D2284),
    primaryContainer = Color(0xFF2A3A9C), onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC4C5DD), tertiary = Color(0xFF4DDADE),
    background = Color(0xFF121318), surface = Color(0xFF121318),
)

@Composable
fun NxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
