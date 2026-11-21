package com.sawt.ai.audio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class VoicePack(
    val id: String,
    val name: String,
    val description: String,
    val engine: String,
    val repository: String,
    val revision: String,
    val dtype: String,
    val priceType: String,
    val priceLabel: String,
    val license: String,
    val sourceUrl: String,
    val sizeLabel: String,
    val languages: Set<String>,
    val downloadable: Boolean
)

/**
 * Loads a small, signed-off-by-source (pinned revision) voice manifest.
 * The manifest never contains executable code or arbitrary Android download URLs.
 */
class VoiceCatalogRepository(private val context: Context) {
    fun bundled(): List<VoicePack> = parseCatalog(
        context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
    )

    fun loadCachedOrBundled(): List<VoicePack> {
        val cache = File(context.filesDir, CACHE_FILE)
        return runCatching { parseCatalog(cache.readText()) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: bundled()
    }

    @Throws(IOException::class)
    fun refresh(): List<VoicePack> {
        val connection = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SAWT-AI-Android")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("تعذر تحديث مكتبة الأصوات (HTTP ${connection.responseCode}).")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.length > MAX_CATALOG_CHARS) throw IOException("ملف مكتبة الأصوات أكبر من الحد المسموح.")
            val entries = parseCatalog(body)
            if (entries.isEmpty()) throw IOException("لم يحتوِ فهرس الأصوات على أصوات متوافقة.")
            val cache = File(context.filesDir, CACHE_FILE)
            val temp = File(context.filesDir, "$CACHE_FILE.part")
            temp.writeText(body)
            if (cache.exists() && !cache.delete()) {
                temp.delete()
                throw IOException("تعذر تحديث نسخة مكتبة الأصوات.")
            }
            if (!temp.renameTo(cache)) {
                temp.delete()
                throw IOException("تعذر حفظ مكتبة الأصوات المحدّثة.")
            }
            return entries
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCatalog(body: String): List<VoicePack> {
        val root = JSONObject(body)
        require(root.optInt("schemaVersion") == 1) { "إصدار فهرس الأصوات غير مدعوم." }
        val array = root.optJSONArray("voices") ?: return emptyList()
        require(array.length() <= MAX_VOICES) { "يحتوي فهرس الأصوات على عدد كبير من العناصر." }
        val result = ArrayList<VoicePack>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val engine = item.optString("engine")
            val repo = item.optString("repository")
            val revision = item.optString("revision")
            val sourceUrl = item.optString("sourceUrl")
            val priceType = item.optString("priceType")
            val languagesJson = item.optJSONArray("languages")
            val languages = buildSet {
                if (languagesJson != null) {
                    for (j in 0 until languagesJson.length()) {
                        languagesJson.optString(j).takeIf { it in ALLOWED_LANGUAGES }?.let(::add)
                    }
                }
            }
            // This first catalog only installs Nabra packs. New engine families need a
            // native integration and a separate review before they can be executable.
            if (!id.matches(ID_REGEX) || engine != "nabra" ||
                repo != ALLOWED_NABRA_REPOSITORY ||
                !revision.matches(SHA_REGEX) ||
                priceType !in setOf("free", "paid") ||
                languages.isEmpty() ||
                !sourceUrl.startsWith("https://huggingface.co/$repo")
            ) continue
            val downloadable = item.optBoolean("downloadable", false) && priceType == "free" &&
                item.optString("dtype") in setOf("fp16", "fp32")
            result += VoicePack(
                id = id,
                name = item.optString("name").take(70),
                description = item.optString("description").take(220),
                engine = engine,
                repository = repo,
                revision = revision,
                dtype = item.optString("dtype", "fp16"),
                priceType = priceType,
                priceLabel = item.optString("priceLabel").take(40),
                license = item.optString("license").take(120),
                sourceUrl = sourceUrl,
                sizeLabel = item.optString("sizeLabel").take(40),
                languages = languages,
                downloadable = downloadable
            )
        }
        return result.distinctBy { it.id }
    }

    companion object {
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/a730889067-sys/SAWT-AI/redesign/sawt-ai-premium/voice_catalog.json"
        private const val ASSET_FILE = "voice_catalog.json"
        private const val CACHE_FILE = "voice_catalog_cache.json"
        private const val MAX_CATALOG_CHARS = 64 * 1024
        private const val MAX_VOICES = 40
        private const val ALLOWED_NABRA_REPOSITORY = "oddadmix/Nabra-7M-Distill-ONNX"
        private val ID_REGEX = Regex("[a-z0-9][a-z0-9-]{2,60}")
        private val SHA_REGEX = Regex("[0-9a-fA-F]{40}")
        private val ALLOWED_LANGUAGES = setOf("ar", "en")
    }
}
