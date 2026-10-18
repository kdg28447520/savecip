package app.saveclip.core.player

import app.saveclip.core.audio.AudioSave
import app.saveclip.core.download.Delivery
import app.saveclip.core.download.TransferActions
import app.saveclip.core.detect.Notices
import app.saveclip.core.hint.FirstRunHint
import app.saveclip.core.library.DeleteMode
import app.saveclip.core.library.LibraryOps
import app.saveclip.core.vault.PrivateFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerLibraryTest {
    @Test
    fun gesturesSpeedResumeAndOrientation() {
        assertEquals(GestureZone.BRIGHTNESS, PlayerGestures.zone(10f, 100f))
        assertEquals(GestureZone.SEEK, PlayerGestures.zone(50f, 100f))
        assertEquals(GestureZone.VOLUME, PlayerGestures.zone(80f, 100f))
        assertEquals(0.75f, PlayerGestures.dragVertical(0.5f, -50f, 200f), 0.001f)
        assertEquals(0f, PlayerGestures.dragVertical(0.1f, 400f, 100f), 0.001f)
        assertEquals(3_500L, PlayerGestures.seekTo(1_000L, 100f, 100f, 10_000L))
        assertEquals(0L, PlayerGestures.seekTo(100L, -400f, 100f, 4_000L))
        assertEquals(1.25f, PlayerGestures.nextSpeed(1f))
        assertEquals(0.5f, PlayerGestures.nextSpeed(2f))
        assertEquals(0L, PlayerGestures.positionToStore(2_000L, 10_000L))
        assertEquals(0L, PlayerGestures.positionToStore(9_500L, 10_000L))
        assertEquals(4_000L, PlayerGestures.resumePosition(4_000L, 10_000L))
        assertEquals(OrientationMode.LANDSCAPE, PlayerGestures.nextOrientation(OrientationMode.FREE))
        assertEquals(OrientationMode.FREE, PlayerGestures.nextOrientation(OrientationMode.PORTRAIT))
    }

    @Test
    fun renameDeleteAndRedownload() {
        assertEquals("클립.mp4", LibraryOps.sanitizeDisplayName("클립", "mp4"))
        assertEquals("영상.mp4", LibraryOps.sanitizeDisplayName("  ../.. ", "mp4"))
        assertFalse(LibraryOps.sanitizeDisplayName("a/b:c", "mp4").contains('/'))
        assertEquals(DeleteMode.DIRECT, LibraryOps.deleteMode(sdk = 29, count = 4, appPrivate = false))
        assertEquals(DeleteMode.SYSTEM_REQUEST, LibraryOps.deleteMode(sdk = 30, count = 2, appPrivate = false))
        assertEquals(DeleteMode.DIRECT, LibraryOps.deleteMode(sdk = 34, count = 3, appPrivate = true))
        assertEquals(DeleteMode.DIRECT, LibraryOps.deleteMode(sdk = 34, count = 1, appPrivate = false))
        assertEquals("https://vimeo.com/1", LibraryOps.redownloadUrl("vimeo.com/1"))
        assertNull(LibraryOps.redownloadUrl("https://www.youtube.com/watch?v=1"))
        assertNull(LibraryOps.redownloadUrl(" "))
    }

    @Test
    fun audioTrackSelectionAndDelivery() {
        assertEquals(listOf(1, 3), AudioSave.audioIndexes(listOf("video/mp4", "audio/mp4a-latm", "video/avc", "audio/mp4")))
        assertFalse(AudioSave.hasAudio(listOf("video/mp4")))
        assertEquals("m4a", AudioSave.EXTENSION)
        assertEquals("audio/mp4", AudioSave.MIME)
        assertEquals(Delivery.AUDIO, Delivery.normalize("audio"))
        assertEquals(Delivery.IMAGE, Delivery.normalize("image"))
        assertEquals(Delivery.VIDEO, Delivery.normalize(null))
        assertEquals("app.saveclip.action.PAUSE", TransferActions.PAUSE)
        assertEquals("app.saveclip.action.CANCEL", TransferActions.CANCEL)
        assertEquals("app.saveclip.action.OPEN", TransferActions.OPEN)
    }

    @Test
    fun privateFolderStaysInsideAppFiles() {
        val root = createTempDir(prefix = "saveclip-private")
        try {
            val file = PrivateFolder.resolve(root, "../../outside.mp4")
            assertTrue(PrivateFolder.isInside(root, file))
            assertTrue(file.path.contains("${File.separator}private${File.separator}"))
            assertFalse(file.path.contains(".."))
            assertFalse(PrivateFolder.isInside(root, File(root, "work/other.mp4")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun firstRunStartsWithCopyright() {
        assertEquals(3, FirstRunHint.steps.size)
        assertEquals(Notices.COPYRIGHT, FirstRunHint.steps[0])
        assertTrue(FirstRunHint.steps[1].contains("재생"))
        assertTrue(FirstRunHint.steps[2].contains("화질"))
        assertFalse(FirstRunHint.isLast(0))
        assertTrue(FirstRunHint.isLast(2))
    }
}
