package com.vaultsync.core.server

import org.json.JSONArray
import org.json.JSONObject

enum class ApiErrorCode(val httpStatus: Int) {
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    VAULT_LOCKED(403),
    THROTTLED(429),
    INVALID_REQUEST(400),
    NOT_FOUND(404),
    INTERNAL_ERROR(500),
}

data class ApiError(
    val code: ApiErrorCode,
    val message: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code.name)
        put("message", message)
    }
}

object ApiResponses {
    fun <T> success(data: T, dataSerializer: ((T) -> Any)? = null): String {
        val json = JSONObject()
        json.put("success", true)
        val serialized = if (dataSerializer != null) {
            dataSerializer(data)
        } else when (data) {
            is JSONObject, is JSONArray, is String, is Number, is Boolean -> data
            else -> data.toString()
        }
        json.put("data", serialized)
        return json.toString()
    }

    fun error(code: ApiErrorCode, message: String): String {
        val json = JSONObject()
        json.put("success", false)
        json.put("error", ApiError(code, message).toJson())
        return json.toString()
    }
}

data class LoginResponse(
    val token: String,
    val expiresAt: Long,
    val hasVaultAccess: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("token", token)
        put("expiresAt", expiresAt)
        put("hasVaultAccess", hasVaultAccess)
    }
}

data class SessionInfo(
    val active: Boolean,
    val clientIp: String,
    val hasVaultAccess: Boolean,
    val remainingSeconds: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("active", active)
        put("clientIp", clientIp)
        put("hasVaultAccess", hasVaultAccess)
        put("remainingSeconds", remainingSeconds)
    }
}

data class ServerStatus(
    val isRunning: Boolean,
    val localIp: String?,
    val port: Int,
    val url: String?,
    val activeSessionsCount: Int,
    val isVaultUnlockedOnPhone: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("isRunning", isRunning)
        put("localIp", localIp ?: JSONObject.NULL)
        put("port", port)
        put("url", url ?: JSONObject.NULL)
        put("activeSessionsCount", activeSessionsCount)
        put("isVaultUnlockedOnPhone", isVaultUnlockedOnPhone)
    }
}
