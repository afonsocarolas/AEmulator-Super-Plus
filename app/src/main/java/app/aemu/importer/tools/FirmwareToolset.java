package app.aemu.importer.tools;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.brotli.dec.BrotliInputStream;
import org.anarres.lzo.LzoAlgorithm;
import org.anarres.lzo.LzoDecompressor;
import org.anarres.lzo.LzoLibrary;
import org.anarres.lzo.lzo_uintp;
import com.github.luben.zstd.Zstd;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Android-native registry for the firmware utilities supplied in New folder.zip.
 * Formats already handled directly by Importer.kt are deliberately left there:
 * ZIP/7z/RAR/CPIO/TAR/TAR.MD5/gzip/xz/bz2/sparse/ext4/YAFFS2/OTA dat(.br).
 * This registry adds the OEM-specific containers and the standalone conversion
 * utilities from the supplied bundle and feeds their outputs back into Importer.
 * HTC RUU installers (.exe), Dream-style NBH and 256-byte-signed HTC images live in {@link HtcFirmware}.
 */
public final class FirmwareToolset {
    private FirmwareToolset() {}
    public enum Role { SYSTEM, BOOT, RECOVERY, OEM, ARCHIVE, UNKNOWN }
    public static final class Artifact {
        public final File file; public final Role role; public final boolean cleanup;
        public Artifact(File f, Role r) { this(f, r, true); }
        public Artifact(File f, Role r, boolean c) { file=f; role=r; cleanup=c; }
    }

    public static String detect(File f) throws IOException {
        byte[] h = readHead(f, 4096);
        // HTC containers first: their first bytes are a random RSA signature / a PE header, so they must not reach the checks below
        if (HtcFirmware.isDreamNbh(h)) return "HTC_NBH";
        if (HtcFirmware.isSignedWrapper(h)) return "HTC_SIGNED";
        if (HtcFirmware.isMz(h) && HtcFirmware.isRuuExe(f)) return "HTC_RUU";
        if (starts(h, "PAC")) { /* handled below by structural PAC probe */ }
        if (h.length >= 4 && u32(h,0) == 0xAA55EC44L) return "LG_BIN";
        if (h.length >= 4 && u32(h,0) == 0xAA55A5A5L) return "LG_BIN";
        if (h.length >= 4 && u32(h,0) == 0xAA55DD44L) return "LG_TOT";
        // Microsoft Cabinet: how LG's support tool delivers firmware (*.cab around a .tot / .kdz / .bin)
        if (Cab.isCab(h)) return "CAB";
        if (h.length >= 8 && utf16(h,0,44).startsWith("BP_R")) return "PAC";
        if (starts(h, "Multi-Interface")) return "SBF";
        if (starts(h, "HTCIMAGE")) return "NBH";
        if (h.length >= 4 && u32(h,0) == 0xED26FF3AL) return "SPARSE";
        if (h.length >= 4 && (u32(h,0) == 0x414C5030L || starts(h, "LP\u00fe\u00ff"))) return "SUPER";
        if (h.length >= 4 && (u32(h,0) == 0x73717368L || u32(h,0) == 0x68737173L)) return "SQUASHFS";
        if (looksRfs(h)) return "RFS";
        String n = f.getName().toLowerCase(Locale.US);
        if (n.endsWith(".ofp")) return "OFP";
        if (n.endsWith(".ops")) return "OPS";
        if (n.endsWith(".pkg")) return "PKG";
        if (n.endsWith(".transfer.list")) return "VDAT_LIST";
        if (n.endsWith(".new.dat")) return "VDAT_DATA";
        if (n.endsWith(".new.dat.br")) return "OTA_DAT_BR";
        if (h.length >= 8 && starts(h, "CrAU")) return "OTA_PAYLOAD";
        if (h.length >= 8 && starts(h, "ANDROID!")) return "ANDROID_BOOT";
        if (h.length >= 6 && (starts(h,"070701") || starts(h,"070702") || starts(h,"070707"))) return "CPIO";
        if (h.length >= 0x43a && (h[0x438] == 0x53) && (h[0x439] == (byte)0xEF)) return "EXT4";
        if (h.length >= 4 && starts(h, "PK\u0003\u0004")) return "ZIP";
        if (h.length >= 6 && h[0]==0x37 && h[1]==0x7A && h[2]==(byte)0xBC && h[3]==(byte)0xAF) return "7Z";
        if (h.length >= 2 && (h[0]&0xff)==0x1f && (h[1]&0xff)==0x8b) return "GZIP";
        if (h.length >= 2 && (h[0]&0xff)==0xfd && (h[1]&0xff)==0x37) return "XZ";
        if (h.length >= 3 && h[0]=='B' && h[1]=='Z' && h[2]=='h') return "BZ2";
        if (looksTar(h)) return "TAR";
        // the FAT/RFS boot sector may sit a few sectors into the image (up to 8 MiB)
        if (Rfs.probe(f)) return "RFS";
        return "UNKNOWN";
    }

    public static List<Artifact> extract(File input, File work, Consumer<String> log) throws Exception {
        if (!work.exists() && !work.mkdirs()) throw new IOException("cannot create work directory");
        String kind = detect(input);
        switch (kind) {
            case "PAC": return Pac.extract(input, work, log);
            case "SBF": return Sbf.extract(input, work, log);
            case "NBH": return Nbh.extract(input, work, log);
            case "HTC_RUU": return htcArtifacts(HtcFirmware.extractRuu(input, work, log));
            case "HTC_NBH": return htcArtifacts(HtcFirmware.extractDreamNbh(input, work, log));
            case "HTC_SIGNED": return htcArtifacts(HtcFirmware.unwrapSigned(input, work, log));
            case "LG_TOT": return Lg.extractTot(input, work, log);
            case "CAB": return Cab.extract(input, work, log);
            case "LG_BIN": return Lg.extractBin(input, work, log);
            case "RFS": return Collections.singletonList(new Artifact(Rfs.toZip(input, work, log), Role.ARCHIVE));
            case "SQUASHFS": return Collections.singletonList(new Artifact(SquashFs.toZip(input, work, log), Role.ARCHIVE));
            case "SUPER": return SuperImage.extract(input, work, log);
            case "VDAT_LIST": return Vdat.extractFromList(input, work, log);
            case "VDAT_DATA": return Vdat.extractFromData(input, work, log);
            case "OFP": return Ofp.extract(input, work, log);
            case "OPS": return Ops.extract(input, work, log);
            case "PKG": return Pkg.extract(input, work, log);
            case "OTA_DAT_BR": return Collections.singletonList(new Artifact(OtaDatBr.extract(input, work, log), Role.SYSTEM));
            case "OTA_PAYLOAD": return PayloadExtractor.extract(input, work, log);
            default: return Collections.emptyList();
        }
    }

    /** True for the HTC wrappers HtcFirmware unpacks: RUU .exe (MZ), Dream NBH, 256-byte-signed zip / boot image. */
    public static boolean isHtcContainer(byte[] head) { return HtcFirmware.looksLikeContainer(head); }

    private static List<Artifact> htcArtifacts(List<HtcFirmware.Item> items) {
        List<Artifact> out = new ArrayList<>();
        for (HtcFirmware.Item i : items) {
            Role r = "boot".equals(i.kind) ? Role.BOOT : "recovery".equals(i.kind) ? Role.RECOVERY
                : "system".equals(i.kind) ? Role.SYSTEM : Role.ARCHIVE;
            out.add(new Artifact(i.file, r));
        }
        return out;
    }

    // ===== combine_sparse.py ==================================================
    public static File combineSparseParts(List<File> parts, File out, boolean writeZeros) throws IOException {
        if (parts.isEmpty()) throw new IOException("no sparse parts");
        List<File> files = new ArrayList<>(parts);
        files.sort((a,b) -> {
            int na = sparsePartIndex(a.getName()), nb = sparsePartIndex(b.getName());
            if (na >= 0 && nb >= 0 && na != nb) return Integer.compare(na, nb);
            if (na >= 0 && nb < 0) return -1;
            if (na < 0 && nb >= 0) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        try (RandomAccessFile dst = new RandomAccessFile(out, "rw")) {
            dst.setLength(0);
            int blockSize = -1;
            for (File p : files) {
                try (RandomAccessFile in = new RandomAccessFile(p, "r")) {
                    SparseHeader sh = readSparseHeader(in);
                    if (blockSize < 0) blockSize = sh.blockSize;
                    if (blockSize != sh.blockSize) throw new IOException("sparse block-size mismatch");
                    long cursor = 0;
                    dst.seek(0);   // simg2img: every part is applied from offset 0 (its DONT_CARE ranges keep what earlier parts wrote)
                    in.seek(sh.fileHeaderSize);
                    for (int i=0;i<sh.totalChunks;i++) {
                        Chunk ch = readChunk(in, sh.chunkHeaderSize);
                        long bytes = (long)ch.blocks * sh.blockSize;
                        if (ch.type == 0xCAC1) {
                            long data = (long)ch.totalSize - sh.chunkHeaderSize;
                            if (data != bytes) throw new IOException("RAW chunk size mismatch");
                            copyRaf(in, dst, bytes);
                        } else if (ch.type == 0xCAC2) {
                            if (dataSize(ch, sh) < 4) throw new IOException("short FILL chunk");
                            byte[] fill = new byte[4]; in.readFully(fill);
                            skipRaf(in, dataSize(ch, sh)-4);
                            if (all(fill,0) && !writeZeros) dst.seek(dst.getFilePointer()+bytes);
                            else writeFill(dst, fill, bytes);
                        } else if (ch.type == 0xCAC3) {
                            if (writeZeros) writeZeros(dst, bytes); else dst.seek(dst.getFilePointer()+bytes);
                            if (dataSize(ch, sh) > 0) skipRaf(in, dataSize(ch, sh));
                        } else if (ch.type == 0xCAC4) {
                            skipRaf(in, dataSize(ch, sh));
                        } else {
                            skipRaf(in, dataSize(ch, sh));
                            throw new IOException("unsupported sparse chunk 0x"+Integer.toHexString(ch.type));
                        }
                        cursor += bytes;
                    }
                    dst.setLength(sparseRawSize(p));   // output_file pad: size = total_blks * blk_sz of this part
                }
            }
        }
        return out;
    }

    // ===== combine_edl_system.py =============================================
    /** One <program> element of a rawprogram XML that belongs to the wanted partition. */
    public static final class EdlEntry {
        public final String file; public final long startSector, numSectors, sectorSize, fileSectorOffset;
        EdlEntry(String f, long st, long n, long ss, long fso) { file = f; startSector = st; numSectors = n; sectorSize = ss; fileSectorOffset = fso; }
    }
    /** The rawprogram XML chosen for a partition and the chunks it declares. */
    public static final class EdlPlan {
        public final File xml; public final String label; public final List<EdlEntry> entries; public final int missing;
        EdlPlan(File x, String l, List<EdlEntry> e, int m) { xml = x; label = l; entries = e; missing = m; }
        /** Distinct chunk file names (as written in the XML) that have to be present. */
        public List<String> files() {
            LinkedHashSet<String> o = new LinkedHashSet<>(); for (EdlEntry e : entries) o.add(e.file); return new ArrayList<>(o);
        }
    }

    static List<EdlEntry> edlEntries(File xml, String label) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        Document doc = f.newDocumentBuilder().parse(xml);
        NodeList nodes = doc.getElementsByTagName("program");
        String want = label.toLowerCase(Locale.US);
        Map<String, EdlEntry> exact = new LinkedHashMap<>(), loose = new LinkedHashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            String filename = e.getAttribute("filename").trim();
            if (filename.isEmpty()) continue;
            String lab = e.getAttribute("label").trim().toLowerCase(Locale.US);
            String base = filename.replace('\\', '/'); base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.US);
            boolean isExact = lab.equals(want);
            // fallback for XMLs with odd/missing labels: file named system*.img (never vendor_system etc.)
            boolean isLoose = lab.isEmpty() && base.startsWith(want);
            if (!isExact && !isLoose) continue;
            long ss = attrLong(e, "SECTOR_SIZE_IN_BYTES", 512), st = attrLong(e, "start_sector", 0),
                 n = attrLong(e, "num_partition_sectors", 0), fso = attrLong(e, "file_sector_offset", 0);
            EdlEntry en = new EdlEntry(filename, st, n, ss <= 0 ? 512 : ss, Math.max(0, fso));
            (isExact ? exact : loose).put(filename.toLowerCase(Locale.US) + "@" + st, en); // later duplicate wins
        }
        return new ArrayList<>(exact.isEmpty() ? loose.values() : exact.values());
    }

    /**
     * Picks, among all rawprogram XMLs, the one that describes {@code label} and whose chunk files are
     * actually available (lower-cased base names in {@code available}). Fully resolvable XMLs win over
     * partial ones, then the one covering more chunks. Returns null if no XML mentions the partition.
     */
    public static EdlPlan planEdl(List<File> xmls, Collection<String> available, String label, Consumer<String> log) throws Exception {
        Set<String> have = new HashSet<>(); for (String a : available) have.add(a.toLowerCase(Locale.US));
        EdlPlan best = null; long bestScore = -1;
        for (File xml : xmls) {
            List<EdlEntry> es;
            try { es = edlEntries(xml, label); } catch (Exception ex) { log.accept("EDL: cannot parse " + xml.getName() + ": " + ex.getMessage()); continue; }
            if (es.isEmpty()) continue;
            int ok = 0; long bytes = 0;
            for (EdlEntry e : es) {
                String b = e.file.replace('\\', '/'); b = b.substring(b.lastIndexOf('/') + 1).toLowerCase(Locale.US);
                if (have.contains(b)) { ok++; bytes += e.numSectors * e.sectorSize; }
            }
            if (ok == 0) { log.accept("EDL: " + xml.getName() + " lists " + es.size() + " '" + label + "' chunk(s), none present"); continue; }
            long score = ((ok == es.size()) ? (1L << 60) : 0) + ((long) ok << 40) + Math.min(bytes >> 12, (1L << 40) - 1);
            log.accept("EDL: " + xml.getName() + " -> " + ok + "/" + es.size() + " '" + label + "' chunk(s) present");
            if (score > bestScore) { bestScore = score; best = new EdlPlan(xml, label, es, es.size() - ok); }
        }
        if (best != null) log.accept("EDL: using " + best.xml.getName() + " for '" + label + "'");
        return best;
    }

    private static File edlFile(File imgDir, String name) {
        String base = name.replace('\\', '/'); base = base.substring(base.lastIndexOf('/') + 1);
        File f = new File(imgDir, base); if (f.isFile()) return f;
        File[] all = imgDir.listFiles(); if (all != null) for (File x : all) if (x.isFile() && x.getName().equalsIgnoreCase(base)) return x;
        return null;
    }

    /** Merges the chunks of {@code plan} into one raw partition image. Offsets are relative to the lowest start sector. */
    public static File combineEdl(EdlPlan plan, File imgDir, File out, Consumer<String> log) throws Exception {
        class Chunk { EdlEntry e; File src; boolean sparse; long rel, raw; }
        List<Chunk> cs = new ArrayList<>();
        List<EdlEntry> es = new ArrayList<>(plan.entries);
        es.sort(Comparator.comparingLong(x -> x.startSector * x.sectorSize));
        if (es.isEmpty()) throw new IOException("no rawprogram entries for " + plan.label);
        long baseByte = es.get(0).startSector * es.get(0).sectorSize;
        for (EdlEntry e : es) {
            File src = edlFile(imgDir, e.file);
            if (src == null) { log.accept("EDL: missing chunk " + e.file + " (left as zeros)"); continue; }
            Chunk c = new Chunk(); c.e = e; c.src = src; c.sparse = isSparse(src);   // magic wins over the XML flag
            c.rel = e.startSector * e.sectorSize - baseByte;
            long declared = e.numSectors * e.sectorSize;
            if (c.sparse) c.raw = sparseRawSize(src);
            else c.raw = Math.max(0, src.length() - e.fileSectorOffset * e.sectorSize);
            if (declared > 0 && c.raw > declared) c.raw = declared;
            cs.add(c);
        }
        if (cs.isEmpty()) throw new IOException("no EDL chunk files found for " + plan.label);
        long total = 0; for (Chunk c : cs) total = Math.max(total, c.rel + c.raw);
        long ss = es.get(0).sectorSize; total = ((total + ss - 1) / ss) * ss;
        try (RandomAccessFile dst = new RandomAccessFile(out, "rw")) {
            dst.setLength(0); dst.setLength(total);   // untouched ranges stay zero
            for (Chunk c : cs) {
                if (c.sparse) unsparseAt(c.src, dst, c.rel, c.raw);
                else try (RandomAccessFile in = new RandomAccessFile(c.src, "r")) {
                    in.seek(c.e.fileSectorOffset * c.e.sectorSize); dst.seek(c.rel); copyRaf(in, dst, c.raw);
                }
                log.accept("EDL: " + c.e.file + " @" + c.rel + " (" + c.raw + " bytes" + (c.sparse ? ", sparse" : "") + ")");
            }
        }
        return out;
    }

    /** Convenience: all XMLs + every file of {@code imgDir} are candidates (old API). */
    public static File combineEdl(List<File> xmls, File imgDir, String label, File out, Consumer<String> log) throws Exception {
        String[] names = imgDir.list(); EdlPlan p = planEdl(xmls, names == null ? new ArrayList<String>() : Arrays.asList(names), label, log);
        if (p == null) throw new IOException("no rawprogram entries for " + label);
        return combineEdl(p, imgDir, out, log);
    }
    public static File combineEdl(File xml, File imgDir, String label, File out, Consumer<String> log) throws Exception {
        return combineEdl(Collections.singletonList(xml), imgDir, label, out, log);
    }

    static long sparseRawSize(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[28]; r.readFully(h);
            if (u32(h, 0) != 0xED26FF3AL) throw new IOException("not sparse");
            return u32(h, 16) * u32(h, 12);
        }
    }

    /** Expands a sparse image into dst at dstOff (DONT_CARE / zero FILL stay holes), writing at most limit bytes. */
    private static void unsparseAt(File src, RandomAccessFile dst, long dstOff, long limit) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(src, "r")) {
            SparseHeader sh = readSparseHeader(in); in.seek(sh.fileHeaderSize);
            long cursor = 0;
            for (int i = 0; i < sh.totalChunks && cursor < limit; i++) {
                Chunk ch = readChunk(in, sh.chunkHeaderSize);
                long n = (long) ch.blocks * sh.blockSize, avail = Math.min(n, limit - cursor), data = dataSize(ch, sh);
                if (ch.type == 0xCAC1) {
                    dst.seek(dstOff + cursor); copyRaf(in, dst, avail);
                    if (data > avail) skipRaf(in, data - avail);
                    cursor += n;
                } else if (ch.type == 0xCAC2) {
                    byte[] fill = new byte[4]; in.readFully(fill); if (data > 4) skipRaf(in, data - 4);
                    if (!all(fill, 0)) { dst.seek(dstOff + cursor); writeFill(dst, fill, avail); }
                    cursor += n;
                } else if (ch.type == 0xCAC3) {
                    if (data > 0) skipRaf(in, data);
                    cursor += n;
                } else if (data > 0) skipRaf(in, data);   // CRC32 / unknown: no block data
            }
        }
    }

    // ===== PAC =================================================================
    /**
     * Spreadtrum/Unisoc PAC. A PAC carries a dozen-plus partitions (FDL1/FDL2, nv, modem, userdata,
     * cache, vendor, ...). Only system.img and boot.img matter for the emulator, so only those two
     * are written out (as system.img / boot.img); everything else is skipped without being copied.
     */
    static final class Pac {
        static final String[] WANTED = {"system.img", "boot.img", "recovery.img", "oem.img"};

        private static final class Ent {
            final int index; final String id, name; final long size, off;
            Ent(int i, String id, String name, long size, long off) { index=i; this.id=id; this.name=name; this.size=size; this.off=off; }
            String baseName() { int k = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')); return (k >= 0 ? name.substring(k + 1) : name).trim(); }
        }

        /** Picks the entry for one wanted image: exact file name first, then the partition id ("system" / "boot"). */
        static Ent pick(List<Ent> all, String wanted) {
            for (Ent e : all) if (e.baseName().equalsIgnoreCase(wanted)) return e;
            String partition = wanted.substring(0, wanted.length() - ".img".length());
            for (Ent e : all) if (e.id.trim().equalsIgnoreCase(partition)) return e;
            return null;
        }

        static List<Artifact> extract(File f,File work,Consumer<String>log)throws Exception {
            final int FH=2580;
            File out=new File(work,"pac");out.mkdirs();List<Artifact>a=new ArrayList<>();
            try(RandomAccessFile r=new RandomAccessFile(f,"r")) {
                byte[] h=new byte[2124]; if(r.read(h)!=h.length)throw new IOException("truncated PAC");
                String ver=utf16(h,0,44); if(!(ver.startsWith("BP_R1.0.0")||ver.startsWith("BP_R2.0.1")))throw new IOException("unsupported PAC version");
                long declared=u64pair(h,4); if(declared!=0 && declared!=r.length())log.accept("PAC: size field differs from container length");
                int count=(int)u32(h,1076), table=(int)u32(h,1080);
                if(count<1||count>4096||table<2124L||table+FH>r.length())throw new IOException("invalid PAC partition table");
                r.seek(table);
                List<Ent> all=new ArrayList<>();
                for(int i=0;i<count;i++) {
                    if(r.getFilePointer()+FH>r.length())break; byte[] x=new byte[FH];r.readFully(x); if(u32(x,0)!=FH)break;
                    String id=utf16(x,4,512), name=utf16(x,516,512);
                    long size=u32(x,1540)|(u32(x,1532)<<32), off=u32(x,1552)|(u32(x,1536)<<32);
                    if(size<=0||off<0||off+size>r.length())continue;
                    all.add(new Ent(i,id,name,size,off));
                }
                for(String wanted:WANTED) {
                    Ent e=pick(all,wanted);
                    if(e==null){log.accept("PAC: no "+wanted+" entry");continue;}
                    File dst=new File(out,wanted);r.seek(e.off);
                    try(OutputStream os=new BufferedOutputStream(new FileOutputStream(dst))){copy(r,os,e.size);}
                    a.add(new Artifact(dst,roleFor(dst.getName())));log.accept("PAC: "+e.id+" ("+e.name+") -> "+dst.getName());
                }
                log.accept("PAC: "+(all.size()-a.size())+" other partition(s) skipped");
            }
            if(a.isEmpty())throw new IOException("PAC contains no system.img or boot.img");
            return a;
        }
    }

    // ===== SBF =================================================================
    /**
     * Motorola "Multi-Interface Boot File".
     *
     * Layout (verified on Xoom EVRSU / HUBWF dumps; both files tile exactly to EOF):
     *   "Multi-Interface\0", zero fill, a ~3 MiB signature / PKI blob, then records back to back
     *   until a short footer. Every record is
     *       [ 4] checksum
     *       [17] fixed prefix  03 02 02 00 00 00 00 03 02 00 00 00 00 00 03 02 01
     *       [ 4] payload length, big endian
     *       [ 4] attribute word (partition slot, e.g. 0x110, 0x120, 0x130 ...)
     *       [len] payload
     *
     * The old code searched for an 18-byte "marker" whose last byte was hard-coded to 0x00. That
     * byte is really the top byte of the length, so every record of 16 MiB or more (the ext4
     * system / cache partitions, length 0x10000000 and 0x0A900000) was invisible and got swallowed
     * by the record before it. It also ignored the length field, labelled sections by guesswork
     * (first Android image = boot, any blob containing "system" = system.img) and handed unusable
     * junk sections to Importer, which aborts on the first file it cannot identify.
     *
     * This version walks the record chain by its length field, classifies payloads by content,
     * and emits only what Importer can use: system.img, boot.img and recovery.img.
     */
    static final class Sbf {
        private static final byte[] PFX = {3,2,2,0,0,0,0,3,2,0,0,0,0,0,3,2,1};
        private static final int CK = 4, HDR = CK + 17 + 4 + 4;
        private static final int CHUNK = 1 << 20;
        private static final long MIN_UNKNOWN_FS = 16L << 20;
        private static final byte[][] SYSTEM_MARKERS = {
            "ro.build.version.sdk=".getBytes(StandardCharsets.US_ASCII),
            "ro.build.version.release=".getBytes(StandardCharsets.US_ASCII)};

        private static final class Rec {
            final long data, len; final int attr;
            Rec(long data, long len, int attr) { this.data = data; this.len = len; this.attr = attr; }
        }
        private static final class Part {
            final Rec r; final String kind; final long copy; Boolean recovery;
            Part(Rec r, String kind, long copy) { this.r = r; this.kind = kind; this.copy = copy; }
        }

        static List<Artifact> extract(File f, File work, Consumer<String> log) throws Exception {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                long len = raf.length();
                byte[] magic = new byte[15];
                if (len < magic.length) throw new IOException("not SBF");
                raf.readFully(magic);
                if (!starts(magic, "Multi-Interface")) throw new IOException("not SBF");

                List<Rec> recs = parseRecords(raf, len, log);
                if (recs.isEmpty()) throw new IOException("SBF: no records found (unsupported SBF layout)");

                List<Part> fs = new ArrayList<>(), android = new ArrayList<>(), other = new ArrayList<>();
                for (Rec r : recs) {
                    Part p = classify(raf, r);
                    if (p.kind.equals("android")) android.add(p);
                    else if (p.kind.equals("other")) other.add(p);
                    else fs.add(p);
                }

                // system = the filesystem that carries build.prop; fall back to the largest filesystem
                Part sys = null;
                for (Part p : fs) if (rangeContains(raf, p.r.data, p.r.len, SYSTEM_MARKERS)) { sys = p; break; }
                if (sys == null && !fs.isEmpty()) {
                    sys = fs.get(0);
                    for (Part p : fs) if (p.r.len > sys.r.len) sys = p;
                    log.accept("SBF: no build.prop found, using the largest filesystem (slot 0x" + Integer.toHexString(sys.r.attr) + ") as system");
                }
                if (sys == null) { // older layouts (YAFFS2 and friends): let Importer try the biggest unknown payload
                    for (Part p : other) if (p.r.len >= MIN_UNKNOWN_FS && (sys == null || p.r.len > sys.r.len)) sys = p;
                    if (sys != null) log.accept("SBF: unrecognised filesystem in slot 0x" + Integer.toHexString(sys.r.attr) + ", trying it as system");
                }

                // boot vs recovery: decided by the ramdisk (only the recovery one carries sbin/recovery)
                for (Part p : android) p.recovery = ramdiskHasRecovery(raf, p.r.data, p.r.len);
                Part boot = null, rec = null;
                for (Part p : android) {
                    if (Boolean.TRUE.equals(p.recovery) && rec == null) rec = p;
                    else if (Boolean.FALSE.equals(p.recovery) && boot == null) boot = p;
                }
                for (Part p : android) {               // undecidable leftovers: stream order, boot first
                    if (p == boot || p == rec) continue;
                    if (boot == null) boot = p; else if (rec == null) rec = p;
                }

                File out = new File(work, "sbf"); out.mkdirs();
                List<Artifact> a = new ArrayList<>();
                if (sys != null) a.add(emit(raf, sys, new File(out, "system.img"), Role.SYSTEM, log));
                if (boot != null) a.add(emit(raf, boot, new File(out, "boot.img"), Role.BOOT, log));
                if (rec != null) a.add(emit(raf, rec, new File(out, "recovery.img"), Role.RECOVERY, log));
                if (a.isEmpty()) throw new IOException("SBF contains no recognizable Android partitions (" + recs.size() + " records)");
                if (sys == null) log.accept("SBF: warning, no system filesystem found among " + recs.size() + " records");
                log.accept("SBF: " + recs.size() + " records, kept " + a.size());
                return a;
            }
        }

        private static Artifact emit(RandomAccessFile raf, Part p, File dst, Role role, Consumer<String> log) throws IOException {
            copyRange(raf, p.r.data, Math.min(p.copy, p.r.len), dst);
            log.accept("SBF: slot 0x" + Integer.toHexString(p.r.attr) + " (" + p.kind + ", " + (p.r.len >> 10) + " KB) -> " + dst.getName());
            return new Artifact(dst, role);
        }

        // ---- record chain ---------------------------------------------------------------

        /**
         * The first record is located by the fixed prefix. A clean chain tiles the file up to its footer;
         * if the chain breaks (damaged length field) the first plausible candidate is walked again with
         * resynchronisation instead of accepting some later record whose chain happens to end at EOF.
         */
        private static List<Rec> parseRecords(RandomAccessFile raf, long len, Consumer<String> log) throws IOException {
            long from = 0;
            for (int tries = 0; tries < 16; tries++) {
                long p = findPrefix(raf, len, from);
                if (p < 0) break;
                from = p + 1;
                if (p < CK) continue;
                long[] end = new long[1];
                List<Rec> strict = walk(raf, len, p - CK, false, null, end);
                if (strict.isEmpty()) continue;
                if (len - end[0] <= 64) return strict;                             // clean chain to EOF
                if (strict.size() >= 2) {                                          // damaged: resync and keep going
                    List<Rec> loose = walk(raf, len, p - CK, true, log, end);
                    if (!loose.isEmpty()) return loose;
                }
            }
            return new ArrayList<Rec>();
        }

        private static List<Rec> walk(RandomAccessFile raf, long len, long start, boolean resync,
                                      Consumer<String> log, long[] endOut) throws IOException {
            List<Rec> out = new ArrayList<>();
            byte[] h = new byte[HDR];
            long pos = start;
            while (pos + HDR <= len) {
                raf.seek(pos); raf.readFully(h);
                if (!prefixAt(h, CK)) {
                    if (!resync) break;
                    long p = findPrefix(raf, len, pos + 1);
                    if (p < CK) break;
                    if (log != null) log.accept("SBF: lost record sync at " + pos + ", resynchronised at " + (p - CK));
                    pos = p - CK; continue;
                }
                long dl = be32(h, CK + 17);
                int attr = (int) be32(h, CK + 21);
                long data = pos + HDR;
                if (data + dl > len) dl = len - data;                              // truncated download
                out.add(new Rec(data, dl, attr));
                pos = data + dl;
            }
            endOut[0] = pos;
            return out;
        }

        private static boolean prefixAt(byte[] b, int o) {
            if (o + PFX.length > b.length) return false;
            for (int i = 0; i < PFX.length; i++) if (b[o + i] != PFX[i]) return false;
            return true;
        }
        private static long be32(byte[] b, int o) {
            return ((b[o] & 255L) << 24) | ((b[o + 1] & 255L) << 16) | ((b[o + 2] & 255L) << 8) | (b[o + 3] & 255L);
        }

        /** Offset of the next fixed prefix at or after {@code from}, or -1. Streamed in 1 MiB chunks. */
        private static long findPrefix(RandomAccessFile raf, long len, long from) throws IOException {
            byte[] buf = new byte[CHUNK + PFX.length];
            long pos = Math.max(0, from);
            while (pos + PFX.length <= len) {
                int want = (int) Math.min(buf.length, len - pos);
                raf.seek(pos); raf.readFully(buf, 0, want);
                int last = want - PFX.length;
                for (int i = 0; i <= last; i++) {
                    if (buf[i] != PFX[0]) continue;
                    int k = 1; while (k < PFX.length && buf[i + k] == PFX[k]) k++;
                    if (k == PFX.length) return pos + i;
                }
                pos += last + 1;                                                   // keep PFX.length-1 bytes of overlap
            }
            return -1;
        }

        // ---- payload classification -----------------------------------------------------

        private static Part classify(RandomAccessFile raf, Rec r) throws IOException {
            byte[] hd = new byte[(int) Math.min(r.len, 0x500)];
            raf.seek(r.data); raf.readFully(hd);
            if (starts(hd, "ANDROID!")) {
                long exact = androidImageSize(hd);
                if (exact <= 0) exact = trimmedLength(raf, r.data, r.len);
                return new Part(r, "android", exact);
            }
            if (isExtSuper(hd)) return new Part(r, "ext", r.len);
            if (hd.length >= 4 && u32(hd, 0) == 0xED26FF3AL) return new Part(r, "sparse", r.len);
            if (starts(hd, "hsqs") || starts(hd, "sqsh")) return new Part(r, "squashfs", r.len);
            return new Part(r, "other", r.len);
        }

        private static boolean isExtSuper(byte[] b) {
            if (b.length < 0x460 || b[0x438] != 0x53 || b[0x439] != (byte) 0xEF) return false;
            return u32(b, 0x400) > 0 && u32(b, 0x404) > 0 && u32(b, 0x418) <= 6 && u32(b, 0x44C) <= 1;
        }

        /** TRUE = ramdisk has sbin/recovery, FALSE = it does not, null = ramdisk could not be read. */
        private static Boolean ramdiskHasRecovery(RandomAccessFile raf, long off, long len) {
            try {
                byte[] hd = new byte[(int) Math.min(len, 64)];
                raf.seek(off); raf.readFully(hd);
                if (!starts(hd, "ANDROID!") || hd.length < 40) return null;
                long page = u32(hd, 36), k = u32(hd, 8), r = u32(hd, 16);
                if (page < 512 || page > 65536 || r <= 0 || r > (256L << 20)) return null;
                long ro = off + page + align(k, page);
                long rl = Math.min(r, off + len - ro);
                if (rl <= 4) return null;
                byte[] m = new byte[6];
                raf.seek(ro); raf.readFully(m, 0, (int) Math.min(rl, 6));
                InputStream in = new BufferedInputStream(new RangeIn(raf, ro, rl), 1 << 16);
                if ((m[0] & 255) == 0x1f && (m[1] & 255) == 0x8b) in = new GZIPInputStream(in, 1 << 16);
                else if (!starts(m, "070701") && !starts(m, "070702")) return null;
                return streamContains(in, "sbin/recovery".getBytes(StandardCharsets.US_ASCII));
            } catch (Exception e) { return null; }
        }

        private static Boolean streamContains(InputStream in, byte[] pat) {
            byte[] buf = new byte[(1 << 16) + pat.length];
            int carry = 0;
            try {
                int n;
                while ((n = in.read(buf, carry, 1 << 16)) > 0) {
                    int total = carry + n;
                    if (total >= pat.length && indexOf(Arrays.copyOf(buf, total), pat, 0) >= 0) return Boolean.TRUE;
                    carry = Math.min(pat.length - 1, total);
                    System.arraycopy(buf, total - carry, buf, 0, carry);
                }
                return Boolean.FALSE;
            } catch (IOException e) { return null; }
        }

        private static final class RangeIn extends InputStream {
            private final RandomAccessFile raf; private long pos; private final long end;
            RangeIn(RandomAccessFile raf, long start, long len) { this.raf = raf; this.pos = start; this.end = start + len; }
            @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
            @Override public int read(byte[] b, int o, int n) throws IOException {
                if (pos >= end) return -1;
                int k = (int) Math.min(n, end - pos);
                raf.seek(pos); k = raf.read(b, o, k);
                if (k > 0) pos += k;
                return k;
            }
        }

        // ---- streaming helpers ----------------------------------------------------------

        /** Length of [start,start+len) with trailing 0x00/0xFF bytes removed (streamed backwards). */
        private static long trimmedLength(RandomAccessFile raf, long start, long len) throws IOException {
            byte[] buf = new byte[CHUNK];
            long e = len;
            while (e > 0) {
                int n = (int) Math.min(CHUNK, e);
                raf.seek(start + e - n); raf.readFully(buf, 0, n);
                for (int i = n - 1; i >= 0; i--) if (buf[i] != 0 && (buf[i] & 255) != 255) return e - (n - 1 - i);
                e -= n;
            }
            return 0;
        }
        /** Streamed search for any of the patterns inside [start,start+len). */
        private static boolean rangeContains(RandomAccessFile raf, long start, long len, byte[]... pats) throws IOException {
            int maxPat = 0; for (byte[] p : pats) maxPat = Math.max(maxPat, p.length);
            byte[] buf = new byte[CHUNK + maxPat];
            long done = 0; int carry = 0;
            while (done < len) {
                int want = (int) Math.min(CHUNK, len - done);
                raf.seek(start + done); raf.readFully(buf, carry, want);
                int total = carry + want;
                byte[] view = Arrays.copyOf(buf, total);
                for (byte[] p : pats) if (total >= p.length && indexOf(view, p, 0) >= 0) return true;
                done += want;
                carry = Math.min(maxPat - 1, total);
                System.arraycopy(buf, total - carry, buf, 0, carry);
            }
            return false;
        }
        private static void copyRange(RandomAccessFile raf, long start, long count, File dst) throws IOException {
            byte[] buf = new byte[CHUNK];
            try (OutputStream o = new BufferedOutputStream(new FileOutputStream(dst), CHUNK)) {
                long done = 0;
                while (done < count) {
                    int n = (int) Math.min(CHUNK, count - done);
                    raf.seek(start + done); raf.readFully(buf, 0, n);
                    o.write(buf, 0, n); done += n;
                }
            }
        }
        static int androidImageSize(byte[]b){if(!starts(b,"ANDROID!")||b.length<40)return -1;long page=u32(b,36),k=u32(b,8),r=u32(b,16),s=u32(b,24);if(page<512||page>65536||k>Integer.MAX_VALUE||r>Integer.MAX_VALUE||s>Integer.MAX_VALUE)return -1;return safeInt((page + align(k,page)+align(r,page)+align(s,page)));}
    }

    // ===== NBH =================================================================
    static final class Nbh {
        static final int SIGNATURE_SIZE=256, HEADER_SIZE=512, SECTION_COUNT=32;
        static final Map<Long,String> TYPES=new HashMap<>();
        static { TYPES.put(0x100L,"IPL"); TYPES.put(0x101L,"G3IPL"); TYPES.put(0x102L,"G4IPL"); TYPES.put(0x200L,"SPL"); TYPES.put(0x201L,"G3SPL"); TYPES.put(0x202L,"G4SPL"); TYPES.put(0x300L,"GSM"); TYPES.put(0x301L,"GSM_2"); TYPES.put(0x400L,"OS"); TYPES.put(0x401L,"OS_2"); TYPES.put(0x500L,"DIAG"); TYPES.put(0x600L,"MainSplash"); TYPES.put(0x601L,"SubSplash"); TYPES.put(0x700L,"ExtROM"); TYPES.put(0x900L,"ExtROM_2"); TYPES.put(0xA00L,"MCPLD"); TYPES.put(0xB02L,"LinuxSystem"); TYPES.put(0xB04L,"LinuxBoot"); TYPES.put(0xB05L,"Recovery"); TYPES.put(0xB06L,"UserData"); }
        static List<Artifact> extract(File f,File work,Consumer<String>log)throws Exception {
            File out=new File(work,"nbh"); out.mkdirs(); List<Artifact>a=new ArrayList<>();
            try(RandomAccessFile r=new RandomAccessFile(f,"r")) {
                if(r.length()<SIGNATURE_SIZE+HEADER_SIZE)throw new IOException("truncated NBH");
                byte[]sig=readAt(r,0,SIGNATURE_SIZE); byte[]magic=readAt(r,SIGNATURE_SIZE,32);
                String m=new String(magic,StandardCharsets.US_ASCII);
                if(!m.startsWith("HTCIMAGE"))throw new IOException("not NBH");
                File sf=new File(out,safe(stripExt(f.getName()))+"_00_Signature.bin");writeBytes(sf,sig);a.add(new Artifact(sf,Role.ARCHIVE));
                byte[]h=readAt(r,SIGNATURE_SIZE,HEADER_SIZE);
                for(int i=0;i<SECTION_COUNT;i++){
                    long type=u32(h,64+i*4), off=u32(h,192+i*4), len=u32(h,320+i*4);
                    if(type==0 || len==0 || off+len>r.length()) continue;
                    String tn=TYPES.getOrDefault(type,"section_"+Long.toHexString(type));
                    File d=new File(out,String.format(Locale.US,"%02d_%s.img",i+1,safe(tn)));r.seek(off);
                    try(OutputStream os=new BufferedOutputStream(new FileOutputStream(d))){copy(r,os,len);}a.add(new Artifact(d,roleFor(d.getName())));log.accept("NBH: "+d.getName());
                }
            }
            return a;
        }
    }

    // ===== LG TOT / BIN ========================================================
    static final class Lg {
        static final int BLOCK=512; static final long DATA_START=0x100000L, UNSET=0xffffffffL;
        static final long[] TOT_NAMES_4220={0x8978F62BL,0x5062C8EAL,0x49838B94L,0xDEBF33AFL,0x42EF4E39L,0x0E65F034L,0x95F57D8CL,0x729092C9L,0x71B31218L,0xCD5F070AL,0x4AC769ECL};
        static final long[] TOT_NAMES_6230={0x416A35AAL,0x36E3F6DBL,0x0777622CL,0x0E2DD7E9L,0x17CBC8C3L,0xC2DF3B1FL,0x00A60D56L,0x9D8E9FD7L,0xD80FC557L,0x12568B19L,0xC652DB34L,0xC4C4055AL,0xF282D8EBL,0x191C1A47L,0x1D3B326DL,0x22D79B07L,0xA1CEEDECL,0xE2C457AEL,0xD0BC5531L};
        static List<Artifact> extractTot(File f,File work,Consumer<String>log)throws Exception {
            File out=new File(work,"tot");out.mkdirs();
            try(RandomAccessFile r=new RandomAccessFile(f,"r")){
                long magic=u32(readAt(r,0,4),0);
                if(magic!=0xAA55DD44L) throw new IOException("unsupported LG TOT/BIN AP header");
                if(u32(readAt(r,0x2000,4),0)!=0xAA55EC33L) throw new IOException("invalid LG 44DD AP header");
                List<Ent> es=read44ddEntries(r); if(es.isEmpty())throw new IOException("no LG TOT partitions");
                fillNames44dd(r,es);
                Map<String,List<Ent>> groups=new LinkedHashMap<>();
                for(Ent e:es) groups.computeIfAbsent(e.name.toLowerCase(Locale.US),k->new ArrayList<>()).add(e);
                Map<String,Long> gpt=parseGptSizes(r);
                List<Artifact>a=new ArrayList<>();
                for(Map.Entry<String,List<Ent>> ge:groups.entrySet()){
                    String partition=ge.getKey()==null?"":ge.getKey().trim().toLowerCase(Locale.US);
                    // Only system, boot, recovery and oem are consumed by AEmulator.
                    if(!partition.equals("system") && !partition.equals("boot") && !partition.equals("recovery") && !partition.equals("oem")) continue;
                    List<Ent> xs=ge.getValue();xs.sort(Comparator.comparingLong(e->e.diskOff));
                    long base=xs.get(0).diskOff, max=0;for(Ent e:xs)max=Math.max(max,e.diskOff+e.fileSize);String n=safe(xs.get(0).name);
                    long sectors=gpt.getOrDefault(ge.getKey(),max-base), imageSize=sectors*BLOCK;
                    File d=new File(out,n+".img");try(RandomAccessFile w=new RandomAccessFile(d,"rw")){w.setLength(imageSize);for(Ent e:xs){long dst=(e.diskOff-base)*BLOCK, src=DATA_START+e.fileOff*BLOCK, left=e.fileSize*BLOCK;if(dst<0||dst>=imageSize||src<0||src>=r.length())continue;w.seek(dst);r.seek(src);long avail=Math.min(left,Math.min(imageSize-dst,r.length()-src));copyRaf(r,w,avail);}}
                    a.add(new Artifact(d,roleFor(d.getName())));log.accept("LG TOT: "+d.getName()+" ("+xs.size()+" chunk(s))");
                }
                return a;
            }
        }
        static List<Artifact> extractBin(File f,File work,Consumer<String>log)throws Exception {
            try(RandomAccessFile r=new RandomAccessFile(f,"r")){
                long magic=u32(readAt(r,0,4),0);
                if(magic==0xAA55DD44L) return extractTot(f,work,log);
                File out=new File(work,"bin");out.mkdirs();AP ap=magic==0xAA55EC44L?read44ec(r):magic==0xAA55A5A5L?readA5(r):null;
                if(ap==null)throw new IOException("unsupported LG BIN magic 0x"+Long.toHexString(magic));
                return split(r,ap,out,log);
            }
        }
        static final class Ent {String name="";long fileOff,fileSize,diskOff,diskSize;int id;}
        static final class AP {List<Ent>e=new ArrayList<>();}
        static AP read44ec(RandomAccessFile r)throws Exception {AP ap=new AP();r.seek(4);while(r.getFilePointer()+8<=r.length()){byte[]b=readBytes(r,8);if(all(b,0xff))break;Ent e=new Ent();e.fileOff=u32(b,0);e.diskOff=e.fileOff;e.fileSize=u32(b,4);ap.e.add(e);}r.seek(0x200);for(Ent e:ap.e){e.id=(int)u32(readAt(r,r.getFilePointer(),4),0);e.diskSize=u32(readAt(r,r.getFilePointer()+4,4),0);r.skipBytes(4);byte[]n=readBytes(r,0x14);e.name=cstr(n);r.skipBytes(0x1e0);}return ap;}
        static AP readA5(RandomAccessFile r)throws Exception {
            AP ap=new AP(); r.seek(0x20c); int p=r.read(); boolean alt=p>=0&&Character.isLetterOrDigit((char)p);
            r.seek(4); while(r.getFilePointer()+8<=r.length()){byte[]b=readBytes(r,8);if(all(b,0xff))break;Ent e=new Ent();e.fileOff=u32(b,0);e.fileSize=u32(b,4);ap.e.add(e);}
            long pos=r.getFilePointer(); r.seek((pos+511)/512*512);
            for(Ent e:ap.e){ e.id=(int)u32(readAt(r,r.getFilePointer(),4),0); e.diskSize=u32(readAt(r,r.getFilePointer()+4,4),0);
                if(alt){r.skipBytes(4); e.name=cstr(readBytes(r,20)); r.skipBytes(480);} else {r.skipBytes(504);} }
            if(!alt){ r.seek(0x2200); for(Ent e:ap.e){e.id=(int)u32(readAt(r,r.getFilePointer(),4),0); r.skipBytes(0); e.name=cstr(readAt(r,r.getFilePointer(),256));} }
            return ap;
        }
        static List<Ent> read44ddEntries(RandomAccessFile r)throws IOException {
            List<Ent>o=new ArrayList<>(); r.seek(0x2010);
            while(r.getFilePointer()+16<=r.length()){byte[]b=readBytes(r,16);if(u32(b,0)==0xffffffffL)break;Ent e=new Ent();e.diskOff=u32(b,0);e.fileOff=u32(b,4);e.fileSize=u32(b,8);o.add(e);} return o;
        }
        static void fillNames44dd(RandomAccessFile r,List<Ent>es)throws IOException {
            int[][]c={{0x6230,0x20},{0x4220,0x20},{0x2400,0x20},{0x3004,0x14},{0x2404,0x14}};
            for(int[]x:c){if(x[0]+(long)x[1]*es.size()>r.length())continue;List<String>names=new ArrayList<>();boolean ok=true;for(int i=0;i<es.size();i++){r.seek(x[0]+(long)i*x[1]);String n=cstr(readBytes(r,x[1]));if(!plausible(n)){ok=false;break;}names.add(n);}if(ok){for(int i=0;i<es.size();i++)es.get(i).name=names.get(i);return;}}
            for(int i=0;i<es.size();i++)es.get(i).name="part_"+i;
        }
        static boolean plausible(String s){if(s==null||s.isEmpty())return false;boolean a=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c<32||c>126)return false;if(Character.isLetterOrDigit(c))a=true;}return a;}
        static Map<String,Long> parseGptSizes(RandomAccessFile r)throws IOException {
            Map<String,Long>m=new HashMap<>();long p=DATA_START+BLOCK;if(p+92>r.length())return m;r.seek(p);byte[]h=readBytes(r,92);if(!"EFI PART".equals(new String(h,0,8,StandardCharsets.US_ASCII)))return m;
            long partLba=u64(h,72),count=u32(h,80),size=u32(h,84);if(size<128||size>4096||count>4096)return m;long table=DATA_START+partLba*BLOCK;if(table>=r.length())return m;r.seek(table);
            for(int i=0;i<count;i++){if(r.getFilePointer()+size>r.length())break;byte[]e=readBytes(r,(int)size);long first=u64(e,32),last=u64(e,40);if(first==0&&last==0)continue;int nameLen=Math.min(72,e.length-56);if(nameLen<=0)continue;String n=new String(e,56,nameLen,StandardCharsets.UTF_16LE).split("\0",2)[0].toLowerCase(Locale.US);if(!n.isEmpty()&&last>=first)m.put(n,last-first+1);}return m;
        }
        static List<Artifact> split(RandomAccessFile r,AP ap,File out,Consumer<String>log)throws Exception {List<Artifact>a=new ArrayList<>();int i=0;while(i<ap.e.size()){Ent first=ap.e.get(i);String key=first.name==null?"":first.name;List<Ent>g=new ArrayList<>();g.add(first);i++;while(i<ap.e.size()&&!ap.e.get(i).name.isEmpty()&&ap.e.get(i).name.equals(key)){g.add(ap.e.get(i));i++;}String n=key.isEmpty()?"part_"+first.id:key;File d=new File(out,String.format(Locale.US,"%03d-%s.img",first.id,safe(n)));try(OutputStream os=new BufferedOutputStream(new FileOutputStream(d))){for(Ent e:g){long off=DATA_START+e.fileOff*BLOCK;r.seek(off);copy(r,os,Math.min(e.fileSize*BLOCK,Math.max(0,r.length()-off)));}}if(d.length()>0){a.add(new Artifact(d,roleFor(d.getName())));log.accept("LG BIN: "+d.getName());}}return a;}
    }

    // ===== OFP MTK + OFP QC ====================================================
    static final class Ofp {
        static final String[][] MTK_KEYS={
            {"67657963787565E837D226B69A495D21","F6C50203515A2CE7D8C3E1F938B7E94C","42F2D5399137E2B2813CD8ECDF2F4D72"},
            {"9E4F32639D21357D37D226B69A495D21","A3D8D358E42F5A9E931DD3917D9A3218","386935399137416B67416BECF22F519A"},
            {"892D57E92A4D8A975E3C216B7C9DE189","D26DF2D9913785B145D18C7219B89F26","516989E4A1BFC78B365C6BC57D944391"},
            {"27827963787265EF89D126B69A495A21","82C50203285A2CE7D8C3E198383CE94C","422DD5399181E223813CD8ECDF2E4D72"},
            {"3C4A618D9BF2E4279DC758CD535147C3","87B13D29709AC1BF2382276C4E8DF232","59B7A8E967265E9BCABE2469FE4A915E"},
            {"1C3288822BF824259DC852C1733127D3","E7918D22799181CF2312176C9E2DF298","3247F889A7B6DECBCA3E28693E4AAAFE"},
            {"1E4F32239D65A57D37D2266D9A775D43","A332D3C3E42F5A3E931DD991729A321D","3F2A35399A373377674155ECF28FD19A"},
            {"122D57E92A518AFF5E3C786B7C34E189","DD6DF2D9543785674522717219989FB0","12698965A132C76136CC88C5DD94EE91"}
        };
        static List<Artifact> extract(File f,File work,Consumer<String>log)throws Exception {
            File out=new File(work,"ofp");out.mkdirs();
            byte[] head=readHead(f,4);
            if(starts(head,"PK\u0003\u0004")){
                return extractEncryptedZip(f,out,log);
            }
            Pair key=bruteMtk(f);
            if(key!=null) return mtk(f,out,key,log);
            Pair qc=bruteQc(f);
            if(qc!=null) return qcExtract(f,out,qc,log);
            throw new IOException("unsupported OFP encryption variant");
        }
        static final class Pair {byte[]k,iv;Pair(byte[]a,byte[]b){k=a;iv=b;}}
        static List<Artifact> extractEncryptedZip(File f,File out,Consumer<String>log)throws Exception {
            final char[] password="flash@realme$50E7F7D847732396F1582CD62DD385ED7ABB0897".toCharArray();
            net.lingala.zip4j.ZipFile z=new net.lingala.zip4j.ZipFile(f,password);
            z.extractAll(out.getAbsolutePath());
            List<Artifact>a=new ArrayList<>();
            for(File x:allFiles(out)){ if(x.isFile()){a.add(new Artifact(x,roleFor(x.getName())));log.accept("OFP QC ZIP: "+x.getName());} }
            if(a.isEmpty()) throw new IOException("encrypted OFP ZIP contains no files");
            return a;
        }
        static Pair bruteMtk(File f)throws Exception {byte[]first=readAt(new RandomAccessFile(f,"r"),0,16);for(String[]t:MTK_KEYS){byte[]obs=hex(t[0]),ek=hex(t[1]),ev=hex(t[2]);byte[]k=md5Ascii(nibbleXor(obs,ek)),iv=md5Ascii(nibbleXor(obs,ev));byte[]p=aesCfb(k,iv,first);if(p.length>=3&&p[0]=='M'&&p[1]=='M'&&p[2]=='M')return new Pair(k,iv);}return null;}
        static List<Artifact>mtk(File f,File out,Pair key,Consumer<String>log)throws Exception {final int HL=0x6c;try(RandomAccessFile r=new RandomAccessFile(f,"r")){long fs=r.length();r.seek(fs-HL);byte[]hdr=new byte[HL];r.readFully(hdr);shuffle(hdr,"geyixue".getBytes(StandardCharsets.US_ASCII));int count=le16(hdr,70);long table=fs-HL-count*0x60L;r.seek(table);List<Artifact>a=new ArrayList<>();for(int i=0;i<count;i++){byte[]e=new byte[0x60];r.readFully(e);long start=u64(e,32),len=u64(e,40),enc=u64(e,48);String name=utf8z(e,56,32);if(name.isEmpty())name="entry_"+i;File d=safeResolve(out,name);d.getParentFile().mkdirs();r.seek(start);try(OutputStream os=new BufferedOutputStream(new FileOutputStream(d))){if(enc>0){byte[]b=new byte[(int)Math.min(enc,Integer.MAX_VALUE)];r.readFully(b);byte[]p=aesCfb(key.k,key.iv,b);os.write(p,0,(int)Math.min(enc,p.length));}long left=Math.max(0,len-enc);while(left>0){int n=(int)Math.min(left,2L*1024*1024);byte[]b=new byte[n];r.readFully(b);os.write(b);left-=n;}}a.add(new Artifact(d,roleFor(d.getName())));log.accept("OFP MTK: "+name);}return a;}}

        static final String[][] QC_KEYS={
            {"V1.4.17/1.4.27","27827963787265EF89D126B69A495A21","82C50203285A2CE7D8C3E198383CE94C","422DD5399181E223813CD8ECDF2E4D72"},
            {"V1.6.17","E11AA7BB558A436A8375FD15DDD4651F","77DDF6A0696841F6B74782C097835169","A739742384A44E8BA45207AD5C3700EA"},
            {"V1.5.13","67657963787565E837D226B69A495D21","F6C50203515A2CE7D8C3E1F938B7E94C","42F2D5399137E2B2813CD8ECDF2F4D72"},
            {"V1.6.6/1.6.9/1.6.17/1.6.24/1.6.26/1.7.6","3C2D518D9BF2E4279DC758CD535147C3","87C74A29709AC1BF2382276C4E8DF232","598D92E967265E9BCABE2469FE4A915E"},
            {"V1.7.2","8FB8FB261930260BE945B841AEFA9FD4","E529E82B28F5A2F8831D860AE39E425D","8A09DA60ED36F125D64709973372C1CF"},
            {"V2.0.3","E8AE288C0192C54BF10C5707E9C4705B","D64FC385DCD52A3C9B5FBA8650F92EDA","79051FD8D8B6297E2E4559E997F63B7F"}
        };
        static Pair bruteQc(File f)throws Exception {for(String[]x:QC_KEYS){byte[]mc=hex(x[1]),uk=hex(x[2]),ivv=hex(x[3]);byte[]k=md5Ascii(deobfuscate(uk,mc)),iv=md5Ascii(deobfuscate(ivv,mc));byte[]xml=qcXml(f,k,iv);if(xml!=null)return new Pair(k,iv);}byte[]mk=asciiHex("42F2D5399137E2B2813CD8ECDF2F4D72"),mu=asciiHex("F6C50203515A2CE7D8C3E198383CE94C"),mc=hex("67657963787565E837D226B69A495D21");byte[]k=md5Ascii(deobfuscate(mu,mc)),iv=md5Ascii(deobfuscate(mk,mc));return qcXml(f,k,iv)!=null?new Pair(k,iv):null;}
        static List<Artifact> qcExtract(File f,File out,Pair key,Consumer<String>log)throws Exception {int page=qcPageSize(f);byte[]xml=qcXml(f,key.k,key.iv);if(xml==null)throw new IOException("QC OFP metadata decrypt failed");Document doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml));List<Artifact>a=new ArrayList<>();for(Element e:elements(doc,"File")){String name=e.hasAttribute("Path")?e.getAttribute("Path"):e.getAttribute("filename");if(name.isEmpty())continue;long start=attrLong(e,"FileOffsetInSrc",-1)*page;long raw=attrLong(e,"SizeInByteInSrc",-1);if(start<0||raw<0||start>=new File(f.getPath()).length())continue;long onDisk=e.hasAttribute("SizeInSectorInSrc")?attrLong(e,"SizeInSectorInSrc",0)*page:raw;File d=safeResolve(out,name);d.getParentFile().mkdirs();try(RandomAccessFile r=new RandomAccessFile(f,"r")){r.seek(start);byte[]first=new byte[(int)Math.min(raw,0x40000)];r.readFully(first);if(first.length>0){byte[]p=aesCfb(key.k,key.iv,pad4(first));try(OutputStream os=new BufferedOutputStream(new FileOutputStream(d))){os.write(p,0,(int)Math.min(raw,p.length));long left=raw-first.length;long src=start+first.length;r.seek(src);copy(r,os,left);}}}a.add(new Artifact(d,roleFor(d.getName())));log.accept("OFP QC: "+name);}return a;}
        static int qcPageSize(File f)throws IOException{long n=f.length();try(RandomAccessFile r=new RandomAccessFile(f,"r")){for(int p:new int[]{0x200,0x1000}){if(n<p)continue;r.seek(n-p+0x10);if(u32(readBytes(r,4),0)==0x7CEF)return p;}}throw new IOException("unknown QC OFP pagesize");}
        static byte[]qcXml(File f,byte[]k,byte[]iv)throws Exception {int page=qcPageSize(f);long n=f.length(), xmlOff=n-page;try(RandomAccessFile r=new RandomAccessFile(f,"r")){r.seek(xmlOff+0x14);long off=u32(readBytes(r,4),0)*page;long len=u32(readBytes(r,4),0);if(len<200)len=Math.max(0,xmlOff-off-0x57);if(off<0||len<=0||off+len>n)return null;r.seek(off);byte[]enc=new byte[(int)Math.min(len,Integer.MAX_VALUE)];r.readFully(enc);byte[]dec=aesCfb(enc,k,iv);int p=indexOf(dec,"<?xml".getBytes(StandardCharsets.US_ASCII),0);return p>=0?Arrays.copyOfRange(dec,p,dec.length):null;}}
    }

    // ===== OPS =================================================================
    static final class Ops {
        // exact data copied from opscrypto.py; the bundled tool uses mbox 5, then 6, then 4.
        static final int[] MBOX5={0x60,0x8a,0x3f,0x2d,0x68,0x6b,0xd4,0x23,0x51,0x0c,0xd0,0x95,0xbb,0x40,0xe9,0x76,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x0a,0x00};
        static final int[] MBOX6={0xaa,0x69,0x82,0x9e,0x5d,0xde,0xb1,0x3d,0x30,0xbb,0x81,0xa3,0x46,0x65,0xa3,0xe1,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x0a,0x00};
        static final int[] MBOX4={0xc4,0x5d,0x05,0x71,0x99,0xdd,0xbb,0xee,0x29,0xa1,0x6d,0xc7,0xad,0xbf,0xa4,0x3f,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x0a,0x00};
        // The Python tool's sbox is an AES T-table. Store it compactly as a 4-byte-word table.
        static final byte[] SBOX=hex(
            "c66363a5c66363a5f87c7c84f87c7c84ee777799ee777799f67b7b8df67b7b8d"+
            "fff2f20dfff2f20dd66b6bbdd66b6bbdde6f6fb1de6f6fb191c5c55491c5c554"+
            "60303050603030500201010302010103ce6767a9ce6767a9562b2b7d562b2b7d"+
            "e7fefe19e7fefe19b5d7d762b5d7d7624dababe64dababe6ec76769aec76769a"+
            "8fcaca458fcaca451f82829d1f82829d89c9c94089c9c940fa7d7d87fa7d7d87"+
            "effafa15effafa15b25959ebb25959eb8e4747c98e4747c9fbf0f00bfbf0f00b"+
            "41adadec41adadecb3d4d467b3d4d4675fa2a2fd5fa2a2fd45afafea45afafea"+
            "239c9cbf239c9cbf53a4a4f753a4a4f7e4727296e47272969bc0c05b9bc0c05b"+
            "75b7b7c275b7b7c2e1fdfd1ce1fdfd1c3d9393ae3d9393ae4c26266a4c26266a"+
            "6c36365a6c36365a7e3f3f417e3f3f41f5f7f702f5f7f70283cccc4f83cccc4f"+
            "6834345c6834345c51a5a5f451a5a5f4d1e5e534d1e5e534f9f1f108f9f1f108"+
            "e2717193e2717193abd8d873abd8d87362313153623131532a15153f2a15153f"+
            "0804040c0804040c95c7c75295c7c75246232365462323659dc3c35e9dc3c35e"+
            "3018182830181828379696a1379696a10a05050f0a05050f2f9a9ab52f9a9ab5"+
            "0e0707090e07070924121236241212361b80809b1b80809bdfe2e23ddfe2e23d"+
            "cdebeb26cdebeb264e2727694e2727697fb2b2cd7fb2b2cdea75759fea75759f"+
            "1209091b1209091b1d83839e1d83839e582c2c74582c2c74341a1a2e341a1a2e"+
            "361b1b2d361b1b2ddc6e6eb2dc6e6eb2b45a5aeeb45a5aee5ba0a0fb5ba0a0fb"+
            "a45252f6a45252f6763b3b4d763b3b4db7d6d661b7d6d6617db3b3ce7db3b3ce"+
            "5229297b5229297bdde3e33edde3e33e5e2f2f715e2f2f711384849713848497"+
            "a65353f5a65353f5b9d1d168b9d1d1680000000000000000c1eded2cc1eded2c"+
            "4020206040202060e3fcfc1fe3fcfc1f79b1b1c879b1b1c8b65b5bedb65b5bed"+
            "d46a6abed46a6abe8dcbcb468dcbcb4667bebed967bebed97239394b7239394b"+
            "944a4ade944a4ade984c4cd4984c4cd4b05858e8b05858e885cfcf4a85cfcf4a"+
            "bbd0d06bbbd0d06bc5efef2ac5efef2a4faaaae54faaaae5edfbfb16edfbfb16"+
            "864343c5864343c59a4d4dd79a4d4dd766333355663333551185859411858594"+
            "8a4545cf8a4545cfe9f9f910e9f9f9100402020604020206fe7f7f81fe7f7f81"+
            "a05050f0a05050f0783c3c44783c3c44259f9fba259f9fba4ba8a8e34ba8a8e3"+
            "a25151f3a25151f35da3a3fe5da3a3fe804040c0804040c0058f8f8a058f8f8a"+
            "3f9292ad3f9292ad219d9dbc219d9dbc7038384870383848f1f5f504f1f5f504"+
            "63bcbcdf63bcbcdf77b6b6c177b6b6c1afdada75afdada754221216342212163"+
            "2010103020101030e5ffff1ae5ffff1afdf3f30efdf3f30ebfd2d26dbfd2d26d"+
            "81cdcd4c81cdcd4c180c0c14180c0c142613133526131335c3ecec2fc3ecec2f"+
            "be5f5fe1be5f5fe1359797a2359797a2884444cc884444cc2e1717392e171739"+
            "93c4c45793c4c45755a7a7f255a7a7f2fc7e7e82fc7e7e827a3d3d477a3d3d47"+
            "c86464acc86464acba5d5de7ba5d5de73219192b3219192be6737395e6737395"+
            "c06060a0c06060a019818198198181989e4f4fd19e4f4fd1a3dcdc7fa3dcdc7f"+
            "4422226644222266542a2a7e542a2a7e3b9090ab3b9090ab0b8888830b888883"+
            "8c4646ca8c4646cac7eeee29c7eeee296bb8b8d36bb8b8d32814143c2814143c"+
            "a7dede79a7dede79bc5e5ee2bc5e5ee2160b0b1d160b0b1daddbdb76addbdb76"+
            "dbe0e03bdbe0e03b6432325664323256743a3a4e743a3a4e140a0a1e140a0a1e"+
            "924949db924949db0c06060a0c06060a4824246c4824246cb85c5ce4b85c5ce4"+
            "9fc2c25d9fc2c25dbdd3d36ebdd3d36e43acacef43acacefc46262a6c46262a6"+
            "399191a8399191a8319595a4319595a4d3e4e437d3e4e437f279798bf279798b"+
            "d5e7e732d5e7e7328bc8c8438bc8c8436e3737596e373759da6d6db7da6d6db7"+
            "018d8d8c018d8d8cb1d5d564b1d5d5649c4e4ed29c4e4ed249a9a9e049a9a9e0"+
            "d86c6cb4d86c6cb4ac5656faac5656faf3f4f407f3f4f407cfeaea25cfeaea25"+
            "ca6565afca6565aff47a7a8ef47a7a8e47aeaee947aeaee91008081810080818"+
            "6fbabad56fbabad5f0787888f07878884a25256f4a25256f5c2e2e725c2e2e72"+
            "381c1c24381c1c2457a6a6f157a6a6f173b4b4c773b4b4c797c6c65197c6c651"+
            "cbe8e823cbe8e823a1dddd7ca1dddd7ce874749ce874749c3e1f1f213e1f1f21"+
            "964b4bdd964b4bdd61bdbddc61bdbddc0d8b8b860d8b8b860f8a8a850f8a8a85"+
            "e0707090e07070907c3e3e427c3e3e4271b5b5c471b5b5c4cc6666aacc6666aa"+
            "904848d8904848d80603030506030305f7f6f601f7f6f6011c0e0e121c0e0e12"+
            "c26161a3c26161a36a35355f6a35355fae5757f9ae5757f969b9b9d069b9b9d0"+
            "178686911786869199c1c15899c1c1583a1d1d273a1d1d27279e9eb9279e9eb9"+
            "d9e1e138d9e1e138ebf8f813ebf8f8132b9898b32b9898b32211113322111133"+
            "d26969bbd26969bba9d9d970a9d9d970078e8e89078e8e89339494a7339494a7"+
            "2d9b9bb62d9b9bb63c1e1e223c1e1e221587879215878792c9e9e920c9e9e920"+
            "87cece4987cece49aa5555ffaa5555ff5028287850282878a5dfdf7aa5dfdf7a"+
            "038c8c8f038c8c8f59a1a1f859a1a1f809898980098989801a0d0d171a0d0d17"+
            "65bfbfda65bfbfdad7e6e631d7e6e631844242c6844242c6d06868b8d06868b8"+
            "824141c3824141c3299999b0299999b05a2d2d775a2d2d771e0f0f111e0f0f11"+
            "7bb0b0cb7bb0b0cba85454fca85454fc6dbbbbd66dbbbbd62c16163a2c16163a"
        );
        static byte[] BASE_KEY={ (byte)0xd1,(byte)0xb5,(byte)0xe3,(byte)0x9e,(byte)0x5e,(byte)0xea,(byte)0x04,(byte)0x9d,(byte)0x67,(byte)0x1d,(byte)0xd5,(byte)0xab,(byte)0xd2,(byte)0xaf,(byte)0xcb,(byte)0xaf };
        static int leWord(byte[]x,int o){return (x[o]&255)|((x[o+1]&255)<<8)|((x[o+2]&255)<<16)|((x[o+3]&255)<<24);}
        static byte[] wordsToBytes(int[]w){byte[]b=new byte[w.length*4];for(int i=0;i<w.length;i++){int x=w[i];b[i*4]=(byte)x;b[i*4+1]=(byte)(x>>>8);b[i*4+2]=(byte)(x>>>16);b[i*4+3]=(byte)(x>>>24);}return b;}
        static long gs(int off){
            if(off<0||off>=SBOX.length)return 0L;
            long v=0;
            for(int i=0;i<4&&off+i<SBOX.length;i++) v |= (long)(SBOX[off+i]&255)<<(8*i);
            return v & 0xffffffffL;
        }
        static long u32(long v){ return v & 0xffffffffL; }
        static long[] keyUpdate(long[] iv,int[] sb){
            long d=u32(iv[0]^sb[0]), a=u32(iv[1]^sb[1]), b=u32(iv[2]^sb[2]), c=u32(iv[3]^sb[3]);
            long e=u32(gs((int)(((b>>>16)&255)*8+2)) ^ gs((int)(((a>>>8)&255)*8+3)) ^ gs((int)(((c>>>24)&255)*8+1)) ^ gs((int)((d&255)*8)) ^ sb[4]);
            long h=u32(gs((int)(((c>>>16)&255)*8+2)) ^ gs((int)(((b>>>8)&255)*8+3)) ^ gs((int)(((d>>>24)&255)*8+1)) ^ gs((int)((a&255)*8)) ^ sb[5]);
            long i=u32(gs((int)(((d>>>16)&255)*8+2)) ^ gs((int)(((c>>>8)&255)*8+3)) ^ gs((int)(((a>>>24)&255)*8+1)) ^ gs((int)((b&255)*8)) ^ sb[6]);
            a=u32(gs((int)(((d>>>8)&255)*8+3)) ^ gs((int)(((a>>>16)&255)*8+2)) ^ gs((int)(((b>>>24)&255)*8+1)) ^ gs((int)((c&255)*8)) ^ sb[7]);
            int g=8;
            for(int f=0;f<((sb[0x3c]&255)-2);f++){
                long dd=e>>>24, m=h>>>16, ss=h>>>24, z=e>>>16, l=i>>>24, t=e>>>8;
                e=u32(gs((int)(((i>>>16)&255)*8+2)) ^ gs((int)(((h>>>8)&255)*8+3)) ^ gs((int)(((a>>>24)&255)*8+1)) ^ gs((int)((e&255)*8)) ^ sb[g]);
                h=u32(gs((int)(((a>>>16)&255)*8+2)) ^ gs((int)(((i>>>8)&255)*8+3)) ^ gs((int)((dd&255)*8+1)) ^ gs((int)((h&255)*8)) ^ sb[g+1]);
                i=u32(gs((int)((z&255)*8+2)) ^ gs((int)(((a>>>8)&255)*8+3)) ^ gs((int)((ss&255)*8+1)) ^ gs((int)((i&255)*8)) ^ sb[g+2]);
                a=u32(gs((int)((t&255)*8+3)) ^ gs((int)((m&255)*8+2)) ^ gs((int)((l&255)*8+1)) ^ gs((int)((a&255)*8)) ^ sb[g+3]);
                g+=4;
            }
            return new long[]{
                u32((gs((int)(((i>>>16)&255)*8))&0xff0000L) ^ (gs((int)(((h>>>8)&255)*8+1))&0xff00L) ^ (gs((int)(((a>>>24)&255)*8+3))&0xff000000L) ^ (gs((int)((e&255)*8+2))&0xffL) ^ sb[g]),
                u32((gs((int)(((a>>>16)&255)*8))&0xff0000L) ^ (gs((int)(((i>>>8)&255)*8+1))&0xff00L) ^ (gs((int)(((e>>>24)&255)*8+3))&0xff000000L) ^ (gs((int)((h&255)*8+2))&0xffL) ^ sb[g+3]),
                u32((gs((int)(((e>>>16)&255)*8))&0xff0000L) ^ (gs((int)(((a>>>8)&255)*8+1))&0xff00L) ^ (gs((int)(((h>>>24)&255)*8+3))&0xff000000L) ^ (gs((int)((i&255)*8+2))&0xffL) ^ sb[g+2]),
                u32((gs((int)(((h>>>16)&255)*8))&0xff0000L) ^ (gs((int)(((e>>>8)&255)*8+1))&0xff00L) ^ (gs((int)(((i>>>24)&255)*8+3))&0xff000000L) ^ (gs((int)((a&255)*8+2))&0xffL) ^ sb[g+1])
            };
        }
        static long wordAt(byte[] b,int o){
            long v=0;
            for(int k=0;k<4;k++) if(o+k<b.length) v|=(long)(b[o+k]&255)<<(8*k);
            return v&0xffffffffL;
        }
        static byte[] custom(byte[]in,long[]rkey,int outLen,int[]mbox){
            ByteArrayOutputStream os=new ByteArrayOutputStream();
            byte[] tmp=Arrays.copyOf(in,in.length);
            int pos=outLen, ptr=0, length=tmp.length;
            if(outLen!=0){
                while(pos<rkey.length && length>0){
                    int v=(int)((rkey[pos] ^ (tmp[pos]&255))&255L);
                    os.write(v); rkey[pos]=tmp[pos]&255L; length--; pos++;
                }
            }
            int loopLength=length;
            if(length>0xF){
                for(ptr=0;ptr<loopLength;ptr+=0x10){
                    rkey=keyUpdate(rkey,mbox);
                    if(pos<0x10){
                        int slen=((0xf-pos)>>2)+1; long[] next=new long[slen];
                        for(int q=0;q<slen;q++){
                            long data=wordAt(tmp,ptr+pos+q*4), v=u32(data^rkey[q]);
                            writeWord(os,(int)v); next[q]=data;
                        }
                        rkey=next;
                    }
                    length-=0x10;
                }
            }
            if(length!=0){
                int[] sbx=new int[SBOX.length];for(int si=0;si<SBOX.length;si++)sbx[si]=SBOX[si]&255;
                rkey=keyUpdate(rkey,sbx);
                int j=pos,m=0;
                while(length>0){
                    long data=wordAt(tmp,j+ptr),v=u32(data^rkey[m]);
                    writeWord(os,(int)v); rkey[m]=data; length-=4; j+=4; m++;
                }
            }
            return os.toByteArray();
        }
        // The desktop tool's decryption path only needs the first metadata stage; it always uses pos=0,
        // which means the transform can be implemented as a block stream with the exact Python state rules.
        static void writeWord(ByteArrayOutputStream os,int v){os.write(v);os.write(v>>>8);os.write(v>>>16);os.write(v>>>24);}

        static List<Artifact> extract(File f,File work,Consumer<String>log)throws Exception {
            File out=new File(work,"ops");out.mkdirs();String xml=null;int[] used=null;for(int[]mb:new int[][]{MBOX5,MBOX6,MBOX4}){byte[]enc=readSettingsBlob(f);byte[]dec=custom(enc,bytesToLongWords(BASE_KEY),0,mb);int p=indexOf(dec,"xml ".getBytes(StandardCharsets.US_ASCII),0);if(p<0)p=indexOf(dec,"<?xml".getBytes(StandardCharsets.US_ASCII),0);if(p>=0){xml=new String(dec,p,stripNulLength(dec,p),StandardCharsets.UTF_8);used=mb;break;}}if(xml==null)throw new IOException("unsupported OPS encryption key");Document doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));List<Artifact>a=new ArrayList<>();try(RandomAccessFile r=new RandomAccessFile(f,"r")){for(Element e:elements(doc,"File")){String name=e.hasAttribute("Path")?e.getAttribute("Path"):e.getAttribute("filename");if(name.isEmpty())continue;long start=attrLong(e,"FileOffsetInSrc",-1)*0x200L;long length=attrLong(e,"SizeInByteInSrc",-1);if(start<0||length<0||start>=r.length())continue;File d=safeResolve(out,name);d.getParentFile().mkdirs();r.seek(start);byte[]first=new byte[(int)Math.min(length,0x40000)];r.readFully(first);boolean decrypt=true;String tag=e.getParentNode().getNodeName();if("UFS_PROVISION".equals(tag)||tag.contains("Program")){decrypt=false;}try(OutputStream os=new BufferedOutputStream(new FileOutputStream(d))){if(decrypt&&first.length>0){byte[]p=custom(first,bytesToLongWords(BASE_KEY),0,used);os.write(p,0,(int)Math.min(length,p.length));}else os.write(first,0,first.length);long left=length-first.length;while(left>0){int n=(int)Math.min(left,2L*1024*1024);byte[]b=new byte[n];r.readFully(b);os.write(b);left-=n;}}a.add(new Artifact(d,roleFor(d.getName())));log.accept("OPS: "+name);}}return a;}
        static byte[] readSettingsBlob(File f)throws IOException{long n=f.length();if(n<0x200)throw new IOException("OPS too small");try(RandomAccessFile r=new RandomAccessFile(f,"r")){r.seek(n-0x200);byte[]h=new byte[0x200];r.readFully(h);int len=(int)FirmwareToolset.u32(h,0x18);if(len<=0||len>0x100000)throw new IOException("bad OPS settings length");long pad=0x200-(len%0x200);if(pad==0x200)pad=0;r.seek(n-0x200-(len+pad));byte[]enc=new byte[(int)(len+pad)];r.readFully(enc);return enc;}}
        static long[]bytesToLongWords(byte[]b){long[]w=new long[4];for(int i=0;i<4;i++)w[i]=leWord(b,i*4)&0xffffffffL;return w;}
    }

    // ===== RFS (port of rfs2zip.py) ==========================================
    static final class Rfs {
        static final int ATTR_DIR = 0x10, ATTR_VOL = 0x08, ATTR_LFN = 0x0f, ATTR_EXT = 0xcf;
        static final byte[] SLH = {(byte)0x99,(byte)0x86,(byte)0xa7,(byte)0xdf}, SLT = {(byte)0xcd,(byte)0x8a,(byte)0xb1,(byte)0x83};
        static final long SCAN_LIMIT = 8L << 20;

        static final class BPB { int bps, spc, res, nf, rootent, spf, type, root; long total, dataStart, clusters; }
        static final class Ent {
            String name; int attr, cluster; long size; int[] mtime; byte[] ext;
            boolean dir() { return (attr & ATTR_DIR) != 0; }
        }

        // ---- boot sector -----------------------------------------------------
        static BPB parseBpb(byte[] b, int o) {
            if (b.length < o + 512 || b[o + 510] != 0x55 || b[o + 511] != (byte)0xaa) return null;
            int bps = le16(b, o + 11), spc = b[o + 13] & 255, res = le16(b, o + 14), nf = b[o + 16] & 255,
                rootent = le16(b, o + 17), tot16 = le16(b, o + 19), media = b[o + 21] & 255, spf16 = le16(b, o + 22);
            long tot32 = u32(b, o + 32);
            if (bps < 512 || (bps & (bps - 1)) != 0) return null;
            if (spc == 0 || (spc & (spc - 1)) != 0 || spc > 128) return null;
            if (res < 1 || nf < 1 || nf > 4 || media < 0xf0) return null;
            boolean fat32 = spf16 == 0 && rootent == 0;
            long spf = fat32 ? u32(b, o + 36) : spf16, total = tot16 != 0 ? tot16 : tot32;
            if (spf == 0 || total == 0 || spf > (1 << 24)) return null;
            long rootSecs = ((long) rootent * 32 + bps - 1) / bps, dataStart = res + nf * spf + rootSecs;
            if (dataStart >= total) return null;
            BPB p = new BPB(); p.bps = bps; p.spc = spc; p.res = res; p.nf = nf; p.rootent = rootent; p.spf = (int) spf;
            p.total = total; p.dataStart = dataStart; p.clusters = (total - dataStart) / spc;
            p.type = fat32 ? 32 : (p.clusters < 4085 ? 12 : 16); p.root = fat32 ? (int) u32(b, o + 44) : 0;
            return p;
        }
        /** Byte offset of the filesystem (0 or a nearby boot sector) or -1. */
        static long findFs(RandomAccessFile r) throws IOException {
            int n = (int) Math.min(r.length(), SCAN_LIMIT); byte[] head = readAt(r, 0, n);
            for (int off = 0; off + 512 <= n; off += 512)
                if (head[off + 510] == 0x55 && head[off + 511] == (byte)0xaa && parseBpb(head, off) != null) return off;
            return -1;
        }
        static boolean probe(File f) {
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) { return findFs(r) >= 0; } catch (IOException e) { return false; }
        }

        // ---- FAT ---------------------------------------------------------------
        static final class Fat {
            final RandomAccessFile r; final long base; final BPB p; final int cl, eoc; final long maxc; final byte[] fat; long shortBytes;
            Fat(RandomAccessFile r, long base, BPB p) throws IOException {
                this.r = r; this.base = base; this.p = p; cl = p.bps * p.spc;
                fat = read((long) p.res * p.bps, (int) Math.min((long) p.spf * p.bps, Integer.MAX_VALUE));
                eoc = p.type == 12 ? 0xff8 : p.type == 16 ? 0xfff8 : 0x0ffffff8; maxc = p.clusters + 1;
            }
            byte[] read(long off, int n) throws IOException {
                long at = base + off, len = r.length();
                if (at >= len) return new byte[0];
                int k = (int) Math.min(n, len - at); byte[] b = new byte[k]; r.seek(at); r.readFully(b); return b;
            }
            long clusterOff(int c) { return (p.dataStart + (long) (c - 2) * p.spc) * p.bps; }
            int next(int c) {
                if (p.type == 16) { int o = c * 2; return o + 2 <= fat.length ? le16(fat, o) : eoc; }
                if (p.type == 32) { long o = (long) c * 4; return o + 4 <= fat.length ? (int) (u32(fat, (int) o) & 0x0fffffffL) : eoc; }
                int o = c + c / 2; if (o + 2 > fat.length) return eoc;
                int v = le16(fat, o); return (c & 1) != 0 ? (v >>> 4) : (v & 0xfff);
            }
            List<Integer> chain(int c) {
                List<Integer> out = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
                while (c >= 2 && c <= maxc && c < eoc - 1 && seen.add(c)) { out.add(c); c = next(c); }
                return out;
            }
            byte[] readChain(List<Integer> chain) throws IOException {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                for (int c : chain) {
                    byte[] d = read(clusterOff(c), cl);
                    if (d.length < cl) { shortBytes += cl - d.length; d = Arrays.copyOf(d, cl); }
                    o.write(d, 0, d.length);
                }
                return o.toByteArray();
            }
            byte[] rootDir() throws IOException {
                if (p.type == 32) return readChain(chain(p.root));
                return read((long) (p.res + (long) p.nf * p.spf) * p.bps, p.rootent * 32);
            }
            void stream(OutputStream out, List<Integer> chain, long size) throws IOException {
                long written = 0; int i = 0;
                while (written < size && i < chain.size()) {
                    int j = i;
                    while (j + 1 < chain.size() && chain.get(j + 1) == chain.get(j) + 1 && (long) (j - i + 1) * cl < (1 << 20)) j++;
                    int n = (int) Math.min((long) (j - i + 1) * cl, size - written);
                    byte[] d = read(clusterOff(chain.get(i)), n);
                    if (d.length < n) { shortBytes += n - d.length; d = Arrays.copyOf(d, n); }
                    out.write(d, 0, n); written += n; i = j + 1;
                }
                while (written < size) { int n = (int) Math.min(65536, size - written); out.write(new byte[n]); shortBytes += n; written += n; }
            }
        }

        // ---- directories -------------------------------------------------------
        static String cp437(byte[] b, int o, int n) {
            try { return new String(b, o, n, "IBM437"); } catch (Exception e) { return new String(b, o, n, StandardCharsets.ISO_8859_1); }
        }
        static String shortName(byte[] e) {
            byte[] base = Arrays.copyOfRange(e, 0, 8); if ((base[0] & 255) == 0x05) base[0] = (byte)0xe5;
            String b = cp437(base, 0, 8).replaceAll(" +$", ""), x = cp437(e, 8, 3).replaceAll(" +$", "");
            return b + (x.isEmpty() ? "" : "." + x);
        }
        static int[] dosTime(int date, int tm) {
            int y = 1980 + (date >> 9), mo = (date >> 5) & 15, d = date & 31, h = tm >> 11, mi = (tm >> 5) & 63, s = (tm & 31) * 2;
            if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 59) return new int[]{1980, 1, 1, 0, 0, 0};
            return new int[]{y, mo, d, h, mi, s};
        }
        static List<Ent> parseDir(byte[] raw) {
            List<Ent> out = new ArrayList<>(); TreeMap<Integer, byte[]> lfn = new TreeMap<>(); Ent last = null;
            for (int i = 0; i + 32 <= raw.length; i += 32) {
                byte[] e = Arrays.copyOfRange(raw, i, i + 32);
                if (e[0] == 0) break;
                if ((e[0] & 255) == 0xe5) { lfn.clear(); last = null; continue; }
                int attr = e[11] & 255;
                if (attr == ATTR_LFN) {
                    byte[] part = new byte[26]; System.arraycopy(e, 1, part, 0, 10); System.arraycopy(e, 14, part, 10, 12); System.arraycopy(e, 28, part, 22, 4);
                    lfn.put(e[0] & 0x1f, part);
                } else if (attr == ATTR_EXT) {
                    if (last != null && last.ext == null) last.ext = e;
                } else if ((attr & ATTR_VOL) != 0) { lfn.clear(); last = null; }
                else {
                    String name;
                    if (!lfn.isEmpty()) {
                        ByteArrayOutputStream bb = new ByteArrayOutputStream();
                        for (byte[] x : lfn.values()) bb.write(x, 0, x.length);
                        name = new String(bb.toByteArray(), StandardCharsets.UTF_16LE);
                        int z = name.indexOf('\0'); if (z >= 0) name = name.substring(0, z);
                        name = name.replace("\uffff", "");
                    } else name = shortName(e);
                    Ent en = new Ent(); en.name = name; en.attr = attr;
                    en.cluster = le16(e, 26) | (le16(e, 20) << 16); en.size = u32(e, 28);
                    en.mtime = dosTime(le16(e, 24), le16(e, 22));
                    last = en; lfn.clear(); out.add(en);
                }
            }
            out.removeIf(x -> x.name.isEmpty() || x.name.equals(".") || x.name.equals(".."));
            return out;
        }
        static int mode(Ent e) { return e.ext != null ? le16(e.ext, 14) & 07777 : (e.dir() ? 0755 : 0644); }
        static String parseSymlink(byte[] p, long size) {
            if (size < 18 || p.length < size || !Arrays.equals(Arrays.copyOfRange(p, 0, 4), SLH)
                || !Arrays.equals(Arrays.copyOfRange(p, (int) size - 4, (int) size), SLT)) return null;
            int n = le16(p, 6); if (18 + 2L * n > size) return null;
            return new String(p, 8, 2 * n, StandardCharsets.UTF_16LE);
        }

        // ---- zip output ----------------------------------------------------------
        static ZipArchiveEntry entry(String path, int[] t, int unixMode) {
            ZipArchiveEntry ze = new ZipArchiveEntry(path);
            ze.setUnixMode(unixMode);
            Calendar c = Calendar.getInstance(); c.clear(); c.set(t[0], t[1] - 1, t[2], t[3], t[4], t[5]); ze.setTime(c.getTimeInMillis());
            return ze;
        }
        static File toZip(File image, File work, Consumer<String> log) throws Exception { return toZip(image, work, null, log); }
        /** @param partName top-level folder inside the zip; defaults to the image name without extension */
        static File toZip(File image, File work, String partName, Consumer<String> log) throws Exception {
            String top = partName != null && !partName.isEmpty() ? partName : safe(stripExt(image.getName()));
            File out = new File(work, safe(stripExt(image.getName())) + ".zip");
            try (RandomAccessFile r = new RandomAccessFile(image, "r")) {
                long base = findFs(r);
                if (base < 0) throw new IOException("no FAT/RFS boot sector");
                BPB p = parseBpb(readAt(r, base, 512), 0);
                Fat fs = new Fat(r, base, p);
                log.accept("RFS/FAT" + p.type + ": sector=" + p.bps + " cluster=" + fs.cl + " clusters=" + p.clusters + " fs_offset=" + base);
                int[] zero = {1980, 1, 1, 0, 0, 0};
                try (ZipArchiveOutputStream z = new ZipArchiveOutputStream(out)) {
                    z.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded);
                    ZipArchiveEntry root = entry(top + "/", zero, 040755); z.putArchiveEntry(root); z.closeArchiveEntry();
                    int[] links = {0};
                    walk(fs, parseDir(fs.rootDir()), z, top, 0, new HashSet<Integer>(), links);
                    if (links[0] > 0) log.accept("RFS: " + links[0] + " symlink(s)");
                }
                if (fs.shortBytes > 0) log.accept("RFS: image is truncated; " + fs.shortBytes + " bytes were missing and zero-filled");
            }
            log.accept("RFS -> " + out.getName());
            return out;
        }
        static void walk(Fat fs, List<Ent> ents, ZipArchiveOutputStream z, String prefix, int depth, Set<Integer> visited, int[] links) throws IOException {
            for (Ent e : ents) {
                String path = prefix + "/" + e.name.replace('/', '_'); int mode = mode(e);
                List<Integer> chain = e.cluster >= 2 ? fs.chain(e.cluster) : new ArrayList<Integer>();
                if (e.dir()) {
                    z.putArchiveEntry(entry(path + "/", e.mtime, 040000 | mode)); z.closeArchiveEntry();
                    if (chain.isEmpty() || depth > 64 || !visited.add(chain.get(0))) continue;
                    walk(fs, parseDir(fs.readChain(chain)), z, path, depth + 1, visited, links);
                    continue;
                }
                String target = null;
                if (e.size >= 18 && e.size <= 1100 && !chain.isEmpty()) {
                    byte[] head = fs.readChain(chain.subList(0, 1));
                    if (e.size > fs.cl) head = fs.readChain(chain);
                    target = parseSymlink(head, e.size);
                }
                if (target != null) {
                    z.putArchiveEntry(entry(path, e.mtime, 0120000 | 0777)); z.write(target.getBytes(StandardCharsets.UTF_8)); z.closeArchiveEntry(); links[0]++;
                } else {
                    z.putArchiveEntry(entry(path, e.mtime, 0100000 | mode)); fs.stream(z, chain, e.size); z.closeArchiveEntry();
                }
            }
        }
    }

    // ===== SquashFS 4 =========================================================
    /** Receives the content of a SquashFS image, see [walkSquashFs]. Paths are relative to the root of the image. */
    public interface SquashVisitor {
        void dir(String path,int mode)throws Exception;
        void link(String path,String target)throws Exception;
        /** Returns the stream that receives the file's bytes (it is closed afterwards), or null to skip the file. */
        OutputStream file(String path,int mode,long size)throws Exception;
        /** Called after the stream returned by file() has been closed. */
        void fileEnd(String path)throws Exception;
    }
    /** Walks a SquashFS 4 image without extracting it, so that the caller can hash, compare or store single files. */
    public static void walkSquashFs(File image,Consumer<String>log,SquashVisitor v)throws Exception{
        try(SquashReader s=new SquashReader(image,log)){s.walkVisit(v);}
    }
    /** True if the file starts with a SquashFS 4 superblock (either byte order). */
    public static boolean isSquashFs(File f){
        try(InputStream i=new FileInputStream(f)){byte[]h=new byte[4];int n=0;while(n<4){int k=i.read(h,n,4-n);if(k<=0)return false;n+=k;}
            return (h[0]=='h'&&h[1]=='s'&&h[2]=='q'&&h[3]=='s')||(h[0]=='s'&&h[1]=='q'&&h[2]=='s'&&h[3]=='h');}
        catch(IOException e){return false;}
    }
    static final class SquashFs {
        static File toZip(File f,File work,Consumer<String>log)throws Exception{File out=new File(work,safe(stripExt(f.getName()))+".zip");try(SquashReader s=new SquashReader(f,log)){s.writeZip(out,f.getName());}return out;}
    }
    static final class SquashReader implements Closeable {
        static final int META=8192,NO_FRAG=0xffffffff,UNCOMP=1<<24;RandomAccessFile r;Consumer<String>log;long base,imgEnd,bytesUsed,inodeTable,dirTable,fragTable,idTable,xattrTable,lookupTable,rootRef;int block,compression;boolean be;byte[]inodes,dirs;Map<Long,Integer>imap,dmap;List<Frag>frags=new ArrayList<>();
        SquashReader(File f,Consumer<String>l)throws Exception{r=new RandomAccessFile(f,"r");log=l;find();load();}
        void find()throws IOException{
            final int CH=1<<20,SB=96;long len=r.length(),limit=Math.min(len,64L<<20);byte[]buf=new byte[CH+SB];
            for(long start=0;start<limit;start+=CH){
                int n=(int)Math.min(CH+SB,len-start);if(n<SB)break;r.seek(start);r.readFully(buf,0,n);
                for(int i=0;i<CH&&i+SB<=n;i++){
                    int m=le32(buf,i);if(m!=0x73717368&&m!=0x68737173)continue;
                    boolean b=(m==0x68737173); // "sqsh" read little-endian = big-endian filesystem
                    int verMaj=u16(buf,i+28,b),bs=le32(buf,i+12,b),blog=u16(buf,i+22,b),comp=u16(buf,i+20,b);
                    if(verMaj==4&&bs>=4096&&bs<=1<<20&&(bs&(bs-1))==0&&bs==(1<<blog)&&comp>=1&&comp<=6){base=start+i;be=b;return;}
                }
            }
            throw new IOException("no SquashFS 4.0 superblock (only SquashFS 4.x is supported)");
        }
        void load()throws Exception{r.seek(base);byte[]s=new byte[96];r.readFully(s);int[]v=new int[5];for(int i=0;i<5;i++)v[i]=le32(s,i*4,be);block=v[3];compression=le16(s,20,be);rootRef=u64(s,32,be);bytesUsed=u64(s,40,be);inodeTable=u64(s,64,be);dirTable=u64(s,72,be);fragTable=u64(s,80,be);idTable=u64(s,48,be);xattrTable=u64(s,56,be);lookupTable=u64(s,88,be);r.seek(r.length());imgEnd=r.length()-base;MapTable it=loadMeta(inodeTable);inodes=it.buf;imap=it.map;MapTable dt=loadMeta(dirTable);dirs=dt.buf;dmap=dt.map;int fc=le32(s,16,be);if(fc>0&&fragTable!=0xffffffffffffffffL){long ptrs=(fc+511L)/512;byte[]pb=readAt(base+fragTable,(int)Math.min(ptrs*8,Integer.MAX_VALUE));for(int j=0;j<ptrs;j++){byte[]f=meta(u64(pb,j*8,be));int cnt=(int)Math.min(512,fc-j*512L);for(int k=0;k<cnt&&(k+1)*16<=f.length;k++){frags.add(new Frag(u64(f,k*16,be),le32(f,k*16+8,be)));}}}log.accept("SquashFS: block="+block+" compression="+compression+" endian="+(be?"BE":"LE"));}
        static final int SeekOrigin_END=2;
        MapTable loadMeta(long start)throws Exception{long end=imgEnd;long[]c={bytesUsed,fragTable,inodeTable,dirTable,idTable,xattrTable,lookupTable,0xffffffffffffffffL};for(long x:c)if(x!=0xffffffffffffffffL&&x>start&&x<=imgEnd)end=Math.min(end,x);ByteArrayOutputStream buf=new ByteArrayOutputStream();Map<Long,Integer>map=new HashMap<>();long off=start;while(off<end){map.put(off-start,buf.size());MetaBlock mb;try{mb=metaAt(off);}catch(Exception ex){if(buf.size()>0){map.remove(off-start);break;}throw ex;}buf.write(mb.data,0,mb.data.length);off=mb.next;}return new MapTable(buf.toByteArray(),map);}
        MetaBlock metaAt(long off)throws Exception{byte[]h=readAt(base+off,2);int v=le16(h,0,be),sz=v&0x7fff;if(sz==0)throw new IOException("bad SquashFS metadata block");byte[]raw=readAt(base+off+2,sz);byte[]d=(v&0x8000)!=0?raw:decomp(raw,META);return new MetaBlock(d,off+2+sz);}
        byte[]meta(long off)throws Exception{return metaAt(off).data;}
        byte[]decomp(byte[]raw,int expected)throws Exception{switch(compression){
            case 1:{java.util.zip.Inflater inf=new java.util.zip.Inflater();try{inf.setInput(raw);ByteArrayOutputStream o=new ByteArrayOutputStream(Math.max(expected,8192));byte[]b=new byte[8192];while(!inf.finished()){int n=inf.inflate(b);if(n>0){o.write(b,0,n);continue;}if(inf.needsDictionary())throw new IOException("SquashFS zlib needs a preset dictionary");if(inf.needsInput())break;}return o.toByteArray();}catch(java.util.zip.DataFormatException ex){throw new IOException("SquashFS zlib block is corrupt: "+ex.getMessage(),ex);}finally{inf.end();}}
            case 2:try(InputStream in=new LZMACompressorInputStream(new ByteArrayInputStream(raw))){return in.readAllBytes();}
            case 3:{LzoDecompressor d=LzoLibrary.getInstance().newDecompressor(LzoAlgorithm.LZO1X,null);byte[]out=new byte[Math.max(expected,1)];lzo_uintp n=new lzo_uintp(out.length);int code=d.decompress(raw,0,raw.length,out,0,n);if(code!=0)throw new IOException("SquashFS LZO error "+code);return Arrays.copyOf(out,(int)n.value);}
            case 4:try(InputStream in=new XZCompressorInputStream(new ByteArrayInputStream(raw))){return in.readAllBytes();}
            case 5:return lz4Block(raw,expected);
            case 6:{byte[]out=new byte[Math.max(expected,1)];long n=Zstd.decompress(out,raw);if(Zstd.isError(n))throw new IOException("SquashFS Zstd error: "+Zstd.getErrorName(n));return Arrays.copyOf(out,(int)n);}
            default:throw new IOException("SquashFS compression "+compression+" is not supported");}}
        static byte[] lz4Block(byte[]src,int expected)throws IOException {
            int cap=Math.max(expected,256),op=0,ip=0;byte[]out=new byte[cap];
            while(ip<src.length){
                int token=src[ip++]&255;
                int lit=token>>>4;if(lit==15){int x;do{if(ip>=src.length)throw new EOFException();x=src[ip++]&255;lit+=x;}while(x==255);}
                if(ip+lit>src.length)throw new IOException("LZ4 literal overruns block");
                if(op+lit>out.length)out=Arrays.copyOf(out,grow(out.length,op+lit));
                System.arraycopy(src,ip,out,op,lit);ip+=lit;op+=lit;
                if(ip>=src.length)break;
                if(ip+2>src.length)throw new IOException("LZ4 match offset truncated");
                int off=(src[ip]&255)|((src[ip+1]&255)<<8);ip+=2;
                if(off<=0||off>op)throw new IOException("LZ4 invalid match offset");
                int m=(token&15)+4;if((token&15)==15){int x;do{if(ip>=src.length)throw new EOFException();x=src[ip++]&255;m+=x;}while(x==255);}
                if(op+m>out.length)out=Arrays.copyOf(out,grow(out.length,op+m));
                for(int i=0;i<m;i++)out[op+i]=out[op-off+i];
                op+=m;
            }
            return Arrays.copyOf(out,op);
        }
        static int grow(int old,int need){int n=Math.max(256,old);while(n<need){int next=n+(n>>1);if(next<=n){n=need;break;}n=next;}return n;}

        Inode inode(long ref)throws Exception{int blk=(int)(ref>>>16),off=(int)ref&0xffff;Integer baseOff=imap.get((long)blk);if(baseOff==null)throw new IOException("inode ref outside table");int p=baseOff+off;int t=le16(inodes,p,be),mode=le16(inodes,p+2,be);int q=p+16;Inode x=new Inode(t,mode);if(t==1){x.db=le32(inodes,q,be);x.ds=le16(inodes,q+8,be);x.doff=le16(inodes,q+10,be);}else if(t==8){x.db=le32(inodes,q+8,be);x.ds=le32(inodes,q+4,be);x.doff=le16(inodes,q+18,be);}else if(t==2){x.start=le32(inodes,q,be)&0xffffffffL;x.frag=le32(inodes,q+4,be);x.fragOff=le32(inodes,q+8,be);x.size=le32(inodes,q+12,be)&0xffffffffL;int n=x.frag==NO_FRAG?ceil((int)x.size,block):(int)(x.size/block);x.sizes=new int[n];for(int i=0;i<n;i++)x.sizes[i]=le32(inodes,q+16+i*4,be);}else if(t==9){x.start=u64(inodes,q,be);x.size=u64(inodes,q+8,be);x.frag=le32(inodes,q+28,be);x.fragOff=le32(inodes,q+32,be);int n=x.frag==NO_FRAG?ceil((int)Math.min(Integer.MAX_VALUE,x.size),block):(int)(x.size/block);x.sizes=new int[n];for(int i=0;i<n;i++)x.sizes[i]=le32(inodes,q+40+i*4,be);}else if(t==3||t==10){int len=le32(inodes,q+4,be);x.target=new String(inodes,q+8,len,StandardCharsets.UTF_8);}return x;}
        List<Pair>dir(Inode i)throws Exception{List<Pair>o=new ArrayList<>();Integer dbase=dmap.get((long)i.db);if(dbase==null)return o;int p=dbase+i.doff;int end=p+i.ds-3;while(p+12<=end){int cnt=le32(dirs,p,be),start=le32(dirs,p+4,be);p+=12;for(int k=0;k<=cnt&&p+8<=end;k++){int off=le16(dirs,p,be),len=le16(dirs,p+6,be);p+=8;if(p+len+1>dirs.length)break;String n=new String(dirs,p,len+1,StandardCharsets.UTF_8);p+=len+1;o.add(new Pair(n,((long)start<<16)|(off&0xffffL)));}}return o;}
        void writeZip(File out,String original)throws Exception{String top=safe(stripExt(original));try(FileOutputStream fos=new FileOutputStream(out); ZipArchiveOutputStream z=new ZipArchiveOutputStream(fos)){Set<Long>seen=new HashSet<>();walk(z,rootRef,"",top,seen);}}
        void walk(ZipArchiveOutputStream z,long ref,String rel,String top,Set<Long>seen)throws Exception{Inode in=inode(ref);String path=rel.isEmpty()?top:top+"/"+rel; if(in.type==1||in.type==8){ZipArchiveEntry ze=new ZipArchiveEntry(path+"/");ze.setUnixMode(040000|in.mode);z.putArchiveEntry(ze);z.closeArchiveEntry();if(!seen.add(ref))return;for(Pair p:dir(in)){if(p.name.equals(".")||p.name.equals("..")||p.name.indexOf('/')>=0)continue;String child=rel.isEmpty()?p.name:rel+"/"+p.name;walk(z,p.ref,child,top,seen);}}else if(in.type==2||in.type==9){ZipArchiveEntry ze=new ZipArchiveEntry(path);ze.setUnixMode(0100000|in.mode);z.putArchiveEntry(ze);streamFile(z,in);z.closeArchiveEntry();}else if(in.type==3||in.type==10){ZipArchiveEntry ze=new ZipArchiveEntry(path);ze.setUnixMode(0120000|0777);z.putArchiveEntry(ze);z.write(in.target.getBytes(StandardCharsets.UTF_8));z.closeArchiveEntry();}}
        void streamFile(OutputStream z,Inode in)throws Exception{long rem=in.size,pos=in.start;for(int sz:in.sizes){long n=Math.min(block,rem);int c=sz&0x00ffffff;if(c==0){writeZeros(z,n);}else{byte[]raw=readAt(base+pos,c);pos+=c;byte[]data=(sz&UNCOMP)!=0?raw:decomp(raw,block);int take=(int)Math.min(n,data.length);z.write(data,0,take);if(take<n)writeZeros(z,n-take);}rem-=n;if(rem<=0)break;}if(rem>0&&in.frag!=NO_FRAG&&in.frag<frags.size()){Frag f=frags.get(in.frag);byte[]raw=readAt(base+f.start,f.size&0x00ffffff);byte[]data=(f.size&UNCOMP)!=0?raw:decomp(raw,block);int off=Math.min(in.fragOff,data.length);int take=(int)Math.min(rem,data.length-off);z.write(data,off,take);rem-=take;}if(rem>0)writeZeros(z,rem);}
        /** Visits every folder, link and regular file of the image (the root folder itself is not reported). */
        void walkVisit(SquashVisitor v)throws Exception{walkV(v,rootRef,"",new HashSet<Long>());}
        void walkV(SquashVisitor v,long ref,String rel,Set<Long>seen)throws Exception{
            Inode in=inode(ref);
            if(in.type==1||in.type==8){
                if(!rel.isEmpty())v.dir(rel,in.mode&07777);
                if(!seen.add(ref))return;
                for(Pair p:dir(in)){if(p.name.equals(".")||p.name.equals("..")||p.name.indexOf('/')>=0)continue;walkV(v,p.ref,rel.isEmpty()?p.name:rel+"/"+p.name,seen);}
            }else if(in.type==2||in.type==9){
                OutputStream o=v.file(rel,in.mode&07777,in.size);
                if(o!=null){try{streamFile(o,in);}finally{o.close();}v.fileEnd(rel);}
            }else if(in.type==3||in.type==10){
                v.link(rel,in.target);
            }
        }
        byte[]readAt(long pos,int n)throws IOException{r.seek(pos);byte[]b=new byte[n];r.readFully(b);return b;}static final class MapTable{byte[]buf;Map<Long,Integer>map;MapTable(byte[]b,Map<Long,Integer>m){buf=b;map=m;}} static final class MetaBlock{byte[]data;long next;MetaBlock(byte[]d,long n){data=d;next=n;}}static final class Frag{long start;int size;Frag(long s,int z){start=s;size=z;}}static final class Inode{int type,mode,db,ds,doff,frag,fragOff;long size,start;int[]sizes=new int[0];String target="";Inode(int t,int m){type=t;mode=m;}}static final class Pair{String name;long ref;Pair(String n,long r){name=n;ref=r;}}
        public void close()throws IOException{r.close();}
    }

    // ===== PKG recursive wrapper ==============================================
    static final class Pkg {
        static List<Artifact> extract(File f,File work,Consumer<String>log)throws Exception{File out=new File(work,"pkg");out.mkdirs();extractAny(f,out,log,0);List<Artifact>a=new ArrayList<>();for(File x:allFiles(out))a.add(new Artifact(x,roleFor(x.getName())));if(a.isEmpty())throw new IOException("PKG produced no extractable files");return a;}
        static void extractAny(File f,File out,Consumer<String>log,int depth)throws Exception{
            if(depth>8)return;
            String k=detect(f);
            // pkg_extract.py first unwraps the common OEM wrappers before recursively dispatching the payload.
            byte[] hh=readHead(f,16);
            if(starts(hh,"PHPL")){oemPhilips(f,out,log);return;}
            if(starts(hh,"HISI")){oemHisense(f,out,log);return;}
            if(starts(hh,"AMLO")){oemAmlogic(f,out,log);return;}
            switch(k){
                case"ZIP":unzip(f,out);break;
                case"GZIP":decompress(f,new File(out,stripExt(f.getName())),"gz");break;
                case"XZ":decompress(f,new File(out,stripExt(f.getName())),"xz");break;
                case"BZ2":decompress(f,new File(out,stripExt(f.getName())),"bz2");break;
                case"TAR":extractTar(f,out);break;
                case"CPIO":{File d=new File(out,safe(f.getName()));copy(f,d);break;}
                case"ANDROID_BOOT":{File d=new File(out,safe(f.getName()));copy(f,d);break;}
                case"SPARSE":{File d=new File(out,safe(f.getName()));copy(f,d);break;}
                case"EXT4":{File d=new File(out,safe(f.getName()));copy(f,d);break;}
                case"SQUASHFS":{File z=SquashFs.toZip(f,out,log);unzip(z,out);break;}
                case"SBF":case"PAC":case"NBH":case"LG_BIN":case"LG_TOT":case"CAB":case"OFP":case"OPS":case"SUPER":case"RFS":case"OTA_PAYLOAD":case"OTA_DAT_BR":case"VDAT_LIST":case"VDAT_DATA":{List<Artifact>x=FirmwareToolset.extract(f,out,log);for(Artifact q:x)if(q.file.isFile())extractAny(q.file,new File(out,"_"+safe(q.file.getName())),log,depth+1);return;}
                default:genericScan(f,out,log);return;
            }
            for(File x:allFiles(out)){if(x.equals(f)||x.getName().equals("report.json"))continue;String sub=detect(x);if(!"UNKNOWN".equals(sub)&&!sub.equals(k))extractAny(x,new File(out,"_"+safe(x.getName())),log,depth+1);}
        }
        static void extractTar(File f,File out)throws Exception{
            try(org.apache.commons.compress.archivers.tar.TarArchiveInputStream in=new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(new FileInputStream(f))){
                org.apache.commons.compress.archivers.tar.TarArchiveEntry e;while((e=in.getNextTarEntry())!=null){if(e.isDirectory())continue;File d=safeResolve(out,e.getName());d.getParentFile().mkdirs();try(OutputStream os=new FileOutputStream(d)){copy(in,os);}}}
        }
        static void oemPhilips(File f,File out,Consumer<String>log)throws Exception{try(RandomAccessFile r=new RandomAccessFile(f,"r")){r.seek(8);long sz=u32(readBytes(r,4),0);byte[]payload=new byte[(int)Math.min(sz,r.length()-16)];r.readFully(payload);File d=new File(out,"philips_payload.bin");writeBytes(d,payload);extractAny(d,new File(out,"philips"),log,1);}}
        static void oemHisense(File f,File out,Consumer<String>log)throws Exception{File d=new File(out,"hisense_payload.bin");try(RandomAccessFile r=new RandomAccessFile(f,"r")){r.seek(Math.min(16,r.length()));try(OutputStream os=new FileOutputStream(d)){copy(r,os,r.length()-r.getFilePointer());}}extractAny(d,new File(out,"hisense"),log,1);}
        static void oemAmlogic(File f,File out,Consumer<String>log)throws Exception{if(detect(f).equals("ZIP"))unzip(f,out);else genericScan(f,out,log);}

        static void unzip(File f,File out)throws Exception{try(ZipFile z=new ZipFile(f)){Enumeration<ZipArchiveEntry>e=z.getEntries();while(e.hasMoreElements()){ZipArchiveEntry x=e.nextElement();if(x.isDirectory())continue;File d=safeResolve(out,x.getName());d.getParentFile().mkdirs();try(InputStream in=z.getInputStream(x);OutputStream os=new FileOutputStream(d)){copy(in,os);}}}}
        static void decompress(File f,File out,String t)throws Exception{try(InputStream in=t.equals("gz")?new GZIPInputStream(new FileInputStream(f)):t.equals("xz")?new XZCompressorInputStream(new FileInputStream(f)):new BZip2CompressorInputStream(new FileInputStream(f));OutputStream os=new FileOutputStream(out)){copy(in,os);}}
        static void genericScan(File f,File out,Consumer<String>log)throws Exception{byte[]h=readHead(f,64<<10);int p=indexOf(h,"PK\u0003\u0004".getBytes(StandardCharsets.ISO_8859_1),0);if(p>0){File d=new File(out,"embedded.zip");try(RandomAccessFile r=new RandomAccessFile(f,"r")){r.seek(p);try(OutputStream os=new FileOutputStream(d)){copy(r,os,r.length()-p);}}log.accept("PKG: embedded ZIP at "+p);return;}File d=new File(out,safe(f.getName()));copy(f,d);}
    }

    // ===== payload.bin =========================================================
    static final class PayloadExtractor {
        // update_engine protobufs are parsed locally so the Android app does not need protobuf runtime.
        static List<Artifact> extract(File f, File work, Consumer<String> log) throws Exception {
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                byte[] magic = new byte[4]; r.readFully(magic);
                if (!starts(magic, "CrAU")) throw new IOException("not payload.bin");
                long version = readBe64(r), manifestSize = readBe64(r);
                long metadataSigSize = readBe32(r);
                long manifestPos = r.getFilePointer();
                if (manifestSize <= 0 || manifestSize > Integer.MAX_VALUE || manifestPos + manifestSize + metadataSigSize > r.length())
                    throw new IOException("invalid payload header");
                byte[] manifest = new byte[(int)manifestSize]; r.readFully(manifest);
                long dataBase = manifestPos + manifestSize + metadataSigSize;
                List<Partition> parts = parseManifest(manifest);
                if (parts.isEmpty()) throw new IOException("payload manifest contains no partitions");
                File out = new File(work, "payload"); out.mkdirs();
                List<Artifact> result = new ArrayList<>();
                long blockSize = 4096;
                for (Partition p : parts) {
                    if (p.name == null || p.name.isEmpty() || p.dstBytes <= 0) continue;
                    File dst = safeResolve(out, safe(p.name) + ".img");
                    dst.getParentFile().mkdirs();
                    try (RandomAccessFile w = new RandomAccessFile(dst, "rw")) {
                        w.setLength(p.dstBytes);
                        for (Operation op : p.ops) applyOperation(r, dataBase, blockSize, w, op, log);
                    }
                    if (dst.isFile()) { result.add(new Artifact(dst, roleFor(dst.getName()))); log.accept("payload: " + dst.getName()); }
                }
                if (result.isEmpty()) throw new IOException("payload contains no extractable partitions");
                return result;
            }
        }
        static List<Partition> parseManifest(byte[] b) throws IOException {
            ProtoReader pr = new ProtoReader(b); List<Partition> out = new ArrayList<>();
            while (pr.has()) { int f=pr.field(); int wt=pr.wire(); if (wt==2 && f==13) out.add(parsePartition(pr.bytes())); else pr.skip(wt); }
            if (out.isEmpty()) { // old metadata put partitions on field 2
                pr = new ProtoReader(b); while(pr.has()){int f=pr.field();int wt=pr.wire();if(wt==2&&f==2)out.add(parsePartition(pr.bytes()));else pr.skip(wt);} }
            return out;
        }
        static Partition parsePartition(byte[] b) throws IOException {
            ProtoReader pr=new ProtoReader(b); Partition p=new Partition();
            while(pr.has()){int f=pr.field(),wt=pr.wire();
                if(f==1&&wt==2)p.name=new String(pr.bytes(),StandardCharsets.UTF_8);
                else if(f==7&&wt==2){PartitionInfo pi=parsePartitionInfo(pr.bytes()); if(pi.size>0)p.dstBytes=pi.size;}
                else if(f==8&&wt==2)p.ops.add(parseOperation(pr.bytes()));
                else if(f==9&&wt==0)p.postinstallOptional=pr.varint()!=0;
                else pr.skip(wt);
            }
            long max=0; for(Operation o:p.ops) for(Extent e:o.dst) max=Math.max(max,(e.start+e.num)*4096L); if(p.dstBytes==0)p.dstBytes=max; return p;
        }
        static PartitionInfo parsePartitionInfo(byte[] b)throws IOException{ProtoReader pr=new ProtoReader(b);PartitionInfo x=new PartitionInfo();while(pr.has()){int f=pr.field(),wt=pr.wire();if(f==1&&wt==0)x.size=pr.varint();else pr.skip(wt);}return x;}
        static Operation parseOperation(byte[] b)throws IOException{ProtoReader pr=new ProtoReader(b);Operation o=new Operation();while(pr.has()){int f=pr.field(),wt=pr.wire();switch(f){case 1:if(wt==0)o.type=(int)pr.varint();else pr.skip(wt);break;case 2:if(wt==0)o.dataOffset=pr.varint();else pr.skip(wt);break;case 3:if(wt==0)o.dataLength=pr.varint();else pr.skip(wt);break;case 6:if(wt==2)o.dst.add(parseExtent(pr.bytes()));else pr.skip(wt);break;case 7:if(wt==0)o.dstBytes=pr.varint();else pr.skip(wt);break;default:pr.skip(wt);}}if(o.dstBytes==0)for(Extent e:o.dst)o.dstBytes=Math.max(o.dstBytes,(e.start+e.num)*4096L);return o;}
        static Extent parseExtent(byte[] b)throws IOException{ProtoReader p=new ProtoReader(b);Extent e=new Extent();while(p.has()){int f=p.field(),wt=p.wire();if(f==1&&wt==0)e.start=p.varint();else if(f==2&&wt==0)e.num=p.varint();else p.skip(wt);}return e;}
        static void applyOperation(RandomAccessFile r,long base,long bs,RandomAccessFile out,Operation o,Consumer<String>log)throws Exception{
            long totalDst=0; for(Extent e:o.dst) totalDst=Math.addExact(totalDst,e.num*bs);
            if(o.type==6||o.type==7){for(Extent e:o.dst){out.seek(e.start*bs);writeZeros(out,e.num*bs);}return;}
            if(o.type!=0&&o.type!=1&&o.type!=8)throw new IOException("payload operation "+o.type+" requires source/delta support");
            InputStream in;
            if(o.type==0){r.seek(base+o.dataOffset);in=new InputStream(){long rem=o.dataLength;public int read(){return -1;}public int read(byte[]b,int off,int len)throws IOException{if(rem<=0)return -1;int k=(int)Math.min(rem,len);r.readFully(b,off,k);rem-=k;return k;}};}
            else {if(o.dataLength>Integer.MAX_VALUE)throw new IOException("compressed payload op too large");r.seek(base+o.dataOffset);byte[]enc=new byte[(int)o.dataLength];r.readFully(enc);in=o.type==1?new BZip2CompressorInputStream(new ByteArrayInputStream(enc)):new XZCompressorInputStream(new ByteArrayInputStream(enc));}
            try(in){byte[]buf=new byte[1<<20];for(Extent e:o.dst){long rem=e.num*bs;out.seek(e.start*bs);while(rem>0){int k=(int)Math.min(rem,buf.length);readFull(in,buf,k);out.write(buf,0,k);rem-=k;}}}
        }
        static long readBe64(RandomAccessFile r)throws IOException{return ((long)readBe32(r)<<32)|(readBe32(r)&0xffffffffL);}
        static long readBe32(RandomAccessFile r)throws IOException{return ((r.readUnsignedByte()<<24)|(r.readUnsignedByte()<<16)|(r.readUnsignedByte()<<8)|r.readUnsignedByte())&0xffffffffL;}
        static void copyAt(RandomAccessFile r,long pos,RandomAccessFile w,long n)throws IOException{r.seek(pos);byte[]b=new byte[1<<20];while(n>0){int k=(int)Math.min(n,b.length);r.readFully(b,0,k);w.write(b,0,k);n-=k;}}
        static final class Partition{String name,postinstall;boolean postinstallOptional;byte[]hash;long version,dstBytes;List<Operation>ops=new ArrayList<>();}
        static final class PartitionInfo{long size;}
        static final class Operation{int type;long dataOffset,dataLength,dstBytes;List<Extent>dst=new ArrayList<>();}
        static final class Extent{long start,num;}
        static final class ProtoReader{
            final byte[] b; int p; long lastKey;
            ProtoReader(byte[] b){this.b=b;}
            boolean has(){return p<b.length;}
            long varint()throws IOException{long v=0;int sh=0;while(p<b.length&&sh<64){int c=b[p++]&255;v|=(long)(c&127)<<sh;if((c&128)==0)return v;sh+=7;}throw new IOException("bad protobuf varint");}
            int field()throws IOException{lastKey=varint();return (int)(lastKey>>>3);}
            int wire(){return (int)lastKey&7;}
            byte[] bytes()throws IOException{long n=varint();if(n<0||n>b.length-p)throw new IOException("bad protobuf bytes");byte[]x=Arrays.copyOfRange(b,p,p+(int)n);p+=(int)n;return x;}
            void skip(int wt)throws IOException{switch(wt){case 0:varint();break;case 1:p+=8;break;case 2:long n=varint();if(n>Integer.MAX_VALUE)throw new IOException("protobuf field too large");p+=(int)n;break;case 5:p+=4;break;default:throw new IOException("unsupported protobuf wire type "+wt);}if(p>b.length)throw new IOException("protobuf overflow");}
        }
    }

    // ===== Android dynamic super.img ==========================================
    static final class SuperImage {
        static final long HEADER=0x414C5030L, GEOM=0x616C4467L, SECTOR=512;
        static List<Artifact> extract(File f,File work,Consumer<String>log)throws Exception{
            try(RandomAccessFile r=new RandomAccessFile(f,"r")){
                byte[] g=new byte[64];r.readFully(g);if(u32(g,0)!=GEOM)throw new IOException("not super geometry");
                long metaMax=u32(g,40), slots=u32(g,44);if(metaMax<=0||slots<=0||metaMax>128*1024*1024L)throw new IOException("invalid super geometry");
                long metaBase=8192; byte[] h=readAt(r,metaBase,256);if(u32(h,0)!=HEADER) {h=readAt(r,metaBase+metaMax*slots,256);metaBase+=metaMax*slots;}
                if(u32(h,0)!=HEADER)throw new IOException("super metadata header not found");
                int hs=(int)u32(h,8), tablesSize=(int)u32(h,44); if(hs<100||hs>256||tablesSize<=0||metaBase+hs+tablesSize>r.length())throw new IOException("invalid super metadata");
                TableDesc part=desc(h,80), ext=desc(h,92), blk=desc(h,116);
                byte[] tables=readAt(r,metaBase+hs,tablesSize); List<BlkDev>devs=new ArrayList<>(); for(int i=0;i<blk.num;i++){int p=blk.off+i*blk.size;if(p+Math.max(64,blk.size)>tables.length)break;devs.add(new BlkDev(u64(tables,p),cstr(Arrays.copyOfRange(tables,p+24,Math.min(tables.length,p+60)))));}
                File out=new File(work,"super");out.mkdirs();List<Part>parts=new ArrayList<>();for(int i=0;i<part.num;i++){int p=part.off+i*part.size;if(p+Math.max(52,part.size)>tables.length)break;String name=cstr(Arrays.copyOfRange(tables,p,Math.min(tables.length,p+36)));int first=(int)u32(tables,p+40), count=(int)u32(tables,p+44);parts.add(new Part(name,first,count));}
                List<Ext>exts=new ArrayList<>();for(int i=0;i<ext.num;i++){int p=ext.off+i*ext.size;if(p+Math.max(24,ext.size)>tables.length)break;exts.add(new Ext(u64(tables,p),u32(tables,p+8),u64(tables,p+12),u32(tables,p+20)));}
                List<Artifact>a=new ArrayList<>();for(Part partx:parts){File dst=new File(out,safe(partx.name)+".img");try(RandomAccessFile w=new RandomAccessFile(dst,"rw")){long size=0;for(int i=0;i<partx.count&&partx.first+i<exts.size();i++)size+=exts.get(partx.first+i).num*SECTOR;w.setLength(size);long outPos=0;for(int i=0;i<partx.count&&partx.first+i<exts.size();i++){Ext e=exts.get(partx.first+i);long n=e.num*SECTOR;if(e.type==0){w.seek(outPos);long phys=e.target*SECTOR;long left=n;while(left>0){int k=(int)Math.min(left,1<<20);byte[]buf=readAt(r,phys,k);w.write(buf);phys+=k;left-=k;}}outPos+=n;}}a.add(new Artifact(dst,roleFor(dst.getName())));log.accept("super: "+partx.name);}
                return a;
            }
        }
        static TableDesc desc(byte[]h,int p){return new TableDesc((int)u32(h,p),(int)u32(h,p+4),(int)u32(h,p+8));}
        static final class TableDesc{int off,num,size;TableDesc(int a,int b,int c){off=a;num=b;size=c;}}
        static final class Part{String name;int first,count;Part(String n,int f,int c){name=n;first=f;count=c;}}
        static final class Ext{long num;long type,target,source;Ext(long n,long t,long d,long s){num=n;type=t;target=d;source=s;}}
        static final class BlkDev{long first;String name;BlkDev(long f,String n){first=f;name=n;}}
    }

    static final class Vdat {
        static List<Artifact> extractFromList(File list,File work,Consumer<String>log)throws Exception{
            String n=list.getName();String stem=n.toLowerCase(Locale.US).endsWith(".transfer.list")?n.substring(0,n.length()-".transfer.list".length()):stripExt(n);File data=findSibling(list,stem+".new.dat");if(data==null)throw new IOException("matching "+stem+".new.dat not found");return build(list,data,work,log);
        }
        static List<Artifact> extractFromData(File data,File work,Consumer<String>log)throws Exception{
            String n=data.getName();String stem=n.toLowerCase(Locale.US).endsWith(".new.dat")?n.substring(0,n.length()-".new.dat".length()):stripExt(n);File list=findSibling(data,stem+".transfer.list");if(list==null)throw new IOException("matching transfer list not found for "+data.getName());return build(list,data,work,log);
        }
        static List<Artifact> build(File list,File data,File work,Consumer<String>log)throws Exception{
            List<String>ls=readLines(list);if(ls.size()<2)throw new IOException("bad transfer list");int ver=Integer.parseInt(ls.get(0));long blocks=Long.parseLong(ls.get(1));String stem=data.getName();if(stem.toLowerCase(Locale.US).endsWith(".new.dat")) stem=stem.substring(0,stem.length()-".new.dat".length()); else stem=stripExt(stem);File out=new File(work,(stem.isEmpty()?"system":stem)+".img");try(RandomAccessFile dst=new RandomAccessFile(out,"rw");InputStream in=new BufferedInputStream(new FileInputStream(data))){dst.setLength(blocks*4096L);int start=ver>=2?4:2;for(int i=start;i<ls.size();i++){String[]p=ls.get(i).split(" +");if(p.length<2)continue;String cmd=p[0];if("new".equals(cmd)){for(PairRange rg:ranges(p[1])){long a=rg.a,b=rg.b;while(a<b){int n=(int)Math.min(128,b-a);byte[]buf=new byte[n*4096];readFull(in,buf,buf.length);dst.seek(a*4096);dst.write(buf);a+=n;}}}else if("zero".equals(cmd)||"erase".equals(cmd)){}else throw new IOException("incremental OTA command "+cmd+" is not supported by vdat2img");}}log.accept("vdat: "+out.getName());return Collections.singletonList(new Artifact(out,roleFor(out.getName())));
        }
        static List<PairRange> ranges(String s){String[]v=s.split(",");List<PairRange>o=new ArrayList<>();for(int i=1;i+1<v.length;i+=2)o.add(new PairRange(Long.parseLong(v[i]),Long.parseLong(v[i+1])));return o;}
        static File findSibling(File f,String name){File p=f.getParentFile();if(p==null)return null;File x=new File(p,name);return x.isFile()?x:null;}
        static final class PairRange{long a,b;PairRange(long x,long y){a=x;b=y;}}
    }
    static final class OtaDatBr {static File extract(File f,File work,Consumer<String>log)throws Exception{File list=new File(f.getParentFile(),f.getName().replaceFirst("\\.br$","").replace("system.new.dat","system.transfer.list"));if(!list.isFile())throw new IOException("system.transfer.list not found");File dat=File.createTempFile("system-",".dat",work);try(InputStream in=new BrotliInputStream(new FileInputStream(f));OutputStream out=new FileOutputStream(dat)){copy(in,out);}File out=new File(work,"system.img");List<String>ls=readLines(list);if(ls.size()<2)throw new IOException("bad transfer list");long blocks=Long.parseLong(ls.get(1));int ver=Integer.parseInt(ls.get(0));int start=ver>=2?4:2;try(RandomAccessFile dst=new RandomAccessFile(out,"rw");InputStream data=new BufferedInputStream(new FileInputStream(dat))){dst.setLength(blocks*4096L);byte[]buf=new byte[4096*128];for(int i=start;i<ls.size();i++){String[]p=ls.get(i).split(" ");if(!p[0].equals("new"))throw new IOException("incremental OTA command "+p[0]+" requires OTA-specific handling already provided by Importer");if(p.length>1){String[]v=p[1].split(",");for(int j=1;j+1<v.length;j+=2){long a=Long.parseLong(v[j]),b=Long.parseLong(v[j+1]);while(a<b){int n=(int)Math.min(128,b-a);readFull(data,buf,n*4096);dst.seek(a*4096);dst.write(buf,0,n*4096);a+=n;}}}}}dat.delete();log.accept("OTA .dat.br -> "+out.getName());return out;}}

    static int sparsePartIndex(String name){
        String n=name.toLowerCase(Locale.US);
        int q=n.lastIndexOf('.');
        if(q<0) return -1;
        try{return Integer.parseInt(n.substring(q+1));}
        catch(Exception e){return -1;}
    }

    // ===== helpers =============================================================
    static final class SparseHeader{int fileHeaderSize,chunkHeaderSize,blockSize,totalChunks;SparseHeader(int a,int b,int c,int d){fileHeaderSize=a;chunkHeaderSize=b;blockSize=c;totalChunks=d;}}
    static final class Chunk{int type,blocks,totalSize;Chunk(int t,int b,int s){type=t;blocks=b;totalSize=s;}}
    static SparseHeader readSparseHeader(RandomAccessFile r)throws IOException{byte[]h=new byte[28];r.seek(0);r.readFully(h);if(u32(h,0)!=0xED26FF3AL)throw new IOException("not sparse");return new SparseHeader(le16(h,8),le16(h,10),(int)u32(h,12),(int)u32(h,20));}
    static Chunk readChunk(RandomAccessFile r,int hdr)throws IOException{byte[]h=new byte[12];r.readFully(h);int t=le16(h,0),blocks=(int)u32(h,4),total=(int)u32(h,8);if(hdr>12)skipRaf(r,hdr-12);return new Chunk(t,blocks,total);}
    static long dataSize(Chunk c,SparseHeader h){return (long)c.totalSize-h.chunkHeaderSize;}
    static void copyRaf(RandomAccessFile in,RandomAccessFile out,long n)throws IOException{byte[]b=new byte[1<<20];while(n>0){int k=(int)Math.min(n,b.length);in.readFully(b,0,k);out.write(b,0,k);n-=k;}}
    static void copy(RandomAccessFile in,OutputStream out,long n)throws IOException{byte[]b=new byte[1<<20];while(n>0){int k=(int)Math.min(n,b.length);in.readFully(b,0,k);out.write(b,0,k);n-=k;}}
    static void copy(InputStream in,OutputStream out)throws IOException{byte[]b=new byte[1<<20];int n;while((n=in.read(b))>0)out.write(b,0,n);}
    static void copy(File a,File b)throws IOException{try(InputStream in=new FileInputStream(a);OutputStream out=new FileOutputStream(b)){copy(in,out);}}
    static void skipRaf(RandomAccessFile r,long n)throws IOException{while(n>0){long k=r.skipBytes((int)Math.min(n,Integer.MAX_VALUE));if(k<=0){if(r.read()<0)throw new EOFException();k=1;}n-=k;}}
    static void readFull(InputStream in,byte[]b,int n)throws IOException{int o=0;while(o<n){int k=in.read(b,o,n-o);if(k<0)throw new EOFException();o+=k;}}
    static byte[] readAt(RandomAccessFile r,long p,int n)throws IOException{r.seek(p);byte[]b=new byte[n];r.readFully(b);return b;}
    static byte[] readBytes(RandomAccessFile r,int n)throws IOException{byte[]b=new byte[n];r.readFully(b);return b;}
    static byte[] readAll(File f)throws IOException{try(InputStream in=new FileInputStream(f)){return in.readAllBytes();}}
    static byte[] readHead(File f,int n)throws IOException{try(InputStream in=new FileInputStream(f)){byte[]b=new byte[n];int o=0,k;while(o<n&&(k=in.read(b,o,n-o))>0)o+=k;return Arrays.copyOf(b,o);}}
    static void writeBytes(File f,byte[]b)throws IOException{try(OutputStream o=new FileOutputStream(f)){o.write(b);}}
    static void writeZeros(RandomAccessFile r,long n)throws IOException{byte[]z=new byte[1<<20];while(n>0){int k=(int)Math.min(n,z.length);r.write(z,0,k);n-=k;}}
    static void writeFill(RandomAccessFile r,byte[]v,long n)throws IOException{byte[]b=new byte[1<<16];for(int i=0;i<b.length;i++)b[i]=v[i&3];while(n>0){int k=(int)Math.min(n,b.length);r.write(b,0,k);n-=k;}}
    static void writeZeros(OutputStream z,long n)throws IOException{byte[]b=new byte[1<<16];while(n>0){int k=(int)Math.min(n,b.length);z.write(b,0,k);n-=k;}}
    static void writeZeros(ZipArchiveOutputStream z,long n)throws IOException{byte[]b=new byte[1<<16];while(n>0){int k=(int)Math.min(n,b.length);z.write(b,0,k);n-=k;}}
    static boolean starts(byte[]b,String s){return starts(b,s.getBytes(StandardCharsets.ISO_8859_1));}
    static boolean starts(byte[]b,byte[]p){return b.length>=p.length&&Arrays.equals(Arrays.copyOf(b,p.length),p);}
    static boolean all(byte[]b,int x){for(byte v:b)if((v&255)!=x)return false;return true;}
    static long u32(byte[]b,int o){return (b[o]&255L)|((b[o+1]&255L)<<8)|((b[o+2]&255L)<<16)|((b[o+3]&255L)<<24);}
    static int le16(byte[]b,int o){return (b[o]&255)|((b[o+1]&255)<<8);}
    static int le16(byte[]b,int o,boolean be){return be?(((b[o]&255)<<8)|(b[o+1]&255)):le16(b,o);}
    static int u16(byte[]b,int o,boolean be){return be?(((b[o]&255)<<8)|(b[o+1]&255)):le16(b,o);}
    static int le32(byte[]b,int o){return (int)u32(b,o);}
    static int le32(byte[]b,int o,boolean be){return be?((b[o]&255)<<24)|((b[o+1]&255)<<16)|((b[o+2]&255)<<8)|(b[o+3]&255):le32(b,o);}
    static long u64(byte[]b,int o){return u32(b,o)|(u32(b,o+4)<<32);}
    static long u64(byte[]b,int o,boolean be){if(!be)return u64(b,o);return ((long)le32(b,o,true)&0xffffffffL)<<32 | ((long)le32(b,o+4,true)&0xffffffffL);}
    static long u64pair(byte[]b,int o){return u32(b,o)|(u32(b,o+4)<<32);}
    static long attrLong(Element e,String k,long d){try{String s=e.getAttribute(k);return s==null||s.isEmpty()?d:Long.parseLong(s);}catch(Exception x){return d;}}
    static String utf16(byte[]b,int o,int n){return new String(b,o,n,StandardCharsets.UTF_16LE).replace("\u0000","").trim();}
    static String utf8z(byte[]b,int o,int n){return new String(b,o,n,StandardCharsets.UTF_8).replace("\u0000","").trim();}
    static String cstr(byte[]b){int e=0;while(e<b.length&&b[e]!=0)e++;return new String(b,0,e,StandardCharsets.UTF_8).trim();}
    static String safe(String x){String s=(x==null?"file":x).replace('\\','_').replace('/','_').replaceAll("[^A-Za-z0-9._-]","_");while(s.startsWith("."))s=s.substring(1);return s.isEmpty()?"file":s;}
    static File safeResolve(File root,String rel)throws IOException{File f=new File(root,rel.replace('\\','/')).getCanonicalFile();File r=root.getCanonicalFile();if(!f.equals(r)&&!f.getPath().startsWith(r.getPath()+File.separator))throw new IOException("unsafe path");return f;}
    static String stripExt(String n){int p=n.lastIndexOf('.');return p>0?n.substring(0,p):n;}
    static long align(long n,long a){return ((n+a-1)/a)*a;}
    static int ceil(int n,int d){return (n+d-1)/d;}
    static int safeInt(long n){return n>Integer.MAX_VALUE?Integer.MAX_VALUE:(int)n;}
    static int trim(byte[]b){int e=b.length;while(e>0&&(b[e-1]==0||((b[e-1]&255)==255)))e--;return e;}
    static int indexOf(byte[]a,byte[]p,int from){outer:for(int i=Math.max(0,from);i<=a.length-p.length;i++){for(int j=0;j<p.length;j++)if(a[i+j]!=p[j])continue outer;return i;}return -1;}
    static boolean containsAscii(byte[]a,String s){return indexOf(a,s.getBytes(StandardCharsets.US_ASCII),0)>=0;}
    static boolean looksTar(byte[]b){return b.length>262&&new String(b,257,5,StandardCharsets.ISO_8859_1).equals("ustar");}
    static boolean looksRfs(byte[]b){return Rfs.parseBpb(b,0)!=null;}
    static String roleName(String n){return n==null?"":n.toLowerCase(Locale.US);}
    /** Partition names that are a recovery without saying so (Sony SOS / FOTAKernel), after an optional numeric prefix ("005-"). */
    private static final java.util.regex.Pattern RECOVERY_ALIAS = java.util.regex.Pattern.compile("(?:\\d{1,4}[-_])?(?:sos|fotakernel)(?:\\.(?:img|bin))?");
    private static final java.util.regex.Pattern OEM_NAME = java.util.regex.Pattern.compile("(?:\\d{1,4}[-_])?oem(?:_signed)?(?:\\.(?:img|ext4|rfs))*");
    static Role roleFor(String n){String x=roleName(n);if(x.contains("recovery")||RECOVERY_ALIAS.matcher(x).matches())return Role.RECOVERY;if(OEM_NAME.matcher(x).matches())return Role.OEM;if(x.contains("boot")||x.contains("kernel"))return Role.BOOT;if(x.contains("system")||x.contains("factoryfs"))return Role.SYSTEM;if(x.endsWith(".zip")||x.endsWith(".7z")||x.endsWith(".rar")||x.endsWith(".tar"))return Role.ARCHIVE;return Role.UNKNOWN;}
    static List<File> allFiles(File root){List<File>o=new ArrayList<>();File[]fs=root.listFiles();if(fs==null)return o;for(File f:fs){if(f.isDirectory())o.addAll(allFiles(f));else o.add(f);}return o;}
    static List<String> readLines(File f)throws IOException{List<String>x=new ArrayList<>();try(BufferedReader r=new BufferedReader(new FileReader(f))){String s;while((s=r.readLine())!=null){s=s.trim();if(!s.isEmpty())x.add(s);}}return x;}
    static NodeList children(Node n,String name){return ((Element)n).getElementsByTagName(name);}
    static List<Element> elements(Document d,String tag){NodeList n=d.getElementsByTagName(tag);List<Element>o=new ArrayList<>();for(int i=0;i<n.getLength();i++)if(n.item(i) instanceof Element)o.add((Element)n.item(i));return o;}
    static byte[] hex(String s){byte[]o=new byte[s.length()/2];for(int i=0;i<o.length;i++)o[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return o;}
    static byte[] asciiHex(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static byte[] nibbleXor(byte[]a,byte[]b){byte[]o=new byte[Math.min(a.length,b.length)];for(int i=0;i<o.length;i++){int t=(a[i]&255)^(b[i]&255);o[i]=(byte)(((t&0xf0)>>>4)|((t&0x0f)<<4));}return o;}
    static byte[] deobfuscate(byte[]data,byte[]mask){byte[]o=new byte[data.length];for(int i=0;i<data.length;i++){int v=(data[i]&255)^(mask[i%mask.length]&255);o[i]=(byte)(((v>>>4)|((v&0xf)<<4))&255);}return o;}
    static byte[] md5Ascii(byte[]b)throws Exception{byte[]d=java.security.MessageDigest.getInstance("MD5").digest(b);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format(Locale.US,"%02x",x));return s.substring(0,16).getBytes(StandardCharsets.US_ASCII);}
    static byte[] aesCfb(byte[]k,byte[]iv,byte[]d)throws Exception{Cipher c=Cipher.getInstance("AES/CFB/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(k,"AES"),new IvParameterSpec(iv));return c.doFinal(d);}
    static void shuffle(byte[]b,byte[]key){for(int i=0;i<b.length;i++){int v=(b[i]&255)^(key[i%key.length]&255);b[i]=(byte)(((v&15)<<4)|((v&240)>>>4));}}
    static byte[] pad4(byte[]b){int n=(b.length+3)&~3;return b.length==n?b:Arrays.copyOf(b,n);}
    static int stripNulLength(byte[]b,int p){int e=p;while(e<b.length&&b[e]!=0)e++;return Math.max(0,e-p);}
    static boolean isSparse(File f)throws IOException{try(RandomAccessFile r=new RandomAccessFile(f,"r")){return u32(readBytes(r,4),0)==0xED26FF3AL;}}
    static long u32(byte[]outer,byte[]b,int o){return u32(b,o);}
    static byte[] longBytes(long x){return new byte[]{(byte)x,(byte)(x>>>8),(byte)(x>>>16),(byte)(x>>>24),(byte)(x>>>32),(byte)(x>>>40),(byte)(x>>>48),(byte)(x>>>56)};}
}