package app.saveclip.core.dash

import app.saveclip.core.quality.QualityPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashMpdParserTest {
    @Test
    fun parsesTemplateInheritanceNumberFormatAndDuration() {
        val xml = """
            <MPD mediaPresentationDuration="PT12S" xmlns="urn:mpeg:dash:schema:mpd:2011">
              <BaseURL>https://cdn.example/dash/</BaseURL>
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <SegmentTemplate timescale="1" duration="4" startNumber="1" media="REP/NUM.m4s" initialization="REP/init.mp4"/>
                  <Representation id="video-low" bandwidth="200000" width="320" height="180" codecs="avc1.64000d"/>
                  <Representation id="video-hi" bandwidth="800000" width="1280" height="720" codecs="avc1.64001f"/>
                </AdaptationSet>
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
                  <SegmentTemplate timescale="1" duration="4" startNumber="1" media="REP/NUM.m4s" initialization="REP/init.mp4"/>
                  <Representation id="audio" bandwidth="64000" codecs="mp4a.40.5"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
            .replace("REP", dollar("RepresentationID"))
            .replace("NUM", dollar("Number%05d"))
        val manifest = DashMpdParser.parse(xml, "https://example.com/ignored.mpd")
        assertEquals(12_000L, manifest.durationMillis)
        assertFalse(manifest.containsContentProtection)
        val low = manifest.tracks.first { it.id == "video-low" }
        assertEquals("https://cdn.example/dash/video-low/init.mp4", low.initializationUrl)
        assertEquals(3, low.mediaUrls.size)
        assertEquals("https://cdn.example/dash/video-low/00001.m4s", low.mediaUrls[0])
        assertEquals("https://cdn.example/dash/video-low/00003.m4s", low.mediaUrls[2])
        val audio = QualityPicker.defaultAudio(manifest.tracks)
        assertEquals("audio", audio?.id)
        assertEquals("video-hi", QualityPicker.defaultVideo(manifest.tracks).id)
        assertEquals("video-low", QualityPicker.lowestVideo(manifest.tracks).id)
    }

    @Test
    fun parsesSegmentTimelineAndEscapedDollar() {
        val xml = """
            <MPD mediaPresentationDuration="PT10S" xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <SegmentTemplate timescale="1000" media="seg-TIME.m4s" initialization="init.mp4">
                    <SegmentTimeline>
                      <S t="0" d="2000" r="1"/>
                      <S d="1000"/>
                    </SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="v" bandwidth="1000" width="160" height="90"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent().replace("TIME", dollar("Time"))
        val manifest = DashMpdParser.parse(xml, "https://cdn.example/path/manifest.mpd")
        val track = manifest.tracks.single()
        assertEquals("https://cdn.example/path/init.mp4", track.initializationUrl)
        assertEquals(
            listOf(
                "https://cdn.example/path/seg-0.m4s",
                "https://cdn.example/path/seg-2000.m4s",
                "https://cdn.example/path/seg-4000.m4s",
            ),
            track.mediaUrls,
        )
        assertEquals("\$Number\$", substitute("\$\$Number\$\$", 1, 0, "v", 1))
    }

    @Test
    fun durationParserReadsFractionalSeconds() {
        assertEquals(634_566L, DashMpdParser.parseXsDurationMillis("PT634.566S"))
        assertEquals(2_000L, DashMpdParser.parseXsDurationMillis("PT2.00S"))
        assertEquals(3_723_500L, DashMpdParser.parseXsDurationMillis("PT1H2M3.5S"))
    }

    private fun dollar(token: String): String = "\$$token\$"
}
