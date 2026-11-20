package com.sawt.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTuningTest {
    @Test
    fun twoGigabytePhoneUsesSingleCpuWorker() {
        assertEquals(
            1,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 2L * 1024L * 1024L * 1024L,
                lowRamDevice = false,
                availableProcessors = 8
            )
        )
    }

    @Test
    fun lowRamFlagUsesSingleWorkerEvenWithMoreMemory() {
        assertEquals(
            1,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                lowRamDevice = true,
                availableProcessors = 8
            )
        )
    }

    @Test
    fun normalDeviceIsLimitedToTwoWorkers() {
        assertEquals(
            2,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                lowRamDevice = false,
                availableProcessors = 8
            )
        )
    }

    @Test
    fun unknownMemoryAndSingleCoreStayWithinValidRange() {
        assertEquals(
            1,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 0L,
                lowRamDevice = false,
                availableProcessors = 1
            )
        )
    }
}
