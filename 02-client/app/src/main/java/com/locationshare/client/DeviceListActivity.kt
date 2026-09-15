package com.locationshare.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
// PreferenceManager removed
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
    private lateinit var btnRegisterDevice: Button
    private lateinit var btnToggleShare: Button
    private lateinit var mapView: MapView
    private var devices: List<Device> = emptyList()
    private var ws: LocationWebSocket? = null
    private var locationMarker: Marker? = null
    private var accuracyCircle: Polygon? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        if (granted) {
            startSharingIfReady()
        } else {
            Toast.makeText(this, "需要定位权限才能分享本机位置", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid 配置（用户代理、缓存路径）
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_device_list)

        listView = findViewById(R.id.listDevices)
        tvLocation = findViewById(R.id.tvLocation)
        tvShareStatus = findViewById(R.id.tvShareStatus)
        btnRegisterDevice = findViewById(R.id.btnRegisterDevice)
        btnToggleShare = findViewById(R.id.btnToggleShare)
        mapView = findViewById(R.id.mapView)
        val btnRefresh = findViewById<Button>(R.id.btnRefresh)

        setupMap()

        listView.setOnItemClickListener { _, _, position, _ ->
            val dev = devices[position]
            if (!dev.online) {
                Toast.makeText(this, "设备离线", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
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

        btnRegisterDevice.setOnClickListener { registerThisDevice() }
        btnToggleShare.setOnClickListener { toggleSharing() }
        btnRefresh.setOnClickListener { loadDevices() }

        updateShareUi()
        loadDevices()
        connectWs()
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        // 默认中心（可改成你所在城市）
        mapView.controller.setCenter(GeoPoint(31.2304, 121.4737))
        mapView.minZoomLevel = 3.0
        mapView.maxZoomLevel = 20.0
    }

    /** 收到位置后更新文字 + 地图标记 */
    private fun showLocationOnMap(loc: LocationMsg) {
        // 过滤明显无效坐标
        if (loc.lat == 0.0 && loc.lon == 0.0) {
            tvLocation.text = "设备 ${loc.device_id}\n暂无有效定位"
            return
        }

        tvLocation.text = "设备 ${loc.device_id}\n" +
                "纬度: ${"%.6f".format(loc.lat)}\n" +
                "经度: ${"%.6f".format(loc.lon)}\n" +
                "精度: ${"%.1f".format(loc.accuracy)} m\n" +
                "时间: ${loc.timestamp}"

        val point = GeoPoint(loc.lat, loc.lon)

        // 标记点
        if (locationMarker == null) {
            locationMarker = Marker(mapView).apply {
                title = "设备 ${loc.device_id}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(this)
            }
        }
        locationMarker?.position = point
        locationMarker?.title = "设备 ${loc.device_id}"
        locationMarker?.snippet = "精度 ${"%.0f".format(loc.accuracy)} m"

        // 精度圆（粗略：1 度纬度约 111km）
        accuracyCircle?.let { mapView.overlays.remove(it) }
        if (loc.accuracy in 1.0..5000.0) {
            accuracyCircle = Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(point, loc.accuracy)
                fillColor = Color.argb(50, 26, 115, 232)
                strokeColor = Color.argb(180, 26, 115, 232)
                strokeWidth = 2f
            }
            mapView.overlays.add(0, accuracyCircle) // 画在标记下面
        }

        mapView.controller.animateTo(point)
        if (mapView.zoomLevelDouble < 14.0) {
            mapView.controller.setZoom(16.0)
        }
        mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        updateShareUi()
        loadDevices()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun updateShareUi() {
        val prefs = getSharedPreferences(DeviceShareService.PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(DeviceShareService.KEY_DEVICE_TOKEN, null)
        val name = prefs.getString(DeviceShareService.KEY_DEVICE_NAME, null)
        val id = prefs.getLong(DeviceShareService.KEY_DEVICE_ID, 0)
        val sharing = DeviceShareService.isSharing(this)

        if (token.isNullOrBlank()) {
            tvShareStatus.text = "未注册本机设备。点击「注册本机」后即可分享位置。"
            btnRegisterDevice.isEnabled = true
            btnToggleShare.isEnabled = false
            btnToggleShare.text = "开始分享"
        } else {
            val status = if (sharing) "🟢 正在分享" else "⚫ 已注册但未分享"
            tvShareStatus.text = "$status\n设备: $name (id=$id)\nToken 已保存（仅显示一次，请勿丢失）"
            btnRegisterDevice.isEnabled = true
            btnRegisterDevice.text = "重新注册"
            btnToggleShare.isEnabled = true
            btnToggleShare.text = if (sharing) "停止分享" else "开始分享"
        }
    }

    private fun registerThisDevice() {
        val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android Phone"
        val deviceName = "Phone-$name"
        Toast.makeText(this, "正在注册本机…", Toast.LENGTH_SHORT).show()
        ApiClient.registerDevice(deviceName) { result ->
            runOnUiThread {
                result.onSuccess { resp ->
                    DeviceShareService.saveDevice(
                        this,
                        resp.device_token,
                        resp.device.id,
                        resp.device.device_name
                    )
                    Toast.makeText(this, "注册成功！可点击「开始分享」", Toast.LENGTH_LONG).show()
                    updateShareUi()
                    loadDevices()
                }.onFailure {
                    Toast.makeText(this, "注册失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggleSharing() {
        if (DeviceShareService.isSharing(this)) {
            DeviceShareService.stop(this)
            Toast.makeText(this, "已停止分享", Toast.LENGTH_SHORT).show()
            tvShareStatus.postDelayed({ updateShareUi() }, 400)
            return
        }
        ensurePermissionsThenShare()
    }

    private fun ensurePermissionsThenShare() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (need.isNotEmpty()) {
            permissionLauncher.launch(need.toTypedArray())
        } else {
            startSharingIfReady()
        }
    }

    private fun startSharingIfReady() {
        val token = DeviceShareService.getSavedDeviceToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "请先注册本机设备", Toast.LENGTH_SHORT).show()
            return
        }
        DeviceShareService.start(this)
        Toast.makeText(this, "已开始分享本机位置", Toast.LENGTH_SHORT).show()
        tvShareStatus.postDelayed({ updateShareUi() }, 500)
        tvShareStatus.postDelayed({ loadDevices() }, 2000)
    }

    private fun loadDevices() {
        ApiClient.listDevices { result ->
            runOnUiThread {
                result.onSuccess { list ->
                    devices = list
                    val names = list.map {
                        val status = if (it.online) "🟢 在线" else "⚫ 离线"
                        "${it.device_name}  (id=${it.id}) $status"
                    }
                    listView.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_list_item_1,
                        names
                    )
                }.onFailure {
                    Toast.makeText(this, "加载失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun connectWs() {
        ws = LocationWebSocket { loc: LocationMsg ->
            runOnUiThread {
                showLocationOnMap(loc)
            }
        }
        ws?.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        ws?.disconnect()
        mapView.onDetach()
    }
}
