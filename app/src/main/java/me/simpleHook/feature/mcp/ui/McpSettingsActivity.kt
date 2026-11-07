package me.simpleHook.feature.mcp.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.simpleHook.R
import me.simpleHook.core.extension.showToast
import me.simpleHook.core.utils.SPUtil
import me.simpleHook.core.utils.ToolUtil
import me.simpleHook.databinding.ActivityMcpSettingsBinding
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
    }

    override fun onResume() {
        super.onResume()
        refreshConnectionInfo()
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
            McpService.start(this)
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

    private fun updateStatusText() {
        binding.textStatus.text = getString(
            if (sp.mcpEnabled) R.string.mcp_settings_status_enabled
            else R.string.mcp_settings_status_disabled
        )
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
    }
}
