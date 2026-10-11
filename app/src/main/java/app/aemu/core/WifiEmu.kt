/* Modified for AEmulator Super Plus, 2026-10-11: signal level and neighbouring networks. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Emulated Wi-Fi. The guest has no wireless hardware and does not need any: its sockets already go out through the
 * host's network. What this adds is the Wi-Fi *identity* of that connection. The guest-side service (guest-wifi,
 * aemu-wifi.jar) answers WifiManager as a connected access point called [VmSettings.wifiSsid] (WPA2 when a password is
 * set, open otherwise) and registers the connection with ConnectivityService as a Wi-Fi network instead of Ethernet.
 *
 * The host hands its settings over in [FILE], a java.util.Properties file in the guest's /data/system.
 */
object WifiEmu {
    const val DEFAULT_SSID = "AEmulator-WiFi"
    const val SSID_MAX_BYTES = 32
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 63

    /** Settings file inside the guest root. */
    const val FILE = "data/system/aemu-wifi.properties"
    const val JAR_NAME = "aemu-wifi.jar"
    const val JAR_PATH = "system/framework/$JAR_NAME"

    /** Same addressing as the Ethernet placeholder network of the 5.0–7.x stub (qemu "user" network layout). */
    const val IP = "10.0.2.15"
    const val GATEWAY = "10.0.2.2"
    const val DNS1 = "8.8.8.8"
    const val DNS2 = "1.1.1.1"
    const val PREFIX = 24
    const val MAC = "02:15:5d:ae:00:01"

    /** Oldest guest the Wi-Fi service is written for: Android 4.0 (API 14); Nougat (25) is the newest the engine runs. */
    const val MIN_API = 14
    const val MAX_API = 25

    /** Guests with the Wi-Fi service (the engine that has the guest services). */
    fun supported(engine: Engine, api: Int, recovery: Boolean, nativeUi: Boolean) =
        engine == Engine.KK && api in MIN_API..MAX_API && !recovery && !nativeUi

    /** SSID: any printable text, at most 32 bytes in UTF-8 (the 802.11 limit); never cut in the middle of a character. */
    fun cleanSsid(v: String): String {
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < v.length) {
            val cp = v.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x20 || cp == 0x7f || Character.isISOControl(cp)) continue
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > SSID_MAX_BYTES) break
            sb.appendCodePoint(cp); bytes += n
        }
        return sb.toString()
    }

    /** Passphrase: printable ASCII only (WPA2 passphrase alphabet), at most 63 characters. */
    fun cleanPassword(v: String): String = v.filter { it in ' '..'~' }.take(PASSWORD_MAX)

    /** Blank means an open network; otherwise WPA2 needs 8..63 characters. */
    fun passwordValid(v: String) = v.isEmpty() || v.length in PASSWORD_MIN..PASSWORD_MAX
    fun secured(s: VmSettings) = passwordValid(s.wifiPassword) && s.wifiPassword.isNotEmpty()

    /** Fixed, locally administered BSSID derived from the network name, so it stays the same between boots. */
    fun bssid(ssid: String): String {
        val h = MessageDigest.getInstance("SHA-1").digest(ssid.toByteArray(Charsets.UTF_8))
        val b = IntArray(6) { h[it].toInt() and 0xff }
        b[0] = (b[0] and 0xfc) or 0x02 // unicast, locally administered
        return b.joinToString(":") { "%02x".format(it) }
    }

    /** What the guest service reads. An invalid passphrase (1..7 characters) is not silently turned into an open network. */
    fun properties(s: VmSettings): Properties {
        val ssid = cleanSsid(s.wifiSsid).ifEmpty { DEFAULT_SSID }
        return Properties().apply {
            setProperty("ssid", ssid)
            setProperty("secured", secured(s).toString())
            setProperty("psk", if (secured(s)) s.wifiPassword else "")
            setProperty("bssid", bssid(ssid))
            setProperty("mac", MAC)
            setProperty("ip", IP)
            setProperty("gateway", GATEWAY)
            setProperty("prefix", PREFIX.toString())
            setProperty("dns1", DNS1)
            setProperty("dns2", DNS2)
            setProperty("rssi", rssiFor(s.wifiSignal).toString())
            setProperty("speed", "72")
            setProperty("frequency", "2437")
            if (s.wifiNeighbors) setProperty("neighbors", neighbors(ssid).joinToString("\n"))
        }
    }

    const val MAX_BARS = 4

    /** dBm for [bars] of 4: Android's calculateSignalLevel maps -100..-55 dBm onto the bars, -48 is "full" (the old fixed value). */
    fun rssiFor(bars: Int): Int = when (bars.coerceIn(1, MAX_BARS)) { 1 -> -84; 2 -> -74; 3 -> -63; else -> -48 }

    private val NEIGHBOR_NAMES = listOf(
        "TP-Link_3F2A", "NETGEAR57", "HUAWEI-B315-9C11", "Vodafone-2A1B", "MEO-48F0C3", "NOS-77D2", "Linksys04821",
        "DIRECT-7b-HP OfficeJet", "Casa", "FRITZ!Box 7530 QX", "Livebox-61E0", "Guest WiFi",
    )

    /**
     * 4-5 other networks "in range", the same ones every boot for a given network name: WPA2 (so they cannot be
     * joined), weaker than ours, on the usual 2.4 and 5 GHz channels. Format of each line:
     * ssid|bssid|capabilities|rssi|frequency (read by guest-wifi WifiConfig).
     */
    fun neighbors(ownSsid: String): List<String> {
        val seed = MessageDigest.getInstance("SHA-1").digest(ownSsid.toByteArray(Charsets.UTF_8)).map { it.toInt() and 0xff }
        val names = NEIGHBOR_NAMES.filter { it != ownSsid }.sortedBy { n -> (n.hashCode() xor seed[0] * 31 xor seed[1]) }
        val count = 4 + seed[2] % 2
        val freqs = listOf(2412, 2437, 2462, 5180, 5240, 5745)
        return names.take(count).mapIndexed { i, n ->
            val rssi = -58 - (seed[3 + i] % 30)
            val caps = if (seed[8 + i] % 3 == 0) "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][WPS][ESS]" else "[WPA2-PSK-CCMP][ESS]"
            "$n|${bssid(n)}|$caps|$rssi|${freqs[seed[13 + i] % freqs.size]}"
        }
    }

    fun encode(p: Properties): ByteArray = ByteArrayOutputStream().also { p.store(it, "AEmulator Wi-Fi") }.toByteArray()

    /** Writes (or, when emulation is off or impossible, removes) the settings file of the guest in [root]. */
    fun write(root: File, s: VmSettings, active: Boolean) {
        val f = File(root, FILE)
        if (!active) { f.delete(); return }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(encode(properties(s)))
        tmp.setReadable(true, false)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
