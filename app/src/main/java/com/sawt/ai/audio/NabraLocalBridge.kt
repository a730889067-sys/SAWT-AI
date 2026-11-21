package com.sawt.ai.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.File
import java.io.RandomAccessFile

sealed class NabraBridgeEvent {
    data object PageReady : NabraBridgeEvent()
    data class Progress(val name: String, val loaded: Long, val total: Long) : NabraBridgeEvent()
    data class EngineReady(val device: String) : NabraBridgeEvent()
    data class AudioReady(val file: File) : NabraBridgeEvent()
    data class Error(val message: String) : NabraBridgeEvent()
    data object ModelsRemoved : NabraBridgeEvent()
}

class NabraLocalBridge(
    context: Context,
    private val onEvent: (NabraBridgeEvent) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeFile: File? = null
    private var activeOutput: RandomAccessFile? = null
    private var sampleRate: Int = 24_000

    private fun emit(event: NabraBridgeEvent) {
        mainHandler.post { onEvent(event) }
    }

    @JavascriptInterface
    fun onEnginePageReady() {
        emit(NabraBridgeEvent.PageReady)
    }

    @JavascriptInterface
    fun onEngineProgress(name: String, loaded: Double, total: Double) {
        emit(NabraBridgeEvent.Progress(name, loaded.toLong().coerceAtLeast(0), total.toLong().coerceAtLeast(0)))
    }

    @JavascriptInterface
    fun onEngineReady(device: String) {
        emit(NabraBridgeEvent.EngineReady(device))
    }

    @JavascriptInterface
    @Synchronized
    fun beginAudio(rate: Int): Boolean {
        try {
            closeActive(delete = true)
            sampleRate = rate.coerceIn(8_000, 48_000)
            val file = File(appContext.cacheDir, "sawt-nabra-${UUID.randomUUID()}.wav")
            val output = RandomAccessFile(file, "rw")
            output.setLength(0)
            output.write(ByteArray(WAV_HEADER_BYTES))
            activeFile = file
            activeOutput = output
            return true
        } catch (error: Exception) {
            emit(NabraBridgeEvent.Error("تعذر تجهيز ملف الصوت: ${error.message ?: "خطأ في التخزين"}"))
            return false
        }
    }

    @JavascriptInterface
    @Synchronized
    fun appendAudioChunk(encoded: String): Boolean {
        try {
            if (encoded.length > MAX_BASE64_CHUNK_CHARS) {
                throw IllegalArgumentException("حزمة صوت أكبر من الحد الآمن.")
            }
            val output = activeOutput ?: throw IllegalStateException("لم يبدأ تسجيل الصوت.")
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            output.write(bytes)
            return true
        } catch (error: Exception) {
            closeActive(delete = true)
            emit(NabraBridgeEvent.Error("تعذر كتابة الصوت: ${error.message ?: "خطأ غير معروف"}"))
            return false
        }
    }

    @JavascriptInterface
    @Synchronized
    fun finishAudio(): Boolean {
        try {
            val output = activeOutput ?: throw IllegalStateException("لا يوجد ملف صوت مفتوح.")
            val file = activeFile ?: throw IllegalStateException("لم يتم إنشاء ملف الصوت.")
            val dataLength = output.length() - WAV_HEADER_BYTES
            if (dataLength < MIN_AUDIO_BYTES || dataLength > Int.MAX_VALUE || dataLength % 2L != 0L) {
                throw IllegalStateException("ملف الصوت فارغ أو غير مكتمل.")
            }

            output.seek(0)
            output.writeBytes("RIFF")
            writeIntLe(output, (36L + dataLength).toInt())
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            writeIntLe(output, 16)
            writeShortLe(output, 1)
            writeShortLe(output, 1)
            writeIntLe(output, sampleRate)
            writeIntLe(output, sampleRate * 2)
            writeShortLe(output, 2)
            writeShortLe(output, 16)
            output.writeBytes("data")
            writeIntLe(output, dataLength.toInt())
            output.fd.sync()
            output.close()

            activeOutput = null
            activeFile = null
            emit(NabraBridgeEvent.AudioReady(file))
            return true
        } catch (error: Exception) {
            closeActive(delete = true)
            emit(NabraBridgeEvent.Error("تعذر إكمال ملف الصوت: ${error.message ?: "خطأ غير معروف"}"))
            return false
        }
    }

    @JavascriptInterface
    @Synchronized
    fun cancelAudio() {
        closeActive(delete = true)
    }

    @JavascriptInterface
    fun onEngineError(message: String) {
        emit(NabraBridgeEvent.Error(message.take(500)))
    }

    @JavascriptInterface
    fun onModelsRemoved() {
        emit(NabraBridgeEvent.ModelsRemoved)
    }

    @Synchronized
    fun close() {
        closeActive(delete = true)
    }

    private fun closeActive(delete: Boolean) {
        try { activeOutput?.close() } catch (_: Exception) { }
        activeOutput = null
        val file = activeFile
        activeFile = null
        if (delete) try { file?.delete() } catch (_: Exception) { }
    }

    private fun writeIntLe(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    private fun writeShortLe(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    companion object {
        private const val WAV_HEADER_BYTES = 44
        private const val MIN_AUDIO_BYTES = 256L
        private const val MAX_BASE64_CHUNK_CHARS = 100_000
    }
}
