package com.sawt.ai.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Pcm16WavWriterTest {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    @Test fun writesValidMonoPcm16WaveHeaderAndSamples() {
        val file = temporaryFolder.newFile("test.wav")
        Pcm16WavWriter(file).use { writer ->
            writer.append(floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f), 22_050)
            writer.finish()
        }
        val bytes = file.readBytes()
        assertEquals(54, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(22_050, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(10, header.getInt(40))
        val pcm = ByteBuffer.wrap(bytes, 44, 10).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-32_767, pcm.short.toInt())
        assertEquals(-16_383, pcm.short.toInt())
        assertEquals(0, pcm.short.toInt())
        assertEquals(16_383, pcm.short.toInt())
        assertEquals(32_767, pcm.short.toInt())
    }

    @Test fun sanitizesNonFiniteSamplesAndAppendsMultipleChunks() {
        val file = temporaryFolder.newFile("joined.wav")
        Pcm16WavWriter(file).use { writer ->
            writer.append(floatArrayOf(Float.NaN, 0.25f), 16_000)
            writer.append(floatArrayOf(0.5f), 16_000)
            writer.finish()
        }
        val bytes = file.readBytes()
        assertEquals(50, bytes.size)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(6, header.getInt(40))
        val pcm = ByteBuffer.wrap(bytes, 44, 6).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, pcm.short.toInt())
        assertEquals(8_191, pcm.short.toInt())
        assertEquals(16_383, pcm.short.toInt())
    }
}
