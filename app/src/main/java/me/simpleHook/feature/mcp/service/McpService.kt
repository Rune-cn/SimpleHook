package me.simpleHook.feature.mcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import me.simpleHook.R
import me.simpleHook.core.utils.SPUtil
import me.simpleHook.feature.mcp.AndroidMcpDataSource
import me.simpleHook.feature.mcp.McpServerFactory
import me.simpleHook.feature.mcp.transport.HttpTransportConfig
import me.simpleHook.feature.mcp.transport.LineTransport
import me.simpleHook.feature.mcp.transport.LineTransportConfig
import me.simpleHook.feature.mcp.transport.StreamableHttpTransport
import me.simpleHook.feature.mcp.ui.McpSettingsActivity

/**
 * Foreground service that hosts the MCP server.
 *
 * The server must keep running while a desktop client is connected, which on modern Android
 * requires a foreground service with a visible notification. The service is started and
 * stopped explicitly by the user from the MCP settings screen; it is never started on boot.
 */
class McpService : Service() {

    @Volatile
    private var http: StreamableHttpTransport? = null

    @Volatile
    private var line: LineTransport? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to the foreground FIRST.
        //
        // A service started with startForegroundService() must call startForeground() within
        // an "ANR-comparable" interval, otherwise the system kills the process
        // (ForegroundServiceDidNotStartInTimeException on Android 12+). Everything below this
        // point can be slow on a cold start: Room opens its databases, the MCP data source is
        // built, and transports bind sockets. Doing any of that first risks the process being
        // killed, which leaves nothing listening on the port and produces ECONNREFUSED for
        // clients.
        startTrace.clear()
        note("onStartCommand")

        val foreground = startForegroundCompat()
        note(if (foreground) "foreground=ok" else "foreground=refused")
        updateNotification(getString(R.string.mcp_service_notification_starting))

        // A restart must not leave two servers bound to the same ports.
        stopServers()
        runningTransports.set(emptyList())

        val sp = SPUtil(applicationContext)
        note("httpEnabled=${sp.mcpHttpEnabled} port=${sp.mcpHttpPort} " +
            "stdioEnabled=${sp.mcpStdioEnabled} port=${sp.mcpStdioPort}")
        val data = AndroidMcpDataSource(applicationContext, sp)
        val core = McpServerFactory.create(data)
        note("serverCore=built")

        val started = mutableListOf<String>()
        val failures = mutableListOf<String>()

        if (sp.mcpHttpEnabled) {
            val port = sp.mcpHttpPort
            val transport = StreamableHttpTransport(
                core = core,
                config = HttpTransportConfig(port = port, authToken = sp.ensureMcpAuthToken()),
                logger = { log(it) }
            )
            runCatching { transport.start() }
                .onSuccess {
                    http = transport
                    started += "HTTP :${transport.boundPort}"
                    note("http=bound:${transport.boundPort}")
                }
                .onFailure { error ->
                    // The port may be taken by another app or by a previous instance of this
                    // service that has not been reaped yet; say so instead of staying silent.
                    note("http=failed:${error.javaClass.simpleName}")
                    log("HTTP transport failed on port $port: ${error.javaClass.simpleName}")
                    failures += getString(
                        R.string.mcp_service_http_bind_failed,
                        port,
                        error.javaClass.simpleName
                    )
                }
        }

        if (sp.mcpStdioEnabled) {
            val port = sp.mcpStdioPort
            val transport = LineTransport(
                core = core,
                config = LineTransportConfig(port = port, authToken = sp.ensureMcpAuthToken()),
                logger = { log(it) }
            )
            runCatching { transport.start() }
                .onSuccess {
                    line = transport
                    started += "stdio :${transport.boundPort}"
                }
                .onFailure { error ->
                    log("stdio transport failed on port $port: ${error.javaClass.simpleName}")
                    failures += getString(
                        R.string.mcp_service_line_bind_failed,
                        port,
                        error.javaClass.simpleName
                    )
                }
        }

        runningTransports.set(started.toList())
        publishState()

        if (started.isEmpty()) {
            note("no-transport")
            log("No MCP transport could be started: $failures")
            lastFailure.set(failures.joinToString("; ").ifEmpty {
                getString(R.string.mcp_service_no_transport)
            })
            publishState()
            // Show why before stopping: a notification posted after stopSelf() disappears with
            // the service, and the settings screen is not guaranteed to be visible.
            updateNotification(
                failures.joinToString("\n").ifEmpty {
                    getString(R.string.mcp_service_no_transport)
                }
            )
            android.os.Handler(mainLooper).postDelayed(
                { stopSelf(startId) },
                FAILED_NOTIFICATION_LINGER_MS
            )
            return START_NOT_STICKY
        }

        lastFailure.set(failures.joinToString("; ").takeIf { it.isNotEmpty() })
        publishState()
        val summaryText = started.joinToString(" · ") +
            if (failures.isNotEmpty()) "\n" + failures.joinToString("\n") else ""
        updateNotification(
            if (foreground) summaryText
            else getString(R.string.mcp_service_notification_foreground_rejected)
        )
        return START_STICKY
    }

    override fun onDestroy() {
        stopServers()
        runningTransports.set(emptyList())
        publishState()
        super.onDestroy()
    }

    private fun stopServers() {
        runCatching { http?.stop() }
        runCatching { line?.stop() }
        http = null
        line = null
    }


    private fun summary(httpStarted: Boolean, lineStarted: Boolean): String {
        val parts = mutableListOf<String>()
        if (httpStarted) parts += "HTTP :${http?.boundPort}"
        if (lineStarted) parts += "stdio :${line?.boundPort}"
        return parts.joinToString(" · ").ifEmpty { "stopped" }
    }

    /**
     * Promotes the service to the foreground.
     *
     * The type matches `android:foregroundServiceType="specialUse"` in the manifest; passing
     * `connectedDevice` here would be rejected from Android 14 on unless the app also holds a
     * Bluetooth/NFC/network-state permission at runtime, which a loopback-only server does not
     * need. Below API 34 the type parameter is not required, so the two-argument form is used
     * to avoid depending on a constant that does not exist on older devices.
     *
     * A failure is logged instead of propagated: without the promotion the service is still
     * usable while the app is in the foreground, and crashing the whole process on a
     * platform policy check would lose the user's in-progress work in the main UI.
     */
    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification("starting")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (invalid: RuntimeException) {
            // SecurityException (permission/policy refusal) and
            // InvalidForegroundServiceTypeException both derive from RuntimeException. Keep
            // serving instead of crashing the process; the cause is recorded in the log.
            log("startForeground rejected: ${invalid.javaClass.simpleName}: ${invalid.message}")
            false
        }
    }

    private fun updateNotification(summary: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(summary))
    }

    private fun buildNotification(summary: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, McpSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.mcp_service_notification_title))
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.mcp_service_notification_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mcp_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.mcp_service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun log(message: String) {
        android.util.Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "McpService"
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 4201

        /** How long a "nothing is listening" notification stays before the service stops. */
        private const val FAILED_NOTIFICATION_LINGER_MS = 10_000L

        const val ACTION_STOP = "me.simpleHook.action.MCP_STOP"
        const val ACTION_START = "me.simpleHook.action.MCP_START"

        /**
         * Endpoints that are actually listening, e.g. `HTTP :57321`.
         *
         * Published by the service so the settings screen can distinguish "the user switched
         * the server on" from "the port is really bound". Without this the screen could claim
         * the server is running while nothing is listening and clients get ECONNREFUSED.
         */
        private val runningTransports = java.util.concurrent.atomic.AtomicReference<List<String>>(
            emptyList()
        )

        /** Reason the last start partially or fully failed, or null. */
        private val lastFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)

        /**
         * Step-by-step record of the most recent start attempt.
         *
         * Exposed in the self-check result so a device where the service never binds can say
         * *where* it stopped (never started / foreground refused / bind failed) instead of
         * only reporting that the port refuses connections.
         */
        private val startTrace = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun trace(): List<String> = startTrace.toList()

        private fun note(step: String) {
            if (startTrace.size > 20) startTrace.clear()
            startTrace.add(step)
            android.util.Log.i(TAG, step)
        }

        /**
         * Listeners notified whenever the published state changes.
         *
         * A callback instead of a broadcast: the service and the settings screen live in the
         * same process, and a callback cannot be missed or spoofed by another app.
         */
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

        /** Registers [listener]; it fires immediately with the current state. */
        fun addStateListener(listener: () -> Unit) {
            listeners.add(listener)
            listener()
        }

        fun removeStateListener(listener: () -> Unit) {
            listeners.remove(listener)
        }

        private fun publishState() {
            listeners.forEach { callback -> runCatching { callback() } }
        }

        fun runningEndpoints(): List<String> = runningTransports.get()

        fun isListening(): Boolean = runningTransports.get().isNotEmpty()

        fun failureReason(): String? = lastFailure.get()

        /**
         * Starts the server service.
         *
         * `startForegroundService` throws when the app is not allowed to start a foreground
         * service from the current state (for example while backgrounded). That is a user-
         * visible configuration problem, not a crash, so it is reported through the return
         * value and the caller shows a message.
         *
         * @return true when the start request was accepted by the system.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, McpService::class.java).setAction(ACTION_START)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (notAllowed: RuntimeException) {
                // ForegroundServiceStartNotAllowedException (API 31+) and friends.
                android.util.Log.w(
                    TAG,
                    "startForegroundService rejected: ${notAllowed.javaClass.simpleName}"
                )
                false
            }
        }

        /**
         * Asks a running service to stop.
         *
         * `stopService` is used instead of sending an ACTION_STOP intent: on Android 8+,
         * `startService` for a background app throws IllegalStateException when the service is
         * not already running, so "stop" could crash exactly when there was nothing to stop.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, McpService::class.java))
        }

        fun isConfigured(context: Context): Boolean {
            val sp = SPUtil(context.applicationContext)
            return sp.mcpEnabled
        }
    }
}
