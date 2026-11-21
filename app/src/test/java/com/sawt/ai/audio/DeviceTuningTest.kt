package com.sawt.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTuningTest {
    @Test
    fun twoGigabytePhoneUsesOneWorkerToReducePeakMemory() {
        assertEquals(
            1,
            DeviceTuning.recommendedCpuThreads(
                totalMemoryBytes = 2L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 900L * 1024L * 1024L,
                lowRamDevice = false,
                availableProcessors = 8
            )
        )
    }

    @Test
    fun lowRamPhoneUsesOneWorkerEvenWhenSystemMemoryIsAvailable() {
        assertEquals(
            1,
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
    fun twoGigabytePhoneUsesShorterChunks() {
        assertEquals(
            320,
            DeviceTuning.recommendedChunkCharacters(
                totalMemoryBytes = 2L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 900L * 1024L * 1024L,
                lowRamDevice = false
            )
        )
    }

    @Test
    fun lowRamPhoneUsesShorterChunks() {
        assertEquals(
            320,
            DeviceTuning.recommendedChunkCharacters(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 2L * 1024L * 1024L * 1024L,
                lowRamDevice = true
            )
        )
    }

    @Test
    fun criticallyLowOrUnknownAvailableMemoryUsesSmallChunks() {
        assertEquals(
            240,
            DeviceTuning.recommendedChunkCharacters(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 0L,
                lowRamDevice = false
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

    @Test
    fun smallOrLowRamPhonesDoNotPreloadTheNativeEngine() {
        assertFalse(
            DeviceTuning.shouldWarmUp(
                totalMemoryBytes = 2L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 900L * 1024L * 1024L,
                lowRamDevice = false
            )
        )
        assertFalse(
            DeviceTuning.shouldWarmUp(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 2L * 1024L * 1024L * 1024L,
                lowRamDevice = true
            )
        )
    }

    @Test
    fun healthyFourGigabytePhoneMayPreloadTheNativeEngine() {
        assertTrue(
            DeviceTuning.shouldWarmUp(
                totalMemoryBytes = 4L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 1L * 1024L * 1024L * 1024L,
                lowRamDevice = false
            )
        )
    }

    @Test
    fun lowAvailableMemoryDisablesNativePreload() {
        assertFalse(
            DeviceTuning.shouldWarmUp(
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 600L * 1024L * 1024L,
                lowRamDevice = false
            )
        )
    }

    @Test
    fun highMemoryDeviceCanUseEightQualitySteps() {
        assertEquals(8, DeviceTuning.recommendedNumSteps(
            8L * 1024L * 1024L * 1024L,
            4L * 1024L * 1024L * 1024L,
            false,
            8
        ))
    }

    @Test
    fun mediumMemoryDeviceCapsQualityAtSixSteps() {
        assertEquals(6, DeviceTuning.recommendedNumSteps(
            4L * 1024L * 1024L * 1024L,
            2L * 1024L * 1024L * 1024L,
            false,
            8
        ))
    }

    @Test
    fun lowMemoryDeviceCapsQualityAtFourSteps() {
        assertEquals(4, DeviceTuning.recommendedNumSteps(
            2L * 1024L * 1024L * 1024L,
            900L * 1024L * 1024L,
            false,
            8
        ))
    }

    @Test
    fun highQualityChoiceIsClampedWhenAvailableRamIsCritical() {
        assertEquals(4, DeviceTuning.recommendedNumSteps(
            8L * 1024L * 1024L * 1024L,
            300L * 1024L * 1024L,
            false,
            8
        ))
    }

    @Test
    fun fastChoiceStaysFastOnPowerfulDevice() {
        assertEquals(4, DeviceTuning.recommendedNumSteps(
            8L * 1024L * 1024L * 1024L,
            4L * 1024L * 1024L * 1024L,
            false,
            4
        ))
    }
}
