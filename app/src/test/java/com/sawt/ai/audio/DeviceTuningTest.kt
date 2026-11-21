package com.sawt.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTuningTest {
    @Test
    fun twoGigabytePhoneUsesTwoWorkersWhenMemoryIsAvailable() {
        assertEquals(
            2,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 2L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 900L * 1024L * 1024L,
                lowRamDevice = false,
                availableProcessors = 8
            )
        )
    }

    @Test
    fun lowRamPhoneStillUsesTwoWorkersForSpeed() {
        assertEquals(
            2,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 2L * 1024L * 1024L * 1024L,
                lowRamDevice = true,
                availableProcessors = 8
            )
        )
    }

    @Test
    fun normalDeviceCanUseFourWorkers() {
        assertEquals(
            4,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
                lowRamDevice = false,
                availableProcessors = 8
            )
        )
    }

    @Test
    fun mediumDeviceUsesThreeWorkers() {
        assertEquals(
            3,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 4L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 2L * 1024L * 1024L * 1024L,
                lowRamDevice = false,
                availableProcessors = 8
            )
        )
    }

    @Test
    fun criticallyLowAvailableMemoryFallsBackToOneWorker() {
        assertEquals(
            1,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 300L * 1024L * 1024L,
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
                availableMemoryBytes = 0L,
                lowRamDevice = false,
                availableProcessors = 1
            )
        )
    }

    @Test
    fun lowMemoryPhoneGetsModeratelyLargerChunks() {
        assertEquals(
            420,
            DeviceTuning.recommendedChunkCharacters(
                totalMemoryBytes = 2L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 900L * 1024L * 1024L,
                lowRamDevice = true
            )
        )
    }

    @Test
    fun highMemoryPhoneGetsLargeChunks() {
        assertEquals(
            1_000,
            DeviceTuning.recommendedChunkCharacters(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
                lowRamDevice = false
            )
        )
    }
}
