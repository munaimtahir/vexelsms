package pk.vexel.health.smsgateway.data

import android.os.Build
import pk.vexel.health.smsgateway.GatewayApplication
import org.json.JSONObject

fun deviceHeartbeat(application: GatewayApplication, fcmToken: String = ""): JSONObject = JSONObject()
    .put("app_version", application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "1.0.0")
    .put("android_version", Build.VERSION.RELEASE.orEmpty())
    .put("manufacturer", Build.MANUFACTURER.take(80))
    .put("model", Build.MODEL.take(80))
    .put("sim_slot_label", "Android system default SIM")
    .put("carrier_name", "")
    .put("fcm_registration_token", fcmToken)
