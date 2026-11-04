package io.github.dovecoteescapee.byedpi.core

/** HTTP reachability is not proof of video playback or an authenticated voice session. */
object MediaProbePolicy {
    fun readStatusLine(input: java.io.InputStream, beforeRead: () -> Unit = {}): String? {
        val line = StringBuilder()
        repeat(512) {
            beforeRead()
            val ch = input.read()
            if (ch < 0) return null
            if (ch == 10) return line.toString().removeSuffix("\r")
            if (ch !in 32..126 && ch != 13) return null
            line.append(ch.toChar())
        }
        return null
    }
    const val GOAL_KEY = "strategy_probe_goal"
    enum class Goal(val id: String, val required: Set<String>, val requireUdp: Boolean) {
        All("all", setOf("youtube-core", "youtube-media", "discord-core", "discord-gateway"), true),
        Video("video", setOf("youtube-core", "youtube-media"), false),
        Voice("voice", setOf("discord-core", "discord-gateway"), true);
        companion object {
            fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: All
        }
    }
    data class Target(val host: String, val product: String, val path: String, val allowNotFound: Boolean = false)
    val targets = listOf(
        Target("discord.com", "discord-core", "/api/v10/gateway"),
        Target("gateway.discord.gg", "discord-gateway", "/", allowNotFound = true),
        Target("www.youtube.com", "youtube-core", "/generate_204"),
        Target("redirector.googlevideo.com", "youtube-media", "/report_mapping"),
    )
    fun acceptsStatus(line: String, allowNotFound: Boolean = false): Boolean {
        val match = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: .*)?$").matchEntire(line) ?: return false
        val status = match.groupValues[1].toInt()
        // Redirects may be captive portals or block pages, not service reachability.
        return status in 200..299 || (allowNotFound && status == 404)
    }
}
