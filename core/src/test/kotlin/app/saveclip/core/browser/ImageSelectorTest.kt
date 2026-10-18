package app.saveclip.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSelectorTest {
    @Test
    fun srcsetPicksWidestCandidate() {
        val best = ImageSelector.largestSrcset(
            "https://pics.example/post",
            "/small.jpg 400w, /large.jpg 1280w, /mid.jpg 800w",
        )
        assertEquals("https://pics.example/large.jpg", best?.url)
        assertEquals(1280, best?.score)
    }

    @Test
    fun keepsLargeImageAndDropsTinyOnes() {
        val large = ImageSelector.choose(
            pageUrl = "https://pics.example/post",
            rawUrl = "https://cdn.example/photo.jpg",
            width = 1080,
            height = 720,
            srcset = "",
        )
        assertEquals("https://cdn.example/photo.jpg", large)
        assertNull(
            ImageSelector.choose(
                pageUrl = "https://pics.example/post",
                rawUrl = "https://cdn.example/icon.png",
                width = 48,
                height = 48,
                srcset = "",
            ),
        )
        val fromSet = ImageSelector.choose(
            pageUrl = "https://pics.example/post",
            rawUrl = "https://cdn.example/icon.png",
            width = 32,
            height = 32,
            srcset = "https://cdn.example/icon.png 32w, https://cdn.example/hero.jpg 1600w",
        )
        assertEquals("https://cdn.example/hero.jpg", fromSet)
    }

    @Test
    fun rejectsYouTubeBlobAndExecutables() {
        assertNull(
            ImageSelector.choose(
                pageUrl = "https://www.youtube.com/watch?v=1",
                rawUrl = "https://i.ytimg.com/vi/1/maxresdefault.jpg",
                width = 1280,
                height = 720,
                srcset = "",
            ),
        )
        assertNull(
            ImageSelector.choose(
                pageUrl = "https://pics.example/post",
                rawUrl = "blob:https://pics.example/111",
                width = 800,
                height = 800,
                srcset = "",
            ),
        )
        assertNull(
            ImageSelector.choose(
                pageUrl = "https://pics.example/post",
                rawUrl = "https://pics.example/payload.apk",
                width = 800,
                height = 800,
                srcset = "",
            ),
        )
    }

    @Test
    fun reportKeepsOnlyLargeImages() {
        val json = """
            {"images":[
              {"url":"https://cdn.example/a.jpg","width":1200,"height":800,"srcset":""},
              {"url":"https://cdn.example/b.jpg","width":16,"height":16,"srcset":""},
              {"url":"/c.jpg","width":10,"height":10,"srcset":"/c.jpg 100w, /c-big.jpg 900w","page":"https://pics.example/p"}
            ]}
        """.trimIndent()
        val urls = ImageReport.urls(json, "https://pics.example/p")
        assertEquals(listOf("https://cdn.example/a.jpg", "https://pics.example/c-big.jpg"), urls)
        assertTrue(ImageReport.urls("{", "https://pics.example/p").isEmpty())
    }
}
