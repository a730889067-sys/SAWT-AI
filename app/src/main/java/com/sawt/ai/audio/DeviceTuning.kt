package com.sawt.ai.audio

/**
 * Conservative CPU and memory policy for on-device inference.
 * Small/low-RAM phones avoid startup preloading and use smaller synthesis chunks.
 */
internal object DeviceTuning {
    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L
    private const val CRITICAL_AVAILABLE_RAM = 512L * MIB
    private const val PRELOAD_AVAILABLE_RAM = 768L * MIB
    private const val SMALL_DEVICE_RAM = 5L * GIB / 2L

    fun shouldWarmUp(
        totalMemoryBytes: Long,
        availableMemoryBytes: Long,
        lowRamDevice: Boolean
    ): Boolean =
        !lowRamDevice &&
            totalMemoryBytes >= 4L * GIB &&
            availableMemoryBytes >= PRELOAD_AVAILABLE_RAM

    fun recommendedCpuThreads(
        totalMemoryBytes: Long,
        availableMemoryBytes: Long,
        lowRamDevice: Boolean,
        availableProcessors: Int
    ): Int {
        val memoryLimit = when {
            totalMemoryBytes <= 0L -> 1
            totalMemoryBytes <= SMALL_DEVICE_RAM -> 1
            totalMemoryBytes <= 3L * GIB -> 2
            totalMemoryBytes <= 6L * GIB -> 3
            else -> 4
        }
        val availableLimit = if (
            availableMemoryBytes <= 0L || availableMemoryBytes < CRITICAL_AVAILABLE_RAM
        ) 1 else 4
        val lowRamLimit = if (lowRamDevice) 1 else 4
        return minOf(
            availableProcessors.coerceAtLeast(1),
            memoryLimit,
            availableLimit,
            lowRamLimit
        ).coerceAtLeast(1)
    }

    fun recommendedChunkCharacters(
        totalMemoryBytes: Long,
        availableMemoryBytes: Long,
        lowRamDevice: Boolean
    ): Int = when {
        availableMemoryBytes <= 0L || availableMemoryBytes < CRITICAL_AVAILABLE_RAM -> 240
        lowRamDevice || totalMemoryBytes in 1L..SMALL_DEVICE_RAM -> 320
        totalMemoryBytes <= 3L * GIB -> 420
        totalMemoryBytes <= 6L * GIB -> 650
        else -> 1_000
    }

    /**
     * High-quality denoising needs more compute time and memory.
     * Clamp the user's choice using both physical and currently available RAM.
     */
    fun recommendedNumSteps(
        totalMemoryBytes: Long,
        availableMemoryBytes: Long,
        lowRamDevice: Boolean,
        requestedSteps: Int
    ): Int {
        val requested = when {
            requestedSteps >= 8 -> 8
            requestedSteps >= 6 -> 6
            else -> 4
        }
        val maxSafeSteps = when {
            lowRamDevice || totalMemoryBytes <= 0L ||
                totalMemoryBytes <= SMALL_DEVICE_RAM ||
                availableMemoryBytes <= CRITICAL_AVAILABLE_RAM -> 4
            totalMemoryBytes < 4L * GIB || availableMemoryBytes < GIB -> 6
            else -> 8
        }
        return minOf(requested, maxSafeSteps)
    }

}
