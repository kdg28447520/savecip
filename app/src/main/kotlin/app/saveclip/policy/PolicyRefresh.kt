package app.saveclip.policy

import android.app.Application
import app.saveclip.UserMessageBus
import app.saveclip.core.policy.ExtraBlocks
import app.saveclip.core.policy.PersistedHosts
import app.saveclip.core.policy.PolicyClient
import app.saveclip.core.policy.PolicyKeys
import app.saveclip.core.policy.PolicyMerge
import app.saveclip.core.policy.PolicySchedule
import app.saveclip.core.policy.PolicySource
import app.saveclip.core.policy.PolicyVerifier
import app.saveclip.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PolicyRefresh(
    private val app: Application,
    private val settings: SettingsStore,
    private val messages: UserMessageBus,
) {
    suspend fun refreshIfDue() = withContext(Dispatchers.IO) {
        val file = File(app.filesDir, "policy/extra-hosts.txt")
        ExtraBlocks.replace(PersistedHosts.read(file))
        val url = PolicySource.DOCUMENT_URL
        if (url.isBlank()) return@withContext
        val current = settings.current()
        if (!PolicySchedule.due(current.policyFetchedAt, System.currentTimeMillis())) return@withContext
        val key = PolicyKeys.bundled() ?: return@withContext
        val document = runCatching { PolicyClient.create(PolicyVerifier(key)).fetch(url) }.getOrNull() ?: return@withContext
        val extras = PolicyMerge.extras(document)
        PersistedHosts.write(file, extras)
        ExtraBlocks.replace(extras)
        settings.setPolicyFetchedAt(System.currentTimeMillis())
        if (document.notice.isNotBlank()) messages.post(document.notice)
    }
}
