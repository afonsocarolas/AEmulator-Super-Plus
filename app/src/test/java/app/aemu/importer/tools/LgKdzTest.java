package app.aemu.importer.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LgKdzTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** an older LG update (LG Optimus L7 V10K_00.kdz): encrypted from its first byte, no readable file table */
    @Test public void encryptedKdzIsNamedAsSuch() throws Exception {
        byte[] data = new byte[2 << 20];
        new Random(7).nextBytes(data);
        File f = new File(tmp.getRoot(), "V10K_00.kdz");
        Files.write(f.toPath(), data);
        assertEquals("LG_KDZ", FirmwareToolset.detect(f));
        try {
            FirmwareToolset.extract(f, tmp.newFolder(), s -> {});
            fail("an encrypted KDZ cannot import");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("encrypted"));
        }
    }

    @Test public void randomDataWithoutKdzNameIsNotTakenForOne() throws Exception {
        byte[] data = new byte[1 << 20];
        new Random(8).nextBytes(data);
        File f = new File(tmp.getRoot(), "blob.img");
        Files.write(f.toPath(), data);
        assertTrue(!"LG_KDZ".equals(FirmwareToolset.detect(f)));
    }
}
