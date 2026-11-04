package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.MediaProbePolicy
import io.github.dovecoteescapee.byedpi.core.StrategyProbeResult
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyUIPreferences
import io.github.dovecoteescapee.byedpi.core.TunnelConfiguration
import org.junit.Assert.*
import org.junit.Test

class MediaProbePolicyTest {
    @Test fun statusLineMustBeTerminatedAndBounded() {
        fun stream(text: String) = text.byteInputStream(Charsets.US_ASCII)
        assertEquals("HTTP/1.1 200 OK",
            MediaProbePolicy.readStatusLine(stream("HTTP/1.1 200 OK\r\nHeader: ignored")))
        assertNull(MediaProbePolicy.readStatusLine(stream("HTTP/1.1 200 OK")))
        assertNull(MediaProbePolicy.readStatusLine(stream("X".repeat(600) + "\n")))
        assertNull(MediaProbePolicy.readStatusLine(stream("HTTP/1.1 200\u0000OK\n")))
    }
    @Test fun rejectsTlsOnlyAndMalformedResponses() {
        listOf("", "200 OK", "HTTP/2 200 OK", "HTTP/1.1 abc OK",
            "HTTP/1.1 20 OK", "HTTP/1.1 2000 OK", "HTTP/1.1 200 OK\r\nInjected: yes")
            .forEach { assertFalse(it, MediaProbePolicy.acceptsStatus(it)) }
    }
    @Test fun acceptsOnlySuccessAndExplicitGatewayNotFound() {
        assertTrue(MediaProbePolicy.acceptsStatus("HTTP/1.1 204 No Content"))
        assertTrue(MediaProbePolicy.acceptsStatus("HTTP/1.0 200 OK"))
        assertFalse(MediaProbePolicy.acceptsStatus("HTTP/1.1 404 Not Found"))
        assertTrue(MediaProbePolicy.acceptsStatus("HTTP/1.1 404 Not Found", true))
        listOf(301,302,307,400,403,429,500,503).forEach {
            assertFalse(MediaProbePolicy.acceptsStatus("HTTP/1.1 $it Blocked", true))
        }
    }
    @Test fun videoSelectionRequiresMediaNotUdpOrDiscord() {
        val goal = MediaProbePolicy.Goal.Video
        val homepageOnly = StrategyProbeResult.fromEndpointResults(listOf("youtube-core" to 10L), null)
        assertFalse(homepageOnly.isEligible(goal.required, goal.requireUdp))
        val media = StrategyProbeResult.fromEndpointResults(
            listOf("youtube-core" to 10L, "youtube-media" to 20L), null)
        assertTrue(media.isEligible(goal.required, goal.requireUdp))
    }
    @Test fun voiceSelectionRequiresGatewayAndUdp() {
        val goal = MediaProbePolicy.Goal.Voice
        val endpoints = listOf("discord-core" to 10L, "discord-gateway" to 20L)
        assertFalse(StrategyProbeResult.fromEndpointResults(endpoints, null)
            .isEligible(goal.required, goal.requireUdp))
        assertTrue(StrategyProbeResult.fromEndpointResults(endpoints, 40L)
            .isEligible(goal.required, goal.requireUdp))
    }
    @Test fun omittedProductsRemainDistinctFromFailures() {
        val result = StrategyProbeResult.fromEndpointResults(
            listOf("youtube-core" to 10L, "youtube-media" to null), null)
        assertEquals(setOf("youtube-core","youtube-media"), result.attemptedProducts)
        assertFalse("discord-core" in result.attemptedProducts)
        assertEquals(setOf("youtube-core"), result.successfulProducts)
    }
    @Test fun goalAndTargetConfigurationIsComplete() {
        assertEquals(MediaProbePolicy.Goal.All, MediaProbePolicy.Goal.fromId("unknown"))
        assertEquals(MediaProbePolicy.Goal.All, MediaProbePolicy.Goal.fromId(null))
        val products = MediaProbePolicy.targets.map { it.product }.toSet()
        MediaProbePolicy.Goal.entries.forEach { assertTrue(products.containsAll(it.required)) }
        assertTrue(MediaProbePolicy.targets.all { it.path.startsWith("/") && !it.host.contains('/') })
        assertTrue(MediaProbePolicy.targets.filter { it.allowNotFound }.all { it.product == "discord-gateway" })
    }
    @Test fun noFakeCandidatesRetainUdpRelayWithoutInjectingFakePackets() {
        listOf(StrategyProfiles.Profile.Split, StrategyProfiles.Profile.Tls).forEach {
            val prefs = StrategyProfiles.candidatePreferences(ByeDpiProxyUIPreferences(), it)
            assertFalse(prefs.desyncUdp)
            assertEquals(0, prefs.udpFakeCount)
            assertEquals(ByeDpiProxyUIPreferences.DesyncMethod.Split, prefs.desyncMethod)
        }
    }
    @Test fun mtuCannotBecomeZeroJumboOrMalformed() {
        listOf(null, "", "0", "9000", "8500", "-1", "1499", "x", "2147483648")
            .forEach { assertEquals(1500, TunnelConfiguration.mtu(it)) }
        listOf(1280,1400,1500).forEach { assertEquals(it, TunnelConfiguration.mtu(it.toString())) }
    }
    @Test fun tunnelUsesTheRequestedMtuAndKeepsNativeUdp() {
        val config = TunnelConfiguration.yaml(1080, 1280)
        assertTrue(config.contains("mtu: 1280"))
        assertTrue(config.contains("port: 1080"))
        assertTrue(config.contains("udp: udp"))
        assertFalse(config.contains("8500"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidTunnelPortIsRejected() { TunnelConfiguration.yaml(0, 1500) }
    @Test(expected = IllegalArgumentException::class)
    fun invalidTunnelMtuIsRejected() { TunnelConfiguration.yaml(1080, 8500) }
}
