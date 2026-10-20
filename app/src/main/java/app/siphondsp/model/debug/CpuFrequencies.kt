package app.siphondsp.model.debug

import java.io.File

/**
 * Current CPU clock per cpufreq policy (cluster), from sysfs, for spotting thermal throttling
 * over a session: a falling scaling_max_freq cap, or a current clock pinned well below the
 * hardware maximum while audio struggles. File reads -- call off the main thread.
 */
object CpuFrequencies {
    data class Policy(
        val name: String,
        val cpus: String,
        val currentKhz: Long?,
        val capKhz: Long?,
        val hardwareMaxKhz: Long?,
    )

    private val root = File("/sys/devices/system/cpu/cpufreq")

    /** One entry per readable policy; empty when the platform hides cpufreq from apps. */
    fun read(): List<Policy> = try {
        root.listFiles { file -> file.name.startsWith("policy") }
            .orEmpty()
            .sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            .map { dir ->
                Policy(
                    name = dir.name,
                    cpus = readText(dir, "related_cpus") ?: "?",
                    currentKhz = readText(dir, "scaling_cur_freq")?.toLongOrNull(),
                    capKhz = readText(dir, "scaling_max_freq")?.toLongOrNull(),
                    hardwareMaxKhz = readText(dir, "cpuinfo_max_freq")?.toLongOrNull(),
                )
            }
    } catch (_: Exception) {
        emptyList()
    }

    private fun readText(dir: File, name: String): String? = try {
        File(dir, name).readText().trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}
