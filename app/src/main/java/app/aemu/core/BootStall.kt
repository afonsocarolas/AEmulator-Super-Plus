/* AEmulator Super Plus, 2026-10-14: what a stuck boot is doing, in the host log. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * A boot that neither finishes nor crashes leaves nothing in the host log: everything happens inside the guest. When
 * the boot has run for a while, the supervisor copies the lines that show where it waits into the host log, so the
 * screen or the host log alone says why (system_server waiting for a service, a watchdog, a slow step...).
 */
object BootStall {
    /** first report after this long without boot completion, then again every [AGAIN_MS] */
    const val FIRST_MS = 150_000L
    const val AGAIN_MS = 180_000L

    private val HINTS = listOf("waiting for", "wait for", "slow operation", "blocked", "watchdog", "timed out", "timeout",
        "not found", "is not ready", "still not", "unable to", "failed")

    /**
     * [system]: the end of the guest's system buffer (system_server, ActivityManager, PackageManager...), [main]: the end
     * of its main buffer. Returns at most about 30 short lines.
     */
    fun report(system: List<GuestLog.Rec>, main: List<GuestLog.Rec>): List<String> {
        fun line(r: GuestLog.Rec) = "${r.prio}/${r.tag}: ${r.msg.trim().replace('\n', ' ').take(200)}"
        val out = ArrayList<String>()
        // what it waits for, from either buffer
        val recent = system.takeLast(400) + main.takeLast(400)
        val hints = recent.filter { r -> HINTS.any { r.msg.contains(it, ignoreCase = true) } }.map(::line).distinct().takeLast(6)
        if (hints.isNotEmpty()) { out += "waiting / failing:"; hints.forEach { out += "  $it" } }
        // where system_server got to
        val sys = collapse(system.takeLast(60).map(::line)).takeLast(16)
        if (sys.isNotEmpty()) { out += "last system log:"; sys.forEach { out += "  $it" } }
        // errors from the other processes
        val errs = collapse(main.filter { it.prio == 'W' || it.prio == 'E' || it.prio == 'F' }.takeLast(40).map(::line)).takeLast(8)
        if (errs.isNotEmpty()) { out += "last warnings and errors:"; errs.forEach { out += "  $it" } }
        if (out.isEmpty()) out += "the guest has logged nothing yet"
        return out
    }

    /** a run of the same line becomes one line with a count */
    private fun collapse(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            var j = i
            while (j + 1 < lines.size && lines[j + 1] == lines[i]) j++
            out += if (j > i) "${lines[i]} (×${j - i + 1})" else lines[i]
            i = j + 1
        }
        return out
    }
}
