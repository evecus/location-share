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
import android.location.Criteria
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
 * 保活前台服务：进程存活时维持轻量设备 WebSocket，仅收「授权相关由用户通道处理」
 * 与「要位置 get_location」。
 * 不持续定位：仅在收到 get_location 时用 LocationManager 单次取点再上报。
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

        const val ACTION_START = "com.locationshare.client.START_CONN"
        const val ACTION_STOP = "com.locationshare.client.STOP_CONN"

        @JvmStatic
        val processRunning = AtomicBoolean(false)

        fun isServiceProcessRunning(): Boolean = processRunning.get()

        fun getSavedDeviceToken(ctx: Context): String? =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEVICE_TOKEN, null)

        fun saveDevice(ctx: Context, token: String, id: Long, name: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DEVICE_TOKEN, token)
                .putLong(KEY_DEVICE_ID, id)
                .putString(KEY_DEVICE_NAME, name)
                .apply()
        }

        /** 有本机 device_token 则启动保活连接（无开始/停止分享开关） */
        fun ensureRunning(ctx: Context) {
            if (getSavedDeviceToken(ctx).isNullOrBlank()) return
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private val okClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
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
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val token = getSavedDeviceToken(this)
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "no device_token")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, buildNotification("连接中…"))
                processRunning.set(true)
                if (running.compareAndSet(false, true)) {
                    connectDeviceWs(token)
                } else if (webSocket == null) {
                    connectDeviceWs(token)
                }
            }
        }
        // 被系统回收后尽量重启
        return START_STICKY
    }

    private fun shutdown() {
        running.set(false)
        processRunning.set(false)
        webSocket?.close(1000, "stop")
        webSocket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        running.set(false)
        processRunning.set(false)
        webSocket?.close(1000, "destroy")
        webSocket = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "位置连接服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持轻量连接，仅在被请求时获取定位"
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
            .setContentTitle("位置服务已连接")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun connectDeviceWs(deviceToken: String) {
        val url = ApiClient.deviceWsUrl(deviceToken)
        Log.i(TAG, "device WS connecting")
        val req = Request.Builder().url(url).build()
        webSocket = okClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "device WS open")
                updateNotif("在线，等待授权/位置请求（不持续定位）")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val cmd = gson.fromJson(text, DeviceCommand::class.java)
                    if (cmd.type == "get_location") {
                        // 仅此时取一次定位
                        fetchLocationOnce { loc ->
                            sendLoc(webSocket, loc, cmd.request_id)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "parse cmd", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "device WS closed")
                this@DeviceShareService.webSocket = null
                if (running.get()) {
                    updateNotif("连接断开，重连中…")
                    scheduleReconnect(deviceToken)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "device WS fail: ${t.message}")
                this@DeviceShareService.webSocket = null
                if (running.get()) {
                    updateNotif("连接失败，重连中…")
                    scheduleReconnect(deviceToken)
                }
            }
        })
    }

    private fun scheduleReconnect(token: String) {
        mainHandler.postDelayed({
            if (running.get() && webSocket == null) {
                connectDeviceWs(token)
            }
        }, 5_000)
    }

    /**
     * 单次定位：优先 lastKnown，否则 requestSingleUpdate，超时则用 lastKnown/空。
     */
    private fun fetchLocationOnce(cb: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "no location permission")
            cb(null)
            return
        }
        val lm = locationManager ?: run {
            cb(null)
            return
        }

        var last: Location? = null
        try {
            val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            last = when {
                gps != null && net != null ->
                    if (gps.time >= net.time) gps else net
                gps != null -> gps
                else -> net
            }
        } catch (_: SecurityException) {
        }

        // 最近 2 分钟内的缓存可直接用，减少等待
        if (last != null && System.currentTimeMillis() - last.time < 120_000) {
            cb(last)
            return
        }

        val criteria = Criteria().apply {
            accuracy = Criteria.ACCURACY_FINE
            powerRequirement = Criteria.POWER_MEDIUM
        }
        val provider = try {
            lm.getBestProvider(criteria, true)
                ?: listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER
                ).firstOrNull { lm.isProviderEnabled(it) }
        } catch (_: Exception) {
            null
        }

        if (provider == null) {
            cb(last)
            return
        }

        val done = AtomicBoolean(false)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done.compareAndSet(false, true)) {
                    try {
                        lm.removeUpdates(this)
                    } catch (_: Exception) {
                    }
                    cb(location)
                }
            }

            @Deprecated("Deprecated in API")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            cb(last)
            return
        } catch (e: Exception) {
            Log.e(TAG, "requestSingleUpdate", e)
            cb(last)
            return
        }

        // 12 秒超时
        mainHandler.postDelayed({
            if (done.compareAndSet(false, true)) {
                try {
                    lm.removeUpdates(listener)
                } catch (_: Exception) {
                }
                cb(last)
            }
        }, 12_000)
    }

    private fun sendLoc(ws: WebSocket, loc: Location?, requestId: String?) {
        val deviceId = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_DEVICE_ID, 0).toString()
        val lat = loc?.latitude ?: 0.0
        val lon = loc?.longitude ?: 0.0
        val acc = loc?.let { if (it.hasAccuracy()) it.accuracy.toDouble() else 50.0 } ?: 9999.0
        val msg = LocationMsg(
            type = "location",
            device_id = deviceId,
            lat = lat,
            lon = lon,
            accuracy = acc,
            timestamp = System.currentTimeMillis() / 1000,
            request_id = requestId
        )
        val ok = ws.send(gson.toJson(msg))
        Log.i(TAG, "sent location lat=$lat lon=$lon ok=$ok")
        updateNotif(
            if (loc != null) "已响应位置 ${"%.5f".format(lat)}, ${"%.5f".format(lon)}"
            else "已响应（暂无有效定位）"
        )
    }
}
