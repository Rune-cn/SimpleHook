package me.simpleHook.feature.keepalive

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.simpleHook.R
import me.simpleHook.core.extension.getColorByAttr
import me.simpleHook.core.extension.showToast
import me.simpleHook.databinding.ActivityKeepAliveBinding

/**
 * "Keep alive" screen: the two permissions a long-running foreground service needs, plus the
 * vendor autostart page on ROMs that have one.
 *
 * The screen is a *status* page, not a switchboard. Every row re-reads the real system state
 * in [onResume], because the user may grant or revoke anything in the system UI while this
 * activity is in the background. Nothing is remembered locally, so the screen can never show
 * "granted" for something that was revoked.
 */
class KeepAliveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeepAliveBinding

    /**
     * Android 13+ notification permission.
     *
     * Registered before `onCreate` returns, as required by the Activity Result API. The result
     * is only used to refresh the UI; the authoritative state is re-read from the system.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showToast(getString(R.string.keepalive_notification_denied))
            }
            refreshState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeepAliveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.buttonNotification.setOnClickListener { onNotificationClicked() }
        binding.buttonBattery.setOnClickListener { requestBatteryExemption() }
        binding.buttonBatterySettings.setOnClickListener {
            if (!KeepAlivePermissions.openBatteryOptimizationSettings(this)) {
                showToast(getString(R.string.keepalive_no_settings_page))
            }
        }
        binding.buttonAutostart.setOnClickListener { openAutostart() }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------------------------------------------------------------------------

    private fun refreshState() {
        val state = readState()
        applyNotificationState(state)
        applyBatteryState(state)
        applyAutostartState(state)

        binding.textOverview.text = getString(
            if (state.allSatisfied) R.string.keepalive_overview_ok
            else R.string.keepalive_overview_pending
        )
        binding.textRom.text = getString(R.string.keepalive_device_format, state.romDescription)
    }

    private fun readState(): KeepAliveState = KeepAliveState(
        notificationsAllowed = KeepAlivePermissions.hasNotificationPermission(this),
        notificationsNeedRuntimeGrant = KeepAlivePermissions.requiresNotificationPermission,
        notificationsPermanentlyDenied =
            KeepAlivePermissions.isNotificationPermissionPermanentlyDenied(this),
        ignoringBatteryOptimizations = KeepAlivePermissions.isIgnoringBatteryOptimizations(this),
        vendorAutostartPage = KeepAlivePermissions.availableVendorPage(this),
        romDescription = KeepAlivePermissions.describeRom()
    )

    private fun applyNotificationState(state: KeepAliveState) {
        binding.textNotificationStatus.text = getString(
            if (state.notificationsAllowed) R.string.keepalive_status_granted
            else R.string.keepalive_status_missing
        )
        binding.textNotificationStatus.setTextColor(statusColor(state.notificationsAllowed))
        binding.buttonNotification.isEnabled = !state.notificationsAllowed
        binding.buttonNotification.text = getString(
            when {
                state.notificationsAllowed -> R.string.keepalive_notification_action_done
                state.notificationsNeedRuntimeGrant && !state.notificationsPermanentlyDenied ->
                    R.string.keepalive_notification_action_request
                else -> R.string.keepalive_notification_action_settings
            }
        )
    }

    private fun applyBatteryState(state: KeepAliveState) {
        binding.textBatteryStatus.text = getString(
            if (state.batteryOk) R.string.keepalive_status_unrestricted
            else R.string.keepalive_status_restricted
        )
        binding.textBatteryStatus.setTextColor(statusColor(state.batteryOk))
        binding.buttonBattery.isEnabled = !state.batteryOk
        binding.buttonBattery.text = getString(
            if (state.batteryOk) R.string.keepalive_battery_action_done
            else R.string.keepalive_battery_action
        )
        binding.buttonBatterySettings.isEnabled = !state.batteryOk
    }

    /**
     * Uses the theme's semantic colours rather than new ones, so light/dark and dynamic colour
     * stay consistent with the rest of the app.
     */
    private fun statusColor(satisfied: Boolean): Int = getColorByAttr(
        if (satisfied) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorError
        }
    )

    private fun applyAutostartState(state: KeepAliveState) {
        val page = state.vendorAutostartPage
        binding.cardAutostart.visibility = android.view.View.VISIBLE
        binding.buttonAutostart.isEnabled = page != null
        binding.buttonAutostart.text = if (page != null) {
            getString(R.string.keepalive_autostart_action_rom, page)
        } else {
            getString(R.string.keepalive_autostart_unavailable)
        }
    }

    // ---------------------------------------------------------------------------------

    private fun onNotificationClicked() {
        val state = readState()
        if (state.notificationsAllowed) return

        val needsRuntime = state.notificationsNeedRuntimeGrant
        if (needsRuntime && !state.notificationsPermanentlyDenied) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Either the grant is permanently denied, or notifications were turned off for the
        // app/channel in settings; both need the system settings page.
        if (!KeepAlivePermissions.openNotificationSettings(this)) {
            showToast(getString(R.string.keepalive_no_settings_page))
        }
    }

    /**
     * Explains both routes before touching the system, because the direct request dialog is a
     * meaningful battery-life decision and some users prefer the list page.
     */
    private fun requestBatteryExemption() {
        if (KeepAlivePermissions.isIgnoringBatteryOptimizations(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.keepalive_battery_dialog_title)
            .setMessage(R.string.keepalive_battery_dialog_message)
            .setNegativeButton(R.string.keepalive_battery_dialog_manual) { _, _ ->
                if (!KeepAlivePermissions.openBatteryOptimizationSettings(this)) {
                    showToast(getString(R.string.keepalive_no_settings_page))
                }
            }
            .setPositiveButton(R.string.keepalive_battery_dialog_request) { _, _ ->
                if (!KeepAlivePermissions.requestIgnoreBatteryOptimizations(this)) {
                    showToast(getString(R.string.keepalive_no_settings_page))
                }
            }
            .show()
    }

    private fun openAutostart() {
        val opened = KeepAlivePermissions.openVendorAutostartPage(this)
        if (opened == null) {
            showToast(getString(R.string.keepalive_autostart_not_found))
            return
        }
        showToast(getString(R.string.keepalive_autostart_opened, opened))
    }

    companion object {
        /** Convenience for callers that only have a Context. */
        fun intent(context: android.content.Context): Intent =
            Intent(context, KeepAliveActivity::class.java)
    }
}
