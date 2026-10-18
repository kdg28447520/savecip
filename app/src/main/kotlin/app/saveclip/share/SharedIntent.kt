package app.saveclip.share

import android.content.Intent
import app.saveclip.core.net.SharedLinks

object SharedIntent {
    fun urlOf(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        return SharedLinks.firstHttpUrl(raw)
    }
}
