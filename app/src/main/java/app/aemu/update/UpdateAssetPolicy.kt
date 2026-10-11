package app.aemu.update

/** Sunset modification, 2026-10-02: never cross-install distribution variants.
 * Modified for AEmulator Plus, 2026-10-04: single package, app.aemu.plus; no clone.
 * Modified for AEmulator Super Plus, 2026-10-11: package app.aemu.superplus. GPL-3.0; see NOTICE.md. */
object UpdateAssetPolicy {
    const val PACKAGE = "app.aemu.superplus"

    fun matches(name: String, applicationId: String): Boolean =
        applicationId == PACKAGE && name.endsWith("-$PACKAGE.apk", ignoreCase = true)
}
