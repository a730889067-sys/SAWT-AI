package com.sawt.ai.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Incremental mono PCM-16 WAV writer matching Sherpa's standard WAV layout. */
internal class Pcm16WavWriter(private val file: File) : Closeable {
    private val fileOutput = FileOutputStream(file)
    private val output = BufferedOutputStream(fileOutput, BUFFER_SIZE)
    private var sampleRate = 0
    private var dataBytes = 0L
    private var streamClosed = false
    private var finished = false

    init { output.write(ByteArray(WAV_HEADER_SIZE)) }

    fun append(samples: FloatArray, rate: Int) {
        check(!streamClosed && !finished) { "WAV writer is already closed" }
        if (rate <= 0 || rate > Int.MAX_VALUE / 2) throw IOException("معدل عينات الصوت غير صالح.")
        if (sampleRate == 0) sampleRate = rate
        else if (sampleRate != rate) throw IOException("تغير معدل عينات الصوت أثناء إنشاء الملف.")

        val additionalBytes = samples.size.toLong() * BYTES_PER_SAMPLE
        if (additionalBytes > MAX_DATA_BYTES - dataBytes) {
            throw IOException("ملف الصوت الناتج أكبر من الحد المسموح لصيغة WAV.")
        }

        val buffer = ByteArray(BUFFER_SIZE)
        var used = 0
        for (sample in samples) {
            val safeSample = if (sample.isFinite()) sample else 0f
            val pcm = (safeSample * 32767f).toInt().coerceIn(-32768, 32767)
            buffer[used++] = (pcm and 0xff).toByte()
            buffer[used++] = ((pcm ushr 8) and 0xff).toByte()
            if (used == buffer.size) {
                output.write(buffer, 0, used)
                used = 0
            }
        }
        if (used > 0) output.write(buffer, 0, used)
        dataBytes += additionalBytes
    }

    fun finish() {
        if (finished) return
        if (sampleRate <= 0 || dataBytes <= 0L) throw IOException("لم ينتج محرك الصوت بيانات صالحة.")
        output.flush()
        fileOutput.fd.sync()
        output.close()
        streamClosed = true

        val header = ByteBuffer.allocate(WAV_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt((36L + dataBytes).toInt())
            .put("WAVE".toByteArray(Charsets.US_ASCII))
            .put("fmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16)
            .putShort(1.toShort())
            .putShort(1.toShort())
            .putInt(sampleRate)
            .putInt(sampleRate * BYTES_PER_SAMPLE.toInt())
            .putShort(BYTES_PER_SAMPLE.toShort())
            .putShort(16.toShort())
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(dataBytes.toInt())
            .array()

        RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.seek(0L)
            randomAccess.write(header)
            randomAccess.setLength(WAV_HEADER_SIZE.toLong() + dataBytes)
            randomAccess.fd.sync()
        }
        finished = true
    }

    override fun close() {
        if (!streamClosed) {
            streamClosed = true
            output.close()
        }
    }

    companion object {
        private const val WAV_HEADER_SIZE = 44
        private const val BYTES_PER_SAMPLE = 2L
        private const val MAX_DATA_BYTES = Int.MAX_VALUE.toLong() - 36L
        private const val BUFFER_SIZE = 32 * 1024
    }
}
