package com.sawt.ai.audio

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class CloudVoiceOption(
    val id: String,
    val gender: String,
    val quality: String
) {
    val label: String get() = when (gender.uppercase()) {
        "MALE" -> "صوت رجالي · فصحى"
        "FEMALE" -> "صوت نسائي · فصحى"
        else -> "صوت عربي · فصحى"
    }
}

class CloudVoiceClient(private val baseUrl: String) {
    private fun endpoint(path: String): URL =
        URL(baseUrl.trimEnd('/') + path)

    fun fetchVoices(): List<CloudVoiceOption> {
        val connection = endpoint("/v1/voices").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("تعذر تحميل مكتبة الأصوات (رمز $responseCode).")
            }

            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val array = json.getJSONArray("voices")
            return (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val id = item.optString("id")
                val gender = item.optString("gender")
                if (!id.matches(Regex("^ar-XA-[A-Za-z0-9-]+$")) ||
                    gender !in setOf("MALE", "FEMALE")) {
                    null
                } else {
                    CloudVoiceOption(id, gender, item.optString("quality"))
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun synthesizeToFile(
        text: String,
        voiceId: String,
        rate: Float,
        pitch: Float,
        output: File
    ) {
        require(text.isNotBlank()) { "اكتب نصًا أولًا." }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
            "النص أطول من الحد المسموح به في طلب واحد. قلّل النص ثم حاول مجددًا."
        }
        require(voiceId.matches(Regex("^ar-XA-[A-Za-z0-9-]+$"))) {
            "هذا الصوت ليس من مكتبة العربية الفصحى."
        }

        val connection = endpoint("/v1/synthesize").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "audio/mpeg")

            val body = JSONObject()
                .put("text", text)
                .put("voiceId", voiceId)
                .put("speakingRate", rate.toDouble().coerceIn(0.7, 1.3))
                .put("pitch", ((pitch - 1f) * 12f).toDouble().coerceIn(-5.0, 5.0))
                .toString()

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                when {
                    detail.contains("ONLY_MODERN_STANDARD_ARABIC_VOICES_ALLOWED") ->
                        error("الخادم يقبل أصوات العربية الفصحى فقط.")
                    detail.contains("INVALID_TEXT") ->
                        error("النص أطول من الحد المسموح به.")
                    responseCode == 429 ->
                        error("وصلت إلى حد الطلبات المؤقت. انتظر قليلًا ثم حاول.")
                    else -> error("فشل إنشاء الصوت (رمز $responseCode). تحقق من الخادم والإنترنت.")
                }
            }

            val contentType = connection.contentType.orEmpty()
            require(contentType.startsWith("audio/", ignoreCase = true)) {
                "لم يرجع الخادم ملفًا صوتيًا صالحًا."
            }

            output.parentFile?.mkdirs()
            output.delete()
            connection.inputStream.use { input ->
                output.outputStream().use { destination -> input.copyTo(destination) }
            }
            require(output.exists() && output.length() >= MIN_AUDIO_BYTES) {
                output.delete()
                "ملف الصوت المستلم فارغ أو غير مكتمل."
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 5_000
        private const val MIN_AUDIO_BYTES = 256L
    }
}
