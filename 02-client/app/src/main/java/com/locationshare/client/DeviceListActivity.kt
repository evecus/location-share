package com.locationshare.client

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.locationshare.client.api.ApiClient
import com.locationshare.client.api.Device
import com.locationshare.client.api.LocationMsg
import com.locationshare.client.ws.LocationWebSocket

class DeviceListActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private lateinit var tvLocation: TextView
    private var devices: List<Device> = emptyList()
    private var ws: LocationWebSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        listView = findViewById(R.id.listDevices)
        tvLocation = findViewById(R.id.tvLocation)

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

        loadDevices()
        connectWs()
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
                tvLocation.text = "设备 ${loc.device_id}\n" +
                        "纬度: ${loc.lat}\n" +
                        "经度: ${loc.lon}\n" +
                        "精度: ${loc.accuracy} m\n" +
                        "时间: ${loc.timestamp}"
            }
        }
        ws?.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        ws?.disconnect()
    }
}
