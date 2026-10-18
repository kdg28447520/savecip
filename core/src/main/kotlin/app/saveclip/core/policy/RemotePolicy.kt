package app.saveclip.core.policy

import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

data class PolicyDocument(
    val addedDomains: List<String>,
    val notice: String,
)

object PolicyDocumentParser {
    private val forbidden = setOf(
        "script",
        "javascript",
        "js",
        "eval",
        "code",
        "removedomains",
        "allowdomains",
        "unblock",
        "whitelist",
    )

    fun parse(json: String): PolicyDocument? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val keys = obj.keys().asSequence().map { it.lowercase(Locale.US) }.toSet()
        if (keys.any { it in forbidden }) return null
        if (!obj.has("addedDomains")) return null
        val array = obj.optJSONArray("addedDomains") ?: return null
        val domains = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val host = normalizeHost(array.optString(index)) ?: continue
            domains += host
        }
        return PolicyDocument(domains, sanitizeNotice(obj.optString("notice")))
    }

    fun sanitizeNotice(raw: String): String {
        return raw
            .replace(Regex("<[^>]*>"), "")
            .replace("javascript:", "", ignoreCase = true)
            .trim()
            .take(180)
    }

    fun normalizeHost(raw: String): String? {
        var host = raw.trim().lowercase(Locale.US)
        if (host.startsWith("http://") || host.startsWith("https://")) {
            host = YouTubeBlocklist.hostOf(host) ?: return null
        }
        host = host.substringBefore('/').substringBefore('?').trimEnd('.')
        if (host.isEmpty() || !host.contains('.') || host.contains(' ') || host.any { it.isISOControl() }) {
            return null
        }
        return host
    }
}

object PolicyMerge {
    fun extras(document: PolicyDocument): Set<String> = document.addedDomains.toSet()

    fun hostBlocked(url: String, extraHosts: Set<String>): Boolean {
        val host = YouTubeBlocklist.hostOf(url) ?: return false
        return extraHosts.any { root -> host == root || host.endsWith(".$root") }
    }

    fun blocks(url: String, extraHosts: Set<String>): Boolean {
        if (YouTubeBlocklist.isBlocked(url)) return true
        return hostBlocked(url, extraHosts)
    }
}

object ExtraBlocks {
    @Volatile
    var hosts: Set<String> = emptySet()
        private set

    fun replace(value: Set<String>) {
        hosts = value
    }

    fun isBlocked(url: String): Boolean = PolicyMerge.hostBlocked(url, hosts)
}

class PolicyVerifier(private val publicKey: PublicKey) {
    fun verify(payload: ByteArray, signature: ByteArray): Boolean {
        return try {
            val signatureEngine = Signature.getInstance("SHA256withRSA")
            signatureEngine.initVerify(publicKey)
            signatureEngine.update(payload)
            signatureEngine.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    fun open(payload: ByteArray, signature: ByteArray): PolicyDocument? {
        if (!verify(payload, signature)) return null
        val text = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        return PolicyDocumentParser.parse(text)
    }
}

object PolicyKeys {
    fun rsaPublic(pem: String): PublicKey {
        val body = pem.lineSequence()
            .filter { !it.startsWith("-----") }
            .joinToString("")
            .replace(Regex("\\s"), "")
        val bytes = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun bundled(): PublicKey? {
        val pem = PolicyKeys::class.java.getResourceAsStream("/policy/policy-public.pem")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return null
        return runCatching { rsaPublic(pem) }.getOrNull()
    }
}

object PolicySource {
    /**
     * Empty until a publisher sets a static JSON URL.
     * The response is verified and parsed as data. It is never executed.
     */
    const val DOCUMENT_URL = ""
}

object PolicySchedule {
    const val INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun due(lastFetchedAt: Long, now: Long): Boolean {
        if (lastFetchedAt <= 0L) return true
        return now - lastFetchedAt >= INTERVAL_MS
    }
}

object PersistedHosts {
    fun write(file: File, hosts: Set<String>) {
        file.parentFile?.mkdirs()
        val lines = hosts
            .mapNotNull { PolicyDocumentParser.normalizeHost(it) }
            .distinct()
            .sorted()
        file.writeText(lines.joinToString("\n"))
    }

    fun read(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        return file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            PolicyDocumentParser.normalizeHost(trimmed)
        }.toSet()
    }
}
