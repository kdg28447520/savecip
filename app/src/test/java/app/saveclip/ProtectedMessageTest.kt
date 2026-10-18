package app.saveclip

import app.saveclip.core.UserMessages
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectedMessageTest {
    @Test
    fun messageMatchesProductCopy() {
        assertEquals("보호된 영상은 받을 수 없어요", UserMessages.PROTECTED)
    }
}
