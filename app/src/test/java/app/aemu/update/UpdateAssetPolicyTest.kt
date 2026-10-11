package app.aemu.update

import org.junit.Assert.*
import org.junit.Test

class UpdateAssetPolicyTest {
    private val plus = "AEmulator-Super-Plus-0.0.0.2.1-app.aemu.superplus.apk"

    @Test fun choosesPlusApkRegardlessOfAssetOrder() {
        assertEquals(plus, listOf(plus, "notes.txt").firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu.superplus") })
        assertEquals(plus, listOf("notes.txt", plus).firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu.superplus") })
    }
    @Test fun neverMatchesOldSunsetOrCloneApks() {
        assertFalse(UpdateAssetPolicy.matches("AEmulator-Sunset-0.0.0.3-sunset.30-app.aemu.apk", "app.aemu.superplus"))
        assertFalse(UpdateAssetPolicy.matches("AEmulator-Sunset-0.0.0.3-sunset.30-app.aemu.clone.apk", "app.aemu.superplus"))
        assertFalse(UpdateAssetPolicy.matches("AEmulator-Plus-0.0.0.2.9-app.aemu.plus.apk", "app.aemu.superplus"))
    }
    @Test fun otherPackagesNeverMatch() {
        assertFalse(UpdateAssetPolicy.matches(plus, "app.aemu"))
        assertFalse(UpdateAssetPolicy.matches(plus, "app.aemu.clone"))
        assertFalse(UpdateAssetPolicy.matches(plus, "other.app"))
    }
    @Test fun matchingIsCaseInsensitiveButRequiresExactPackageSuffix() {
        assertTrue(UpdateAssetPolicy.matches(plus.uppercase(), "app.aemu.superplus"))
        assertFalse(UpdateAssetPolicy.matches("$plus.zip", "app.aemu.superplus"))
        assertFalse(UpdateAssetPolicy.matches("$plus.asc", "app.aemu.superplus"))
        assertFalse(UpdateAssetPolicy.matches("prefix-app.aemu.superplus.fake.apk", "app.aemu.superplus"))
    }
}
