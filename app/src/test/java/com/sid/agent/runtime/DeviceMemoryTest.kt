package com.sid.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMemoryTest {

    private fun gib(value: Double): Long = (value * 1_073_741_824.0).toLong()

    @Test
    fun `reported memory snaps up to the marketing size`() {
        // Representative ActivityManager.totalMem values from real hardware.
        assertEquals(2, DeviceMemory.approximateTotalGb(gib(1.84)))
        assertEquals(3, DeviceMemory.approximateTotalGb(gib(2.76)))
        assertEquals(4, DeviceMemory.approximateTotalGb(gib(3.56)))
        assertEquals(6, DeviceMemory.approximateTotalGb(gib(5.36)))
        assertEquals(8, DeviceMemory.approximateTotalGb(gib(7.15)))
        assertEquals(12, DeviceMemory.approximateTotalGb(gib(10.71)))
    }

    @Test
    fun `exact sizes are preserved`() {
        assertEquals(4, DeviceMemory.approximateTotalGb(gib(4.0)))
        assertEquals(8, DeviceMemory.approximateTotalGb(gib(8.0)))
    }

    @Test
    fun `a smaller device is never promoted to the next tier`() {
        // 3 GB of physical RAM must not be reported as 4 GB.
        assertEquals(3, DeviceMemory.approximateTotalGb(gib(2.95)))
        assertFalse(DeviceMemory.meetsRequirement(gib(2.95), requiredGb = 4))
    }

    @Test
    fun `four gigabyte arm64 devices clear the arm64 floor`() {
        // Regression: floor division reported 3 GB here and blocked setup.
        assertTrue(DeviceMemory.meetsRequirement(gib(3.56), HostArch.ARM64.minimumRamGb))
    }

    @Test
    fun `two gigabyte armv7 devices clear the arm32 floor`() {
        assertTrue(DeviceMemory.meetsRequirement(gib(1.84), HostArch.ARM32.minimumRamGb))
    }

    @Test
    fun `degenerate values do not crash`() {
        assertEquals(0, DeviceMemory.approximateTotalGb(0L))
        assertEquals(0, DeviceMemory.approximateTotalGb(-1L))
        assertEquals(1, DeviceMemory.approximateTotalGb(gib(0.4)))
    }

    @Test
    fun `sizes beyond the table fall back to truncation`() {
        assertEquals(48, DeviceMemory.approximateTotalGb(gib(48.5)))
    }
}
