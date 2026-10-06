package pk.vexel.health.smsgateway

import android.app.Application
import pk.vexel.health.smsgateway.work.GatewaySyncWorker

class GatewayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GatewaySyncWorker.schedule(this)
    }
}
