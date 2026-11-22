package com.sawt.ai.audio

import android.content.Context
import com.sawt.ai.BuildConfig
import com.sawt.ai.auth.SawtAuthSession
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class SawtCloudProvider(
    val id: String,
    val company: String,
    val name: String,
    val model: String,
    val status: String,
    val note: String,
    val dataConsentRequired: Boolean
)

data class SawtCloudAudio(
    val file: File,
    val providerId: String,
    val contentType: String
)

class SawtCloudTtsClient(context: Context) {
    private val cacheDir = context.applicationContext.cacheDir
    private val baseUrl = BuildConfig.SAWT_API_BASE_URL.trim().trimEnd('/')

    val isConfigured: Boolean
        get() = baseUrl.startsWith("https://") && baseUrl.length > "https://".length

    fun listProviders(session: SawtAuthSession): List<SawtCloudProvider> {
        check(isConfigured) { "خدمة الإنترنت غير مهيأة بعد." }
        val connection = open("/v2/providers", session.idToken, "GET")
        return try {
            val code = connection.responseCode
            val raw = readResponse(connection, code)
            if (code !in 200..299) throw IOException(parseApiError(raw))
            val entries = JSONObject(raw).optJSONArray("providers") ?: return emptyList()
            buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    add(
                        SawtCloudProvider(
                            id = item.optString("id"),
                            company = item.optString("company"),
                            name = item.optString("name"),
                            model = item.optString("model"),
                            status = item.optString("status"),
                            note = item.optString("freeTierNote"),
                            dataConsentRequired = item.optBoolean("dataConsentRequired")
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun synthesize(
        session: SawtAuthSession,
        text: String,
        voice: String,
        cloudDataConsent: Boolean,
        provider: String = "auto"
    ): SawtCloudAudio {
        check(isConfigured) { "خدمة الإنترنت غير مهيأة بعد." }
        val connection = open("/v2/synthesize", session.idToken, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            val request = JSONObject()
                .put("text", text)
                .put("voice", voice)
                .put("provider", provider)
                .put("cloudDataConsent", cloudDataConsent)
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val raw = readResponse(connection, code)
                throw IOException(parseApiError(raw))
            }
            val contentType = connection.contentType?.substringBefore(";")?.lowercase().orEmpty()
            if (!contentType.startsWith("audio/")) throw IOException("استجابة محرك الصوت ليست ملفًا صوتيًا صالحًا.")
            val extension = if (contentType.contains("wav")) "wav" else "mp3"
            val output = File(cacheDir, "sawt-cloud-" + System.currentTimeMillis() + "." + extension)
            connection.inputStream.use { input ->
                output.outputStream().use { target -> input.copyTo(target) }
            }
            if (!output.isFile || output.length() < 512L) {
                output.delete()
                throw IOException("ملف الصوت الناتج غير مكتمل.")
            }
            SawtCloudAudio(
                file = output,
                providerId = connection.getHeaderField("x-sawt-provider").orEmpty(),
                contentType = contentType
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, idToken: String, method: String): HttpURLConnection {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            useCaches = false
            setRequestProperty("Authorization", "Bearer " + idToken)
            setRequestProperty("Accept", "application/json, audio/wav, audio/mpeg")
        }
        return connection
    }

    private fun readResponse(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(4_000) }.orEmpty()
    }

    private fun parseApiError(raw: String): String {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        return json?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json?.optString("error")?.takeIf { it.isNotBlank() }
            ?: "تعذر الاتصال بمحرك الصوت المجاني."
    }
}
