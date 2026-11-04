package io.github.dovecoteescapee.byedpi

import android.content.SharedPreferences
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PresetSnapshotTest {
    private fun memory(values: MutableMap<String, Any?>): SharedPreferences {
        val pending = mutableMapOf<String, Any?>()
        val removed = mutableSetOf<String>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "apply", "commit" -> {
                    removed.forEach(values::remove)
                    values.putAll(pending)
                    pending.clear(); removed.clear()
                    if (method.name == "commit") true else null
                }
                "remove" -> { removed += args!![0] as String; editor }
                else -> {
                    check(method.name.startsWith("put"))
                    pending[args!![0] as String] = args[1]; editor
                }
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> error("Unexpected access in snapshot test: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun restorePreservesCustomModeAndUnrelatedPreferences() {
        val values = mutableMapOf<String, Any?>(
            "byedpi_enable_cmd_settings" to true,
            "byedpi_strategy_profile" to "balanced",
            "byedpi_split_position" to "7",
            "byedpi_cmd_args" to "--split 7",
            "byedpi_mode" to "vpn",
            "dns_ip" to "9.9.9.9")
        val prefs = memory(values)
        val snapshot = StrategyProfiles.snapshot(prefs)
        values["byedpi_enable_cmd_settings"] = false
        values["byedpi_strategy_profile"] = "auto"
        values["byedpi_split_position"] = "1"
        values["byedpi_desync_udp"] = true
        values["dns_ip"] = "1.1.1.1"
        StrategyProfiles.restore(prefs, snapshot)
        assertEquals(true, values["byedpi_enable_cmd_settings"])
        assertEquals("balanced", values["byedpi_strategy_profile"])
        assertEquals("7", values["byedpi_split_position"])
        assertEquals("--split 7", values["byedpi_cmd_args"])
        assertEquals("vpn", values["byedpi_mode"])
        assertEquals("1.1.1.1", values["dns_ip"])
        assertFalse(values.containsKey("byedpi_desync_udp"))
    }

    @Test fun snapshotIsIndependentAndRetainsAbsentKeys() {
        val values = mutableMapOf<String, Any?>("byedpi_strategy_config_version" to 1)
        val prefs = memory(values)
        val snapshot = StrategyProfiles.snapshot(prefs)
        values["byedpi_strategy_config_version"] = 999
        values["telegram_compat"] = true
        StrategyProfiles.restore(prefs, snapshot)
        assertEquals(1, values["byedpi_strategy_config_version"])
        assertFalse(values.containsKey("telegram_compat"))
    }
}
