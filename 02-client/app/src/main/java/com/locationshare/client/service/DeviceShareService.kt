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
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.locationshare.client.DeviceListActivity
import com.locationshare.client.api.ApiClient
import com.locationshare.client.api.DeviceCommand
import com.locationshare.client.api.LocationMsg
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：以 device_token 连接服务器 WebSocket，
 * 收到 get_location 时用系统 LocationManager 上报本机 GPS。
 * 不依赖 Google Play 服务。
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
    private var locationManager: LocationManager? = null
    @Volatile private var lastLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            Log.d(TAG, "location update ${location.latitude},${location.longitude}")
        }

        @Deprecated("Deprecated in API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
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
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
        val lm = locationManager ?: return
        try {
            // 先读缓存
            val lastGps = try {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (_: SecurityException) {
                null
            }
            val lastNet = try {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (_: SecurityException) {
                null
            }
            lastLocation = pickBetter(lastGps, lastNet) ?: lastLocation

            val minTime = 8_000L
            val minDist = 5f
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, minTime, minDist,
                    locationListener, Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, minTime, minDist,
                    locationListener, Looper.getMainLooper()
                )
            }
            // 部分机型还有 PASSIVE
            try {
                if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER, minTime, minDist,
                        locationListener, Looper.getMainLooper()
                    )
                }
            } catch (_: Exception) {
            }
            Log.i(TAG, "LocationManager updates started, last=${lastLocation?.latitude},${lastLocation?.longitude}")
        } catch (e: SecurityException) {
            Log.e(TAG, "location security", e)
        } catch (e: Exception) {
            Log.e(TAG, "startLocationUpdates", e)
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
    }

    private fun pickBetter(a: Location?, b: Location?): Location? {
        if (a == null) return b
        if (b == null) return a
        // 优先更新时间新的；相近时优先精度更好的
        val timeDelta = a.time - b.time
        if (timeDelta > 30_000) return a
        if (timeDelta < -30_000) return b
        val accA = if (a.hasAccuracy()) a.accuracy else Float.MAX_VALUE
        val accB = if (b.hasAccuracy()) b.accuracy else Float.MAX_VALUE
        return if (accA <= accB) a else b
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
        Handler(Looper.getMainLooper()).postDelayed({
            if (running.get()) {
                webSocket?.cancel()
                connectDeviceWs(token)
            }
        }, 5_000)
    }

    private fun respondLocation(ws: WebSocket, requestId: String?) {
        val deviceId = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_DEVICE_ID, 0).toString()

        // 再尝试刷新 lastKnown
        refreshLastKnown()

        val loc = lastLocation
        val lat: Double
        val lon: Double
        val acc: Double
        if (loc != null) {
            lat = loc.latitude
            lon = loc.longitude
            acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 30.0
        } else {
            lat = 0.0
            lon = 0.0
            acc = 9999.0
            Log.w(TAG, "no location available")
        }
        sendLoc(ws, deviceId, lat, lon, acc, requestId)
    }

    private fun refreshLastKnown() {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: return
        try {
            val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val better = pickBetter(gps, net)
            if (better != null) {
                lastLocation = pickBetter(lastLocation, better)
            }
        } catch (_: SecurityException) {
        }
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
