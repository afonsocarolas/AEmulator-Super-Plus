/* AEmulator Super Plus, 2026-10-14: Microsoft Cabinet (.cab) archives, as LG ships firmware. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer.tools;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Microsoft Cabinet archives. LG's support tool delivers firmware as *.cab (e.g. AS73012a_00.S12a_00.cab for the
 * Optimus Select); inside is the LG container (.tot, .kdz, .bin) that {@link FirmwareToolset} and {@link LgKdz} unpack.
 *
 * <pre>
 * CFHEADER  "MSCF", reserved, cbCabinet, reserved, coffFiles, reserved, version(2), cFolders, cFiles, flags,
 *           setID, iCabinet; [cbCFHeader(2), cbCFFolder(1), cbCFData(1), reserve] when flags &amp; 4
 * CFFOLDER  coffCabStart(4), cCFData(2), typeCompress(2), reserve
 * CFFILE    cbFile(4), uoffFolderStart(4), iFolder(2), date, time, attribs, name NUL
 * CFDATA    csum(4), cbData(2), cbUncomp(2), reserve, data
 * </pre>
 * Compression per folder: none, MSZIP (each block "CK" + deflate, the 32 KiB history carried from block to block) or
 * LZX (window 2^15..2^21, one 32 KiB frame per block, state carried through the folder). Quantum and split cabinets
 * are refused with a clear message. Everything streams: one block of input, one window of history.
 */
final class Cab {
    private Cab() {}

    static boolean isCab(byte[] h) { return h.length >= 8 && h[0] == 'M' && h[1] == 'S' && h[2] == 'C' && h[3] == 'F'; }

    private static final class Block { long dataPos; int cbData, cbUncomp; }
    private static final class Folder { int typeCompress; final List<Block> blocks = new ArrayList<>(); }
    private static final class Entry { String name; long size, offset; int folder; }

    static List<FirmwareToolset.Artifact> extract(File input, File work, Consumer<String> log) throws IOException {
        File out = new File(work, "cab");
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot create " + out);
        List<FirmwareToolset.Artifact> result = new ArrayList<>();
        try (RandomAccessFile r = new RandomAccessFile(input, "r")) {
            byte[] h = readAt(r, 0, 36);
            if (!isCab(h)) throw new IOException("not a CAB archive");
            long coffFiles = u32(h, 16);
            int cFolders = u16(h, 26), cFiles = u16(h, 28), flags = u16(h, 30);
            if ((flags & 3) != 0) throw new IOException("this CAB is one part of a multi-part set; put all parts in one CAB or import the file inside it");
            long pos = 36;
            int cbCFFolder = 0, cbCFData = 0;
            if ((flags & 4) != 0) {
                byte[] res = readAt(r, pos, 4);
                int cbCFHeader = u16(res, 0);
                cbCFFolder = res[2] & 0xff; cbCFData = res[3] & 0xff;
                pos += 4 + cbCFHeader;
            }
            List<Folder> folders = new ArrayList<>();
            for (int i = 0; i < cFolders; i++) {
                byte[] f = readAt(r, pos, 8);
                pos += 8 + cbCFFolder;
                Folder folder = new Folder();
                folder.typeCompress = u16(f, 6);
                long d = u32(f, 0);
                int n = u16(f, 4);
                for (int k = 0; k < n; k++) {
                    byte[] dh = readAt(r, d, 8);
                    Block b = new Block();
                    b.cbData = u16(dh, 4); b.cbUncomp = u16(dh, 6);
                    b.dataPos = d + 8 + cbCFData;
                    if (b.dataPos + b.cbData > r.length()) throw new IOException("CAB data block " + k + " runs past the end of the file (truncated download?)");
                    folder.blocks.add(b);
                    d = b.dataPos + b.cbData;
                }
                folders.add(folder);
            }
            List<Entry> entries = new ArrayList<>();
            pos = coffFiles;
            for (int i = 0; i < cFiles; i++) {
                byte[] fh = readAt(r, pos, 16);
                Entry e = new Entry();
                e.size = u32(fh, 0); e.offset = u32(fh, 4); e.folder = u16(fh, 8);
                int attribs = u16(fh, 14);
                byte[] nameBytes = cstr(r, pos + 16, 512);
                pos += 16 + nameBytes.length + 1;
                e.name = new String(nameBytes, (attribs & 0x80) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
                if (e.folder >= folders.size()) throw new IOException("CAB file " + e.name + " is continued from another cabinet");
                entries.add(e);
            }
            log.accept("CAB: " + cFiles + " file(s) in " + cFolders + " folder(s)");
            for (int fi = 0; fi < folders.size(); fi++) {
                final int idx = fi;
                List<Entry> files = new ArrayList<>();
                for (Entry e : entries) if (e.folder == idx) files.add(e);
                if (files.isEmpty()) continue;
                files.sort(Comparator.comparingLong(e -> e.offset));
                Folder folder = folders.get(fi);
                int type = folder.typeCompress & 0x0f;
                String method = type == 0 ? "stored" : type == 1 ? "MSZIP" : type == 3 ? "LZX (window 2^" + ((folder.typeCompress >> 8) & 0x1f) + ")" : "Quantum";
                log.accept("CAB: folder " + fi + ", " + method + ", " + folder.blocks.size() + " block(s)");
                List<File> written = writeFolder(r, folder, files, out);
                for (int k = 0; k < written.size(); k++) {
                    log.accept("CAB: " + files.get(k).name + " (" + files.get(k).size + " bytes)");
                    result.add(new FirmwareToolset.Artifact(written.get(k), FirmwareToolset.Role.UNKNOWN));
                }
            }
        }
        return result;
    }

    /** Decodes one folder block by block and cuts its files out of the stream. */
    private static List<File> writeFolder(RandomAccessFile r, Folder folder, List<Entry> files, File out) throws IOException {
        int type = folder.typeCompress & 0x0f;
        BlockDecoder dec;
        switch (type) {
            case 0: dec = (raf, b) -> readAt(raf, b.dataPos, b.cbData); break;
            case 1: { MsZip z = new MsZip(); dec = (raf, b) -> z.decode(readAt(raf, b.dataPos, b.cbData), b.cbUncomp); break; }
            case 3: dec = new Lzx((folder.typeCompress >> 8) & 0x1f, folder.blocks, r); break;
            case 2: throw new IOException("Quantum-compressed CAB archives are not supported; extract the file inside it with 7-Zip and import that");
            default: throw new IOException("unknown CAB compression type " + type);
        }
        List<File> written = new ArrayList<>();
        int fileIdx = 0;
        OutputStream os = null;
        File cur = null;
        long folderPos = 0;
        try {
            for (Block b : folder.blocks) {
                byte[] data = dec.decode(r, b);
                if (data.length != b.cbUncomp) throw new IOException("CAB block decoded to " + data.length + " bytes, expected " + b.cbUncomp);
                int p = 0;
                while (p < data.length && fileIdx < files.size()) {
                    Entry e = files.get(fileIdx);
                    long here = folderPos + p;
                    if (here < e.offset) { p += (int) Math.min(data.length - p, e.offset - here); continue; }
                    if (os == null) {
                        cur = new File(out, uniqueName(out, e.name));
                        os = new FileOutputStream(cur);
                    }
                    long left = e.offset + e.size - here;
                    int n = (int) Math.min(left, data.length - p);
                    os.write(data, p, n);
                    p += n;
                    if (here + n >= e.offset + e.size) { os.close(); os = null; written.add(cur); fileIdx++; }
                }
                folderPos += data.length;
                // zero-length files at the very end
                while (fileIdx < files.size() && files.get(fileIdx).size == 0 && files.get(fileIdx).offset <= folderPos) {
                    File empty = new File(out, uniqueName(out, files.get(fileIdx).name));
                    new FileOutputStream(empty).close();
                    written.add(empty); fileIdx++;
                }
            }
        } finally { if (os != null) os.close(); }
        if (fileIdx < files.size()) throw new IOException("CAB folder ended before " + files.get(fileIdx).name + " was complete (truncated download?)");
        return written;
    }

    private static String uniqueName(File dir, String name) {
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty() || base.startsWith(".")) base = "file" + base;
        String n = base;
        for (int i = 1; new File(dir, n).exists(); i++) n = i + "_" + base;
        return n;
    }

    /** one CAB data block in, its uncompressed bytes out */
    interface BlockDecoder { byte[] decode(RandomAccessFile r, Block b) throws IOException; }

    // ================================================================== MSZIP

    /** "CK" + a deflate stream per block; each block starts with the previous 32 KiB of output as its dictionary. */
    static final class MsZip {
        private byte[] history = new byte[0];

        byte[] decode(byte[] in, int uncompressed) throws IOException {
            if (in.length < 2 || in[0] != 'C' || in[1] != 'K') throw new IOException("MSZIP block without its CK signature");
            Inflater inf = new Inflater(true);
            try {
                // raw deflate: one extra input byte, as java.util.zip.Inflater wants with nowrap
                byte[] src = new byte[in.length - 2 + 1];
                System.arraycopy(in, 2, src, 0, in.length - 2);
                if (history.length > 0) inf.setDictionary(history);
                inf.setInput(src);
                byte[] out = new byte[uncompressed];
                int n = 0;
                while (n < uncompressed) {
                    int got = inf.inflate(out, n, uncompressed - n);
                    if (got == 0) {
                        if (inf.needsDictionary()) throw new IOException("MSZIP block wants a dictionary");
                        if (inf.finished() || inf.needsInput()) break;
                    }
                    n += got;
                }
                if (n != uncompressed) throw new IOException("MSZIP block decoded to " + n + " bytes, expected " + uncompressed);
                int keep = Math.min(32768, history.length + out.length);
                byte[] h = new byte[keep];
                int fromOut = Math.min(keep, out.length);
                System.arraycopy(history, history.length - (keep - fromOut), h, 0, keep - fromOut);
                System.arraycopy(out, out.length - fromOut, h, keep - fromOut, fromOut);
                history = h;
                return out;
            } catch (DataFormatException e) {
                throw new IOException("MSZIP data is damaged: " + e.getMessage());
            } finally { inf.end(); }
        }
    }

    // ================================================================== LZX

    /**
     * LZX as used in CAB (after libmspack's lzxd.c and the Microsoft LZX DATA FORMAT note): a sliding window, three
     * repeated offsets, verbatim / aligned-offset / uncompressed blocks, canonical Huffman trees sent as deltas through
     * a 20-symbol pretree, an optional E8 call translation. The bit stream is 16-bit little-endian words read from
     * the most significant bit and runs on across CAB data blocks; each block yields one frame of output.
     */
    static final class Lzx implements BlockDecoder {
        private static final int NUM_CHARS = 256, MIN_MATCH = 2, NUM_PRIMARY = 7, NUM_SECONDARY = 249;
        private static final int PRETREE = 20, ALIGNED = 8, TABLEBITS = 16;
        private static final int[] EXTRA = new int[52], BASE = new int[52];
        static {
            for (int i = 0, j = 0; i < 51; i += 2) { EXTRA[i] = j; EXTRA[i + 1] = j; if (i != 0 && j < 17) j++; }
            for (int i = 0, j = 0; i < 51; i++) { BASE[i] = j; j += 1 << EXTRA[i]; }
        }

        private final byte[] window;
        private final int mask, mainElements;
        private final int[] mainLen, lengthLen = new int[NUM_SECONDARY], alignedLen = new int[ALIGNED];
        private int[] mainTable, lengthTable, alignedTable;
        private int r0 = 1, r1 = 1, r2 = 1;
        private long decoded;                 // bytes produced into the window so far
        private boolean headerRead;
        private int intelFileSize;
        private boolean intelStarted;
        private int blockType, blockRemaining, blockLength;
        private long frameIndex;

        // input: the folder's blocks run on as one stream (the decoder looks a few bits past a block's end)
        private final List<Block> blocks;
        private final RandomAccessFile raf;
        private byte[] cur = new byte[0];
        private int curPos, nextBlock;
        private long bitbuf;
        private int bitsLeft;

        Lzx(int windowBits, List<Block> blocks, RandomAccessFile raf) throws IOException {
            if (windowBits < 15 || windowBits > 21) throw new IOException("LZX window 2^" + windowBits + " is not valid in a CAB");
            window = new byte[1 << windowBits];
            mask = window.length - 1;
            int posnSlots = windowBits == 21 ? 50 : windowBits == 20 ? 42 : windowBits * 2;
            mainElements = NUM_CHARS + posnSlots * 8;
            mainLen = new int[mainElements];
            this.blocks = blocks;
            this.raf = raf;
        }

        @Override public byte[] decode(RandomAccessFile unused, Block block) throws IOException {
            int frameSize = block.cbUncomp;
            if (!headerRead) {
                headerRead = true;
                if (bits(1) != 0) { int hi = bits(16), lo = bits(16); intelFileSize = (hi << 16) | lo; }
            }
            long frameStart = decoded, frameEnd = decoded + frameSize;
            // a match may have run past the previous frame's end: those bytes already belong to this frame
            while (decoded < frameEnd) {
                if (blockRemaining == 0) readBlockHeader();
                long want = Math.min(blockRemaining, frameEnd - decoded);
                long produced = blockType == 3 ? copyRaw(want) : decodeSymbols(want);
                blockRemaining -= produced;
                if (blockRemaining < 0) throw new IOException("LZX match runs past the end of its block");
            }
            byte[] out = new byte[frameSize];
            for (int i = 0; i < frameSize; i++) out[i] = window[(int) ((frameStart + i) & mask)];
            if (intelStarted && intelFileSize != 0 && frameIndex < 32768 && frameSize > 10) undoE8(out, frameStart);
            frameIndex++;
            // frames end on a 16-bit boundary of the input
            if (blockType != 3 || blockRemaining == 0) {
                if (bitsLeft > 0) ensure(16);
                int extra = bitsLeft & 15;
                if (extra != 0) drop(extra);
            }
            return out;
        }

        private void readBlockHeader() throws IOException {
            // an uncompressed block of odd length is followed by one padding byte
            if (blockType == 3 && (blockLength & 1) != 0) rawByte();
            blockType = bits(3);
            int hi = bits(16), lo = bits(8);
            blockLength = blockRemaining = (hi << 8) | lo;
            switch (blockType) {
                case 2:
                    for (int i = 0; i < ALIGNED; i++) alignedLen[i] = bits(3);
                    alignedTable = build(alignedLen, ALIGNED);
                    // fall through: the rest is a verbatim block header
                case 1:
                    readLengths(mainLen, 0, NUM_CHARS);
                    readLengths(mainLen, NUM_CHARS, mainElements);
                    mainTable = build(mainLen, mainElements);
                    if (mainLen[0xE8] != 0) intelStarted = true;
                    readLengths(lengthLen, 0, NUM_SECONDARY);
                    lengthTable = build(lengthLen, NUM_SECONDARY);
                    break;
                case 3:
                    intelStarted = true;
                    // align to 16 bits: already aligned means a whole padding word
                    ensure(16);
                    if ((bitsLeft & 15) != 0) drop(bitsLeft & 15); else drop(16);
                    r0 = raw32(); r1 = raw32(); r2 = raw32();
                    break;
                default: throw new IOException("bad LZX block type " + blockType);
            }
        }

        private void readLengths(int[] lens, int first, int last) throws IOException {
            int[] pre = new int[PRETREE];
            for (int i = 0; i < PRETREE; i++) pre[i] = bits(4);
            int[] preTable = build(pre, PRETREE);
            for (int x = first; x < last; ) {
                int z = sym(preTable);
                if (z == 17) { int y = bits(4) + 4; while (y-- > 0 && x < last) lens[x++] = 0; }
                else if (z == 18) { int y = bits(5) + 20; while (y-- > 0 && x < last) lens[x++] = 0; }
                else if (z == 19) {
                    int y = bits(1) + 4;
                    z = sym(preTable);
                    int v = lens[x] - z; if (v < 0) v += 17;
                    while (y-- > 0 && x < last) lens[x++] = v;
                } else { int v = lens[x] - z; if (v < 0) v += 17; lens[x++] = v; }
            }
        }

        private long decodeSymbols(long want) throws IOException {
            long produced = 0;
            boolean aligned = blockType == 2;
            while (produced < want) {
                int main = sym(mainTable);
                if (main < NUM_CHARS) { window[(int) (decoded & mask)] = (byte) main; decoded++; produced++; continue; }
                main -= NUM_CHARS;
                int len = main & 7;
                if (len == NUM_PRIMARY) len += sym(lengthTable);
                len += MIN_MATCH;
                int slot = main >>> 3, off;
                if (slot > 2) {
                    int extra = EXTRA[slot];
                    off = BASE[slot] - 2;
                    if (aligned && extra >= 3) {
                        if (extra > 3) off += bits(extra - 3) << 3;
                        off += sym(alignedTable);
                    } else if (extra > 0) off += bits(extra);
                    r2 = r1; r1 = r0; r0 = off;
                } else if (slot == 0) off = r0;
                else if (slot == 1) { off = r1; r1 = r0; r0 = off; }
                else { off = r2; r2 = r0; r0 = off; }
                for (int i = 0; i < len; i++) {
                    window[(int) (decoded & mask)] = window[(int) ((decoded - off) & mask)];
                    decoded++;
                }
                produced += len;
            }
            return produced;
        }

        private long copyRaw(long want) throws IOException {
            for (long i = 0; i < want; i++) { window[(int) (decoded & mask)] = (byte) rawByte(); decoded++; }
            return want;
        }

        /** turns the CALL targets that compression made absolute back into relative ones */
        private void undoE8(byte[] b, long frameStart) {
            int end = b.length - 10;
            long curpos = frameStart;
            for (int i = 0; i < end; ) {
                if ((b[i++] & 0xff) != 0xE8) { curpos++; continue; }
                int abs = (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
                if (abs >= -curpos && abs < intelFileSize) {
                    int rel = abs >= 0 ? (int) (abs - curpos) : abs + intelFileSize;
                    b[i] = (byte) rel; b[i + 1] = (byte) (rel >> 8); b[i + 2] = (byte) (rel >> 16); b[i + 3] = (byte) (rel >> 24);
                }
                i += 4;
                curpos += 5;
            }
        }

        // ---------------------------------------------------------------- Huffman

        /** a full 16-bit lookup table: entry = symbol | code length << 16, codes canonical, MSB first */
        private static int[] build(int[] lens, int n) throws IOException {
            int[] table = new int[1 << TABLEBITS];
            int code = 0;
            boolean any = false;
            for (int len = 1; len <= TABLEBITS; len++) {
                for (int s = 0; s < n; s++) {
                    if (lens[s] != len) continue;
                    any = true;
                    int fill = 1 << (TABLEBITS - len), start = code << (TABLEBITS - len);
                    if (start + fill > table.length) throw new IOException("LZX Huffman table is over-subscribed");
                    for (int k = 0; k < fill; k++) table[start + k] = s | len << 16;
                    code++;
                }
                code <<= 1;
            }
            return any ? table : null;
        }

        private int sym(int[] table) throws IOException {
            if (table == null) throw new IOException("LZX uses an empty Huffman tree");
            ensure(TABLEBITS);
            int e = table[(int) (bitbuf >>> (64 - TABLEBITS))];
            int len = e >>> 16;
            if (len == 0) throw new IOException("LZX bit pattern matches no code");
            drop(len);
            return e & 0xffff;
        }

        // ---------------------------------------------------------------- bits

        private int nextByte() throws IOException {
            while (curPos >= cur.length) {
                if (nextBlock >= blocks.size()) return -1;   // past the input: zeros, the decoder may look a little ahead
                Block b = blocks.get(nextBlock++);
                cur = readAt(raf, b.dataPos, b.cbData);
                curPos = 0;
            }
            return cur[curPos++] & 0xff;
        }

        private void ensure(int n) throws IOException {
            while (bitsLeft < n) {
                int lo = nextByte(), hi = nextByte();
                int word = (lo < 0 ? 0 : lo) | (hi < 0 ? 0 : hi) << 8;
                bitbuf |= (long) word << (48 - bitsLeft);
                bitsLeft += 16;
            }
        }

        private void drop(int n) { bitbuf <<= n; bitsLeft -= n; }

        private int bits(int n) throws IOException {
            if (n == 0) return 0;
            ensure(n);
            int v = (int) (bitbuf >>> (64 - n));
            drop(n);
            return v;
        }

        /** a byte of an uncompressed block: what the bit buffer already holds (whole words, low byte first) comes first */
        private int rawByte() throws IOException {
            if (hasPending) { hasPending = false; return pendingHigh; }
            if (bitsLeft >= 16) {
                // words already in the bit buffer are the next bytes of the stream, low byte first
                if ((bitsLeft & 15) != 0) throw new IOException("LZX raw data is not word aligned");
                int word = (int) (bitbuf >>> 48);
                drop(16);
                pendingHigh = word >>> 8; hasPending = true;
                return word & 0xff;
            }
            int b = nextByte();
            if (b < 0) throw new EOFException("LZX input ended inside an uncompressed block");
            return b;
        }
        private int pendingHigh;
        private boolean hasPending;

        private int raw32() throws IOException {
            int a = rawByte(), b = rawByte(), c = rawByte(), d = rawByte();
            return a | b << 8 | c << 16 | d << 24;
        }
    }

    // ================================================================== helpers

    private static byte[] readAt(RandomAccessFile r, long pos, int n) throws IOException {
        byte[] b = new byte[n];
        r.seek(pos);
        r.readFully(b);
        return b;
    }

    private static byte[] cstr(RandomAccessFile r, long pos, int max) throws IOException {
        r.seek(pos);
        byte[] buf = new byte[max];
        int n = 0;
        for (; n < max; n++) {
            int c = r.read();
            if (c < 0) throw new EOFException("CAB file name runs past the end");
            if (c == 0) break;
            buf[n] = (byte) c;
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    static long u32(byte[] b, int o) { return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24; }
    static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
}
