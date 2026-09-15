package com.locationshare.client.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.locationshare.client.DeviceListActivity
import com.locationshare.client.R
import com.locationshare.client.api.ApiClient
import com.locationshare.client.api.DeviceCommand
import com.locationshare.client.api.LocationMsg
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：以 device_token 连接服务器 WebSocket，
 * 收到 get_location 时上报本机真实 GPS。
 * 使 Android 客户端同时具备 03-device 代理的发送位置能力。
 */
class DeviceShareService : Service() {

    companion object {
        private const val TAG = "DeviceShareService"
        const val CHANNEL_ID = "location_share_device"
        const val NOTIF_ID = 1001

        const val PREFS = "location_share"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_SHARING = "sharing_enabled"

        const val ACTION_START = "com.locationshare.client.START_SHARE"
        const val ACTION_STOP = "com.locationshare.client.STOP_SHARE"

        fun isSharing(ctx: Context): Boolean {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHARING, false)
        }

        fun getSavedDeviceToken(ctx: Context): String? {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DEVICE_TOKEN, null)
        }

        fun saveDevice(ctx: Context, token: String, id: Long, name: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DEVICE_TOKEN, token)
                .putLong(KEY_DEVICE_ID, id)
                .putString(KEY_DEVICE_NAME, name)
                .apply()
        }

        fun setSharing(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SHARING, on)
                .apply()
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, DeviceShareService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, DeviceShareService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private var fusedClient: FusedLocationProviderClient? = null
    private var lastLocation: Location? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { lastLocation = it }
        }
    }

    private val okClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharing()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val token = getSavedDeviceToken(this)
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "no device_token, stop")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, buildNotification("正在连接…"))
                setSharing(this, true)
                if (running.compareAndSet(false, true)) {
                    startLocationUpdates()
                    connectDeviceWs(token)
                }
            }
        }
        return START_STICKY
    }

    private fun stopSharing() {
        running.set(false)
        setSharing(this, false)
        stopLocationUpdates()
        webSocket?.close(1000, "stop")
        webSocket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopSharing()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "位置分享服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "本机作为设备在线，响应位置请求"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, DeviceListActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("位置分享中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "no location permission")
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(8_000L)
            .setMaxUpdates(Int.MAX_VALUE)
            .build()
        try {
            fusedClient?.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            fusedClient?.lastLocation?.addOnSuccessListener { loc ->
                if (loc != null) lastLocation = loc
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "location security", e)
        }
    }

    private fun stopLocationUpdates() {
        fusedClient?.removeLocationUpdates(locationCallback)
    }

    private fun connectDeviceWs(deviceToken: String) {
        val url = ApiClient.deviceWsUrl(deviceToken)
        Log.i(TAG, "connecting device WS")
        val req = Request.Builder().url(url).build()
        webSocket = okClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "device WS open")
                updateNotif("本机在线，等待位置请求")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val cmd = gson.fromJson(text, DeviceCommand::class.java)
                    if (cmd.type == "get_location") {
                        respondLocation(webSocket, cmd.request_id)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "parse cmd fail", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "device WS closed $code $reason")
                if (running.get()) {
                    updateNotif("连接断开，5秒后重连…")
                    scheduleReconnect(deviceToken)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "device WS fail: ${t.message}")
                if (running.get()) {
                    updateNotif("连接失败，5秒后重连…")
                    scheduleReconnect(deviceToken)
                }
            }
        })
    }

    private fun scheduleReconnect(token: String) {
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            if (running.get()) {
                webSocket?.cancel()
                connectDeviceWs(token)
            }
        }, 5_000)
    }

    private fun respondLocation(ws: WebSocket, requestId: String?) {
        val loc = lastLocation
        val deviceId = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_DEVICE_ID, 0).toString()

        val lat: Double
        val lon: Double
        val acc: Double
        if (loc != null) {
            lat = loc.latitude
            lon = loc.longitude
            acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 30.0
        } else {
            // 无定位时回退到 0,0 并标记低精度（调用方仍能收到响应）
            lat = 0.0
            lon = 0.0
            acc = 9999.0
            Log.w(TAG, "no location available, sending zeros")
            // 尝试再取一次 lastLocation
            if (hasLocationPermission()) {
                try {
                    fusedClient?.lastLocation?.addOnSuccessListener { l ->
                        if (l != null) {
                            lastLocation = l
                            sendLoc(ws, deviceId, l.latitude, l.longitude,
                                if (l.hasAccuracy()) l.accuracy.toDouble() else 30.0, requestId)
                        }
                    }
                } catch (_: SecurityException) {}
            }
        }

        sendLoc(ws, deviceId, lat, lon, acc, requestId)
    }

    private fun sendLoc(
        ws: WebSocket,
        deviceId: String,
        lat: Double,
        lon: Double,
        acc: Double,
        requestId: String?
    ) {
        val msg = LocationMsg(
            type = "location",
            device_id = deviceId,
            lat = lat,
            lon = lon,
            accuracy = acc,
            timestamp = System.currentTimeMillis() / 1000,
            request_id = requestId
        )
        val json = gson.toJson(msg)
        val ok = ws.send(json)
        Log.i(TAG, "sent location lat=$lat lon=$lon ok=$ok")
        updateNotif("已上报位置 ${"%.5f".format(lat)}, ${"%.5f".format(lon)}")
    }
}
