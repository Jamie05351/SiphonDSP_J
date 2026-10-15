package app.siphondsp.session.dump.provider

import android.content.Context
import app.siphondsp.model.AudioSessionDumpEntry
import app.siphondsp.session.dump.data.AudioServiceDump
import app.siphondsp.session.dump.data.ISessionInfoDump
import app.siphondsp.session.dump.utils.AudioFlingerServiceDumpUtils
import app.siphondsp.session.dump.utils.DumpUtils
import app.siphondsp.utils.extensions.ContextExtensions.getPackageNameFromUid
import timber.log.Timber

class AudioServiceDumpProvider : ISessionDumpProvider {

    override fun dump(context: Context): ISessionInfoDump? {
        val dump = DumpUtils.dumpAll(context, TARGET_SERVICE)
        dump ?: return null

        return process(context, dump)
    }

    private fun process(context: Context, dump: String): ISessionInfoDump {

        // API 29 (No session id)
        val playbackConfRegex29 = """ID:\d+.*u\/pid:(\d+)\/(\d+).*usage=(\w+).*content=(\w+)""".toRegex()
        // API 30 (No session id)
        val playbackConfRegex30 = """AudioPlaybackConfiguration.*u\/pid:(\d+)\/(\d+).*usage=(\w+).*content=(\w+)""".toRegex()
        // API 31+
        val playbackConfRegex31 = """AudioPlaybackConfiguration.*u\/pid:(\d+)\/(\d+).*usage=(\w+).*content=(\w+).*sessionId:(\d+)""".toRegex()

        val sidPidLookupMap = mutableMapOf<Int /* pid */, Int /* sid */>()
        val globalSessionRefs = AudioFlingerServiceDumpUtils.dump(context)
        globalSessionRefs?.forEach {
            if(sidPidLookupMap.contains(it.pid))
            {
                Timber.w("SID/PID map: Duplicated PID (pid=${it.pid}; sid=${it.sid})")
            }
            else
            {
                Timber.d("SID/PID map: AudioFlinger: pid=${it.pid}; sid=${it.sid}")
            }

            sidPidLookupMap[it.pid] = it.sid
        }

        val sessions = hashMapOf<Int, AudioSessionDumpEntry>()

        var matches = playbackConfRegex31.findAll(dump)
        // Fallbacks
        if(matches.count() <= 0)
            matches = playbackConfRegex30.findAll(dump)
        if(matches.count() <= 0)
            matches = playbackConfRegex29.findAll(dump)

        // Note: API 29 & 30 lack a session id
        matches.forEach next@ {
            try {
                // AudioService retains AudioPlaybackConfiguration entries after a player is
                // paused/stopped, so presence in the dump is not proof that capture should be
                // producing buffers. Android reports the player state as `state:started`,
                // `state:paused`, `state:stopped`, `state:idle`, etc. When that field is present,
                // only STARTED is an active source. Older/OEM dump formats that omit state retain
                // the historical behaviour so session discovery does not regress there.
                if (!isPlaybackConfigurationActive(it.value)) {
                    Timber.v("Ignoring inactive AudioPlaybackConfiguration: %s", it.value)
                    return@next
                }

                var uid: Int? = null
                var pid: Int? = null
                var usage: String? = null
                var content = "CONTENT_TYPE_UNKNOWN"
                try{
                    uid = it.groups[1]?.value?.toInt()
                    pid = it.groups[2]?.value?.toInt()
                    usage = it.groups[3]?.value
                    content = it.groups[4]?.value ?: "CONTENT_TYPE_UNKNOWN"
                }
                catch(ex: IndexOutOfBoundsException)
                {
                    Timber.e(ex)
                }

                if(pid == null || uid == null || usage == null)
                {
                    Timber.e("Failed to parse match for p/uid: $pid/$uid (usage=$usage)")
                    return@next
                }

                var sid = try {
                    it.groups[5]?.value?.toInt()
                }
                catch(ex: Exception) {
                    null
                }
                if(sid == null && sidPidLookupMap.contains(pid))
                {
                    // Fallback to SID/PID table from AudioFlinger
                    sid = sidPidLookupMap[pid]
                    Timber.d("Falling back to SID lookup via AudioFlinger (p/uid=$pid/$uid; usage=$usage; content=$content) => sid=$sid")
                }

                if(sid == null)
                {
                    Timber.e("Failed to determine session id for p/uid: $pid/$uid (usage=$usage; content=$content)")
                    return@next
                }

                val pkg = context.getPackageNameFromUid(uid) ?: uid.toString()
                sessions[sid] = AudioSessionDumpEntry(uid, pkg, usage, content)
            } catch (ex: NumberFormatException) {
                Timber.e("Failed to parse match")
                Timber.e(ex)
            }
        }

        Timber.d("Dump processed")
        return AudioServiceDump(sessions)
    }

    override fun dumpString(context: Context): String {
        val dump = DumpUtils.dumpAll(context, TARGET_SERVICE)
        val sb = StringBuilder("=====> $TARGET_SERVICE raw dump\n")
        sb.append(dump)
        sb.append("\n\n")
        sb.append("=====> $TARGET_SERVICE processed dump\n")
        sb.append(process(context, dump ?: ""))
        sb.append("\n\n")
        sb.append(AudioFlingerServiceDumpUtils.dumpString(context))

        return sb.toString()
    }

    companion object {
        const val TARGET_SERVICE = "audio"
        private val PLAYER_STATE_REGEX = """(?:--\s*)?state:\s*([A-Za-z_]+)""".toRegex(RegexOption.IGNORE_CASE)

        /**
         * AudioPlaybackConfiguration.isActive() is true only for PLAYER_STATE_STARTED. Mirror
         * that semantics when AudioService exposes a state token in its text dump. A missing
         * token means an older/OEM format we cannot classify, so preserve the provider's legacy
         * behaviour rather than dropping a potentially real session.
         */
        internal fun isPlaybackConfigurationActive(line: String): Boolean {
            val state = PLAYER_STATE_REGEX.find(line)?.groupValues?.getOrNull(1) ?: return true
            return state.equals("started", ignoreCase = true)
        }
    }
}
