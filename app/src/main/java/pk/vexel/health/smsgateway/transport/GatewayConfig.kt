package pk.vexel.health.smsgateway.transport

import android.content.Context
import org.json.JSONObject
import pk.vexel.health.smsgateway.security.SecretStore

class GatewayConfig(context: Context) {
    private val secrets = SecretStore(context)
    private val configKey = "gateway_config"
    fun current(): JSONObject? = secrets.getJson(configKey)
    fun save(values: JSONObject) = secrets.putJson(configKey, values)
    fun clear() = secrets.clear(configKey)
}
