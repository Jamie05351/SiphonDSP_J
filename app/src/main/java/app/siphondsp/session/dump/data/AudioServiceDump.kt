package app.siphondsp.session.dump.data

import app.siphondsp.model.AudioSessionDumpEntry

/**
 * [nothingPlaying]: the dump listed players with a readable state and none was started, so an
 * empty [sessions] is a real answer ("nothing is playing"), not a failure to read the dump.
 */
data class AudioServiceDump(
    override val sessions: HashMap<Int /* sid */, AudioSessionDumpEntry>,
    val nothingPlaying: Boolean = false,
) : ISessionInfoDump
{
    override fun toString(): String {
        val sb = StringBuilder("\n--> Session stack\n")
        sessions.forEach { (key, value) ->
            sb.append("sid=$key\t-> $value\n")
        }
        return sb.toString()
    }
}