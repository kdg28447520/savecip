package app.saveclip.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectedSheetTest {
    private val hash = "544956cc01fca32c70a52a28875354f61bde6ed8.mp4"
    private val otherHash = "7c1e09b2a4d5f6e8c3b1a0d9e8f7c6b5a4d3e2f1.mp4"

    @Test
    fun groupsRenditionsAndPreselectsTheBest() {
        val page = "https://site-a.example/v/28412"
        val built = DetectedSheet.build(
            clips = listOf(
                ClipInput("https://cdn.example/video_720.mp4", MediaKind.PROGRESSIVE_MP4, page, "한강 야경 - 사이트A"),
                ClipInput("https://cdn.example/video_1080.mp4", MediaKind.PROGRESSIVE_MP4, page, "한강 야경 - 사이트A"),
            ),
            photos = emptyList(),
            facts = mapOf(
                "https://cdn.example/video_720.mp4" to MediaFacts(bytes = 46L * 1024 * 1024, height = 720, durationMs = 222_000),
                "https://cdn.example/video_1080.mp4" to MediaFacts(bytes = 84L * 1024 * 1024, height = 1080, durationMs = 222_000),
            ),
            variants = emptyMap(),
        )
        assertEquals(1, built.videoCount)
        assertEquals("한강 야경", built.videos.single().title)
        assertEquals("3:42", built.videos.single().durationLabel)
        assertEquals("site-a.example · 최고 1080p", built.videos.single().meta)
        assertEquals(listOf("1080p", "720p"), built.videos.single().chips.map { it.headline })
        assertTrue(built.videos.single().chips.first().original)
        assertEquals("1080p로 받기 · 84.0 MB", DetectedSheet.cta(audio = false, built.videos.single().chips.first()))
        assertEquals("음성만 받기", DetectedSheet.cta(audio = true, built.videos.single().chips.first()))
        assertFalse(DetectedSheet.visibleCopy(built.videos.single()).contains("video_1080"))
        assertEquals(built.videos.single().id, built.selectedId)
        assertEquals(built.videos.single().chips.first().id, built.selectedChipId)
    }

    @Test
    fun hidesShortClipsAndNeverShowsHashNames() {
        val page = "https://cdn.example/watch"
        val longUrl = "https://cdn.example/media/$hash"
        val shortUrl = "https://cdn.example/preview/$otherHash"
        val built = DetectedSheet.build(
            clips = listOf(
                ClipInput(shortUrl, MediaKind.PROGRESSIVE_MP4, page, ""),
                ClipInput(longUrl, MediaKind.PROGRESSIVE_MP4, page, "   "),
            ),
            photos = listOf(PhotoInput("https://cdn.example/1652834595.webp", page, "")),
            facts = mapOf(
                longUrl to MediaFacts(bytes = 20_000_000, durationMs = 200_000),
                shortUrl to MediaFacts(bytes = 400_000, durationMs = 9_000),
            ),
            variants = emptyMap(),
        )
        assertEquals(1, built.videos.size)
        assertEquals(1, built.short.size)
        assertEquals("cdn.example 영상 2", built.videos.single().title)
        assertEquals("0:09", built.short.single().durationLabel)
        assertTrue(built.short.single().short)
        assertEquals(longUrl, built.videos.single().detailUrls.single())
        assertFalse(DetectedSheet.visibleCopy(built.videos.single()).contains("544956"))
        assertFalse(DetectedSheet.visibleCopy(built.short.single()).contains("7c1e09"))
        assertEquals("cdn.example 사진 1", built.photos.single().title)
        assertFalse(built.photos.single().title.contains("webp"))
        assertEquals("사진 1장 받기", DetectedSheet.photoCta(1))
        assertEquals("10초 미만 영상 1개 숨김 · 보기", DetectedSheet.shortLabel(1, open = false))
        assertEquals(built.videos.single().id, built.selectedId)
    }

    @Test
    fun hlsChoicesBecomeOneRowWithApproximateSizes() {
        val master = "https://cdn.example/hls/playlist.txt"
        val built = DetectedSheet.build(
            clips = listOf(
                ClipInput(master, MediaKind.HLS, "https://site-a.example/watch", "타임랩스 | 사이트"),
            ),
            photos = emptyList(),
            facts = mapOf(master to MediaFacts(durationMs = 10_000, approximate = true)),
            variants = mapOf(
                master to listOf(
                    SheetVariant("high", height = 1080, bandwidth = 10_400_000),
                    SheetVariant("low", height = 360, bandwidth = 800_000),
                ),
            ),
        )
        val card = built.videos.single()
        assertEquals("타임랩스", card.title)
        assertEquals(listOf("1080p", "360p"), card.chips.map { it.headline })
        assertEquals("약 12.4 MB", card.chips.first().sizeLabel)
        assertTrue(card.chips.first().original)
        assertEquals("1080p로 받기 · 약 12.4 MB", DetectedSheet.cta(false, card.chips.first()))
        assertFalse(DetectedSheet.visibleCopy(card).contains("playlist"))
        assertEquals("high", card.chips.first().variantId)
        assertFalse(card.short)
    }

    @Test
    fun tenSecondsStaysVisibleAndUnknownDurationIsNotShort() {
        val known = "https://cdn.example/a.mp4"
        val unknown = "https://cdn.example/b.mp4"
        val built = DetectedSheet.build(
            clips = listOf(
                ClipInput(known, MediaKind.PROGRESSIVE_MP4, "https://cdn.example/p", "정확히 10초"),
                ClipInput(unknown, MediaKind.WEBM, "https://cdn.example/p", "길이 모름"),
            ),
            photos = emptyList(),
            facts = mapOf(known to MediaFacts(durationMs = 10_000, bytes = 2_000_000)),
            variants = emptyMap(),
        )
        assertEquals(2, built.videos.size)
        assertTrue(built.short.isEmpty())
        assertEquals(known, built.videos.first().detailUrls.single())
        assertNull(built.videos[1].durationMs)
    }

    @Test
    fun noticesUseTheReviewedSentences() {
        assertEquals(
            "저작권이 있는 영상은 권리자의 허락을 받은 경우에만 저장하고, 다시 올리지 마세요.",
            Notices.COPYRIGHT,
        )
        assertTrue(Notices.PRIVATE_FOLDER.contains("앱을 삭제하면"))
        assertTrue(Notices.DRM.contains("DRM"))
    }
}
