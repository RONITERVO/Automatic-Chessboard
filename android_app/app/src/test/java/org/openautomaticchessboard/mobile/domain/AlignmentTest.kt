package org.openautomaticchessboard.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openautomaticchessboard.mobile.protocol.GeometrySettings

class AlignmentTest {
    @Test fun calculatesPitchAndE6Origin() {
        val current = GeometrySettings("ACB1", 188, 188, 354, 871, 1)
        val values = calculateGeometry(
            AlignmentPoint("a2", 1, 0), AlignmentPoint("h7", 15, -5), current,
        )
        assertEquals(GeometrySourceValues(190, 187, 358, 880), values)
        assertTrue(values.firmwareLines().contains("FILE_PITCH_STEPS = 190U * MOTOR_MICROSTEPS"))
    }

    @Test fun reportsSourceValuesForMicrostepping() {
        val current = GeometrySettings("ACB1", 376, 376, 708, 1742, 2)
        val values = calculateGeometry(
            AlignmentPoint("a2", 2, 0), AlignmentPoint("h7", 30, -10), current,
        )
        assertEquals(GeometrySourceValues(190, 187, 358, 880), values)
    }

    @Test fun validatesMeasurementsAndSignedRounding() {
        val current = GeometrySettings("ACB1", 188, 188, 354, 871, 1)
        assertThrows(IllegalArgumentException::class.java) {
            calculateGeometry(AlignmentPoint("a2", 0, 0), AlignmentPoint("h2", 0, 0), current)
        }
        assertEquals(2, roundedDivide(7, 3))
        assertEquals(-2, roundedDivide(-7, 3))
        assertEquals(-2, roundedDivide(7, -3))
    }
}
