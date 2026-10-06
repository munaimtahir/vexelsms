package pk.vexel.health.smsgateway.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pk.vexel.health.smsgateway.work.GatewaySyncWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) GatewaySyncWorker.schedule(context)
    }
}
