package me.simpleHook.feature.keepalive

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Reads and requests the two platform capabilities that decide whether a foreground service
 * survives in the background:
 *
 *  1. **Notifications** (Android 13+): without `POST_NOTIFICATIONS` the foreground-service
 *     notification is hidden. The service still runs, but the user cannot see or stop it,
 *     which is both confusing and a policy risk.
 *  2. **Battery optimisation exemption** ("unrestricted background"): without it the system
 *     may freeze or kill the process while the screen is off.
 *
 * ## Why some of this is not a plain runtime permission
 *
 * `POST_NOTIFICATIONS` is a normal runtime permission and is requested directly.
 *
 * The background exemption has no runtime permission on stock Android. The only public
 * mechanism is `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which needs the normal
 * permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest and shows a system
 * dialog. Play policy discourages that dialog for most apps, so this screen offers **both**
 * paths and lets the user choose: the direct request, and the plain settings list
 * (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) where the user can pick the app manually.
 *
 * Vendor ROMs (MIUI/HyperOS, EMUI, ColorOS, Flyme, Funtouch, ...) additionally gate an
 * "autostart" switch that stock Android does not have. Those pages have no public API; each
 * one is an undocumented component that may be renamed or removed between ROM versions. They
 * are therefore tried **best effort**, one after another, and a failure falls through to the
 * stock app-details page instead of crashing or claiming success.
 *
 * AOS side references:
 *  * `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 *  * `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
 *  * `PowerManager.isIgnoringBatteryOptimizations(packageName)`
 */
object KeepAlivePermissions {

    /**
     * Vendor autostart pages, most specific first. Every entry is wrapped in `runCatching`
     * and verified with `resolveActivity` before launch, so a stale component on one ROM only
     * costs one failed lookup.
     */
    private data class VendorPage(
        val rom: String,
        val component: ComponentName
    )

    private fun vendorPages(packageName: String): List<VendorPage> = listOf(
        // Xiaomi MIUI / HyperOS
        VendorPage(
            "MIUI",
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        ),
        // Huawei / Honor EMUI & HarmonyOS
        VendorPage(
            "EMUI",
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        ),
        VendorPage(
            "EMUI",
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        ),
        // OPPO ColorOS / realme
        VendorPage(
            "ColorOS",
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
        ),
        VendorPage(
            "ColorOS",
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        ),
        VendorPage(
            "ColorOS",
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
        ),
        // vivo / iQOO
        VendorPage(
            "Funtouch",
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        ),
        VendorPage(
            "iQOO",
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        ),
        // Meizu Flyme
        VendorPage(
            "Flyme",
            ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
        ),
        // ASUS / Lenovo / Samsung fallbacks are deliberately omitted: those ROMs mostly rely on
        // the stock battery-optimisation page, which is already offered above.
    )

    /** Package schemes used by the vendor pages that accept a `package:` URI. */
    private val vendorPagesAcceptingPackage: Set<String> = setOf(
        "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter.startupapp.StartupAppListActivity"
    )

    // ---------------------------------------------------------------------------------
    // Notification permission
    // ---------------------------------------------------------------------------------

    /** True when this Android version requires `POST_NOTIFICATIONS` at runtime. */
    val requiresNotificationPermission: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun notificationPermission(): String = "android.permission.POST_NOTIFICATIONS"

    fun hasNotificationPermission(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (!requiresNotificationPermission) return true
        return ContextCompat.checkSelfPermission(
            context,
            notificationPermission()
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * True when the permission was refused in a way that re-asking shows no dialog
     * ("don't ask again"), so the UI should send the user to app settings instead.
     */
    fun isNotificationPermissionPermanentlyDenied(activity: Activity): Boolean {
        if (!requiresNotificationPermission) return false
        if (hasNotificationPermission(activity)) return false
        return !activity.shouldShowRequestPermissionRationale(notificationPermission())
    }

    /** Opens the notification settings of this app (channel + toggle). */
    fun openNotificationSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        openAppDetails(context)
    }

    // ---------------------------------------------------------------------------------
    // Battery optimisation / background exemption
    // ---------------------------------------------------------------------------------

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Shows the system dialog that asks to exempt this app from battery optimisation.
     *
     * Requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest. Some builds (and some
     * vendor ROMs) hide the dialog; the caller falls back to [openBatteryOptimizationSettings].
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData("package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        openBatteryOptimizationSettings(context)
    }

    /** Opens the list of apps allowed to ignore battery optimisation. */
    fun openBatteryOptimizationSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        openAppDetails(context)
    }

    // ---------------------------------------------------------------------------------
    // Vendor autostart / background-run pages
    // ---------------------------------------------------------------------------------

    /** Name of the vendor page that can be opened on this device, or null on stock Android. */
    fun availableVendorPage(context: Context): String? =
        vendorPages(context.packageName).firstOrNull { canHandle(context, it) }?.rom

    /**
     * Tries every known vendor autostart page in order.
     *
     * @return the ROM name whose page was opened, or null when none exists (then the caller
     *         should fall back to [openAppDetails]).
     */
    fun openVendorAutostartPage(context: Context): String? {
        for (page in vendorPages(context.packageName)) {
            if (!canHandle(context, page)) continue
            val intent = Intent().apply {
                component = page.component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (page.component.className in vendorPagesAcceptingPackage) {
                    data = "package:${context.packageName}".toUri()
                }
            }
            val opened = runCatching { context.startActivity(intent) }
                .recoverCatching {
                    // Some ROMs export the activity but reject the package URI.
                    val plain = Intent().apply {
                        component = page.component
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(plain)
                    Unit
                }
                .isSuccess
            if (opened) return page.rom
        }
        return null
    }

    // ---------------------------------------------------------------------------------
    // Generic fallback
    // ---------------------------------------------------------------------------------

    /** The stock "App info" page, which every Android version has. */
    fun openAppDetails(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse { false }

    private fun canHandle(context: Context, page: VendorPage): Boolean = runCatching {
        context.packageManager.resolveActivity(
            Intent().setComponent(page.component),
            PackageManager.MATCH_DEFAULT_ONLY
        ) != null
    }.getOrDefault(false)

    // Activities that are not public API; the names are stable in AOSP and documented by the
    // `@hide` annotations in `android.provider.Settings`.
    private const val ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS =
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    private const val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS =
        "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"

    /** True when an activity for [action] exists, used to hide dead buttons. */
    fun canOpen(context: Context, action: String): Boolean = runCatching {
        context.packageManager.resolveActivity(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PackageManager.MATCH_DEFAULT_ONLY
        ) != null
    }.getOrDefault(false)

    /** Exposed for diagnostics in the settings screen. */
    fun describeRom(): String = listOfNotNull(
        Build.MANUFACTURER?.takeIf { it.isNotBlank() },
        Build.MODEL?.takeIf { it.isNotBlank() }
    ).joinToString(" ")
}
