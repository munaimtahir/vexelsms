package pk.vexel.health.smsgateway.transport

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import pk.vexel.health.smsgateway.work.GatewaySyncWorker

class GatewayFirebaseService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data.keys == setOf("type") && message.data["type"] == "work_available") {
            GatewaySyncWorker.enqueue(applicationContext)
        }
    }

    override fun onNewToken(token: String) {
        GatewayConfig(this).current()?.apply { put("fcm_token", token) }?.let { GatewayConfig(this).save(it) }
        GatewaySyncWorker.enqueue(applicationContext)
    }
}
