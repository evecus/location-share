package com.locationshare.client.ws

import com.google.gson.Gson
import com.locationshare.client.api.ApiClient
import com.locationshare.client.api.LocationMsg
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class LocationWebSocket(private val onLocation: (LocationMsg) -> Unit) {
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val url = ApiClient.wsUrl()
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = gson.fromJson(text, LocationMsg::class.java)
                    if (msg.type == "location") {
                        onLocation(msg)
                    }
                } catch (_: Exception) {
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
    }
}
