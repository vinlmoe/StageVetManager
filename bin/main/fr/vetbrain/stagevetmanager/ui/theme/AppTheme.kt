package fr.vetbrain.stagevetmanager.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VetBlue   = Color(0xFF1565C0)
private val VetGreen  = Color(0xFF2E7D32)
private val VetSurface = Color(0xFFF5F7FA)

private val LightColors = lightColorScheme(
    primary        = VetBlue,
    onPrimary      = Color.White,
    primaryContainer   = Color(0xFFBBDEFB),
    secondary      = VetGreen,
    onSecondary    = Color.White,
    background     = VetSurface,
    surface        = Color.White,
    onSurface      = Color(0xFF1A1A2E),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography(),
        content = content,
    )
}
