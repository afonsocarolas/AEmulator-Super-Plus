# Firmware import format coverage

AEmulator Plus imports firmware into the guest filesystem; it is not a general-purpose file manager. The importer now handles these archive/container paths:

- ZIP and nested ZIP firmware bundles.
- 7z, including sequential/solid archives. Entries are read through `SevenZFile.read()` while the current entry is active, then nested images/archives are processed after the archive pass.
- RAR bundles through Junrar, with path traversal checks and selective extraction of firmware-relevant entries.
- TAR, TAR.MD5, TWRP `.win`, gzip, xz, and bzip2 streams.
- Android OTA `system.new.dat` / `system.new.dat.br` plus transfer lists, Android sparse images and Motorola sparse chunks.
- ext2/3/4 images, YAFFS2 images, rootfs trees, boot/recovery images, and CPIO newc/crc/odc archives.
- Sony Flashtool `.ftf` bundles and loose `.sin` images (SIN v1/v2 hash-block images, v3 with MMCF ADDR/LZ4A maps, v4 tar/tar.gz). The system, kernel/boot (ELF or `ANDROID!`), fotakernel/recovery, oem and vendor images are used; the rest of the bundle is skipped.

Several scripts in the supplied companion archive are standalone desktop tools for vendor-specific or encrypted formats (for example PAC, SBF, NBH, LG BIN, TOT, OFP, and OPS). They depend on Python, external utilities, device/vendor-specific parsing, or decryption keys and have **not** been ported into the Android importer by this change. They are not silently treated as supported. SquashFS and RFS images are converted by the Android importer (see firmware-tools-port.md).

The app build could not be run in this environment because Gradle's distribution download was blocked by unavailable network access; validate with `bash gradlew :app:assembleDebug` in an environment with the configured Gradle distribution and Maven dependencies available.
