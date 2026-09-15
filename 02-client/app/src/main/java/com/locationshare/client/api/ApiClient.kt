package com.locationshare.client.api

import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    // 模拟器访问宿主机用 10.0.2.2；真机请改成服务器局域网 IP 或 https 域名
    var baseUrl = "http://10.0.2.2:8080"
    var token: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()

    fun login(username: String, password: String, cb: (Result<LoginResponse>) -> Unit) {
        val body = gson.toJson(LoginRequest(username, password)).toRequestBody(json)
        val req = Request.Builder()
            .url("$baseUrl/api/login")
            .post(body)
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val str = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    cb(Result.failure(IOException("HTTP ${response.code}: $str")))
                    return
                }
                try {
                    val lr = gson.fromJson(str, LoginResponse::class.java)
                    token = lr.token
                    cb(Result.success(lr))
                } catch (e: Exception) {
                    cb(Result.failure(e))
                }
            }
        })
    }

    fun register(username: String, password: String, cb: (Result<LoginResponse>) -> Unit) {
        val body = gson.toJson(LoginRequest(username, password)).toRequestBody(json)
        val req = Request.Builder()
            .url("$baseUrl/api/register")
            .post(body)
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val str = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    cb(Result.failure(IOException("HTTP ${response.code}: $str")))
                    return
                }
                try {
                    val lr = gson.fromJson(str, LoginResponse::class.java)
                    token = lr.token
                    cb(Result.success(lr))
                } catch (e: Exception) {
                    cb(Result.failure(e))
                }
            }
        })
    }

    fun listDevices(cb: (Result<List<Device>>) -> Unit) {
        val t = token ?: return cb(Result.failure(IOException("not logged in")))
        val req = Request.Builder()
            .url("$baseUrl/api/devices")
            .header("Authorization", "Bearer $t")
            .get()
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val str = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    cb(Result.failure(IOException("HTTP ${response.code}: $str")))
                    return
                }
                try {
                    val dr = gson.fromJson(str, DevicesResponse::class.java)
                    cb(Result.success(dr.devices ?: emptyList()))
                } catch (e: Exception) {
                    cb(Result.failure(e))
                }
            }
        })
    }

    fun registerDevice(deviceName: String, cb: (Result<RegisterDeviceResponse>) -> Unit) {
        val t = token ?: return cb(Result.failure(IOException("not logged in")))
        val body = gson.toJson(RegisterDeviceRequest(deviceName)).toRequestBody(json)
        val req = Request.Builder()
            .url("$baseUrl/api/devices")
            .header("Authorization", "Bearer $t")
            .post(body)
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val str = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    cb(Result.failure(IOException("HTTP ${response.code}: $str")))
                    return
                }
                try {
                    cb(Result.success(gson.fromJson(str, RegisterDeviceResponse::class.java)))
                } catch (e: Exception) {
                    cb(Result.failure(e))
                }
            }
        })
    }

    fun requestLocation(deviceId: Long, cb: (Result<LocationRequestResponse>) -> Unit) {
        val t = token ?: return cb(Result.failure(IOException("not logged in")))
        val body = gson.toJson(LocationRequest(deviceId)).toRequestBody(json)
        val req = Request.Builder()
            .url("$baseUrl/api/location/request")
            .header("Authorization", "Bearer $t")
            .post(body)
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val str = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    cb(Result.failure(IOException("HTTP ${response.code}: $str")))
                    return
                }
                try {
                    cb(Result.success(gson.fromJson(str, LocationRequestResponse::class.java)))
                } catch (e: Exception) {
                    cb(Result.failure(e))
                }
            }
        })
    }

    fun wsUrl(): String {
        val t = token ?: ""
        val http = baseUrl.removePrefix("http://").removePrefix("https://")
        val scheme = if (baseUrl.startsWith("https")) "wss" else "ws"
        return "$scheme://$http/ws?token=$t"
    }

    fun deviceWsUrl(deviceToken: String): String {
        val http = baseUrl.removePrefix("http://").removePrefix("https://")
        val scheme = if (baseUrl.startsWith("https")) "wss" else "ws"
        return "$scheme://$http/ws?device_token=${java.net.URLEncoder.encode(deviceToken, "UTF-8")}"
    }
}
