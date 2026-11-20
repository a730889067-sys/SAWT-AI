package com.sawt.ai.audio

/**
 * Conservative ONNX CPU threading policy for Android devices.
 * Supertonic's model weights are sizable; reducing parallel workers on low-RAM
 * phones helps lower native-memory peaks during model loading and inference.
 */
internal object DeviceTuning {
    private const val LOW_RAM_THRESHOLD_BYTES = 3L * 1024L * 1024L * 1024L

    fun recommendedCpuThreads(
        totalMemoryBytes: Long,
        lowRamDevice: Boolean,
        availableProcessors: Int
    ): Int {
        val memoryClassIsLow =
            totalMemoryBytes in 1L..LOW_RAM_THRESHOLD_BYTES
        return if (lowRamDevice || memoryClassIsLow) {
            1
        } else {
            availableProcessors.coerceIn(1, 2)
        }
    }
}
