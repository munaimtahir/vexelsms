package pk.vexel.health.smsgateway.work

import org.junit.Assert.*
import org.junit.Test

class SendSafetyTest {
    @Test fun rejectedAuthorizationDoesNotInvokeTheCarrier() {
        var invoked = false
        var marked = false
        try {
            SendSafety.authorizeAndInvoke(
                reauthorize = { throw IllegalStateException("expired authorization") },
                begin = { marked = true; true },
                send = { invoked = true },
            )
            fail("Authorization rejection must escape")
        } catch (_: IllegalStateException) { }
        assertFalse(marked)
        assertFalse(invoked)
    }

    @Test fun crashAfterDurableMarkerNeverInvokesAgain() {
        var state = "PHYSICAL"
        var sends = 0
        val begin = {
            val allowed = state == "PHYSICAL"
            if (allowed) state = "INVOKING"
            allowed
        }
        try {
            SendSafety.authorizeAndInvoke({}, begin) {
                sends++
                throw IllegalStateException("process interrupted after external submission")
            }
        } catch (_: IllegalStateException) { }
        SendSafety.authorizeAndInvoke({}, begin) { sends++ }
        assertEquals(1, sends)
        assertTrue(SendSafety.needsInterruptionReview(state, 120_001))
        assertFalse(SendSafety.canAuthorize(state))
    }

    @Test fun resumedReadyWorkMustReauthorizeBeforeTheDurableMarker() {
        val sequence = mutableListOf<String>()
        SendSafety.authorizeAndInvoke(
            { sequence += "authorization" },
            { sequence += "durable marker"; true },
            { sequence += "carrier" },
        )
        assertEquals(listOf("authorization", "durable marker", "carrier"), sequence)
        assertTrue(SendSafety.canAuthorize("STARTING"))
        assertTrue(SendSafety.canAuthorize("PHYSICAL"))
        assertFalse(SendSafety.canAuthorize("UNKNOWN"))
        assertFalse(SendSafety.canAuthorize("REVIEW_REQUIRED"))
    }

    @Test fun partialMultipartSuccessIsUnknownAndIncompletePartsWait() {
        assertNull(SendSafety.multipartOutcome("SENT_PART", 1, 0, 1, 2))
        assertEquals("UNKNOWN", SendSafety.multipartOutcome("SENT_PART", 2, 1, 1, 2))
        assertEquals("FAILED", SendSafety.multipartOutcome("SENT_PART", 2, 2, 0, 2))
        assertEquals("SENT", SendSafety.multipartOutcome("SENT_PART", 2, 0, 2, 2))
        assertEquals("DELIVERED", SendSafety.multipartOutcome("DELIVERED_PART", 2, 0, 2, 2))
        assertNull(SendSafety.multipartOutcome("DELIVERED_PART", 2, 1, 1, 2))
    }

    @Test fun lateCallbacksPreserveDefinitiveLocalOutcomes() {
        assertEquals("DELIVERED", SendSafety.nextCallbackState("DELIVERED", "SENT"))
        assertEquals("DELIVERED", SendSafety.nextCallbackState("DELIVERED", "UNKNOWN"))
        assertEquals("SENT", SendSafety.nextCallbackState("SENT", "FAILED"))
        assertEquals("SENT", SendSafety.nextCallbackState("UNKNOWN", "SENT"))
    }
}
