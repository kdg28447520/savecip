package app.saveclip.core.policy

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

class PolicyClient internal constructor(
    private val verifier: PolicyVerifier,
    private val http: OkHttpClient,
) {
    fun fetch(url: String): PolicyDocument? {
        var current = url.trim()
        if (current.isEmpty() || YouTubeBlocklist.isBlocked(current)) return null
        repeat(MAX_HOPS) {
            val request = Request.Builder().url(current).header("Accept", "application/json").build()
            http.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location") ?: return null
                    val next = response.request.url.resolve(location)?.toString() ?: return null
                    if (YouTubeBlocklist.isBlocked(next)) return null
                    if (!next.startsWith("https://") && !next.startsWith("http://")) return null
                    current = next
                    return@use
                }
                if (!response.isSuccessful) return null
                val signature = response.header("X-SaveClip-Signature").orEmpty()
                if (signature.isBlank()) return null
                val body = response.body?.bytes() ?: return null
                val signatureBytes = runCatching { Base64.getDecoder().decode(signature.trim()) }.getOrNull()
                    ?: return null
                return verifier.open(body, signatureBytes)
            }
        }
        return null
    }

    companion object {
        private const val MAX_HOPS = 3

        fun create(verifier: PolicyVerifier): PolicyClient {
            val http = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            return PolicyClient(verifier, http)
        }
    }
}
