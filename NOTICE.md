# AEmulator Super Plus modification notice

**AEmulator Super Plus** is a modified fork of
[somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus),
derived from AEmulator Plus `0.0.0.2.9` on 2026-10-11. It remains licensed under the GNU General
Public License, version 3; see [LICENSE](LICENSE). Files modified for Super Plus carry a dated
"AEmulator Super Plus" notice; the Plus, Sunset and original notices below are retained.

Super Plus changes (2026-10-11):

- Sony Flashtool `.ftf` / `.sin` import (`app/src/main/java/app/aemu/importer/SonySin.kt`); the SIN layouts
  follow [Androxyde/Flashtool](https://github.com/Androxyde/Flashtool) (GPL-3.0).
- ELF boot images (Sony `kernel.sin` / `fotakernel.sin`) in `BootImage.ramdisk`.
- Application id `app.aemu.superplus` and the "AEmulator Super Plus" name.

---

# AEmulator Plus modification notice

**AEmulator Plus** is a modified fork of
[drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), which is a modified fork of [uxazu/AEmulator](https://github.com/uxazu/AEmulator). It remains licensed
under the GNU General Public License, version 3; see [LICENSE](LICENSE).

The upstream project grew from the HTC Desire HD and HTC One M7 emulators by
the [original engine author](https://t.me/istratiit_ech). Those credits and the
upstream Git history are intentionally preserved.

**Modification statement:** AEmulator Plus was derived from AEmulator Sunset
`0.0.0.3-sunset.30`. Modifications by somerandomusernam2 (GitHub display name
somerandomusername2) are dated from 2026-10-04; the individual changes are recorded in this
repository's Git history. Files modified for Plus carry a dated
"Modified for AEmulator Plus" notice.

This work has been
modified by somerandomusernam2 and contributors to AEmulator Sunset. Original copyright,
license notices, credits and Git history are retained; no upstream authorship
is claimed for Plus's additions.

Source code for AEmulator Plus releases, including the exact tagged revision
used to build each APK, is published at:

<https://github.com/somerandomusernam2/AEmulator-Plus>

The Plus and Sunset application and fork-owned modifications are released under GPL-3.0.
This does not relicense independently licensed dependencies or user firmware.
See [build/source instructions](docs/build-source.md) and the
[source/licensing audit](docs/license-audit.md).

**Unresolved source-provenance gap:** this checkout contains inherited prebuilt
engine binaries for which matching source, patches and build instructions have
not been located or verified. The tagged checkout can rebuild the APK using
those prebuilts, but is not established as complete Corresponding Source for
every bundled component. This notice is not a claim of full GPL compliance;
publishing notices or hashes does not remedy missing required source.
