package com.locationshare.client.api

data class LoginRequest(val username: String, val password: String)
data class LoginResponse(val user: User, val token: String)
data class User(val id: Long, val username: String)

data class Device(
    val id: Long,
    val user_id: Long,
    val device_name: String,
    val online: Boolean = false
)

data class DevicesResponse(val devices: List<Device>?)

data class RegisterDeviceRequest(val device_name: String)
data class RegisterDeviceResponse(
    val device: Device,
    val device_token: String,
    val note: String? = null
)

data class LocationRequest(val device_id: Long)
data class LocationRequestResponse(
    val message: String,
    val request_id: String,
    val device_id: Long
)

data class LocationMsg(
    val type: String,
    val device_id: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
    val timestamp: Long,
    val request_id: String? = null
)

/** Incoming command from server to device */
data class DeviceCommand(
    val type: String,
    val device_id: String? = null,
    val request_id: String? = null
)
