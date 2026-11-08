package me.simpleHook.feature.keepalive

/**
 * Snapshot of everything the keep-alive screen shows.
 *
 * Rebuilt in `onResume` so it is never stale after the user returns from a system settings
 * page. Every flag is read from the system, never assumed.
 */
data class KeepAliveState(
    /** `POST_NOTIFICATIONS` granted on Android 13+ and the app's notifications not blocked. */
    val notificationsAllowed: Boolean,
    /** Whether this Android version needs the runtime grant at all. */
    val notificationsNeedRuntimeGrant: Boolean,
    /** The permission is refused and the system will no longer show a dialog. */
    val notificationsPermanentlyDenied: Boolean,
    /** The app is exempt from battery optimisation ("unrestricted" in the system UI). */
    val ignoringBatteryOptimizations: Boolean,
    /** ROM name of an autostart page that exists on this device, or null on stock Android. */
    val vendorAutostartPage: String?,
    val romDescription: String
) {
    val batteryOk: Boolean get() = ignoringBatteryOptimizations

    /** True when nothing left needs the user's attention. */
    val allSatisfied: Boolean get() = notificationsAllowed && ignoringBatteryOptimizations
}
