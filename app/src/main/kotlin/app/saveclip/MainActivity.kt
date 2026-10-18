package app.saveclip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.staticCompositionLocalOf
import app.saveclip.ui.SaveClipRoot
import app.saveclip.ui.theme.SaveClipTheme

val LocalContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer가 없습니다")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SaveClipApp).container
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalContainer provides container) {
                SaveClipTheme {
                    SaveClipRoot()
                }
            }
        }
    }
}
