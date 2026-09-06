package com.anonamus.mobileagent.runtime

/**
 * Turns [android.app.ActivityManager.MemoryInfo.totalMem] into the RAM figure a
 * user recognises.
 *
 * `totalMem` is the memory the *kernel* manages, which excludes carve-outs for
 * the bootloader, modem, GPU and other firmware. It therefore always lands
 * below the number printed on the box — typically 8-12% below, and more on
 * devices with a large GPU reservation. Flooring it to whole gibibytes (the
 * previous behaviour) under-reported by a full gigabyte on most hardware and
 * made every 4 GB phone fail a `>= 4 GB` compatibility gate.
 */
object DeviceMemory {

    private const val BYTES_PER_GIB = 1_073_741_824.0

    /** RAM configurations Android hardware actually ships with. */
    private val STANDARD_SIZES_GB = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 18, 24, 32)

    /**
     * The largest firmware carve-out we are willing to attribute to a given
     * standard size. 0.22 covers even heavy reservations (e.g. a 4 GB device
     * reporting 3.13 GiB) without letting a 3 GB device masquerade as 4 GB.
     */
    private const val MAX_RESERVED_FRACTION = 0.22

    /**
     * Best-effort marketing RAM size in GB for [totalMemBytes].
     *
     * Snaps to the nearest standard configuration at or above the reported
     * value, provided the shortfall is plausibly a firmware reservation.
     * Falls back to truncation for sizes outside the table.
     */
    fun approximateTotalGb(totalMemBytes: Long): Int {
        if (totalMemBytes <= 0L) return 0
        val reportedGib = totalMemBytes / BYTES_PER_GIB
        val snapped = STANDARD_SIZES_GB.firstOrNull { size ->
            reportedGib <= size && reportedGib >= size * (1.0 - MAX_RESERVED_FRACTION)
        }
        return snapped ?: reportedGib.toInt().coerceAtLeast(1)
    }

    /** Whether [totalMemBytes] satisfies a floor of [requiredGb] gigabytes. */
    fun meetsRequirement(totalMemBytes: Long, requiredGb: Int): Boolean =
        approximateTotalGb(totalMemBytes) >= requiredGb

    /**
     * Devices at or above this figure run the full toolchain comfortably;
     * below it the runtime is started in a reduced-footprint configuration.
     */
    const val FULL_MODE_GB: Int = 8
}
