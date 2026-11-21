package com.sawt.ai.audio

/**
 * Performance-first ONNX CPU policy.
 * Use more native CPU workers and longer inference chunks on capable phones,
 * while retaining a one-worker/short-chunk fallback when free RAM is critically low.
 */
internal object DeviceTuning {
    private const val GIB = 1024L * 1024L * 1024L
    private const val CRITICAL_AVAILABLE_RAM = 512L * 1024L * 1024L

    fun recommendedCpuThreads(
        totalMemoryBytes: Long,
        availableMemoryBytes: Long,
        lowRamDevice: Boolean,
        availableProcessors: Int
    ): Int {
        val memoryLimit = when {
            totalMemoryBytes <= 0L -> 2
            totalMemoryBytes <= 3L * GIB -> 2
            totalMemoryBytes <= 6L * GIB -> 3
            else -> 4
        }
        val availableLimit = if (
            availableMemoryBytes in 1L until CRITICAL_AVAILABLE_RAM
        ) 1 else 4
        val lowRamLimit = if (lowRamDevice) 2 else 4
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
        availableMemoryBytes in 1L until CRITICAL_AVAILABLE_RAM -> 240
        lowRamDevice || totalMemoryBytes in 1L..(3L * GIB) -> 420
        totalMemoryBytes <= 0L || totalMemoryBytes <= 6L * GIB -> 650
        else -> 1_000
    }
}
