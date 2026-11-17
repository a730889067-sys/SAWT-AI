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
    private fun endpoint(path: String) = URL(baseUrl.trimEnd('/') + path)

    fun fetchVoices(): List<CloudVoiceOption> {
        val connection = endpoint("/v1/voices").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            if (connection.responseCode !in 200..299) error("تعذر تحميل مكتبة الأصوات")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val array = json.getJSONArray("voices")
            return (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val id = item.optString("id")
                if (!id.startsWith("ar-XA-")) null
                else CloudVoiceOption(id, item.optString("gender"), item.optString("quality"))
            }
        } finally {
            connection.disconnect()
        }
    }

    fun synthesizeToFile(text: String, voiceId: String, rate: Float, pitch: Float, output: File) {
        require(text.isNotBlank() && text.length <= 5000) { "النص يجب ألا يتجاوز 5000 حرف." }
        require(voiceId.startsWith("ar-XA-")) { "هذه المكتبة مخصصة للعربية الفصحى فقط." }
        val connection = endpoint("/v1/synthesize").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject()
                .put("text", text)
                .put("voiceId", voiceId)
                .put("speakingRate", rate.toDouble())
                .put("pitch", ((pitch - 1f) * 12f).toDouble())
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error(if (detail.contains("ONLY_MODERN")) "الخادم يقبل أصوات العربية الفصحى فقط." else "فشل إنشاء الصوت. تحقق من إعداد الخادم.")
            }
            connection.inputStream.use { input -> output.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }
}
