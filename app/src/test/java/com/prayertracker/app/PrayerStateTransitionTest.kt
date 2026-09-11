package com.prayertracker.app

import com.prayertracker.app.core.model.PrayerStatus
import org.junit.Assert.*
import org.junit.Test

class PrayerStateTransitionTest {

    @Test
    fun testInitialPendingStateCapabilities() {
        val status = PrayerStatus.PENDING
        assertTrue("PENDING status should allow user confirmation", status.canConfirm)
        assertFalse("PENDING status should not be terminal", status.isTerminal)
        assertFalse("PENDING status cannot be qadha'd before missing", status.canQadha)
    }

    @Test
    fun testOtwStateCapabilities() {
        val status = PrayerStatus.OTW
        assertTrue("OTW status should allow user confirmation", status.canConfirm)
        assertFalse("OTW status should not be terminal", status.isTerminal)
        assertFalse("OTW status cannot be qadha'd before missing", status.canQadha)
    }

    @Test
    fun testCompletedTerminalState() {
        val status = PrayerStatus.COMPLETED
        assertTrue("COMPLETED status is terminal", status.isTerminal)
        assertFalse("COMPLETED status cannot be confirmed again", status.canConfirm)
        assertFalse("COMPLETED status cannot be qadha'd", status.canQadha)
    }

    @Test
    fun testMissedStateAllowsQadhaOnly() {
        val status = PrayerStatus.MISSED
        assertFalse("MISSED status cannot be confirmed as regular completed", status.canConfirm)
        assertFalse("MISSED status is not terminal until qadha is fulfilled", status.isTerminal)
        assertTrue("MISSED status MUST be eligible for manual Qadha", status.canQadha)
    }

    @Test
    fun testQadhaCompletedTerminalState() {
        val status = PrayerStatus.QADHA_COMPLETED
        assertTrue("QADHA_COMPLETED is a terminal state", status.isTerminal)
        assertFalse("QADHA_COMPLETED cannot be qadha'd again", status.canQadha)
        assertFalse("QADHA_COMPLETED cannot be confirmed again", status.canConfirm)
    }
}
