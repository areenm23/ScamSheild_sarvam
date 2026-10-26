package live.scamshield.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF05070A)
val CardBg = Color(0x9E141C26)
val CardLine = Color(0x2678DCFF)
val TextMain = Color(0xFFE6F1F7)
val Muted = Color(0xFF8AA0AD)
val Cyan = Color(0xFF22D3EE)
val Emerald = Color(0xFF10B981)
val Amber = Color(0xFFF59E0B)
val Red = Color(0xFFEF4444)
val Violet = Color(0xFFA78BFA)

@Composable
fun ScamShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan, secondary = Emerald, tertiary = Amber, error = Red,
            background = Bg, surface = Bg, onBackground = TextMain, onSurface = TextMain,
        ),
        typography = Typography(),
        content = content,
    )
}
