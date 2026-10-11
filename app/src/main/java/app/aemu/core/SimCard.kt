/* AEmulator Super Plus, 2026-10-11: configurable fake SIM cards. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.json.JSONObject
import java.io.File

/**
 * One fake SIM card of the emulated modem ([RilStub]). Blank fields keep the old behaviour: operator code from the
 * firmware's apns-conf.xml, name "AEmulator", no phone number, identifiers derived from the operator code.
 */
data class SimCard(
    /** false = slot empty: the firmware shows "No SIM card" and has no mobile network */
    val present: Boolean = true,
    /** network / service provider name shown in the status bar and on the lock screen */
    val operator: String = "",
    /** mobile country code, 3 digits */
    val mcc: String = "",
    /** mobile network code, 2 or 3 digits */
    val mnc: String = "",
    /** own phone number (Settings > About phone > Status), digits with an optional leading + */
    val number: String = "",
    /** SIM serial; blank = generated from the operator code */
    val iccid: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("present", present).put("operator", operator).put("mcc", mcc)
        .put("mnc", mnc).put("number", number).put("iccid", iccid)

    companion object {
        const val DEFAULT_OPERATOR = "AEmulator"
        const val OPERATOR_MAX = 16          // EF_SPN holds 16 characters

        fun fromJson(o: JSONObject?): SimCard = if (o == null) SimCard() else SimCard(
            present = o.optBoolean("present", true),
            operator = cleanOperator(o.optString("operator", "")),
            mcc = cleanMcc(o.optString("mcc", "")),
            mnc = cleanMnc(o.optString("mnc", "")),
            number = cleanNumber(o.optString("number", "")),
            iccid = cleanIccid(o.optString("iccid", "")),
        )

        /** Printable characters only; GSM 7-bit is not required, EF_SPN falls back to UCS-2. */
        fun cleanOperator(v: String) = v.filter { !it.isISOControl() }.take(OPERATOR_MAX)
        fun cleanMcc(v: String) = v.filter(Char::isDigit).take(3)
        fun cleanMnc(v: String) = v.filter(Char::isDigit).take(3)
        fun cleanNumber(v: String): String {
            val digits = v.filter(Char::isDigit).take(15)
            return if (v.trimStart().startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
        }
        fun cleanIccid(v: String) = v.filter(Char::isDigit).take(20)
        fun codeValid(mcc: String, mnc: String) = mcc.length == 3 && mnc.length in 2..3
    }
}

/** A real network, offered in the settings so the codes need not be typed. */
data class OperatorPreset(val country: String, val name: String, val mcc: String, val mnc: String)

object SimCards {
    val PRESETS = listOf(
        OperatorPreset("Portugal", "MEO", "268", "06"), OperatorPreset("Portugal", "Vodafone P", "268", "01"),
        OperatorPreset("Portugal", "NOS", "268", "03"),
        OperatorPreset("Brasil", "Vivo", "724", "06"), OperatorPreset("Brasil", "Claro BR", "724", "05"),
        OperatorPreset("Brasil", "TIM BRASIL", "724", "02"), OperatorPreset("Brasil", "Oi", "724", "31"),
        OperatorPreset("USA", "AT&T", "310", "410"), OperatorPreset("USA", "T-Mobile", "310", "260"),
        OperatorPreset("USA", "Verizon", "311", "480"),
        OperatorPreset("Canada", "Rogers", "302", "720"), OperatorPreset("Canada", "Bell", "302", "610"),
        OperatorPreset("Canada", "TELUS", "302", "220"),
        OperatorPreset("México", "Telcel", "334", "020"),
        OperatorPreset("UK", "EE", "234", "30"), OperatorPreset("UK", "Vodafone UK", "234", "15"),
        OperatorPreset("UK", "O2 - UK", "234", "10"), OperatorPreset("UK", "3", "234", "20"),
        OperatorPreset("España", "Movistar", "214", "07"), OperatorPreset("España", "Vodafone ES", "214", "01"),
        OperatorPreset("España", "Orange", "214", "03"),
        OperatorPreset("France", "Orange F", "208", "01"), OperatorPreset("France", "SFR", "208", "10"),
        OperatorPreset("France", "Bouygues", "208", "20"), OperatorPreset("France", "Free", "208", "15"),
        OperatorPreset("Deutschland", "Telekom.de", "262", "01"), OperatorPreset("Deutschland", "Vodafone.de", "262", "02"),
        OperatorPreset("Deutschland", "o2 - de", "262", "03"),
        OperatorPreset("Italia", "TIM", "222", "01"), OperatorPreset("Italia", "Vodafone IT", "222", "10"),
        OperatorPreset("Italia", "WINDTRE", "222", "88"),
        OperatorPreset("Polska", "Orange PL", "260", "03"), OperatorPreset("Polska", "T-Mobile PL", "260", "02"),
        OperatorPreset("Polska", "Play", "260", "06"), OperatorPreset("Polska", "Plus", "260", "01"),
        OperatorPreset("Türkiye", "Turkcell", "286", "01"), OperatorPreset("Türkiye", "Vodafone TR", "286", "02"),
        OperatorPreset("Россия", "MTS RUS", "250", "01"), OperatorPreset("Россия", "MegaFon", "250", "02"),
        OperatorPreset("Россия", "Beeline", "250", "99"),
        OperatorPreset("Україна", "Kyivstar", "255", "03"), OperatorPreset("Україна", "Vodafone UA", "255", "01"),
        OperatorPreset("India", "Airtel", "404", "10"), OperatorPreset("India", "Jio", "405", "857"),
        OperatorPreset("Indonesia", "Telkomsel", "510", "10"),
        OperatorPreset("日本", "NTT DOCOMO", "440", "10"), OperatorPreset("日本", "SoftBank", "440", "20"),
        OperatorPreset("Emulator", "Android", "310", "260"),
    )

    /** What the modem reports for [sim]: blank fields resolved against the firmware ([fallback] = apns-conf operator). */
    data class Resolved(val operator: String, val mcc: String, val mnc: String, val number: String, val iccid: String, val imsi: String)

    fun resolve(sim: SimCard, fallback: Pair<String, String>, slot: Int = 0): Resolved {
        val (mcc, mnc) = if (SimCard.codeValid(sim.mcc, sim.mnc)) sim.mcc to sim.mnc else fallback
        val operator = sim.operator.ifBlank {
            if (SimCard.codeValid(sim.mcc, sim.mnc)) PRESETS.firstOrNull { it.mcc == mcc && it.mnc == mnc }?.name ?: SimCard.DEFAULT_OPERATOR
            else SimCard.DEFAULT_OPERATOR
        }
        // subscriber number part of the IMSI: from the phone number when there is one, so it stays stable per SIM
        val msinLen = 15 - mcc.length - mnc.length
        val digits = sim.number.filter(Char::isDigit)
        // untouched first SIM: exactly the identifiers the modem always reported, so existing VMs see no SIM change
        val legacy = slot == 0 && digits.isEmpty() && !SimCard.codeValid(sim.mcc, sim.mnc)
        val msin = when {
            digits.isNotEmpty() -> digits.takeLast(msinLen).padStart(msinLen, '0')
            else -> ("$slot" + "123456789").take(msinLen).let { if (slot == 0) "0123456789".take(msinLen) else it }
        }
        val iccid = sim.iccid.ifBlank { if (legacy) LegacySimIo.ICCID else iccidFor(mcc, mnc, msin) }
        return Resolved(operator, mcc, mnc, sim.number, iccid, mcc + mnc + msin)
    }

    /** 19-digit ICCID: 89 (telecom), country digits from the MCC, issuer from the MNC, account, Luhn check digit. */
    fun iccidFor(mcc: String, mnc: String, msin: String): String {
        val body = ("89" + mcc + mnc.padStart(3, '0') + msin).take(18).padEnd(18, '0')
        return body + luhn(body)
    }

    fun luhn(digits: String): Int {
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 0) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return (10 - sum % 10) % 10
    }

    /** The operator codes the firmware has an internet APN for (mobile data works only with one of them). */
    fun apnOperators(root: File): Set<Pair<String, String>> = runCatching {
        val xml = File(root, "system/etc/apns-conf.xml").readText()
        Regex("<apn\\b([^>]*)>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val a = m.groupValues[1]
            fun attr(n: String) = Regex("\\b$n\\s*=\\s*\"([^\"]*)\"").find(a)?.groupValues?.get(1)
            val type = attr("type")
            val mcc = attr("mcc"); val mnc = attr("mnc")
            if (mcc == null || mnc == null || attr("mvno_type") != null) null
            else if (type == null || type.contains("default") || type == "*") mcc to mnc else null
        }.toSet()
    }.getOrDefault(emptySet())

    // ------------------------------------------------------------------ SIM elementary files (TS 51.011 / 31.102)

    /** EF_SPN (6F46): display condition + 16 bytes of name, GSM 7-bit default alphabet (UCS-2 if the name needs it). */
    fun efSpn(name: String): ByteArray {
        val out = ByteArray(17) { 0xff.toByte() }
        out[0] = 0                                        // show the SPN; the registered network name is optional
        val gsm = name.all { it.code in 0x20..0x7e && it !in "[]{}\\^~|`" }
        if (gsm) name.take(16).forEachIndexed { i, c -> out[1 + i] = (if (c == '@') 0 else if (c == '$') 2 else if (c == '_') 0x11 else c.code).toByte() }
        else {
            out[1] = 0x80.toByte()                        // UCS-2, big endian
            val b = name.take(7).toByteArray(Charsets.UTF_16BE)
            b.copyInto(out, 2)
        }
        return out
    }

    const val MSISDN_RECORD = 28                          // 14 bytes alpha id + 14 bytes number

    /** EF_MSISDN (6F40) record: alpha id "My number", BCD number (international when it starts with +). */
    fun efMsisdn(number: String): ByteArray {
        val r = ByteArray(MSISDN_RECORD) { 0xff.toByte() }
        "Mobile".forEachIndexed { i, c -> r[i] = c.code.toByte() }
        val digits = number.filter(Char::isDigit).take(20)
        if (digits.isEmpty()) return r
        val bcd = bcd(digits)
        r[14] = (1 + bcd.size).toByte()                   // TON/NPI byte + number bytes
        r[15] = if (number.startsWith("+")) 0x91.toByte() else 0x81.toByte()
        bcd.copyInto(r, 16)
        return r
    }

    /** EF_AD (6FAD): normal operation, length of the MNC in the IMSI. */
    fun efAd(mnc: String) = byteArrayOf(0, 0, 0, mnc.length.toByte())

    /** EF_ICCID (2FE2): nibble-swapped BCD, F padded. */
    fun efIccid(iccid: String): ByteArray = bcd(iccid.padEnd(20, 'F').take(20)).copyOf(10)

    /** semi-octets, nibbles swapped, F padded */
    private fun bcd(d: String): ByteArray {
        val s = if (d.length % 2 == 1) d + "F" else d
        return ByteArray(s.length / 2) { i ->
            val lo = s[2 * i].digitToInt(16); val hi = s[2 * i + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }
}
