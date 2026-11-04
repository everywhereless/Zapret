package io.github.dovecoteescapee.byedpi.core

/** One MTU for Android's TUN and native stack, rather than implicit jumbo frames. */
object TunnelConfiguration {
    const val DEFAULT_MTU = 1500
    fun mtu(value: String?): Int = value?.toIntOrNull()
        ?.takeIf { it in setOf(1280, 1400, 1500) } ?: DEFAULT_MTU

    fun yaml(port: Int, mtu: Int): String {
        require(port in 1..65535)
        require(mtu in setOf(1280, 1400, 1500))
        return """
            tunnel:
              mtu: $mtu
            socks5:
              address: 127.0.0.1
              port: $port
              udp: udp
            misc:
              task-stack-size: 86016
              udp-recv-buffer-size: 1048576
              udp-copy-buffer-nums: 32
              udp-read-write-timeout: 120000
        """.trimIndent()
    }
}
