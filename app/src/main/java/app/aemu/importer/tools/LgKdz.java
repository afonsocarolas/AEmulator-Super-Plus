package app.aemu.importer.tools;

import app.aemu.importer.tools.FirmwareToolset.Artifact;
import app.aemu.importer.tools.FirmwareToolset.Role;
import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * LG firmware packages: .kdz (container) and .dz (partition chunks inside it).
 *
 * <pre>
 * KDZ   [u32 header size][u32 magic] then records: name[256] + size + offset
 *       (u64 in the 64-bit variants, u32 in the old ones). The table layout is not trusted blindly:
 *       several record layouts are tried and the one whose ranges fit the real file (non-overlapping,
 *       contiguous, summing to the file length) wins, so unknown header-size/magic variants still work.
 * DZ    header (magic 32 96 18 74, major/minor, device, build string) followed by chunks:
 *       magic 30 12 95 78, slice[32], name[64], target_size, data_size, md5[16], start_sector, trim, device, crc.
 *       Chunk data is zlib (all versions seen so far), zstd, lz4-frame, gzip or stored; headers are padded to
 *       0x200 in DZ v2 and tightly packed in the older ones (auto-detected per chunk).
 * </pre>
 *
 * Only what the emulator needs is unpacked: the system, boot and recovery slices, each rebuilt as a raw
 * partition image (chunks are placed at their start sector, gaps stay zero, all-zero blocks are left as
 * file holes). Chunks are streamed, nothing large is held in memory. Older LG BIN/TOT containers that
 * sit inside a KDZ are passed on to the existing {@link FirmwareToolset} LG handlers.
 */
final class LgKdz {
    private LgKdz() {}

    static final long DZ_MAGIC = 0x74189632L;               // bytes 32 96 18 74
    static final byte[] DZ_MAGIC_BYTES = {0x32, (byte) 0x96, 0x18, 0x74};
    static final byte[] CHUNK_MAGIC = {0x30, 0x12, (byte) 0x95, 0x78};
    static final int CHUNK_FIXED = 0x8C, CHUNK_PADDED = 0x200, SECTOR = 512;
    static final long[] KDZ_MAGICS = {0x25223824L, 0x20247932L, 0x80253134L};
    private static final long MAX_IMAGE = 64L << 30;

    // ================================================================== detection

    static boolean isDz(byte[] head) { return head.length >= 4 && u32(head, 0) == DZ_MAGIC; }

    /** True if the file's header parses as a KDZ file table (or carries a known KDZ magic). */
    static boolean looksLikeKdz(File f, byte[] head, boolean nameSaysKdz) {
        if (head.length < 0x40) return false;
        long hs = u32(head, 0);
        boolean magic = false;
        for (long m : KDZ_MAGICS) if (u32(head, 4) == m) magic = true;
        if (!magic && !nameSaysKdz && (hs < 0x40 || hs > 0x20000)) return false;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            Table t = readTable(r);
            if (t == null) return magic || nameSaysKdz;
            if (magic || nameSaysKdz) return true;
            for (Entry e : t.files) {
                String n = e.name.toLowerCase(Locale.US);
                if (n.endsWith(".dz") || n.endsWith(".dll") || n.endsWith(".bin")) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    // ================================================================== KDZ container

    static final class Entry { String name; long size, offset; }
    static final class Table { final List<Entry> files = new ArrayList<>(); long headerSize; String layout; int score; }

    /** Parses the file table. Returns null when no record layout fits the file. */
    static Table readTable(RandomAccessFile r) throws IOException {
        long len = r.length();
        byte[] head = readAt(r, 0, (int) Math.min(len, 0x20000));
        if (head.length < 0x40) return null;
        long hs = u32(head, 0);
        boolean hsOk = hs >= 0x40 && hs <= head.length && hs < len;
        int hdrLen = hsOk ? (int) hs : head.length;
        long minOff = hsOk ? hs : 0x10;

        Table best = null;
        int[] starts = {8, 4, 12, 16};
        int[] nameLens = {256, 128};
        int[] widths = {8, 4};
        int[] extras = {0, 4, 8, 16};
        for (int start : starts) for (int nameLen : nameLens) for (int w : widths)
            for (int order = 0; order < 2; order++) for (int extra : extras) {
                Table t = tryLayout(head, hdrLen, len, minOff, hsOk, hs, start, nameLen, w, order == 0, extra);
                if (t != null && (best == null || t.score > best.score)) best = t;
            }
        return best;
    }

    private static Table tryLayout(byte[] head, int hdrLen, long len, long minOff, boolean hsOk, long hs,
                                   int start, int nameLen, int w, boolean sizeFirst, int extra) {
        int stride = nameLen + 2 * w + extra;
        Table t = new Table();
        for (long pos = start; pos + stride <= hdrLen; pos += stride) {
            int p = (int) pos;
            String name = cstrPrintable(head, p, nameLen);
            if (name == null || name.isEmpty()) break;
            long a = w == 8 ? u64(head, p + nameLen) : u32(head, p + nameLen);
            long b = w == 8 ? u64(head, p + nameLen + w) : u32(head, p + nameLen + w);
            long size = sizeFirst ? a : b, off = sizeFirst ? b : a;
            boolean bad = size < 0 || off < minOff || size > len || off + size > len;
            if (bad) { if (t.files.isEmpty()) return null; break; }
            Entry e = new Entry(); e.name = name; e.size = size; e.offset = off;
            t.files.add(e);
        }
        if (t.files.isEmpty()) return null;
        List<Entry> sorted = new ArrayList<>(t.files);
        Collections.sort(sorted, Comparator.comparingLong(e -> e.offset));
        boolean contiguous = true; long sum = hsOk ? hs : 0;
        long expect = hsOk ? hs : sorted.get(0).offset;
        for (Entry e : sorted) {
            if (e.offset < expect && e.size > 0) return null;               // overlapping ranges
            if (e.offset != expect) contiguous = false;
            expect = e.offset + e.size; sum += e.size;
        }
        t.headerSize = hsOk ? hs : 0;
        t.score = t.files.size() * 1000 + (contiguous ? 3000 : 0) + (hsOk && sum == len ? 5000 : 0)
            + (nameLen == 256 ? 10 : 0) + (extra == 0 ? 10 : 0) + (start == 8 ? 10 : 0) + (w == 8 ? 5 : 0) + (sizeFirst ? 1 : 0);
        t.layout = "name" + nameLen + "+" + (w == 8 ? "u64" : "u32") + (sizeFirst ? " size,offset" : " offset,size")
            + " @" + start + (extra != 0 ? " +pad" + extra : "");
        return t;
    }

    static List<Artifact> extractKdz(File f, File work, Consumer<String> log) throws IOException {
        if (!work.exists() && !work.mkdirs()) throw new IOException("cannot create work directory");
        List<Artifact> out = new ArrayList<>();
        IOException firstError = null;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            Table t = readTable(r);
            if (t == null && looksEncrypted(readAt(r, 0, (int) Math.min(r.length(), 65536)))) {
                // LG's older updates (2012-2013, e.g. LG Optimus L7 V10K_00.kdz) are encrypted from the first byte
                throw new IOException("this LG KDZ is encrypted (older LG updates were) and cannot be imported; "
                    + "look for an unencrypted copy of the same firmware, such as a recovery-flashable ZIP");
            }
            if (t == null) {
                // unknown table layout: the .dz is still recognisable by its own magic
                long at = scanMagic(r, DZ_MAGIC_BYTES);
                if (at < 0) throw new IOException("LG KDZ: unrecognized file table and no DZ payload found");
                log.accept("LG KDZ: unknown table layout, DZ payload found at 0x" + Long.toHexString(at));
                return extractDzRange(r, at, r.length() - at, work, log);
            }
            log.accept("LG KDZ: " + t.files.size() + " file(s) [" + t.layout + "]");
            int n = 0;
            for (Entry e : t.files) {
                log.accept("LG KDZ:   " + e.name + " (" + (e.size >> 10) + " KB @0x" + Long.toHexString(e.offset) + ")");
            }
            for (Entry e : t.files) {
                String lower = e.name.toLowerCase(Locale.US);
                byte[] mag = readAt(r, e.offset, (int) Math.min(e.size, 16));
                try {
                    if (isDz(mag) || (lower.endsWith(".dz") && mag.length >= 4)) {
                        out.addAll(extractDzRange(r, e.offset, e.size, new File(work, "dz" + (n++)), log));
                    } else if ((mag.length >= 4 && isLgBin(u32(mag, 0))) || Cab.isCab(mag) || lower.endsWith(".cab")) {
                        File dir = new File(work, "bin" + (n++)); dir.mkdirs();
                        File copy = new File(dir, safe(e.name));
                        copyRange(r, e.offset, e.size, copy);
                        try { out.addAll(FirmwareToolset.extract(copy, new File(dir, "out"), log)); }
                        catch (IOException | RuntimeException ex) { throw ex; }
                        catch (Exception ex) { throw new IOException(ex); }
                        finally { copy.delete(); }
                    } else if (lower.endsWith(".img") || lower.endsWith(".bin")) {
                        Role role = FirmwareToolset.roleFor(e.name);
                        String canon = role == Role.SYSTEM ? "system.img" : role == Role.BOOT ? "boot.img"
                            : role == Role.RECOVERY ? "recovery.img" : role == Role.OEM ? "oem.img" : null;
                        if (canon != null) {
                            File dir = new File(work, "img" + (n++)); dir.mkdirs();
                            File d = new File(dir, canon);
                            copyRange(r, e.offset, e.size, d);
                            out.add(new Artifact(d, role));
                        }
                    } else {
                        log.accept("LG KDZ: skipping " + e.name);
                    }
                } catch (IOException | RuntimeException ex) {
                    log.accept("LG KDZ: " + e.name + " failed: " + ex.getMessage());
                    if (firstError == null) firstError = ex instanceof IOException ? (IOException) ex : new IOException(ex);
                }
            }
        }
        out = firstPerRole(out);
        if (out.isEmpty()) {
            if (firstError != null) throw firstError;
            throw new IOException("LG KDZ: no system/boot/recovery image inside");
        }
        return out;
    }

    // ================================================================== DZ

    static final class Chunk {
        String slice, name; long targetSize, dataSize, addr; byte[] md5; long pos, dataPos;
    }

    static List<Artifact> extractDz(File f, File work, Consumer<String> log) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            return extractDzRange(r, 0, r.length(), work, log);
        }
    }

    static List<Artifact> extractDzRange(RandomAccessFile r, long base, long len, File work, Consumer<String> log) throws IOException {
        if (!work.exists() && !work.mkdirs()) throw new IOException("cannot create work directory");
        byte[] hd = readAt(r, base, (int) Math.min(len, 0xC0));
        if (!isDz(hd)) throw new IOException("LG DZ: bad magic");
        long major = u32(hd, 4), minor = u32(hd, 8);
        String device = hd.length >= 0x30 ? cstrPrintable(hd, 0x10, 32) : null;
        String build = hd.length >= 0xC0 ? cstrPrintable(hd, 0x30, 144) : null;
        List<Chunk> chunks = readChunks(r, base, len);
        Map<String, List<Chunk>> slices = new LinkedHashMap<>();
        for (Chunk c : chunks) slices.computeIfAbsent(c.slice.toLowerCase(Locale.US), k -> new ArrayList<>()).add(c);
        StringBuilder names = new StringBuilder();
        for (Map.Entry<String, List<Chunk>> e : slices.entrySet()) names.append(names.length() > 0 ? ", " : "").append(e.getKey()).append('(').append(e.getValue().size()).append(')');
        log.accept("LG DZ v" + major + "." + minor + (device != null ? " " + device : "") + (build != null && !build.isEmpty() ? " " + build : "")
            + ": " + chunks.size() + " chunks: " + names);

        List<Artifact> out = new ArrayList<>();
        IOException firstError = null;
        String[][] wanted = {
            {"system", "system_a", "systema", "system.img"},
            {"boot", "boot_a", "boota", "kernel"},
            {"recovery", "recovery_a", "recoverya", "recoveryb", "recovery_b"},
            {"oem", "oem_a", "oema", "oem_b", "oemb"},
        };
        Role[] roles = {Role.SYSTEM, Role.BOOT, Role.RECOVERY, Role.OEM};
        String[] files = {"system.img", "boot.img", "recovery.img", "oem.img"};
        for (int i = 0; i < wanted.length; i++) {
            List<Chunk> cl = null; String used = null;
            for (String n : wanted[i]) { cl = slices.get(n); if (cl != null) { used = n; break; } }
            if (cl == null) continue;
            File dst = new File(work, files[i]);
            try {
                assemble(r, cl, dst, log);
                out.add(new Artifact(dst, roles[i]));
                log.accept("LG DZ: " + used + " -> " + files[i] + " (" + (dst.length() >> 20) + " MB)");
            } catch (IOException | RuntimeException ex) {
                dst.delete();
                log.accept("LG DZ: " + used + " failed: " + ex.getMessage());
                if (firstError == null) firstError = ex instanceof IOException ? (IOException) ex : new IOException(ex);
            }
        }
        if (out.isEmpty()) {
            if (firstError != null) throw firstError;
            throw new IOException("LG DZ: no system/boot/recovery slice (found: " + names + ")");
        }
        return out;
    }

    /** Walks every chunk header of the DZ. The first one is located by magic, so any DZ header size works. */
    static List<Chunk> readChunks(RandomAccessFile r, long base, long len) throws IOException {
        long end = base + len;
        byte[] buf = readAt(r, base, (int) Math.min(len, 4L << 20));
        long first = -1;
        for (int i = 0; i + CHUNK_FIXED <= buf.length; i++) {
            if (buf[i] == CHUNK_MAGIC[0] && buf[i + 1] == CHUNK_MAGIC[1] && buf[i + 2] == CHUNK_MAGIC[2] && buf[i + 3] == CHUNK_MAGIC[3]
                && parseChunk(r, base + i, end) != null) { first = base + i; break; }
        }
        if (first < 0) throw new IOException("LG DZ: no chunk table found");
        List<Chunk> list = new ArrayList<>();
        long pos = first;
        while (list.size() < 200000) {
            Chunk c = parseChunk(r, pos, end);
            if (c == null) break;
            // padded (0x200) or tight (0x8C) header: take the one after which the next chunk header is found
            byte[] tail = readAt(r, pos + CHUNK_FIXED, CHUNK_PADDED - CHUNK_FIXED);
            boolean zeros = tail.length == CHUNK_PADDED - CHUNK_FIXED && allZero(tail, 0, tail.length);
            int[] cands = zeros ? new int[]{CHUNK_PADDED, CHUNK_FIXED} : new int[]{CHUNK_FIXED, CHUNK_PADDED};
            int hdr = -1; long next = -1;
            for (int h : cands) {
                long dEnd = pos + h + c.dataSize;
                if (dEnd > end) continue;
                long nx = findNext(r, base, dEnd, end);
                if (nx >= 0 || end - dEnd < 0x4000) { hdr = h; next = nx; break; }
            }
            if (hdr < 0) { hdr = cands[0]; if (pos + hdr + c.dataSize > end) break; }
            c.pos = pos; c.dataPos = pos + hdr;
            list.add(c);
            if (next < 0) break;
            pos = next;
        }
        if (list.isEmpty()) throw new IOException("LG DZ: no chunks");
        return list;
    }

    private static Chunk parseChunk(RandomAccessFile r, long pos, long end) throws IOException {
        if (pos < 0 || pos + CHUNK_FIXED > end) return null;
        byte[] h = readAt(r, pos, CHUNK_FIXED);
        if (h.length < CHUNK_FIXED || h[0] != CHUNK_MAGIC[0] || h[1] != CHUNK_MAGIC[1] || h[2] != CHUNK_MAGIC[2] || h[3] != CHUNK_MAGIC[3]) return null;
        String slice = cstrPrintable(h, 4, 32), name = cstrPrintable(h, 36, 64);
        if (slice == null || slice.isEmpty() || name == null) return null;
        Chunk c = new Chunk();
        c.slice = slice; c.name = name;
        c.targetSize = u32(h, 0x64); c.dataSize = u32(h, 0x68);
        c.md5 = Arrays.copyOfRange(h, 0x6C, 0x7C);
        c.addr = u32(h, 0x7C);
        if (c.dataSize <= 0 || c.dataSize > end - pos - CHUNK_FIXED) return null;
        return c;
    }

    private static long findNext(RandomAccessFile r, long base, long from, long end) throws IOException {
        long rel = from - base;
        long[] cand = {from, base + ((rel + 0x1FF) & ~0x1FFL), base + ((rel + 0xFFF) & ~0xFFFL)};
        for (long c : cand) if (parseChunk(r, c, end) != null) return c;
        int win = (int) Math.min(0x2000, end - from);
        if (win >= CHUNK_FIXED) {
            byte[] b = readAt(r, from, win);
            for (int i = 0; i + CHUNK_FIXED <= b.length; i++)
                if (b[i] == CHUNK_MAGIC[0] && b[i + 1] == CHUNK_MAGIC[1] && b[i + 2] == CHUNK_MAGIC[2] && b[i + 3] == CHUNK_MAGIC[3]
                    && parseChunk(r, from + i, end) != null) return from + i;
        }
        return -1;
    }

    /** Rebuilds one partition image: every chunk is decompressed to its start sector (relative to the slice's first). */
    private static void assemble(RandomAccessFile r, List<Chunk> cl, File dst, Consumer<String> log) throws IOException {
        int n = cl.size();
        long[] at = new long[n];
        boolean sequential = n == 1;
        if (n > 1) {
            sequential = true;
            for (Chunk c : cl) if (c.addr != cl.get(0).addr) { sequential = false; break; }
        }
        long total = 0;
        if (sequential) {
            long cur = 0;
            for (int i = 0; i < n; i++) { at[i] = cur; cur += cl.get(i).targetSize; }
            total = cur;
        } else {
            long min = Long.MAX_VALUE;
            for (Chunk c : cl) min = Math.min(min, c.addr);
            for (int i = 0; i < n; i++) {
                at[i] = (cl.get(i).addr - min) * SECTOR;
                total = Math.max(total, at[i] + cl.get(i).targetSize);
            }
        }
        if (total > MAX_IMAGE) throw new IOException("image size implausible: " + total);
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        try (RandomAccessFile w = new RandomAccessFile(dst, "rw")) {
            w.setLength(total);
            for (int i = 0; i < n; i++) {
                Chunk c = cl.get(i);
                long wrote = decodeChunk(r, c, w, at[i], log);
                if (wrote != c.targetSize) log.accept("LG DZ: " + c.name + " expanded to " + wrote + " bytes, header says " + c.targetSize);
            }
        }
    }

    private static long decodeChunk(RandomAccessFile r, Chunk c, RandomAccessFile w, long dstOff, Consumer<String> log) throws IOException {
        try (BufferedInputStream raw = new BufferedInputStream(new RangeIn(r, c.dataPos, c.dataSize), 1 << 20)) {
            raw.mark(8);
            byte[] m = new byte[4];
            int got = 0;
            while (got < 4) { int k = raw.read(m, got, 4 - got); if (k < 0) break; got += k; }
            raw.reset();
            int b0 = m[0] & 255, b1 = m[1] & 255;
            InputStream in;
            if (got >= 2 && (b0 & 0x0F) == 8 && ((b0 << 8) | b1) % 31 == 0) in = new InflaterInputStream(raw, new Inflater(), 1 << 16);
            else if (got >= 4 && b0 == 0x28 && b1 == 0xB5 && (m[2] & 255) == 0x2F && (m[3] & 255) == 0xFD) in = new ZstdInputStream(raw);
            else if (got >= 4 && b0 == 0x04 && b1 == 0x22 && (m[2] & 255) == 0x4D && (m[3] & 255) == 0x18) in = new FramedLZ4CompressorInputStream(raw);
            else if (got >= 2 && b0 == 0x1F && b1 == 0x8B) in = new GZIPInputStream(raw, 1 << 16);
            else if (c.dataSize == c.targetSize) in = raw;              // stored
            else throw new IOException("chunk " + c.name + ": unknown compression");
            MessageDigest md;
            try { md = MessageDigest.getInstance("MD5"); } catch (Exception e) { throw new IOException(e); }
            long wrote = copyTo(in, w, dstOff, md);
            if (!allZero(c.md5, 0, c.md5.length) && !Arrays.equals(md.digest(), c.md5))
                log.accept("LG DZ: " + c.name + " MD5 mismatch (image may be corrupt)");
            return wrote;
        }
    }

    /** Streams into the output; all-zero 4 KB blocks are skipped (the pre-sized file already reads zero there). */
    private static long copyTo(InputStream in, RandomAccessFile w, long dst, MessageDigest md) throws IOException {
        byte[] buf = new byte[1 << 20];
        long pos = dst; int n;
        while ((n = in.read(buf, 0, buf.length)) > 0) {
            md.update(buf, 0, n);
            int run = -1;
            for (int i = 0; i < n; i += 4096) {
                int blk = Math.min(4096, n - i);
                if (allZero(buf, i, blk)) {
                    if (run >= 0) { w.seek(pos + run); w.write(buf, run, i - run); run = -1; }
                } else if (run < 0) run = i;
            }
            if (run >= 0) { w.seek(pos + run); w.write(buf, run, n - run); }
            pos += n;
        }
        return pos - dst;
    }

    // ================================================================== helpers

    private static final class RangeIn extends InputStream {
        private final RandomAccessFile r; private long pos; private final long end;
        RangeIn(RandomAccessFile r, long off, long len) { this.r = r; pos = off; end = off + len; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int o, int l) throws IOException {
            if (pos >= end) return -1;
            int n = (int) Math.min(l, end - pos);
            r.seek(pos);
            int k = r.read(b, o, n);
            if (k > 0) pos += k;
            return k;
        }
    }

    private static List<Artifact> firstPerRole(List<Artifact> in) {
        List<Artifact> out = new ArrayList<>();
        boolean sys = false, boot = false, rec = false, oem = false;
        for (Artifact a : in) {
            if (a.role == Role.SYSTEM) { if (sys) continue; sys = true; }
            else if (a.role == Role.BOOT) { if (boot) continue; boot = true; }
            else if (a.role == Role.RECOVERY) { if (rec) continue; rec = true; }
            else if (a.role == Role.OEM) { if (oem) continue; oem = true; }
            out.add(a);
        }
        return out;
    }

    /** Encrypted data: close to 8 bits of entropy per byte and no file table (a real KDZ table is mostly zero padding). */
    static boolean looksEncrypted(byte[] b) {
        if (b.length < 4096) return false;
        int[] count = new int[256];
        for (byte x : b) count[x & 0xff]++;
        double h = 0;
        for (int c : count) if (c > 0) { double p = (double) c / b.length; h -= p * Math.log(p) / Math.log(2); }
        return h > 7.9;
    }

    private static boolean isLgBin(long m) { return m == 0xAA55EC44L || m == 0xAA55A5A5L || m == 0xAA55DD44L; }

    private static long scanMagic(RandomAccessFile r, byte[] magic) throws IOException {
        byte[] buf = new byte[(1 << 20) + 3];
        long pos = 0, len = r.length();
        while (pos < len) {
            r.seek(pos);
            int n = r.read(buf, 0, (int) Math.min(buf.length, len - pos));
            if (n <= 0) break;
            for (int i = 0; i + magic.length <= n; i++) {
                int j = 0; while (j < magic.length && buf[i + j] == magic[j]) j++;
                if (j == magic.length) return pos + i;
            }
            if (pos + n >= len) break;
            pos += n - (magic.length - 1);
        }
        return -1;
    }

    private static void copyRange(RandomAccessFile r, long off, long len, File dst) throws IOException {
        try (java.io.OutputStream o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(dst), 1 << 20)) {
            byte[] b = new byte[1 << 20]; long left = len; r.seek(off);
            while (left > 0) {
                int k = r.read(b, 0, (int) Math.min(b.length, left));
                if (k <= 0) throw new IOException("unexpected end of file");
                o.write(b, 0, k); left -= k;
            }
        }
    }

    private static String safe(String n) {
        String s = n.replace('\\', '/'); s = s.substring(s.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "entry" : s;
    }

    /** NUL-terminated printable ASCII inside a fixed field, or null when the field is not a clean name. */
    private static String cstrPrintable(byte[] b, int off, int max) {
        if (off + max > b.length) max = b.length - off;
        int e = 0;
        while (e < max && b[off + e] != 0) { int c = b[off + e] & 255; if (c < 0x20 || c > 0x7E) return null; e++; }
        if (e == max && max > 0 && b[off + max - 1] != 0) return null;       // no terminator inside the field
        return new String(b, off, e, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static boolean allZero(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) if (b[i] != 0) return false;
        return true;
    }

    private static byte[] readAt(RandomAccessFile r, long pos, int n) throws IOException {
        if (n <= 0 || pos < 0) return new byte[0];
        r.seek(pos);
        byte[] b = new byte[n]; int got = 0;
        while (got < n) { int k = r.read(b, got, n - got); if (k < 0) break; got += k; }
        return got == n ? b : Arrays.copyOf(b, got);
    }

    static long u32(byte[] b, int o) {
        return (b[o] & 255L) | (b[o + 1] & 255L) << 8 | (b[o + 2] & 255L) << 16 | (b[o + 3] & 255L) << 24;
    }

    static long u64(byte[] b, int o) { return u32(b, o) | u32(b, o + 4) << 32; }
}
