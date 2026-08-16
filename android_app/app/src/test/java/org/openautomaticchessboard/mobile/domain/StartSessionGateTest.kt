package org.openautomaticchessboard.mobile.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartSessionGateTest {
    @Test
    fun acknowledgementClearsPendingStartAndInvalidatesItsTimeout() {
        val gate = StartSessionGate()
        val token = gate.begin()

        assertTrue(gate.pending)
        assertTrue(gate.matches(token))

        gate.acknowledge()

        assertFalse(gate.pending)
        assertFalse(gate.matches(token))
    }

    @Test
    fun replacementStartMakesPreviousTimeoutStale() {
        val gate = StartSessionGate()
        val oldToken = gate.begin()
        gate.invalidate()
        val currentToken = gate.begin()

        assertFalse(gate.matches(oldToken))
        assertTrue(gate.matches(currentToken))
    }
}
