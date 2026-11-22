package com.sawt.ai.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sawt.ai.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SawtAuthSession(
    val idToken: String,
    val refreshToken: String,
    val localId: String,
    val email: String,
    val expiresAtMs: Long
)

class SawtFirebaseAuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sawt_secure_auth", Context.MODE_PRIVATE)
    val isConfigured: Boolean
        get() = BuildConfig.SAWT_FIREBASE_WEB_API_KEY.isNotBlank() &&
            BuildConfig.SAWT_FIREBASE_PROJECT_ID.isNotBlank()

    fun signIn(email: String, password: String): SawtAuthSession =
        authenticate("accounts:signInWithPassword", email, password)

    fun createAccount(email: String, password: String): SawtAuthSession =
        authenticate("accounts:signUp", email, password)

    private fun authenticate(endpoint: String, email: String, password: String): SawtAuthSession {
        require(isConfigured) { "لم يتم إعداد تسجيل الدخول للتطبيق بعد." }
        require(email.contains("@") && email.length <= 254) { "أدخل بريدًا إلكترونيًا صحيحًا." }
        require(password.length >= 6 && password.length <= 128) { "كلمة المرور يجب أن تكون 6 أحرف على الأقل." }
        val url = "https://identitytoolkit.googleapis.com/v1/" + endpoint +
            "?key=" + URLEncoder.encode(BuildConfig.SAWT_FIREBASE_WEB_API_KEY, "UTF-8")
        val json = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("returnSecureToken", true)
        val response = postJson(url, json.toString())
        val session = SawtAuthSession(
            idToken = response.getString("idToken"),
            refreshToken = response.getString("refreshToken"),
            localId = response.getString("localId"),
            email = response.optString("email", email.trim()),
            expiresAtMs = System.currentTimeMillis() + response.optString("expiresIn", "3600").toLong() * 1000L
        )
        save(session)
        return session
    }

    fun currentSession(): SawtAuthSession? {
        val saved = loadEncrypted() ?: return null
        if (saved.expiresAtMs > System.currentTimeMillis() + 90_000L) return saved
        return try {
            refresh(saved)
        } catch (_: Exception) {
            if (saved.expiresAtMs > System.currentTimeMillis()) saved else {
                signOut()
                null
            }
        }
    }

    @Synchronized
    private fun refresh(old: SawtAuthSession): SawtAuthSession {
        val key = URLEncoder.encode(BuildConfig.SAWT_FIREBASE_WEB_API_KEY, "UTF-8")
        val form = "grant_type=refresh_token&refresh_token=" +
            URLEncoder.encode(old.refreshToken, "UTF-8")
        val connection = (URL("https://securetoken.googleapis.com/v1/token?key=" + key).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        val response = try {
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (code !in 200..299) throw IOException(firebaseError(json))
            json
        } finally {
            connection.disconnect()
        }
        val session = SawtAuthSession(
            idToken = response.getString("id_token"),
            refreshToken = response.getString("refresh_token"),
            localId = response.optString("user_id", old.localId),
            email = old.email,
            expiresAtMs = System.currentTimeMillis() + response.optString("expires_in", "3600").toLong() * 1000L
        )
        save(session)
        return session
    }

    fun signOut() {
        prefs.edit().remove("encrypted_session").apply()
    }

    private fun postJson(url: String, body: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (code !in 200..299) throw IOException(firebaseError(json))
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun firebaseError(json: JSONObject): String {
        val code = json.optJSONObject("error")?.optString("message").orEmpty()
        return when {
            code.contains("INVALID_LOGIN_CREDENTIALS") || code.contains("EMAIL_NOT_FOUND") || code.contains("INVALID_PASSWORD") ->
                "البريد الإلكتروني أو كلمة المرور غير صحيحة."
            code.contains("EMAIL_EXISTS") -> "هذا البريد مسجل بالفعل. سجّل الدخول بدل إنشاء حساب جديد."
            code.contains("WEAK_PASSWORD") -> "كلمة المرور ضعيفة؛ استخدم 6 أحرف على الأقل."
            code.contains("OPERATION_NOT_ALLOWED") -> "لم يتم تفعيل تسجيل الدخول بالبريد في إعدادات التطبيق."
            code.contains("TOO_MANY_ATTEMPTS") -> "محاولات كثيرة. انتظر قليلًا ثم أعد المحاولة."
            code.contains("INVALID_EMAIL") -> "صيغة البريد الإلكتروني غير صحيحة."
            else -> "تعذر تسجيل الدخول. تحقق من الاتصال وإعدادات الحساب."
        }
    }

    private fun save(session: SawtAuthSession) {
        val json = JSONObject()
            .put("idToken", session.idToken)
            .put("refreshToken", session.refreshToken)
            .put("localId", session.localId)
            .put("email", session.email)
            .put("expiresAtMs", session.expiresAtMs)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        prefs.edit().putString("encrypted_session", Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    private fun loadEncrypted(): SawtAuthSession? {
        val encoded = prefs.getString("encrypted_session", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 28)
            val iv = bytes.copyOfRange(0, 12)
            val encrypted = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            SawtAuthSession(
                idToken = json.getString("idToken"),
                refreshToken = json.getString("refreshToken"),
                localId = json.getString("localId"),
                email = json.optString("email"),
                expiresAtMs = json.getLong("expiresAtMs")
            )
        } catch (_: Exception) {
            signOut()
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "sawt.ai.auth.session.v2"
    }
}
