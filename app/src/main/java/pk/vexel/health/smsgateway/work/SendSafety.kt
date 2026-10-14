package pk.vexel.health.smsgateway.work

/** Small transport rules shared by Room, callbacks and the sync worker. */
object SendSafety {
    const val INTERRUPTION_AFTER_MS = 120_000L

    fun canAuthorize(state: String): Boolean = state in setOf("CLAIMED", "STARTING", "PHYSICAL")

    fun needsInterruptionReview(state: String, elapsedMs: Long): Boolean =
        state == "INVOKING" && elapsedMs > INTERRUPTION_AFTER_MS

    fun authorizeAndInvoke(reauthorize: () -> Unit, begin: () -> Boolean, send: () -> Unit) {
        reauthorize()
        if (begin()) send()
    }

    fun multipartOutcome(kind: String, count: Int, failures: Int, successes: Int, parts: Int): String? {
        if (parts < 1 || count < parts) return null
        return when {
            kind == "SENT_PART" && failures > 0 && successes > 0 -> "UNKNOWN"
            kind == "SENT_PART" && failures > 0 -> "FAILED"
            kind == "SENT_PART" -> "SENT"
            kind == "DELIVERED_PART" && failures == 0 -> "DELIVERED"
            else -> null
        }
    }

    fun nextCallbackState(current: String, outcome: String): String = when {
        current == "DELIVERED" -> "DELIVERED"
        current == "SENT" && outcome in setOf("UNKNOWN", "FAILED") -> "SENT"
        current == "FAILED" && outcome == "UNKNOWN" -> "FAILED"
        else -> outcome
    }
}
