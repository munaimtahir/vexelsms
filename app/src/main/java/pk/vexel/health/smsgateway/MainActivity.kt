package pk.vexel.health.smsgateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pk.vexel.health.smsgateway.transport.GatewayApi
import pk.vexel.health.smsgateway.transport.GatewayConfig
import pk.vexel.health.smsgateway.transport.WakeReceipt
import pk.vexel.health.smsgateway.work.GatewaySyncWorker

class MainActivity : ComponentActivity() {
    private var wakeSummary by mutableStateOf("Last FCM wake received: Never")
    private var status by mutableStateOf("Not paired")
    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.getStringExtra("pairing_payload")?.let(::pair)
    }
    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = statusText()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) smsPermission.launch(Manifest.permission.SEND_SMS)
        setContent {
            val scope = rememberCoroutineScope()
            var payload by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.app_logo),
                                contentDescription = "Vexel SMS Gateway Logo",
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                            Column {
                                Text("Vexel SMS Gateway", style = MaterialTheme.typography.titleLarge)
                                Text("Automated SMS Dispatcher", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(status)
                        Text(wakeSummary)
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            Text("SMS permission is required before this phone can claim or send gateway messages.")
                        }
                        Text("Keep this dedicated phone powered, connected to the internet, and able to send SMS. Message content and phone numbers are not shown here.")
                        Button(onClick = { scan.launch(Intent(this@MainActivity, pk.vexel.health.smsgateway.ui.ScanActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Scan pairing QR") }
                        OutlinedTextField(payload, { payload = it }, label = { Text("Or paste pairing payload") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        Button(enabled = !busy && payload.isNotBlank(), onClick = {
                            busy = true
                            scope.launch {
                                status = withContext(Dispatchers.IO) { pairPayload(payload) }
                                busy = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Pairing…" else "Pair device") }
                        Button(enabled = GatewayConfig(this@MainActivity).current() != null, onClick = {
                            GatewaySyncWorker.enqueue(this@MainActivity)
                            status = "Checking connection…"
                        }, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wakeSummary = WakeReceipt.summary(this)
        status = statusText()
    }

    private fun pair(raw: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { pairPayload(raw) }
            // Keep Sync now and the wake observation visible after scanning a pairing QR.
            status = result
        }
    }

    private fun pairPayload(raw: String): String = try {
        val values = JSONObject(raw)
        val server = values.getString("server_url").trimEnd('/')
        val response = GatewayApi(server, null).post(
            "/api/v1/communications/gateway/pair/",
            JSONObject().put("device_uuid", values.getString("device_uuid")).put("pairing_token", values.getString("pairing_token")),
        )
        val config = JSONObject().put("server_url", server)
            .put("device_uuid", response.getString("device_uuid"))
            .put("device_credential", response.getString("device_credential"))
        GatewayConfig(this).save(config)
        GatewaySyncWorker.schedule(this)
        "Paired. The one-time code has been exchanged for a protected device credential."
    } catch (_: Exception) {
        "Pairing could not be completed. Check the QR code, internet connection, and expiry."
    }

    private fun statusText(): String {
        val config = GatewayConfig(this).current() ?: return "Not paired"
        if (config.optBoolean("credential_invalid")) return "Credential rejected. Ask an administrator to revoke and pair this device again."
        return "Paired to " + config.optString("server_url")
    }
}
