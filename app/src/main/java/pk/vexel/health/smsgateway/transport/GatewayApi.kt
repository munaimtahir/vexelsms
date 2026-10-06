package pk.vexel.health.smsgateway.transport

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GatewayApi(private val baseUrl: String, private val credential: String?) {
    init {
        require(baseUrl.startsWith("https://")) { "Gateway connections require HTTPS" }
        require(baseUrl.substringAfter("https://").substringBefore('/').isNotBlank()) { "Server address is invalid" }
    }

    fun post(path: String, payload: JSONObject = JSONObject()): JSONObject {
        val target = URL(baseUrl.trimEnd('/') + path)
        require(target.protocol == "https")
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            credential?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GatewayHttpException(code)
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}

class GatewayHttpException(val status: Int) : Exception("Gateway request failed ($status)")
