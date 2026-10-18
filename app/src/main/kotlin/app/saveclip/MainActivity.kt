package app.saveclip

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.staticCompositionLocalOf
import app.saveclip.share.SharedIntent
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
        if (savedInstanceState == null) deliver(intent, container)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalContainer provides container) {
                SaveClipTheme {
                    SaveClipRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliver(intent, (application as SaveClipApp).container)
    }

    private fun deliver(intent: Intent?, container: AppContainer) {
        val url = SharedIntent.urlOf(intent) ?: return
        container.incoming.offer(url)
    }
}
