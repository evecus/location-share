package com.locationshare.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.locationshare.client.api.ApiClient
import com.locationshare.client.api.Device
import com.locationshare.client.api.IncomingPermission
import com.locationshare.client.api.LocationMsg
import com.locationshare.client.service.DeviceShareService
import com.locationshare.client.ws.LocationWebSocket
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class DeviceListActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private lateinit var tvLocation: TextView
    private lateinit var tvShareStatus: TextView
    private lateinit var tvIncoming: TextView
    private lateinit var btnRegisterDevice: Button
    private lateinit var mapView: MapView
    private var devices: List<Device> = emptyList()
    private var incoming: List<IncomingPermission> = emptyList()
    private var ws: LocationWebSocket? = null
    private var locationMarker: Marker? = null
    private var accuracyCircle: Polygon? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            DeviceShareService.ensureRunning(this)
            updateShareUi()
        } else {
            Toast.makeText(this, "需要定位权限，被请求位置时才能上报", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_device_list)

        listView = findViewById(R.id.listDevices)
        tvLocation = findViewById(R.id.tvLocation)
        tvShareStatus = findViewById(R.id.tvShareStatus)
        tvIncoming = findViewById(R.id.tvIncoming)
        btnRegisterDevice = findViewById(R.id.btnRegisterDevice)
        mapView = findViewById(R.id.mapView)
        val btnRefresh = findViewById<Button>(R.id.btnRefresh)
        val btnLogout = findViewById<Button>(R.id.btnLogout)
        val btnAccept = findViewById<Button>(R.id.btnAccept)
        val btnDeny = findViewById<Button>(R.id.btnDeny)

        setupMap()

        listView.setOnItemClickListener { _, _, position, _ ->
            onDeviceClicked(devices[position])
        }

        btnRegisterDevice.setOnClickListener { registerThisDevice() }
        btnRefresh.setOnClickListener {
            loadDevices()
            loadIncoming()
        }
        btnLogout.setOnClickListener {
            // 停止本机分享
            if (DeviceShareService.isServiceProcessRunning()) {
                DeviceShareService.stop(this)
            }
            LoginActivity.clearSession(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
        btnAccept.setOnClickListener { respondFirst(true) }
        btnDeny.setOnClickListener { respondFirst(false) }

        updateShareUi()
        loadDevices()
        loadIncoming()
        connectWs()
    }

    private fun onDeviceClicked(dev: Device) {
        when (dev.access) {
            "owner", "allowed" -> {
                if (!dev.online) {
                    Toast.makeText(this, "设备离线，无法请求位置", Toast.LENGTH_SHORT).show()
                    return
                }
                ApiClient.requestLocation(dev.id) { result ->
                    runOnUiThread {
                        result.onSuccess {
                            Toast.makeText(this, "已请求位置，等待返回…", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(this, "请求失败: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            "pending" -> {
                Toast.makeText(this, "已申请授权，等待对方同意", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // none → 发起授权请求
                ApiClient.requestPermission(dev.id) { result ->
                    runOnUiThread {
                        result.onSuccess {
                            Toast.makeText(this, "已向对方发送授权请求", Toast.LENGTH_SHORT).show()
                            loadDevices()
                        }.onFailure {
                            Toast.makeText(this, "申请失败: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun respondFirst(accept: Boolean) {
        val first = incoming.firstOrNull()
        if (first == null) {
            Toast.makeText(this, "没有待处理请求", Toast.LENGTH_SHORT).show()
            return
        }
        ApiClient.respondPermission(first.id, accept) { result ->
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(
                        this,
                        if (accept) "已同意 ${first.requester_username}" else "已拒绝",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadIncoming()
                    loadDevices()
                }.onFailure {
                    Toast.makeText(this, "操作失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(31.2304, 121.4737))
        mapView.minZoomLevel = 3.0
        mapView.maxZoomLevel = 20.0
    }

    private fun showLocationOnMap(loc: LocationMsg) {
        if (loc.lat == 0.0 && loc.lon == 0.0) {
            tvLocation.text = "设备 ${loc.device_id}\n暂无有效定位"
            return
        }
        val title = buildString {
                append(loc.device_name ?: "设备")
                append(" ")
                append(loc.device_id)
                if (!loc.owner_username.isNullOrBlank()) append(" @${loc.owner_username}")
            }
        tvLocation.text = "$title\n" +
                "纬度: ${"%.6f".format(loc.lat)}\n" +
                "经度: ${"%.6f".format(loc.lon)}\n" +
                "精度: ${"%.1f".format(loc.accuracy)} m\n" +
                "时间: ${loc.timestamp}"
        val point = GeoPoint(loc.lat, loc.lon)
        if (locationMarker == null) {
            locationMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(this)
            }
        }
        locationMarker?.position = point
        locationMarker?.title = loc.device_name ?: "设备 ${loc.device_id}"
        locationMarker?.snippet = "精度 ${"%.0f".format(loc.accuracy)} m"
        accuracyCircle?.let { mapView.overlays.remove(it) }
        if (loc.accuracy in 1.0..5000.0) {
            accuracyCircle = Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(point, loc.accuracy)
                fillColor = Color.argb(50, 26, 115, 232)
                strokeColor = Color.argb(180, 26, 115, 232)
                strokeWidth = 2f
            }
            mapView.overlays.add(0, accuracyCircle)
        }
        mapView.controller.animateTo(point)
        if (mapView.zoomLevelDouble < 14.0) mapView.controller.setZoom(16.0)
        mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // 已注册本机则保持保活连接（轻量 WS，不持续定位）
        DeviceShareService.ensureRunning(this)
        updateShareUi()
        loadDevices()
        loadIncoming()
        tvShareStatus.postDelayed({
            updateShareUi()
            loadDevices()
        }, 2500)
    }

    private fun updateShareUi() {
        val prefs = getSharedPreferences(DeviceShareService.PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(DeviceShareService.KEY_DEVICE_TOKEN, null)
        val name = prefs.getString(DeviceShareService.KEY_DEVICE_NAME, null)
        val id = prefs.getLong(DeviceShareService.KEY_DEVICE_ID, 0)
        val alive = DeviceShareService.isServiceProcessRunning()
        if (token.isNullOrBlank()) {
            tvShareStatus.text = "未注册本机。注册后自动保持轻量连接，仅在被要位置时定位。"
            btnRegisterDevice.text = "注册本机"
        } else {
            val conn = if (alive) "🟢 连接服务中（不持续定位）" else "🟡 连接未运行（将自动拉起）"
            tvShareStatus.text = "$conn\n$name (id=$id)\n别人要位置时才会使用系统定位"
            btnRegisterDevice.text = "重新注册"
            DeviceShareService.ensureRunning(this)
        }
    }

    private fun ensureLocationPermission() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
    }

    private fun registerThisDevice() {
        val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android Phone"
        ApiClient.registerDevice("Phone-$name") { result ->
            runOnUiThread {
                result.onSuccess { resp ->
                    DeviceShareService.saveDevice(
                        this, resp.device_token, resp.device.id, resp.device.device_name
                    )
                    ensureLocationPermission()
                    DeviceShareService.ensureRunning(this)
                    Toast.makeText(this, "注册成功，已启动连接", Toast.LENGTH_SHORT).show()
                    updateShareUi()
                    loadDevices()
                }.onFailure {
                    Toast.makeText(this, "注册失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }




    private fun loadDevices() {
        ApiClient.listDevices { result ->
            runOnUiThread {
                result.onSuccess { list ->
                    devices = list
                    val names = list.map { d ->
                        val online = if (d.online) "🟢" else "⚫"
                        val owner = d.owner_username ?: "?"
                        val acc = when (d.access) {
                            "owner" -> "我的"
                            "allowed" -> "已授权"
                            "pending" -> "待对方同意"
                            else -> "点此申请授权"
                        }
                        "$online ${d.device_name} @$owner  id=${d.id}\n$acc"
                    }
                    listView.adapter = ArrayAdapter(
                        this, android.R.layout.simple_list_item_1, names
                    )
                }.onFailure {
                    Toast.makeText(this, "加载失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadIncoming() {
        ApiClient.listIncomingPermissions { result ->
            runOnUiThread {
                result.onSuccess { list ->
                    incoming = list
                    if (list.isEmpty()) {
                        tvIncoming.text = "暂无待处理请求"
                    } else {
                        tvIncoming.text = list.joinToString("\n") {
                            "• ${it.requester_username} 想看你的设备 ${it.device_name} (perm#${it.id})"
                        }
                    }
                }.onFailure {
                    tvIncoming.text = "加载授权请求失败"
                }
            }
        }
    }

    private fun connectWs() {
        ws = LocationWebSocket(
            onLocation = { loc -> runOnUiThread { showLocationOnMap(loc) } },
            onEvent = { ev ->
                runOnUiThread {
                    Toast.makeText(this, ev.message ?: ev.type, Toast.LENGTH_LONG).show()
                    loadDevices()
                    loadIncoming()
                }
            }
        )
        ws?.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        ws?.disconnect()
        mapView.onDetach()
    }
}
