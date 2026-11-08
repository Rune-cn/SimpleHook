package me.simpleHook.feature.mcp.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.simpleHook.R
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.simpleHook.core.extension.getColorByAttr
import me.simpleHook.core.extension.showToast
import me.simpleHook.core.utils.SPUtil
import me.simpleHook.core.utils.ToolUtil
import me.simpleHook.databinding.ActivityMcpSettingsBinding
import me.simpleHook.feature.keepalive.KeepAliveActivity
import me.simpleHook.feature.keepalive.KeepAlivePermissions
import me.simpleHook.feature.mcp.selfcheck.McpSelfCheck
import me.simpleHook.feature.mcp.service.McpService

/**
 * MCP server configuration screen: start/stop the foreground service, choose transports and
 * ports, see the bearer token and copy a ready-to-paste client configuration.
 *
 * Every switch writes straight to [SPUtil]; the service re-reads the settings when it starts,
 * so changing anything while the server runs shows a "restart required" hint instead of
 * silently applying a partially new configuration.
 */
class McpSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMcpSettingsBinding

    private val sp by lazy { SPUtil(applicationContext) }

    /**
     * Called by the service whenever it publishes a new state.
     *
     * The service binds sockets on its own thread, so the settings screen cannot assume the
     * state right after `startForegroundService`; this callback delivers the real result
     * (listening, or a bind failure) as soon as it is known.
     */
    private val stateListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) updateStatusText()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMcpSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindSettings()
        binding.buttonCopyConfig.setOnClickListener { copyClientConfig() }
        binding.buttonRegenerateToken.setOnClickListener { confirmRegenerateToken() }
        binding.switchServer.setOnCheckedChangeListener { _, checked -> onServerToggled(checked) }
        binding.buttonKeepAlive.setOnClickListener {
            startActivity(KeepAliveActivity.intent(this))
        }
        binding.buttonSelfCheck.setOnClickListener { runSelfCheck() }
    }

    override fun onResume() {
        super.onResume()
        refreshConnectionInfo()
        McpService.addStateListener(stateListener)
        restoreServerIfNeeded()
    }

    /**
     * Re-launches the service when the user has it enabled but nothing is listening.
     *
     * The switch only records intent. If the process was killed (low memory, ROM cleaner,
     * reboot) START_STICKY restart can be suppressed, and the user would otherwise see an
     * enabled switch, a green "running" state from a previous session, and a port that
     * refuses connections. Opening this screen is an explicit user action, which also makes
     * the foreground-service start legal.
     */
    private fun restoreServerIfNeeded() {
        if (!sp.mcpEnabled) return
        if (McpService.isListening()) return
        if (!McpService.start(this)) return
        binding.textStatus.postDelayed(
            { if (!isFinishing) updateStatusText() },
            RESTORE_SETTLE_MS
        )
    }

    override fun onPause() {
        super.onPause()
        McpService.removeStateListener(stateListener)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindSettings() {
        with(binding) {
            switchHttp.isChecked = sp.mcpHttpEnabled
            switchStdio.isChecked = sp.mcpStdioEnabled
            switchMutations.isChecked = sp.mcpAllowMutations
            editHttpPort.setText(sp.mcpHttpPort.toString())
            editStdioPort.setText(sp.mcpStdioPort.toString())

            switchHttp.setOnCheckedChangeListener { _, checked ->
                sp.mcpHttpEnabled = checked
                refreshConnectionInfo()
            }
            switchStdio.setOnCheckedChangeListener { _, checked ->
                sp.mcpStdioEnabled = checked
                refreshConnectionInfo()
            }
            switchMutations.setOnCheckedChangeListener { _, checked ->
                sp.mcpAllowMutations = checked
                if (checked) {
                    showToast(getString(R.string.mcp_settings_mutations_enabled_warning))
                }
            }
            editHttpPort.inputType = InputType.TYPE_CLASS_NUMBER
            editStdioPort.inputType = InputType.TYPE_CLASS_NUMBER
            editHttpPort.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) applyPort(editHttpPort.text?.toString(), http = true)
            }
            editStdioPort.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) applyPort(editStdioPort.text?.toString(), http = false)
            }
            switchServer.isChecked = sp.mcpEnabled
        }
        updateStatusText()
    }

    /** Validates a typed port and stores it, or restores the previous value. */
    private fun applyPort(raw: String?, http: Boolean) {
        val port = raw?.trim()?.toIntOrNull()
        val valid = port != null && port in PORT_RANGE
        if (!valid) {
            val layout = if (http) binding.layoutHttpPort else binding.layoutStdioPort
            val edit = if (http) binding.editHttpPort else binding.editStdioPort
            layout.error = getString(R.string.mcp_settings_invalid_port)
            edit.setText(if (http) sp.mcpHttpPort.toString() else sp.mcpStdioPort.toString())
            return
        }
        if (http) binding.layoutHttpPort.error = null else binding.layoutStdioPort.error = null
        if (http && port != sp.mcpHttpPort) {
            sp.mcpHttpPort = port!!
            hintRestartIfRunning()
        } else if (!http && port != sp.mcpStdioPort) {
            sp.mcpStdioPort = port!!
            hintRestartIfRunning()
        }
    }

    private fun onServerToggled(checked: Boolean) {
        sp.mcpEnabled = checked
        if (checked) {
            if (!sp.mcpHttpEnabled && !sp.mcpStdioEnabled) {
                // Starting a server with every transport disabled would immediately stop.
                sp.mcpHttpEnabled = true
                binding.switchHttp.isChecked = true
            }
            sp.ensureMcpAuthToken()
            if (!McpService.start(this)) {
                // The system refused the start (background restriction): keep the UI honest
                // instead of showing "running" for a server that never started.
                sp.mcpEnabled = false
                binding.switchServer.isChecked = false
                showToast(getString(R.string.mcp_settings_start_rejected))
            }
        } else {
            McpService.stop(this)
        }
        updateStatusText()
        refreshConnectionInfo()
    }

    private fun hintRestartIfRunning() {
        if (sp.mcpEnabled) {
            showToast(getString(R.string.mcp_settings_restart_required))
        }
        refreshConnectionInfo()
    }

    /**
     * Shows what is actually running, not what the switch says.
     *
     * `McpService.runningEndpoints()` is published by the service after the sockets are
     * bound, so "running" here means a client can connect. When the user enabled the server
     * but the port is not bound, the failure reason is shown instead of a green state.
     */
    private fun updateStatusText() {
        val endpoints = McpService.runningEndpoints()
        val failure = McpService.failureReason()
        binding.textStatus.text = when {
            endpoints.isNotEmpty() -> getString(
                R.string.mcp_settings_status_listening,
                endpoints.joinToString(" · ")
            )

            !sp.mcpEnabled -> getString(R.string.mcp_settings_status_disabled)

            !failure.isNullOrBlank() && !McpService.isListening() -> getString(
                R.string.mcp_settings_status_failed,
                failure
            )

            else -> getString(R.string.mcp_settings_status_not_listening)
        }
        // The foreground-service notification is the only way to stop the server from
        // outside the app; if it cannot be shown, point the user at the keep-alive screen.
        binding.buttonKeepAlive.visibility = if (
            KeepAlivePermissions.hasNotificationPermission(this) &&
            KeepAlivePermissions.isIgnoringBatteryOptimizations(this)
        ) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
    }

    /**
     * Connects to the configured endpoint as a real client would and reports which stage
     * failed. Runs off the main thread: the check blocks on socket I/O for up to a few seconds.
     */
    private fun runSelfCheck() {
        if (!sp.mcpHttpEnabled) {
            // Nothing to connect to; say so instead of reporting ECONNREFUSED.
            showSelfCheckResult(
                getString(R.string.mcp_settings_selfcheck_http_disabled),
                success = false
            )
            return
        }
        // The check still runs when the service reports no listener: it distinguishes "service
        // not started" from "started but the port is taken", which is what the user needs. The
        // hint is prepended to the result instead of shown separately, so it cannot be
        // overwritten by the "running" state a moment later.
        val hint = if (McpService.isListening()) null
        else getString(R.string.mcp_settings_selfcheck_not_started)

        val port = sp.mcpHttpPort
        val token = sp.ensureMcpAuthToken()
        binding.buttonSelfCheck.isEnabled = false
        showSelfCheckResult(getString(R.string.mcp_settings_selfcheck_running), success = null)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { McpSelfCheck(port = port, authToken = token).run() }
                    .getOrElse { error ->
                        McpSelfCheck.Result(
                            stage = McpSelfCheck.Stage.PORT,
                            ok = false,
                            message = getString(R.string.mcp_settings_selfcheck_crashed),
                            detail = "${error.javaClass.simpleName}: ${error.message}"
                        )
                    }
            }
            binding.buttonSelfCheck.isEnabled = true

            val text = buildString {
                if (hint != null) {
                    append(hint)
                    append("\n\n")
                }
                append(result.message)
                if (!result.detail.isNullOrBlank()) {
                    append("\n")
                    append(result.detail)
                }
                // The server side of the story: without it, "connection refused" cannot be
                // distinguished from "service never started".
                val trace = McpService.trace()
                append("\n\n")
                append(getString(R.string.mcp_settings_selfcheck_trace_title))
                append(" ")
                append(
                    if (trace.isEmpty()) getString(R.string.mcp_settings_selfcheck_trace_empty)
                    else trace.joinToString(" → ")
                )
            }
            showSelfCheckResult(text, success = result.ok)
        }
    }

    /** @param success null while running, true/false after completion. */
    private fun showSelfCheckResult(text: String, success: Boolean?) {
        binding.textSelfCheck.visibility = android.view.View.VISIBLE
        binding.textSelfCheck.text = text
        binding.textSelfCheck.setTextColor(
            when (success) {
                null -> getColorByAttr(android.R.attr.textColorSecondary)
                true -> getColorByAttr(android.R.attr.colorPrimary)
                false -> getColorByAttr(android.R.attr.colorError)
            }
        )
        if (success == false) {
            // Make the diagnostic easy to paste into a bug report.
            binding.textSelfCheck.setOnLongClickListener {
                ToolUtil.toClip(this, text)
                showToast(getString(R.string.mcp_settings_selfcheck_copied))
                true
            }
        } else {
            binding.textSelfCheck.setOnLongClickListener(null)
        }
    }

    private fun refreshConnectionInfo() {
        val token = sp.ensureMcpAuthToken()
        val httpEndpoint = if (sp.mcpHttpEnabled) {
            "http://127.0.0.1:${sp.mcpHttpPort}/mcp"
        } else {
            null
        }
        val stdioEndpoint = if (sp.mcpStdioEnabled) "127.0.0.1:${sp.mcpStdioPort}" else null

        binding.textEndpoint.text = listOfNotNull(httpEndpoint, stdioEndpoint)
            .joinToString("\n")
            .ifEmpty { getString(R.string.mcp_settings_no_transport) }
        binding.textToken.text = token
        binding.textPairing.text = buildString {
            append(getString(R.string.mcp_settings_pairing_hint))
            if (httpEndpoint != null) {
                append("\n\n")
                append(getString(R.string.mcp_settings_adb_forward, sp.mcpHttpPort))
            }
        }
        binding.buttonCopyConfig.isEnabled = httpEndpoint != null
    }

    /** Copies an MCP client `mcpServers` entry that the user can paste into their config. */
    private fun copyClientConfig() {
        val token = sp.ensureMcpAuthToken()
        val endpoint = "http://127.0.0.1:${sp.mcpHttpPort}/mcp"
        val snippet = buildString {
            append("{\n")
            append("  \"mcpServers\": {\n")
            append("    \"simplehook\": {\n")
            append("      \"type\": \"http\",\n")
            append("      \"url\": \"").append(endpoint).append("\",\n")
            append("      \"headers\": {\n")
            append("        \"Authorization\": \"Bearer ").append(token).append("\"\n")
            append("      }\n")
            append("    }\n")
            append("  }\n")
            append("}\n")
        }
        ToolUtil.toClip(this, snippet)
        showToast(getString(R.string.mcp_settings_config_copied))
    }

    private fun confirmRegenerateToken() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mcp_settings_regenerate_token)
            .setMessage(R.string.mcp_settings_regenerate_token_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                sp.regenerateMcpAuthToken()
                refreshConnectionInfo()
                hintRestartIfRunning()
                showToast(getString(R.string.mcp_settings_token_regenerated))
            }
            .show()
    }

    private companion object {
        /** Ports below 1024 are privileged on Linux and unusable for an app. */
        val PORT_RANGE = 1024..65535

        /** Time allowed for the service to bind before the status is re-read. */
        const val RESTORE_SETTLE_MS = 1200L
    }
}
