package com.locationshare.client.api

data class LoginRequest(val username: String, val password: String)
data class RegisterRequest(val username: String, val password: String, val registration_key: String)
data class LoginResponse(val user: User, val token: String)
data class User(val id: Long, val username: String)

data class Device(
    val id: Long,
    val user_id: Long,
    val device_name: String,
    val online: Boolean = false,
    val owner_username: String? = null,
    val access: String? = null
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
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val accuracy: Double = 0.0,
    val timestamp: Long = 0,
    val request_id: String? = null,
    val device_name: String? = null,
    val owner_username: String? = null,
    val message: String? = null
)

data class DeviceCommand(
    val type: String,
    val device_id: String? = null,
    val request_id: String? = null
)

data class PermissionRequestBody(val device_id: Long)
data class PermissionRespondBody(val permission_id: Long, val accept: Boolean)

data class IncomingPermission(
    val id: Long,
    val requester_user_id: Long,
    val target_device_id: Long,
    val allowed: Boolean = false,
    val requester_username: String? = null,
    val device_name: String? = null
)

data class IncomingPermissionsResponse(val requests: List<IncomingPermission>?)
