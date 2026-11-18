package com.sawt.ai.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ChatTurn(val role: String, val text: String)

object GeminiChatClient {
    private const val MODEL = "gemini-3.8-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val MAX_INPUT_CHARS = 12000

    private const val SYSTEM_PROMPT = """
أنت مساعد SAWT AI الذكي. تحدث بالعربية الفصحى الواضحة افتراضيًا، وافهم اللهجات العربية ومنها اللهجة اليمنية.
أجب مباشرة وبأسلوب ودود ودقيق، وراعِ سياق الرسائل السابقة. لا تدّعِ أنك ChatGPT أو أنك تملك قدرات غير متاحة.
إذا طلب المستخدم الإنجليزية فأجب بالإنجليزية. نظّم الإجابات الطويلة بعناوين موجزة.
عندما يرسل المستخدم نصًا ويطلب قراءته أو تلخيصه أو شرحه، تعامل مع النص المرسل نفسه ولا تخترع معلومات غير موجودة فيه.
إذا لم تعرف معلومة أو كانت تحتاج إلى تحديث آني، صرّح بذلك بوضوح ولا تخمّن.
"""

    suspend fun reply(apiKey: String, history: List<ChatTurn>): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أضف مفتاح المساعد أولًا." }
        val recent = history.takeLast(12)
        require(recent.isNotEmpty()) { "اكتب رسالتك أولًا." }
        require(recent.last().text.length <= MAX_INPUT_CHARS) { "الرسالة طويلة جدًا. قسّمها إلى أجزاء أقصر." }

        val contents = JSONArray()
        recent.forEach { turn ->
            if (turn.text.isNotBlank()) {
                contents.put(
                    JSONObject()
                        .put("role", if (turn.role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                )
            }
        }

        val request = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("topP", 0.9)
                    .put("maxOutputTokens", 700)
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
            )

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("x-goog-api-key", apiKey.trim())
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(responseText) } catch (_: Exception) { JSONObject() }

            if (code !in 200..299) {
                val detail = json.optJSONObject("error")?.optString("message").orEmpty()
                when (code) {
                    400, 403 -> throw IOException("تعذر قبول مفتاح Gemini أو صلاحية النموذج. راجع المفتاح من إعدادات المساعد.")
                    401 -> throw IOException("مفتاح Gemini غير صحيح. افتح إعدادات المساعد وأعد إدخاله.")
                    429 -> throw IOException("وصلت إلى الحد المجاني المؤقت لطلبات Gemini. انتظر قليلًا ثم حاول مجددًا.")
                    503 -> throw IOException("المساعد مشغول الآن. أعد المحاولة بعد قليل.")
                    else -> throw IOException("تعذر الاتصال بالمساعد (رمز $code). " + detail.take(180))
                }
            }

            val candidates = json.optJSONArray("candidates") ?: throw IOException("لم يصل رد من المساعد. حاول مرة أخرى.")
            if (candidates.length() == 0) throw IOException("لم يتمكن المساعد من إنشاء رد مناسب. أعد صياغة السؤال.")
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("وصل رد فارغ من المساعد.")
            val answer = buildString {
                for (index in 0 until parts.length()) {
                    val text = parts.optJSONObject(index)?.optString("text").orEmpty()
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(text)
                    }
                }
            }.trim()
            if (answer.isBlank()) throw IOException("وصل رد فارغ من المساعد. جرّب سؤالًا آخر.")
            answer
        } finally {
            connection.disconnect()
        }
    }
}
