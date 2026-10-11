/* AEmulator Super Plus, 2026-10-11: camcorder profiles for the host camera. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CamcorderProfilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val xml = """
        <MediaSettings>
          <CamcorderProfiles cameraId="0">
            <EncoderProfile quality="1080p" fileFormat="mp4" duration="30">
              <Video codec="h264" bitRate="17000000" width="1920" height="1080" frameRate="30" />
              <Audio codec="aac" bitRate="96000" sampleRate="48000" channels="1" />
            </EncoderProfile>
            <EncoderProfile quality="low" fileFormat="3gp" duration="30">
              <Video codec="h263" bitRate="128000" width="176" height="144" frameRate="15" />
            </EncoderProfile>
          </CamcorderProfiles>
          <EncoderOutputFileFormat name="mp4" />
          <VideoEncoderCap name="h264" enabled="true" minBitRate="64000" maxBitRate="17000000" minFrameWidth="176" maxFrameWidth="1920" />
        </MediaSettings>
    """.trimIndent()

    @Test fun everyProfileBecomesVga() {
        val p = CamcorderProfiles.patch(xml)
        assertTrue(p.contains("""<Video codec="h264" bitRate="3000000" width="640" height="480" frameRate="30" />"""))
        assertTrue(p.contains("""<Video codec="h263" bitRate="128000" width="640" height="480" frameRate="15" />"""))
        // outside the camcorder profiles nothing changes (encoder caps, audio)
        assertTrue(p.contains("""maxFrameWidth="1920""""))
        assertTrue(p.contains("""<Audio codec="aac" bitRate="96000""""))
        assertEquals(p, CamcorderProfiles.patch(p))
    }

    @Test fun parkedAndRestored() {
        val root = tmp.newFolder("root")
        val f = File(root, "system/etc/media_profiles.xml").apply { parentFile.mkdirs(); writeText(xml) }
        assertTrue(CamcorderProfiles.configure(root, true))
        assertTrue(f.readText().contains("width=\"640\""))
        assertFalse(CamcorderProfiles.configure(root, true))        // already done
        assertTrue(CamcorderProfiles.configure(root, false))
        assertEquals(xml, f.readText())
        assertFalse(CamcorderProfiles.configure(root, false))
    }

    @Test fun firmwareUpdateBecomesTheNewOriginal() {
        val root = tmp.newFolder("root2")
        val f = File(root, "system/etc/media_profiles.xml").apply { parentFile.mkdirs(); writeText(xml) }
        CamcorderProfiles.configure(root, true)
        val updated = xml.replace("duration=\"30\"", "duration=\"60\"")
        f.writeText(updated)                                          // e.g. an OTA rewrote the file
        assertTrue(CamcorderProfiles.configure(root, true))
        CamcorderProfiles.configure(root, false)
        assertEquals(updated, f.readText())
    }
}
