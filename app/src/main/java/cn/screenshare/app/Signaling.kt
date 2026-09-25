package cn.screenshare.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ApiException(val status: Int, message: String) : IOException(message)

data class RoomCredentials(val roomId: String, val role: String, val token: String, val iceServers: List<org.webrtc.PeerConnection.IceServer>, val hasTurn: Boolean)

object ServerAddress {
    fun invitation(input: String, allowHttp: Boolean): Pair<String, String> {
        val uri = try { URI(input.trim()) } catch (_: Exception) { throw IllegalArgumentException("请输入完整的邀请链接") }
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.path.orEmpty() in listOf("", "/", "/screenshare", "/screenshare/")) { "邀请链接应指向同屏搭子首页" }
        val room = uri.fragment.orEmpty().removePrefix("room=")
        require(uri.fragment == "room=$room" && room.matches(Regex("[0-9]{8}"))) { "邀请链接缺少有效的 8 位房间号" }
        val address = normalize(URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString(), allowHttp)
        return address to room
    }
    fun normalize(input: String, allowHttp: Boolean): String {
        val uri = try { URI(input.trim().trimEnd('/')) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的服务器地址") }
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty()) { "服务器地址应为 https://域名，不包含路径或账号" }
        require(uri.scheme == "https" || (allowHttp && uri.scheme == "http")) { if (allowHttp) "地址需要以 https:// 或 http:// 开头" else "服务器必须使用 HTTPS" }
        return uri.toString()
    }
}

class Signaling(private val base: String) {
    @Volatile private var pollingConnection: HttpURLConnection? = null
    @Volatile var closed = false
        private set
    var credentials: RoomCredentials? = null
        private set

    suspend fun connect(room: String?): RoomCredentials {
        val response = request("POST", if (room == null) "/v1/rooms" else "/v1/rooms/$room/join", JSONObject())
        val servers = response.getJSONArray("iceServers")
        val ice = (0 until servers.length()).map { i ->
            val item = servers.getJSONObject(i)
            val urls = item.getJSONArray("urls")
            org.webrtc.PeerConnection.IceServer.builder((0 until urls.length()).map { urls.getString(it) })
                .setUsername(item.optString("username")).setPassword(item.optString("credential")).createIceServer()
        }
        return RoomCredentials(response.getString("roomId"), response.getString("role"), response.getString("token"), ice, response.optBoolean("hasTurn")).also { credentials = it }
    }
    suspend fun poll(after: Long): JSONObject = request("GET", path("/events?after=$after"), polling = true)
    suspend fun send(type: String, data: JSONObject, id: String) = request("POST", path("/signal"), JSONObject().put("id", id).put("type", type).put("data", data))
    suspend fun share(enabled: Boolean) = request("POST", path("/share"), JSONObject().put("enabled", enabled))
    suspend fun leave() { if (credentials != null) request("DELETE", path("")) }
    fun close() { closed = true; pollingConnection?.disconnect() }
    private fun path(suffix: String) = "/v1/rooms/${checkNotNull(credentials).roomId}$suffix"

    private suspend fun request(method: String, path: String, body: JSONObject? = null, polling: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        if (closed) throw IOException("连接已关闭")
        val connection = URL(base + path).openConnection() as HttpURLConnection
        if (polling) pollingConnection = connection
        try {
            connection.requestMethod = method
            connection.connectTimeout = if (method == "DELETE") 1500 else 8_000
            connection.readTimeout = if (polling) 26_000 else if (method == "DELETE") 1500 else 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            credentials?.let { connection.setRequestProperty("Authorization", "Bearer ${it.token}") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = input?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) throw ApiException(status, json.optString("error", "服务器返回错误（$status）"))
            json
        } finally { connection.disconnect(); if (polling) pollingConnection = null }
    }
}
