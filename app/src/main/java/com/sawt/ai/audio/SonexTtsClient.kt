package com.sawt.ai.audio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class SonexVoiceOption(
    val id: String,
    val name: String,
    val gender: String
) {
    val genderLabel: String
        get() = when (gender.lowercase(Locale.ROOT)) {
            "male" -> "صوت رجالي · فصحى"
            "female" -> "صوت نسائي · فصحى"
            else -> "صوت عربي · فصحى"
        }

    val label: String
        get() = if (name.isBlank()) genderLabel else "$name · $genderLabel"
}

/**
 * Direct Sonex Labs client. The user's key is supplied at runtime and is never
 * embedded in the APK or uploaded to the repository.
 */
class SonexTtsClient(private val apiKey: String) {
    private fun open(path: String): HttpURLConnection {
        val connection = URL("https://api.sonexlabs.com$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 60000
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Connection", "keep-alive")
        return connection
    }

    fun fetchArabicVoices(): List<SonexVoiceOption> {
        val connection = open("/v1/voices")
        try {
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(apiError(connection.responseCode))
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(body)
            val result = mutableListOf<SonexVoiceOption>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val language = item.optString("language")
                val languages = item.optJSONArray("languages") ?: JSONArray()
                val isArabic = language.contains("arab", ignoreCase = true) ||
                    (0 until languages.length()).any {
                        languages.optString(it).contains("arab", ignoreCase = true)
                    }
                val id = item.optString("id")
                if (isArabic && id.isNotBlank()) {
                    result += SonexVoiceOption(
                        id = id,
                        name = item.optString("name", "صوت عربي"),
                        gender = item.optString("gender")
                    )
                }
            }
            return result.sortedWith(compareBy<SonexVoiceOption> { it.gender != "female" }.thenBy { it.name })
        } finally {
            connection.disconnect()
        }
    }

    fun synthesizeToFile(text: String, voiceId: String, rate: Float, output: File) {
        require(text.isNotBlank()) { "اكتب نصًا أولًا." }
        require(text.length <= 5000) { "الحد الأقصى للنص في الطلب الواحد 5000 حرف." }
        require(voiceId.isNotBlank()) { "اختر صوتًا من المكتبة." }

        val connection = open("/v1/speech")
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "audio/mpeg")
            val body = JSONObject()
                .put("text", text)
                .put("voice_id", voiceId)
                .put("language", "auto")
                .put("speed", rate.toDouble().coerceIn(0.5, 2.0))
                .put("output_format", "mp3")
                .put("sample_rate", 24000)
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(apiError(connection.responseCode))
            }
            if (connection.getHeaderField("X-Voice-Fallback") == "true") {
                throw IllegalStateException("الخدمة لم تتعرف على الصوت المختار. حدّث مكتبة الأصوات ثم جرّب مرة أخرى.")
            }
            connection.inputStream.use { input ->
                output.outputStream().use { target -> input.copyTo(target) }
            }
            if (!output.exists() || output.length() < 256) {
                output.delete()
                throw IllegalStateException("لم يصل ملف صوت صالح من الخدمة.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(code: Int): String = when (code) {
        401, 403 -> "مفتاح Sonex غير صحيح أو لم يعد صالحًا. راجع المفتاح في الإعدادات."
        402 -> "انتهت الأحرف المجانية المتاحة في حساب Sonex."
        429 -> "طلبات كثيرة الآن. انتظر قليلًا ثم حاول مجددًا."
        else -> "تعذر الاتصال بخدمة الصوت (رمز $code). تحقق من الإنترنت وحاول لاحقًا."
    }
}
