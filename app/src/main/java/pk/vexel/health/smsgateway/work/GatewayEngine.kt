package pk.vexel.health.smsgateway.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pk.vexel.health.smsgateway.GatewayApplication
import pk.vexel.health.smsgateway.data.GatewayDatabase
import pk.vexel.health.smsgateway.data.GatewayEvent
import pk.vexel.health.smsgateway.data.GatewayWork
import pk.vexel.health.smsgateway.data.deviceHeartbeat
import pk.vexel.health.smsgateway.security.SecretStore
import pk.vexel.health.smsgateway.transport.GatewayApi
import pk.vexel.health.smsgateway.transport.GatewayConfig
import pk.vexel.health.smsgateway.transport.SmsCallbackReceiver
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GatewaySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as GatewayApplication
        val config = GatewayConfig(app).current() ?: return@withContext Result.success()
        val server = config.optString("server_url")
        val credential = config.optString("device_credential")
        if (server.isBlank() || credential.isBlank()) return@withContext Result.success()
        val api = GatewayApi(server, credential)
        val dao = GatewayDatabase.get(app).gatewayDao()
        val secrets = SecretStore(app)
        try {
            val fcm = currentFcmToken(config)
            GatewayConfig(app).save(config)
            val heartbeat = deviceHeartbeat(app, fcm)
            api.post("/api/v1/communications/gateway/heartbeat/", heartbeat)

            // A process that died after the pre-send marker must never invoke SmsManager again.
            dao.currentWork()?.takeIf { SendSafety.needsInterruptionReview(it.state, System.currentTimeMillis() - it.updatedAt) }?.let { work ->
                val payload = JSONObject(secrets.decrypt(work.encryptedPayload))
                val unknown = JSONObject(payload.toString()).put("event_type", "UNKNOWN").put("error_code", "PROCESS_INTERRUPTED")
                dao.recordCallback(work, "UNKNOWN", GatewayEvent(attemptId = work.attemptId, eventType = "UNKNOWN", encryptedPayload = secrets.encrypt(unknown.toString())))
            }

            syncEvents(api, dao, secrets)
            if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                return@withContext Result.success()
            }
            if (dao.currentWork() == null) {
                val response = api.post("/api/v1/communications/gateway/claim/")
                val claimed = response.optJSONObject("work")
                if (claimed != null && !claimed.isNull("message_uuid")) {
                    val attempt = claimed.getString("attempt_uuid")
                    val body = claimed.getString("body")
                    val payload = JSONObject()
                        .put("message_uuid", claimed.getString("message_uuid"))
                        .put("attempt_uuid", attempt)
                        .put("lease_token", claimed.getString("lease_token"))
                        .put("recipient", claimed.getString("recipient"))
                        .put("body", body)
                        .put("part_count", SmsManager.getDefault().divideMessage(body).size)
                    val work = GatewayWork(attempt, claimed.getString("message_uuid"), "CLAIMED", secrets.encrypt(payload.toString()), System.currentTimeMillis())
                    dao.persistClaim(work)
                }
            }

            // Resume a claim persisted before a crash, without claiming a second message.
            dao.nextClaimed()?.let { work ->
                val payload = JSONObject(secrets.decrypt(work.encryptedPayload))
                val started = JSONObject(payload.toString()).put("event_type", "SEND_STARTED")
                dao.beginTransmission(work, GatewayEvent(attemptId = work.attemptId, eventType = "SEND_STARTED", encryptedPayload = secrets.encrypt(started.toString())))
            }

            // SEND_STARTED is acknowledged by the server before local transition to PHYSICAL.
            syncEvents(api, dao, secrets)
            val physical = dao.physicalWork()
            if (physical != null) submitOnce(app, api, dao, secrets, physical)
            Result.success()
        } catch (e: pk.vexel.health.smsgateway.transport.GatewayHttpException) {
            if (e.status == 401) {
                GatewayConfig(app).current()?.apply { put("credential_invalid", true) }?.let { GatewayConfig(app).save(it) }
                Result.failure()
            } else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun syncEvents(api: GatewayApi, dao: pk.vexel.health.smsgateway.data.GatewayDao, secrets: SecretStore) {
        for (event in dao.pendingEvents()) {
            val work = dao.work(event.attemptId) ?: continue
            try {
                postEvent(api, secrets, work, event)
            } catch (error: pk.vexel.health.smsgateway.transport.GatewayHttpException) {
                if (event.eventType == "SEND_STARTED" && error.status in setOf(400, 403, 404, 409, 410)) {
                    dao.archiveBeforeSend(event)
                    continue
                }
                throw error
            }
            // One Room commit: a crash cannot separate receipt from the ready state.
            dao.acknowledgeEvent(event)
        }
    }

    private fun postEvent(api: GatewayApi, secrets: SecretStore, work: GatewayWork, event: GatewayEvent) {
        val stored = JSONObject(secrets.decrypt(event.encryptedPayload))
        val values = JSONObject()
            .put("attempt_uuid", stored.getString("attempt_uuid"))
            .put("lease_token", stored.getString("lease_token"))
            .put("event_type", stored.getString("event_type"))
            .put("event_uuid", event.eventId)
        if (stored.has("error_code")) values.put("error_code", stored.getString("error_code"))
        api.post("/api/v1/communications/gateway/messages/${work.messageId}/events/", values)
    }

    private fun submitOnce(context: Context, api: GatewayApi, dao: pk.vexel.health.smsgateway.data.GatewayDao, secrets: SecretStore, work: GatewayWork) {
        // Every resumed ready state needs current server authorization using the SAME event.
        val started = dao.event(work.attemptId, "SEND_STARTED") ?: return
        val values = JSONObject(secrets.decrypt(work.encryptedPayload))
        val parts = SmsManager.getDefault().divideMessage(values.getString("body"))
        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered = ArrayList<PendingIntent>(parts.size)
        for (index in parts.indices) {
            sent += callbackIntent(context, work.attemptId, "SENT_PART", index)
            delivered += callbackIntent(context, work.attemptId, "DELIVERED_PART", index)
        }
        try {
            SendSafety.authorizeAndInvoke(
                reauthorize = {
                    postEvent(api, secrets, work, started)
                    dao.acknowledgeEvent(started)
                },
                begin = { dao.markInvoking(work.attemptId) },
                send = {
                    try {
                        SmsManager.getDefault().sendMultipartTextMessage(values.getString("recipient"), null, parts, sent, delivered)
                    } catch (_: Exception) {
                        // The carrier may have accepted the SMS; never invoke it again.
                        val unknown = JSONObject(values.toString()).put("event_type", "UNKNOWN").put("error_code", "PROCESS_INTERRUPTED")
                        dao.recordCallback(work, "UNKNOWN", GatewayEvent(attemptId = work.attemptId, eventType = "UNKNOWN", encryptedPayload = secrets.encrypt(unknown.toString())))
                    }
                },
            )
        } catch (error: pk.vexel.health.smsgateway.transport.GatewayHttpException) {
            if (error.status in setOf(400, 403, 404, 409, 410)) {
                dao.archiveBeforeSend(started)
                return
            }
            throw error
        }
    }

    private fun callbackIntent(context: Context, attemptId: String, kind: String, index: Int): PendingIntent {
        val intent = Intent(context, SmsCallbackReceiver::class.java)
            .setAction("pk.vexel.health.smsgateway.$kind")
            .setData(android.net.Uri.parse("vexelsms://$kind/$attemptId/$index"))
            .putExtra("attempt_id", attemptId).putExtra("kind", kind).putExtra("part_index", index)
        val code = ("$kind:$attemptId:$index").hashCode()
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private suspend fun currentFcmToken(config: JSONObject): String = try {
        val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.awaitToken()
        if (token.isNotBlank()) config.put("fcm_token", token)
        token.ifBlank { config.optString("fcm_token") }
    } catch (_: Exception) { config.optString("fcm_token") }

    companion object {
        private const val UNIQUE = "vexel-gateway-sync"
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<GatewaySyncWorker>().build())
        }
        fun schedule(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<GatewaySyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("vexel-gateway-reconcile", ExistingPeriodicWorkPolicy.KEEP, periodic)
            enqueue(context)
        }
    }
}

private suspend fun com.google.android.gms.tasks.Task<String>.awaitToken(): String = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
}
