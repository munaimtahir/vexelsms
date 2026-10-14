package pk.vexel.health.smsgateway.transport

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Observability only: a timestamp, without registration tokens or message content. */
object WakeReceipt {
    private const val FILE = "gateway-wake-observation"
    private const val RECEIVED_AT = "fcm_received_at"

    fun record(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(RECEIVED_AT, System.currentTimeMillis()).commit()
    }

    fun summary(context: Context): String {
        val time = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(RECEIVED_AT, 0L)
        if (time == 0L) return "Last FCM wake received: Never"
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'PKT'", Locale.ENGLISH)
        format.timeZone = TimeZone.getTimeZone("Asia/Karachi")
        return "Last FCM wake received: " + format.format(Date(time))
    }
}
