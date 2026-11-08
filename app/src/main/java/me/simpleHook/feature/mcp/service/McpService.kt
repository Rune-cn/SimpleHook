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

        // A restart must not leave two servers bound to the same ports.
        stopServers()

        val sp = SPUtil(applicationContext)
        val data = AndroidMcpDataSource(applicationContext, sp)
        val core = McpServerFactory.create(data)

        var httpStarted = false
        var lineStarted = false

        if (sp.mcpHttpEnabled) {
            val transport = StreamableHttpTransport(
                core = core,
                config = HttpTransportConfig(
                    port = sp.mcpHttpPort,
                    authToken = sp.ensureMcpAuthToken()
                ),
                logger = { log(it) }
            )
            runCatching {
                transport.start()
                http = transport
                httpStarted = true
            }.onFailure {
                log("HTTP transport failed to start: ${it.javaClass.simpleName}")
            }
        }

        if (sp.mcpStdioEnabled) {
            val transport = LineTransport(
                core = core,
                config = LineTransportConfig(
                    port = sp.mcpStdioPort,
                    authToken = sp.ensureMcpAuthToken()
                ),
                logger = { log(it) }
            )
            runCatching {
                transport.start()
                line = transport
                lineStarted = true
            }.onFailure {
                log("stdio bridge failed to start: ${it.javaClass.simpleName}")
            }
        }

        if (!httpStarted && !lineStarted) {
            log("No MCP transport could be started")
            stopSelf()
            return START_NOT_STICKY
        }

        // Without the promotion the platform may still stop the service while the app is
        // backgrounded, so tell the user rather than failing silently.
        val foreground = startForegroundCompat()
        updateNotification(
            if (foreground) summary(httpStarted, lineStarted)
            else getString(R.string.mcp_service_notification_foreground_rejected)
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServers()
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

        const val ACTION_STOP = "me.simpleHook.action.MCP_STOP"
        const val ACTION_START = "me.simpleHook.action.MCP_START"

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

        fun stop(context: Context) {
            context.startService(Intent(context, McpService::class.java).setAction(ACTION_STOP))
        }

        fun isConfigured(context: Context): Boolean {
            val sp = SPUtil(context.applicationContext)
            return sp.mcpEnabled
        }
    }
}
