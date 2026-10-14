package app.aemu.importer.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Cabinets made by Microsoft's own makecab (Windows), stored, MSZIP and LZX with the smallest and the largest window.
 * Their five files: 3000 lines of text (spans many 32 KiB blocks), 100 000 random bytes (LZX stores them
 * uncompressed), x86-like code full of E8 CALLs (exercises the E8 translation), text and random mixed, an empty file.
 */
public class CabTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final Map<String, String> SHA256 = new LinkedHashMap<>();
    static {
        SHA256.put("text.txt", "1f61acc5bb7caf7c68517b599611be20ea8623200cadc6376c4aa3cfa2600cb5");
        SHA256.put("rand.bin", "aa3f750f5432f875cb8c023396fd3e87cf1d6548324f726973504ef7a170bf29");
        SHA256.put("e8.bin", "6c29884750634eec7b295c810b51ab59676a2916d65b14e794685686618f78d0");
        SHA256.put("mixed.bin", "586981f74a6d243f0ecdb80f8888ea77938e6bc0a86e939201af11084f76a449");
        SHA256.put("empty.txt", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    private File fixture(String name) throws Exception {
        File f = new File(tmp.getRoot(), name);
        try (InputStream in = getClass().getResourceAsStream("/cab/" + name)) {
            if (in == null) fail("missing test resource cab/" + name);
            Files.copy(in, f.toPath());
        }
        return f;
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(Files.readAllBytes(f.toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void check(String cab) throws Exception {
        File in = fixture(cab);
        assertEquals("CAB", FirmwareToolset.detect(in));
        List<String> log = new ArrayList<>();
        List<FirmwareToolset.Artifact> out = FirmwareToolset.extract(in, tmp.newFolder(), log::add);
        assertEquals(cab + ": " + log, SHA256.size(), out.size());
        for (FirmwareToolset.Artifact a : out) {
            String expected = SHA256.get(a.file.getName());
            assertTrue(cab + ": unexpected file " + a.file.getName(), expected != null);
            assertEquals(cab + ": " + a.file.getName(), expected, sha256(a.file));
        }
    }

    @Test public void stored() throws Exception { check("none.cab"); }
    @Test public void mszip() throws Exception { check("mszip.cab"); }
    @Test public void lzxSmallestWindow() throws Exception { check("lzx15.cab"); }
    @Test public void lzxLargestWindow() throws Exception { check("lzx21.cab"); }

    @Test public void truncatedDownloadIsReportedClearly() throws Exception {
        File in = fixture("lzx21.cab");
        byte[] all = Files.readAllBytes(in.toPath());
        File cut = new File(tmp.getRoot(), "cut.cab");
        Files.write(cut.toPath(), java.util.Arrays.copyOf(all, all.length / 2));
        try {
            FirmwareToolset.extract(cut, tmp.newFolder(), s -> {});
            fail("a truncated CAB must not import");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("truncated"));
        }
    }
}
