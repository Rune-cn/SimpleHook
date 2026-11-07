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

        startForegroundCompat()
        updateNotification(summary(httpStarted, lineStarted))
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

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("starting"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("starting"))
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

        fun start(context: Context) {
            val intent = Intent(context, McpService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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
