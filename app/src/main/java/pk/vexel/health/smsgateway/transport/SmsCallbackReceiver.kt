package pk.vexel.health.smsgateway.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pk.vexel.health.smsgateway.data.GatewayCallback
import pk.vexel.health.smsgateway.data.GatewayDatabase
import pk.vexel.health.smsgateway.data.GatewayEvent
import pk.vexel.health.smsgateway.security.SecretStore
import pk.vexel.health.smsgateway.work.GatewaySyncWorker
import org.json.JSONObject

class SmsCallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        callbackExecutor.execute {
            try {
                persistCallback(context.applicationContext, intent, resultCode)
            } finally {
                pending.finish()
            }
        }
    }

    private fun persistCallback(context: Context, intent: Intent, resultCode: Int) {
        val attempt = intent.getStringExtra("attempt_id") ?: return
        val kind = intent.getStringExtra("kind") ?: return
        val index = intent.getIntExtra("part_index", -1)
        if (index < 0 || kind !in setOf("SENT_PART", "DELIVERED_PART")) return
        val dao = GatewayDatabase.get(context).gatewayDao()
        val work = dao.work(attempt) ?: return
        val (count, failures, successes) = dao.recordCallback(GatewayCallback(attempt, kind, index, resultCode))
        val secrets = SecretStore(context)
        val payload = JSONObject(secrets.decrypt(work.encryptedPayload))
        if (count >= payload.optInt("part_count", 1)) {
            val eventType = when {
                kind == "SENT_PART" && failures > 0 && successes > 0 -> "UNKNOWN"
                kind == "SENT_PART" && failures > 0 -> "FAILED"
                kind == "SENT_PART" -> "SENT"
                failures > 0 -> return
                else -> "DELIVERED"
            }
            if (dao.event(attempt, eventType) != null) return
            val eventPayload = JSONObject(payload.toString()).put("event_type", eventType)
            dao.recordCallback(work, eventType, GatewayEvent(attemptId = attempt, eventType = eventType, encryptedPayload = secrets.encrypt(eventPayload.toString())))
            GatewaySyncWorker.enqueue(context)
        }
    }

    companion object {
        private val callbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}
