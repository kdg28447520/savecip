package app.saveclip.core.policy

import app.saveclip.core.UserMessages
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class RemotePolicyTest {
    @Test
    fun validSignatureAddsHostsAndCannotRemoveYouTube() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val verifier = PolicyVerifier(keys.public)
        val payload = """{"addedDomains":["rights.example","https://cdn.rights.example/a"],"notice":"권리자 요청"}"""
            .toByteArray()
        val document = verifier.open(payload, sign(keys.private, payload))
        assertEquals(listOf("rights.example", "cdn.rights.example"), document?.addedDomains)
        val extra = PolicyMerge.extras(document!!)
        assertTrue(PolicyMerge.blocks("https://www.youtube.com/watch?v=1", extra))
        assertTrue(PolicyMerge.blocks("https://clip.rights.example/v.mp4", extra))
        assertFalse(PolicyMerge.hostBlocked("https://www.youtube.com/watch?v=1", emptySet()))
        assertTrue(YouTubeBlocklist.isBlocked("https://www.youtube.com/watch?v=1"))
        assertFalse(PolicyMerge.blocks("https://example.com/v.mp4", extra))
    }

    @Test
    fun badSignatureScriptAndAllowListAreRejected() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val verifier = PolicyVerifier(keys.public)
        val payload = """{"addedDomains":["rights.example"]}""".toByteArray()
        assertNull(verifier.open(payload, sign(other.private, payload)))
        assertNull(verifier.open(payload, ByteArray(32)))
        assertNull(
            PolicyDocumentParser.parse(
                """{"addedDomains":["rights.example"],"script":"alert(1)"}""",
            ),
        )
        assertNull(
            PolicyDocumentParser.parse(
                """{"addedDomains":[],"allowDomains":["youtube.com"],"javascript":"void 0"}""",
            ),
        )
        assertNull(
            PolicyDocumentParser.parse(
                """{"addedDomains":["youtube.com"],"unblock":["youtube.com"]}""",
            ),
        )
        val notice = PolicyDocumentParser.sanitizeNotice("<script>alert(1)</script>안내 javascript:steal")
        assertFalse(notice.contains("<"))
        assertFalse(notice.contains("javascript:"))
        assertTrue(notice.contains("안내"))
    }

    @Test
    fun youtubeStaysBlockedWhenRemoteListIsEmptyOrTriesToAddIt() {
        val document = PolicyDocumentParser.parse("""{"addedDomains":["youtube.com","news.example"]}""")
        val extra = PolicyMerge.extras(document!!)
        ExtraBlocks.replace(extra)
        try {
            assertTrue(YouTubeBlocklist.isBlocked("https://youtu.be/abc"))
            try {
                DownloadGuard.checkUrl("https://youtu.be/abc")
                org.junit.Assert.fail("youtube should throw")
            } catch (error: YouTubeBlockedException) {
                assertEquals(UserMessages.YOUTUBE, error.message)
            }
            try {
                DownloadGuard.checkUrl("https://media.news.example/a.mp4")
                org.junit.Assert.fail("rights host should throw")
            } catch (error: RightsBlockedException) {
                assertEquals(UserMessages.RIGHTS, error.message)
            }
        } finally {
            ExtraBlocks.replace(emptySet())
        }
        assertFalse(ExtraBlocks.isBlocked("https://media.news.example/a.mp4"))
    }

    @Test
    fun clientAcceptsSignedBodyAndRefusesRedirectToYouTube() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val server = MockWebServer()
        val body = """{"addedDomains":["blocked.example"],"notice":"추가 안내"}"""
        val signature = Base64.getEncoder().encodeToString(sign(keys.private, body.toByteArray()))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-SaveClip-Signature", signature)
                .setBody(body),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://www.youtube.com/watch?v=1"),
        )
        server.start()
        try {
            val client = PolicyClient.create(PolicyVerifier(keys.public))
            val document = client.fetch(server.url("/policy.json").toString())
            assertEquals(listOf("blocked.example"), document?.addedDomains)
            assertEquals("추가 안내", document?.notice)
            assertNull(client.fetch(server.url("/redirect").toString()))
            assertNull(client.fetch(""))
            assertNull(client.fetch("https://youtu.be/abc"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun schedulePersistedHostsAndBundledKey() {
        assertTrue(PolicySchedule.due(0L, 10L))
        assertFalse(PolicySchedule.due(1_000L, 1_000L + PolicySchedule.INTERVAL_MS - 1))
        assertTrue(PolicySchedule.due(1_000L, 1_000L + PolicySchedule.INTERVAL_MS))
        val file = kotlin.io.path.createTempFile("hosts").toFile()
        try {
            PersistedHosts.write(file, setOf("Rights.Example", "<script>", "ok.example"))
            assertEquals(setOf("rights.example", "ok.example"), PersistedHosts.read(file))
        } finally {
            file.delete()
        }
        assertTrue(PolicyKeys.bundled() != null)
        assertEquals("", PolicySource.DOCUMENT_URL)
    }

    private fun sign(privateKey: java.security.PrivateKey, payload: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(payload)
        return signature.sign()
    }
}
