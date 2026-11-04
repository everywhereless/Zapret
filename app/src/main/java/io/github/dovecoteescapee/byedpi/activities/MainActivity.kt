package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.fragments.MainSettingsFragment
import io.github.dovecoteescapee.byedpi.databinding.ActivityMainBinding
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import io.github.dovecoteescapee.byedpi.core.StrategyProbeCoordinator
import io.github.dovecoteescapee.byedpi.core.StrategyProbeResult
import io.github.dovecoteescapee.byedpi.core.MediaProbePolicy
import io.github.dovecoteescapee.byedpi.core.TProxyService
import io.github.dovecoteescapee.byedpi.core.StrategyProbeProxyRunner
import io.github.dovecoteescapee.byedpi.core.Socks5UdpProbe
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyPreferences
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyUIPreferences
import io.github.dovecoteescapee.byedpi.core.ConnectionDiagnostics
import io.github.dovecoteescapee.byedpi.services.ServiceTransitionCoordinator
import io.github.dovecoteescapee.byedpi.services.TelegramWsProxyService
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var metricsJob: Job? = null
    private var strategyProbeJob: Job? = null
    private var telegramStartJob: Job? = null
    private var mediaCheckJob: Job? = null
    private var updatingTelegramSwitch = false
    private var connectionStartedAt = 0L
    private var restartModeAfterProfile: Mode? = null
    private val strategyProbeInProgress: Boolean
        get() = StrategyProbeCoordinator.isActive
    private val controlsLocked: Boolean
        get() = strategyProbeInProgress || ServiceTransitionCoordinator.isActive
    private var lastVisualStatus: AppStatus? = null
    private var lastVisualMode: Mode? = null

    private val pressInInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val pressOutInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    companion object {
        const val EXTRA_AUTO_START = "io.github.lolososka.zapretmobile.AUTO_START"
        private const val STATE_RESTART_MODE_AFTER_PROFILE = "main.restart_mode_after_profile"
        private val TAG: String = MainActivity::class.java.simpleName
        private val PROBE_TARGETS = MediaProbePolicy.targets
        private val PRIORITY_PROBE_PRODUCTS = setOf(
            "discord-gateway",
            "discord-media",
            "youtube-media",
        )
    }

    private fun collectLogs(minimal: Boolean): String? = try {
        val preferences = getPreferences()
        val config = runCatching {
            when (val proxyPreferences = ByeDpiProxyPreferences.fromSharedPreferences(preferences)) {
                is ByeDpiProxyUIPreferences ->
                    "listen=${proxyPreferences.ip}:${proxyPreferences.port}, " +
                        "method=${proxyPreferences.desyncMethod.name.lowercase(Locale.ROOT)}, " +
                        "split=${proxyPreferences.splitPosition}, " +
                        "tlsrec=${proxyPreferences.tlsRecordSplit}, " +
                        "udp=${proxyPreferences.desyncUdp}/${proxyPreferences.udpFakeCount}"
                else -> "custom command line enabled (arguments omitted)"
            }
        }.getOrElse { "invalid settings: ${it.message ?: it.javaClass.simpleName}" }
        val configuredMode = runCatching { preferences.mode() }.getOrElse { Mode.VPN }
        val report = StringBuilder()
            .appendLine("Zapret Mobile ${BuildConfig.VERSION_NAME}")
            .appendLine("Android ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}")
            .appendLine("Status: ${appStatus.first} · mode=$configuredMode")
            .appendLine("Strategy: ${StrategyProfiles.diagnosticLabel(preferences)}")
            .appendLine("Config: $config")
            .appendLine(
                "Telegram proxy: running=${TelegramWsProxyService.running.value}, " +
                    "starting=${TelegramWsProxyService.starting.value}, " +
                    "port=${TelegramWsProxyService.portForLink(this)}",
            )
            .appendLine("Last failure: ${ConnectionDiagnostics.lastFailure(this) ?: "none"}")

        if (minimal) {
            report.appendLine("Application log: omitted by privacy setting")
        } else {
            val command = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                arrayOf("logcat", "--pid=${android.os.Process.myPid()}", "-d", "-v", "threadtime")
            } else {
                arrayOf("logcat", "-d", "-v", "threadtime")
            }
            val applicationLog = Runtime.getRuntime()
                .exec(command)
                .inputStream.bufferedReader()
                .use { it.readText() }
            report.appendLine().appendLine("Application log:").append(applicationLog)
        }
        report.toString()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to collect logs", e)
        null
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val autoProbeRequest = StrategyProbeCoordinator.takePermissionRequest()
            if (it.resultCode == RESULT_OK) {
                if (autoProbeRequest != null) {
                    startAutoStrategyProbe(
                        autoProbeRequest.mode,
                        autoProbeRequest.shouldRunAfterProbe,
                    )
                } else ServiceTransitionCoordinator.startPrepared(this)
            } else {
                if (autoProbeRequest != null) StrategyProbeCoordinator.finish()
                ServiceTransitionCoordinator.cancelPrepared()
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch {
                val minimal = getPreferences().getBoolean("privacy_minimal_logs", true)
                val logs = withContext(Dispatchers.IO) { collectLogs(minimal) }

                if (logs == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.logs_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val uri = it.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use {
                            try {
                                it.write(logs.toByteArray())
                            } catch (e: IOException) {
                                Log.e(TAG, "Failed to save logs", e)
                            }
                        } ?: run {
                            Log.e(TAG, "Failed to open output stream")
                        }
                    }
                }
            }
        }

    private val profilePickerRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val restartMode = restartModeAfterProfile?.takeIf {
                appStatus.first == AppStatus.Running && appStatus.second == it
            }
            restartModeAfterProfile = null
            if (result.resultCode == RESULT_OK) {
                updateStatus()
                val preferences = getPreferences()
                val autoProbeRequested =
                    StrategyProfiles.selected(preferences) == StrategyProfiles.Profile.Auto
                if (autoProbeRequested) {
                    val targetMode = restartMode ?: preferences.mode()
                    preferences.edit()
                        .putString("byedpi_mode", targetMode.name.lowercase(Locale.ROOT))
                        .apply()
                    startAutoStrategyProbeWithPermission(
                        targetMode,
                        shouldRunAfterProbe = restartMode != null,
                    )
                } else if (restartMode != null) {
                    preferences.edit()
                        .putString("byedpi_mode", restartMode.name.lowercase(Locale.ROOT))
                        .apply()
                    Toast.makeText(
                        this,
                        R.string.strategy_applied_restart,
                        Toast.LENGTH_SHORT,
                    ).show()
                    ServiceTransitionCoordinator.beginRestart(
                        this,
                        stopMode = restartMode,
                        startMode = restartMode,
                    )
                }
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (debugLoggingEnabled()) Log.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                if (debugLoggingEnabled()) Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                if (debugLoggingEnabled()) Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> {
                    updateStatus()
                }

                FAILED_BROADCAST -> {
                    if (!strategyProbeInProgress) {
                        Toast.makeText(
                            context,
                            getString(R.string.failed_to_start, sender.name),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    updateStatus()
                }

                else -> if (debugLoggingEnabled()) Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        restartModeAfterProfile = savedInstanceState
            ?.getString(STATE_RESTART_MODE_AFTER_PROFILE)
            ?.let { name -> runCatching { Mode.valueOf(name) }.getOrNull() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(TelegramWsProxyService.running, TelegramWsProxyService.starting,
                        TelegramWsProxyService.port) { _, _, _ -> Unit }
                        .collect { updateTelegramStatus() }
                }
                combine(
                    StrategyProbeCoordinator.active,
                    ServiceTransitionCoordinator.active,
                ) { probeActive, transitionActive -> probeActive || transitionActive }
                    .collect { active ->
                        setStrategyProbeControls(enabled = !active)
                        updateStatus()
                    }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        ContextCompat.registerReceiver(
            this,
            receiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        binding.statusButton.setOnClickListener {
            toggleConnection()
        }

        binding.routeDial.setOnClickListener { toggleConnection() }
        binding.modeRow.setOnClickListener { showModeChoice() }
        binding.settingsButton.setOnClickListener { openSettings() }
        binding.strategyBadge.setOnClickListener {
            if (controlsLocked) return@setOnClickListener
            restartModeAfterProfile = appStatus
                .takeIf { it.first == AppStatus.Running }
                ?.second
            profilePickerRegister.launch(Intent(this, StrategyPickerActivity::class.java))
        }
        binding.telegramSetupButton.setOnClickListener {
            if (TelegramWsProxyService.starting.value) return@setOnClickListener
            if (TelegramWsProxyService.running.value) openTelegramProxyLink(telegramProxyLink())
            else startTelegramAndApply()
        }
        binding.telegramSetupButton.setOnLongClickListener { showTelegramSetup(); true }
        binding.telegramSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingTelegramSwitch) return@setOnCheckedChangeListener
            try {
                if (checked) TelegramWsProxyService.start(this) else TelegramWsProxyService.stop(this)
            } catch (_: RuntimeException) {
                Toast.makeText(this, R.string.telegram_ws_failed, Toast.LENGTH_LONG).show()
            }
            updateTelegramStatus()
        }
        binding.updatesButton.setOnClickListener { startActivity(Intent(this, UpdateActivity::class.java)) }
        binding.checkButton.setOnClickListener { checkMedia() }
        binding.autoPickButton.setOnClickListener {
            if (strategyProbeInProgress) {
                strategyProbeJob?.cancel()
                binding.autoPickButton.isEnabled = false
            } else chooseAutoGoal()
        }
        binding.saveLogsButton.setOnClickListener { saveLogs() }

        val theme = getPreferences()
            .getString("app_theme", null)
        MainSettingsFragment.setTheme(theme ?: "dark")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_AUTO_START, false) &&
            appStatus.first == AppStatus.Halted
        ) {
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        metricsJob?.cancel()
        strategyProbeJob?.cancel()
        telegramStartJob?.cancel()
        mediaCheckJob?.cancel()
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        restartModeAfterProfile?.let {
            outState.putString(STATE_RESTART_MODE_AFTER_PROFILE, it.name)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_settings -> {
                openSettings(status)
                true
            }

            R.id.action_save_logs -> {
                saveLogs()
                true
            }

            R.id.action_update -> {
                startActivity(Intent(this, UpdateActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun start() {
        val preferences = getPreferences()
        val mode = preferences.mode()
        if (StrategyProfiles.selected(preferences) == StrategyProfiles.Profile.Auto) {
            startAutoStrategyProbeWithPermission(mode, shouldRunAfterProbe = true)
        } else {
            startDirect(mode)
        }
    }

    private fun startAutoStrategyProbeWithPermission(
        mode: Mode,
        shouldRunAfterProbe: Boolean,
    ) {
        if (controlsLocked) return
        val request = StrategyProbeCoordinator.Request(mode, shouldRunAfterProbe)
        if (shouldRunAfterProbe && mode == Mode.VPN) {
            val intentPrepare = VpnService.prepare(this)
            if (intentPrepare != null) {
                if (!StrategyProbeCoordinator.tryBegin(request)) return
                val launched = runCatching { vpnRegister.launch(intentPrepare) }.isSuccess
                if (!launched) StrategyProbeCoordinator.finish()
                return
            }
        }
        if (!StrategyProbeCoordinator.tryBegin()) return
        startAutoStrategyProbe(mode, shouldRunAfterProbe)
    }

    private fun startDirect(mode: Mode) {
        when (mode) {
            Mode.VPN -> {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    if (ServiceTransitionCoordinator.prepareStartAfterPermission(Mode.VPN)) {
                        val launched = runCatching { vpnRegister.launch(intentPrepare) }.isSuccess
                        if (!launched) ServiceTransitionCoordinator.cancelPrepared()
                    }
                } else {
                    ServiceTransitionCoordinator.beginStart(this, Mode.VPN)
                }
            }

            Mode.Proxy -> ServiceTransitionCoordinator.beginStart(this, Mode.Proxy)
        }
    }

    private fun stop() {
        val (status, mode) = appStatus
        if (status == AppStatus.Running) {
            ServiceTransitionCoordinator.beginStop(this, mode)
        }
    }

    private fun toggleConnection() {
        if (controlsLocked || mediaCheckJob?.isActive == true) return
        when (appStatus.first) {
            AppStatus.Halted -> start()
            AppStatus.Running -> stop()
        }
    }

    private fun chooseAutoGoal() {
        if (controlsLocked || mediaCheckJob?.isActive == true) return
        val goals = MediaProbePolicy.Goal.entries
        AlertDialog.Builder(this).setTitle(R.string.media_goal_title)
            .setItems(arrayOf(getString(R.string.media_goal_all), getString(R.string.media_goal_video),
                getString(R.string.media_goal_voice))) { _, index ->
                getPreferences().edit().putString(MediaProbePolicy.GOAL_KEY, goals[index].id).apply()
                val running = appStatus.first == AppStatus.Running
                val mode = if (running) appStatus.second else getPreferences().mode()
                startAutoStrategyProbeWithPermission(mode, shouldRunAfterProbe = running)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun mediaSummary(result: StrategyProbeResult): String {
        fun state(ok: Boolean) = getString(if (ok) R.string.media_probe_ok else R.string.media_probe_failed)
        fun product(id: String) = if (id in result.attemptedProducts)
            state(id in result.successfulProducts) else getString(R.string.media_probe_skipped)
        return getString(R.string.media_check_summary,
            product("youtube-core"), product("youtube-media"),
            if ("discord-core" !in result.attemptedProducts) getString(R.string.media_probe_skipped)
            else state("discord-core" in result.successfulProducts && "discord-gateway" in result.successfulProducts),
            state(result.udpLatencyMs != null))
    }

    private fun checkMedia() {
        if (controlsLocked || mediaCheckJob?.isActive == true) return
        if (appStatus.first != AppStatus.Running) {
            Toast.makeText(this, R.string.media_check_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        mediaCheckJob = lifecycleScope.launch {
            binding.checkButton.isEnabled = false
            binding.checkProgress.visibility = View.VISIBLE
            setStrategyProbeControls(false)
            try {
                val config = ByeDpiProxyUIPreferences(getPreferences())
                val host = when (config.ip.trim()) {
                    "", "0.0.0.0" -> "127.0.0.1"
                    "::", "[::]" -> "::1"
                    else -> config.ip.trim()
                }
                val result = probeBlockedEndpoints(host, config.port)
                binding.checkResult.text = mediaSummary(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ConnectionDiagnostics.record(this@MainActivity, "Media check", error)
                binding.checkResult.setText(R.string.update_check_failed)
            } finally {
                mediaCheckJob = null
                if (!isDestroyed) {
                    binding.checkProgress.visibility = View.GONE
                    setStrategyProbeControls(!controlsLocked)
                }
            }
        }
    }

    private fun updateTelegramStatus() {
        val busy = TelegramWsProxyService.starting.value
        val running = TelegramWsProxyService.running.value
        updatingTelegramSwitch = true
        binding.telegramSwitch.isChecked = running || busy
        binding.telegramSwitch.isEnabled = !busy
        updatingTelegramSwitch = false
        binding.telegramSetupButton.isEnabled = !busy
        binding.telegramProxyStatus.setText(when {
            busy -> R.string.telegram_proxy_starting
            running -> R.string.telegram_proxy_ready
            else -> R.string.telegram_proxy_off
        })
    }

    private fun showModeChoice() {
        if (controlsLocked) return
        val current = if (appStatus.first == AppStatus.Running) appStatus.second else getPreferences().mode()
        AlertDialog.Builder(this)
            .setTitle(R.string.mode_setting)
            .setSingleChoiceItems(arrayOf(getString(R.string.mode_vpn_value),
                getString(R.string.mode_proxy_value)), if (current == Mode.VPN) 0 else 1) { dialog, which ->
                dialog.dismiss()
                if ((which == 0) != (current == Mode.VPN)) toggleMode()
            }
            .setNeutralButton(R.string.mode_switch) { _, _ ->
                AlertDialog.Builder(this).setMessage(R.string.mode_choose_hint)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun toggleMode() {
        if (controlsLocked) return
        val current = if (appStatus.first == AppStatus.Running) {
            appStatus.second
        } else {
            getPreferences().mode()
        }
        val target = if (current == Mode.VPN) Mode.Proxy else Mode.VPN
        if (appStatus.first == AppStatus.Running) {
            val stopMode = appStatus.second
            val vpnPermission = if (target == Mode.VPN) VpnService.prepare(this) else null
            if (vpnPermission != null) {
                if (ServiceTransitionCoordinator.prepareRestartAfterPermission(
                        stopMode = stopMode,
                        startMode = target,
                    )
                ) {
                    val launched = runCatching { vpnRegister.launch(vpnPermission) }.isSuccess
                    if (!launched) ServiceTransitionCoordinator.cancelPrepared()
                }
            } else {
                ServiceTransitionCoordinator.beginRestart(
                    this,
                    stopMode = stopMode,
                    startMode = target,
                )
            }
        } else {
            getPreferences().edit()
                .putString("byedpi_mode", target.name.lowercase(Locale.ROOT))
                .apply()
            updateStatus()
        }
    }

    private fun openSettings(status: AppStatus = appStatus.first) {
        if (status == AppStatus.Halted) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLogs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "zapret-mobile-diagnostics.txt")
        }

        logsRegister.launch(intent)
    }

    private fun startAutoStrategyProbe(targetMode: Mode, shouldRunAfterProbe: Boolean) {
        if (!strategyProbeInProgress) return

        strategyProbeJob = lifecycleScope.launch {
            val preferences = getPreferences()
            val previousActive = StrategyProfiles.active(preferences)
            val previousConfiguration = StrategyProfiles.snapshot(preferences)
            var configurationChanged = false
            val probeRunner = StrategyProbeProxyRunner()
            var completed = false
            var probeStop: ServiceTransitionCoordinator.ProbeStop? = null
            setStrategyProbeControls(enabled = false)
            binding.statusText.setText(R.string.strategy_auto_checking)
            Toast.makeText(
                this@MainActivity,
                R.string.strategy_auto_checking,
                Toast.LENGTH_SHORT,
            ).show()
            try {
                val activeMode = appStatus
                    .takeIf { it.first == AppStatus.Running }
                    ?.second
                if (activeMode != null) {
                    val request = ServiceTransitionCoordinator.beginProbeStop(
                        applicationContext,
                        stopMode = activeMode,
                        recoveryMode = targetMode,
                    ) ?: return@launch
                    probeStop = request
                    val stopped = try {
                        withTimeoutOrNull(8_000L) {
                            request.stopped.await()
                            true
                        } == true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                    if (!stopped || !ServiceTransitionCoordinator.claimProbeStop(request.id)) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.strategy_auto_waiting,
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                    probeStop = null
                }

                val baseProxyPreferences = ByeDpiProxyUIPreferences(preferences)
                val proxyIp = when (baseProxyPreferences.ip.trim()) {
                    "", "0.0.0.0" -> "127.0.0.1"
                    "::", "[::]" -> "::1"
                    else -> baseProxyPreferences.ip.trim()
                }
                val proxyPort = baseProxyPreferences.port
                val candidates = buildList {
                    add(previousActive)
                    add(StrategyProfiles.Profile.Split)
                    add(StrategyProfiles.Profile.Tls)
                    add(StrategyProfiles.Profile.Strong)
                    add(StrategyProfiles.Profile.Messaging)
                    add(StrategyProfiles.Profile.Games)
                    add(StrategyProfiles.Profile.Balanced)
                }.distinct()

                var selected: StrategyProfiles.Profile? = null
                var selectedResult: StrategyProbeResult? = null
                val goal = MediaProbePolicy.Goal.fromId(preferences.getString(MediaProbePolicy.GOAL_KEY, null))
                val priority = goal.required + PRIORITY_PROBE_PRODUCTS.filter { product ->
                    if (goal == MediaProbePolicy.Goal.Video) product.startsWith("youtube")
                    else if (goal == MediaProbePolicy.Goal.Voice) product.startsWith("discord") else true
                }
                binding.checkProgress.visibility = View.VISIBLE
                for ((index, candidate) in candidates.withIndex()) {
                    binding.checkResult.text = getString(R.string.media_probe_progress,
                        index + 1, candidates.size, getString(candidate.title))
                    val proxyPreferences = StrategyProfiles.candidatePreferences(
                        baseProxyPreferences,
                        candidate,
                    )
                    if (!probeRunner.start(proxyPreferences)) continue
                    val result = try {
                        probeBlockedEndpoints(proxyIp, proxyPort, goal)
                    } finally {
                        probeRunner.stop()
                    }
                    if (result.isEligible(goal.required, requireUdp = goal.requireUdp) &&
                        result.isBetterThan(
                            selectedResult,
                            priority,
                            mediaReady = result.udpLatencyMs != null,
                            otherMediaReady = selectedResult?.udpLatencyMs != null,
                        )
                    ) {
                        selected = candidate
                        selectedResult = result
                    }
                    // Reuse the last profile only after both media hosts and
                    // the UDP relay are healthy. A frontend-only TLS success is
                    // not enough for YouTube playback or Discord calls.
                    if (index == 0 &&
                        result.isEligible(goal.required, requireUdp = goal.requireUdp) &&
                        result.successes == PROBE_TARGETS.count { goalIncludes(goal, it) }
                    ) break
                }

                val chosen = selected ?: previousActive
                binding.checkResult.text = selectedResult?.let { mediaSummary(it) }
                    ?: getString(R.string.strategy_auto_failed)
                if (selected != null) {
                    StrategyProfiles.applyAutoCandidate(preferences, chosen)
                    configurationChanged = true
                }
                preferences.edit()
                    .putString("byedpi_mode", targetMode.name.lowercase(Locale.ROOT))
                    .apply()

                if (selected == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.strategy_auto_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.strategy_auto_selected, getString(chosen.title)),
                        Toast.LENGTH_LONG,
                    ).show()
                }

                probeRunner.stop()
                if (shouldRunAfterProbe) {
                    if (!ServiceTransitionCoordinator.beginStart(
                            applicationContext,
                            startMode = targetMode,
                        )
                    ) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.strategy_auto_waiting,
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                    // From this point the selected profile is committed. A slow
                    // STARTED broadcast must not roll preferences back underneath
                    // a service that is already reading the chosen configuration.
                    completed = true
                    if (!waitForStatus(
                            AppStatus.Running,
                            expectedMode = targetMode,
                            timeoutMs = 8_000L,
                        )
                    ) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.strategy_auto_waiting,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } else {
                    completed = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ConnectionDiagnostics.record(this@MainActivity, "Strategy probe", error)
                if (!isDestroyed) binding.checkResult.setText(R.string.strategy_auto_failed)
            } finally {
                withContext(NonCancellable) {
                    probeRunner.stop()
                    if (!completed) {
                        if (configurationChanged) StrategyProfiles.restore(preferences, previousConfiguration)
                        preferences.edit()
                            .putString("byedpi_mode", targetMode.name.lowercase(Locale.ROOT))
                            .apply()
                        val pendingStop = probeStop
                        if (pendingStop != null) {
                            ServiceTransitionCoordinator.abandonProbeStop(
                                applicationContext,
                                pendingStop.id,
                            )
                        } else if (shouldRunAfterProbe &&
                            appStatus.first == AppStatus.Halted &&
                            !ServiceTransitionCoordinator.isActive
                        ) {
                            ServiceTransitionCoordinator.beginStart(
                                applicationContext,
                                startMode = targetMode,
                            )
                        }
                    }
                    probeRunner.shutdown()
                }
                strategyProbeJob = null
                StrategyProbeCoordinator.finish()
                if (!isDestroyed) {
                    binding.checkProgress.visibility = View.GONE
                    setStrategyProbeControls(enabled = true)
                    updateStatus()
                }
            }
        }
    }

    private suspend fun waitForStatus(
        target: AppStatus,
        expectedMode: Mode? = null,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (appStatus.first == target &&
                (expectedMode == null || appStatus.second == expectedMode)
            ) return true
            delay(100L)
        }
        return appStatus.first == target &&
            (expectedMode == null || appStatus.second == expectedMode)
    }

    private fun goalIncludes(goal: MediaProbePolicy.Goal, target: MediaProbePolicy.Target): Boolean =
        goal == MediaProbePolicy.Goal.All ||
            (goal == MediaProbePolicy.Goal.Video && target.product.startsWith("youtube")) ||
            (goal == MediaProbePolicy.Goal.Voice && target.product.startsWith("discord"))

    private suspend fun probeBlockedEndpoints(proxyIp: String, proxyPort: Int,
        goal: MediaProbePolicy.Goal = MediaProbePolicy.Goal.All): StrategyProbeResult =
        coroutineScope {
            val endpoints = PROBE_TARGETS.filter { goalIncludes(goal, it) }.map { target ->
                async(Dispatchers.IO) {
                    target.product to probeEndpoint(proxyIp, proxyPort, target)
                }
            }
            val udp = async(Dispatchers.IO) {
                Socks5UdpProbe.probe(proxyIp, proxyPort)
            }
            StrategyProbeResult.fromEndpointResults(
                endpoints.awaitAll(),
                udpLatencyMs = udp.await(),
            )
        }

    private fun probeEndpoint(proxyIp: String, proxyPort: Int, target: MediaProbePolicy.Target): Long? {
        val host = target.host
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                socket.soTimeout = 3_500
                socket.connect(InetSocketAddress(proxyIp, proxyPort), 2_500)
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()

                        output.write(byteArrayOf(0x05, 0x01, 0x00))
                        output.flush()
                        val greeting = ByteArray(2)
                        if (!readFully(input, greeting) ||
                            greeting[0] != 0x05.toByte() ||
                            greeting[1] != 0x00.toByte()
                        ) {
                            return null
                        }

                        val hostBytes = host.toByteArray(Charsets.US_ASCII)
                        val request = ByteArray(7 + hostBytes.size)
                        request[0] = 0x05
                        request[1] = 0x01
                        request[2] = 0x00
                        request[3] = 0x03
                        request[4] = hostBytes.size.toByte()
                        hostBytes.copyInto(request, destinationOffset = 5)
                        request[5 + hostBytes.size] = 0x01
                        request[6 + hostBytes.size] = 0xBB.toByte()
                        output.write(request)
                        output.flush()

                        val response = ByteArray(4)
                        if (!readFully(input, response) ||
                            response[0] != 0x05.toByte() ||
                            response[1] != 0x00.toByte() ||
                            !consumeSocksAddress(input, response[3])
                        ) {
                            return null
                        }

                        val tlsFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        val tls = tlsFactory.createSocket(socket, host, 443, false) as SSLSocket
                        tls.use {
                            it.soTimeout = 3_500
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                it.sslParameters = it.sslParameters.apply {
                                    endpointIdentificationAlgorithm = "HTTPS"
                                }
                            }
                            it.startHandshake()
                            if (!it.session.isValid) return null
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N &&
                                !HttpsURLConnection.getDefaultHostnameVerifier()
                                    .verify(host, it.session)
                            ) return null
                            it.getOutputStream().apply {
                                write(("GET " + target.path + " HTTP/1.1\r\nHost: " + host +
                                    "\r\nUser-Agent: Zapret-Mobile-Probe\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII))
                                flush()
                            }
                            val statusLine = MediaProbePolicy.readStatusLine(it.getInputStream()) {
                                val remaining = 12_000L - (SystemClock.elapsedRealtime() - startedAt)
                                if (remaining <= 0L) throw java.net.SocketTimeoutException("HTTP probe deadline")
                                it.soTimeout = remaining.coerceAtMost(3_500L).toInt()
                            } ?: return null
                            if (!MediaProbePolicy.acceptsStatus(statusLine, target.allowNotFound)) return null
                        }
                SystemClock.elapsedRealtime() - startedAt
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun consumeSocksAddress(input: java.io.InputStream, type: Byte): Boolean {
        val addressLength = when (type.toInt() and 0xff) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                val length = input.read()
                if (length < 0) return false
                length
            }
            else -> return false
        }
        return readFully(input, ByteArray(addressLength + 2))
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun showTelegramSetup() {
        val running = TelegramWsProxyService.running.value
        val link = telegramProxyLink()
        AlertDialog.Builder(this)
            .setTitle(R.string.telegram_setup_title)
            .setMessage(getString(if (running) R.string.telegram_ws_setup_running else R.string.telegram_ws_setup_stopped, link))
            .setNeutralButton(R.string.telegram_copy_proxy) { _, _ -> copyTelegramProxy(link) }
            .setNegativeButton(if (running) R.string.telegram_ws_stop else R.string.telegram_open_app) { _, _ ->
                if (running) {
                    TelegramWsProxyService.stop(this)
                    updateStatus()
                } else {
                    openTelegram()
                }
            }
            .setPositiveButton(if (running) R.string.telegram_add_proxy else R.string.telegram_ws_start) { _, _ ->
                if (running) {
                    openTelegramProxyLink(link)
                } else {
                    startTelegramAndApply()
                }
            }
            .show()
    }

    private fun startTelegramAndApply() {
        try {
            TelegramWsProxyService.start(this)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to start Telegram proxy service", error)
            Toast.makeText(this, R.string.telegram_ws_failed, Toast.LENGTH_LONG).show()
            updateStatus()
            return
        }

        Toast.makeText(this, R.string.telegram_ws_starting, Toast.LENGTH_SHORT).show()
        telegramStartJob?.cancel()
        telegramStartJob = lifecycleScope.launch {
            if (TelegramWsProxyService.awaitReady()) {
                updateStatus()
                openTelegramProxyLink(telegramProxyLink())
            } else {
                updateStatus()
                Toast.makeText(
                    this@MainActivity,
                    R.string.telegram_ws_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun telegramProxyLink(): String {
        val secret = TelegramWsProxyService.secretForLink(this)
        return Uri.Builder()
            .scheme("https")
            .authority("t.me")
            .appendPath("proxy")
            .appendQueryParameter("server", "127.0.0.1")
            .appendQueryParameter("port", TelegramWsProxyService.portForLink(this).toString())
            .appendQueryParameter("secret", secret)
            .build()
            .toString()
    }

    private fun copyTelegramProxy(link: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Telegram MTProto WS", link))
        Toast.makeText(this, R.string.telegram_proxy_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openTelegram() {
        val launchIntent = packageManager.getLaunchIntentForPackage("org.telegram.messenger")
            ?: packageManager.getLaunchIntentForPackage("org.telegram.messenger.web")
            ?: packageManager.getLaunchIntentForPackage("org.thunderdog.challegram")
        if (launchIntent == null) {
            Toast.makeText(this, R.string.telegram_not_installed, Toast.LENGTH_SHORT).show()
        } else {
            startActivity(launchIntent)
        }
    }

    private fun openTelegramProxyLink(link: String) {
        val httpsUri = Uri.parse(link)
        val tgUri = Uri.Builder()
            .scheme("tg")
            .authority("proxy")
            .appendQueryParameter("server", httpsUri.getQueryParameter("server") ?: "127.0.0.1")
            .appendQueryParameter(
                "port",
                httpsUri.getQueryParameter("port")
                    ?: TelegramWsProxyService.portForLink(this).toString(),
            )
            .appendQueryParameter("secret", httpsUri.getQueryParameter("secret") ?: "")
            .build()
        val packageCandidates = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
        )
        for (uri in listOf(tgUri, httpsUri)) {
            for (packageName in packageCandidates) {
                val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(packageName)
                if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    val launched = runCatching { startActivity(intent) }.isSuccess
                    if (launched) {
                        Toast.makeText(this, R.string.telegram_proxy_opened, Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            }
        }

        // A small number of Telegram builds do not expose package metadata to
        // queries. Try the Telegram deep link before falling back to HTTPS.
        for (uri in listOf(tgUri, httpsUri)) {
            val genericIntent = Intent(Intent.ACTION_VIEW, uri)
            if (packageManager.resolveActivity(genericIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                val launched = runCatching { startActivity(genericIntent) }.isSuccess
                if (launched) {
                    Toast.makeText(this, R.string.telegram_proxy_opened, Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }

        openTelegram()
        Toast.makeText(this, R.string.telegram_proxy_manual_fallback, Toast.LENGTH_LONG).show()
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        if (debugLoggingEnabled()) Log.d(TAG, "Updating status: $status, $mode")

        val preferences = getPreferences()
        val selectedMode = preferences.mode()
        val proxyIp = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val proxyPort = preferences.getStringNotNull("byedpi_proxy_port", "1080")
        binding.proxyAddress.text = getString(R.string.proxy_address, proxyIp, proxyPort)
        binding.telegramProxyAddress.text = getString(
            R.string.telegram_proxy_address,
            "127.0.0.1",
            TelegramWsProxyService.portForLink(this).toString(),
        )
        updateTelegramStatus()
        binding.proxyAddress.visibility = if (selectedMode == Mode.Proxy) View.VISIBLE else View.GONE
        binding.modeHint.visibility = if (selectedMode == Mode.Proxy) View.VISIBLE else View.GONE
        binding.modeValue.setText(
            when (selectedMode) {
                Mode.VPN -> R.string.mode_vpn_value
                Mode.Proxy -> R.string.mode_proxy_value
            }
        )
        binding.strategyBadge.text = getString(
            R.string.strategy_badge,
            StrategyProfiles.title(this, preferences),
        )
        binding.routeDial.contentDescription = getString(R.string.dial_toggle)

        when (status) {
            AppStatus.Halted -> {
                binding.routeDial.setRunning(false)
                binding.routeDial.contentDescription = getString(R.string.dial_toggle)
                binding.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.dial_quiet_strong)
                )
                when (selectedMode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_disconnected)
                        binding.statusButton.setText(R.string.vpn_connect)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_down)
                        binding.statusButton.setText(R.string.proxy_start)
                    }
                }
                binding.statusButton.isEnabled = true
                stopMetricsLoop()
            }

            AppStatus.Running -> {
                binding.routeDial.setRunning(true)
                binding.routeDial.contentDescription = getString(R.string.dial_toggle)
                binding.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.zapret_violet_soft)
                )
                when (mode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_connected)
                        binding.statusButton.setText(R.string.vpn_disconnect)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_up)
                        binding.statusButton.setText(R.string.proxy_stop)
                    }
                }
                binding.statusButton.isEnabled = true
                startMetricsLoop()
            }
        }

        if (strategyProbeInProgress) {
            binding.routeDial.setRunning(false)
            binding.statusText.setText(R.string.strategy_auto_checking)
            binding.statusButton.setText(R.string.strategy_auto_checking)
            stopMetricsLoop()
        }
        if (controlsLocked) binding.statusButton.isEnabled = false

        val stateChanged = lastVisualStatus != null &&
            (lastVisualStatus != status || lastVisualMode != selectedMode)
        lastVisualStatus = status
        lastVisualMode = selectedMode
        if (stateChanged) animateConnectionState()
    }

    private fun setStrategyProbeControls(enabled: Boolean) {
        val safeEnabled = enabled && mediaCheckJob?.isActive != true
        binding.routeDial.isEnabled = safeEnabled
        binding.statusButton.isEnabled = safeEnabled
        binding.modeRow.isEnabled = safeEnabled
        binding.strategyBadge.isEnabled = safeEnabled
        binding.settingsButton.isEnabled = safeEnabled
        binding.checkButton.isEnabled = safeEnabled
        binding.autoPickButton.isEnabled = (strategyProbeInProgress && strategyProbeJob?.isActive == true) ||
            safeEnabled
        binding.autoPickButton.setText(if (strategyProbeInProgress) android.R.string.cancel else R.string.media_auto_pick)
    }

    private fun animateConnectionState() {
        val views = listOf(
            binding.statusText,
            binding.modeValue,
            binding.strategyBadge,
        )

        if (!uiAnimationsEnabled()) {
            views.forEach {
                it.alpha = 1f
                it.translationY = 0f
            }
            binding.statusDot.alpha = 1f
            binding.statusDot.scaleX = 1f
            binding.statusDot.scaleY = 1f
            return
        }

        views.forEachIndexed { index, view ->
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = dp(6f)
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 24L)
                .setDuration(280L)
                .setInterpolator(pressInInterpolator)
                .start()
        }

        binding.statusDot.animate().cancel()
        binding.statusDot.alpha = 0.4f
        binding.statusDot.scaleX = 0.72f
        binding.statusDot.scaleY = 0.72f
        binding.statusDot.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(pressInInterpolator)
            .start()
    }

    private fun startMetricsLoop() {
        if (connectionStartedAt == 0L) {
            connectionStartedAt = SystemClock.elapsedRealtime()
        }
        if (metricsJob?.isActive == true) return
        metricsJob = lifecycleScope.launch {
            while (isActive && appStatus.first == AppStatus.Running) {
                val now = SystemClock.elapsedRealtime()
                updateMetrics(now)
                delay(1_000L)
            }
        }
    }

    private fun stopMetricsLoop() {
        metricsJob?.cancel()
        metricsJob = null
        connectionStartedAt = 0L
        binding.connectionUptime.text = getString(R.string.session_uptime, "00:00:00")
        binding.connectionTraffic.text = getString(
            R.string.connection_traffic,
            "0 Б",
            "0 Б",
        )
    }

    private fun updateMetrics(now: Long) {
        val elapsed = (now - connectionStartedAt).coerceAtLeast(0L) / 1_000L
        val hours = elapsed / 3_600L
        val minutes = (elapsed % 3_600L) / 60L
        val seconds = elapsed % 60L
        val uptime = String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        binding.connectionUptime.text = getString(R.string.session_uptime, uptime)
        val stats = if (appStatus.second == Mode.VPN) runCatching {
            TProxyService.TProxyGetStats().takeIf { it.size == 4 }
        }.getOrNull() else null
        binding.connectionTraffic.text = if (stats == null) getString(R.string.proxy_traffic_unavailable)
        else getString(R.string.connection_traffic,
            formatBytes(stats[3].coerceAtLeast(0L)), formatBytes(stats[1].coerceAtLeast(0L)))
    }

    override fun onStop() {
        metricsJob?.cancel()
        metricsJob = null
        super.onStop()
    }

    private fun uiAnimationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun debugLoggingEnabled(): Boolean =
        !getPreferences().getBoolean("privacy_minimal_logs", true)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun formatBytes(value: Long): String = when {
        value < 1_024L -> "$value Б"
        value < 1_024L * 1_024L -> String.format(Locale.ROOT, "%.1f КБ", value / 1_024f)
        value < 1_024L * 1_024L * 1_024L ->
            String.format(Locale.ROOT, "%.1f МБ", value / (1_024f * 1_024f))
        else -> String.format(Locale.ROOT, "%.2f ГБ", value / (1_024f * 1_024f * 1_024f))
    }
}
