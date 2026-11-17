package com.sawt.ai

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.sawt.ai.audio.CloudVoiceClient
import com.sawt.ai.audio.CloudVoiceOption
import com.sawt.ai.audio.NabraBridgeEvent
import com.sawt.ai.audio.NabraLocalBridge
import com.sawt.ai.audio.SpeechPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Background = Color(0xFF070707)
private val SurfaceDark = Color(0xFF121212)
private val SurfaceRaised = Color(0xFF1A1A1A)
private val Gold = Color(0xFFD8B56D)
private val GoldSoft = Color(0xFFB99A5C)
private val TextMain = Color(0xFFF5F1E8)
private val TextMuted = Color(0xFF9B978E)
private val Border = Color(0xFF302B22)
private const val MAX_TEXT_BYTES = 5_000

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SawtStudio() }
    }
}

@Composable
private fun SawtStudio() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiBase = BuildConfig.SAWT_API_BASE_URL.trim()
    val client = remember(apiBase) { apiBase.takeIf { it.isNotBlank() }?.let(::CloudVoiceClient) }

    var input by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<CloudVoiceOption>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf<CloudVoiceOption?>(null) }
    var loading by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var lastFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("مساحة هادئة لتحويل النص إلى صوت") }
    var showHelp by remember { mutableStateOf(false) }
    var localPageReady by remember { mutableStateOf(false) }
    var localEngineReady by remember { mutableStateOf(false) }
    var localLoading by remember { mutableStateOf(false) }
    var localProgress by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val localBridge = remember(context) {
        NabraLocalBridge(context) { event ->
            when (event) {
                NabraBridgeEvent.PageReady -> localPageReady = true
                is NabraBridgeEvent.Progress -> {
                    val percent = if (event.total > 0) (event.loaded * 100 / event.total).toInt().coerceIn(0, 100) else 0
                    localProgress = if (event.total > 0) "${event.name}: $percent%" else "جارٍ تجهيز ${event.name}…"
                }
                is NabraBridgeEvent.EngineReady -> {
                    localEngineReady = true
                    localLoading = false
                    localError = null
                    status = "الصوت النسائي المحلي جاهز. يمكنك التحويل دون إنترنت بعد اكتمال التنزيل."
                }
                is NabraBridgeEvent.AudioReady -> {
                    lastFile = event.file
                    generating = false
                    SpeechPlaybackService.playFile(context, event.file.absolutePath)
                    status = "اكتمل إنشاء الصوت المحلي وبدأ التشغيل."
                }
                is NabraBridgeEvent.Error -> {
                    localLoading = false
                    generating = false
                    localError = event.message
                    status = event.message
                }
                NabraBridgeEvent.ModelsRemoved -> {
                    localEngineReady = false
                    localLoading = false
                    localProgress = ""
                    status = "تم حذف ملفات الصوت المحلية. يلزم تنزيلها مجددًا للاستخدام دون إنترنت."
                }
            }
        }
    }

    LaunchedEffect(client) {
        if (client == null) {
            status = "مكتبة أصوات الفصحى لم تُربط بالخادم بعد."
        } else {
            loading = true
            try {
                voices = withContext(Dispatchers.IO) { client.fetchVoices() }
                selectedVoice = voices.firstOrNull()
                status = if (voices.isEmpty()) "لم يعثر الخادم على أصوات عربية فصحى." else "اختر الصوت واكتب نصك."
            } catch (e: Exception) {
                status = e.message ?: "تعذر الاتصال بمكتبة الأصوات."
            } finally {
                loading = false
            }
        }
    }

    val male = voices.firstOrNull { it.gender.equals("MALE", true) }
    val female = voices.firstOrNull { it.gender.equals("FEMALE", true) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Background,
                surface = SurfaceDark,
                primary = Gold,
                onPrimary = Background,
                onBackground = TextMain,
                onSurface = TextMain
            )
        ) {
            Scaffold(containerColor = Background) { insets ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF17130D), Background, Background)))
                        .padding(insets)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                ) {
                    AndroidView(
                        factory = { viewContext ->
                            val assetLoader = WebViewAssetLoader.Builder()
                                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(viewContext))
                                .build()
                            WebView(viewContext).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                addJavascriptInterface(localBridge, "SAWTBridge")
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                                        request?.url?.let(assetLoader::shouldInterceptRequest)
                                }
                                webViewRef = this
                                loadUrl("https://appassets.androidplatform.net/assets/voice_engine/index.html")
                            }
                        },
                        modifier = Modifier.size(1.dp)
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(listOf(Gold, GoldSoft))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Background, modifier = Modifier.size(29.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text("SAWT AI", color = TextMain, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("استوديو الصوت العربي", color = TextMuted, fontSize = 11.sp)
                        }
                        IconButton(
                            onClick = { showHelp = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Default.HelpOutline,
                                contentDescription = "طريقة الاستخدام",
                                tint = Gold,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Surface(color = Color(0xFF211C13), shape = CircleShape) {
                            Text("صوت عربي", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp))
                        }
                    }

                    Spacer(Modifier.height(34.dp))
                    Text("صوتٌ يليق بكلماتك", color = TextMain, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text("حوّل النصوص إلى قراءة عربية واضحة وأنيقة", color = TextMuted, fontSize = 13.sp)

                    Spacer(Modifier.height(25.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("النص", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("${input.length} حرف · ${input.toByteArray(Charsets.UTF_8).size} / $MAX_TEXT_BYTES بايت", color = TextMuted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { nextValue ->
                                val byteCount = nextValue.toByteArray(Charsets.UTF_8).size
                                if (nextValue.length <= 5000 && byteCount <= MAX_TEXT_BYTES) {
                                    input = nextValue
                                    lastFile = null
                                } else {
                                    status = "الحد الأقصى للنص في الطلب الواحد 5000 بايت."
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 205.dp),
                            placeholder = { Text("اكتب أو الصق النص هنا…", color = TextMuted) },
                            textStyle = LocalTextStyle.current.copy(color = TextMain, fontSize = 16.sp, lineHeight = 28.sp),
                            shape = RoundedCornerShape(22.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark,
                                cursorColor = Gold
                            )
                        )
                    }

                    Spacer(Modifier.height(25.dp))
                    Text("اختر صوتك", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(11.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.fillMaxWidth()) {
                        VoiceCard(
                            title = "رجالي عميق",
                            subtitle = "قراءة هادئة ورزينة",
                            selected = selectedVoice?.id == male?.id,
                            enabled = male != null,
                            modifier = Modifier.weight(1f)
                        ) { male?.let { selectedVoice = it } }
                        VoiceCard(
                            title = "نسائي واضح",
                            subtitle = "قراءة ناعمة وواثقة",
                            selected = selectedVoice?.id == female?.id,
                            enabled = female != null,
                            modifier = Modifier.weight(1f)
                        ) { female?.let { selectedVoice = it } }
                    }

                    Spacer(Modifier.height(22.dp))
                    Text("الصوت المحلي دون إنترنت", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("صوت نسائي عربي فصيح. نزّل المحرك مرة واحدة، ثم استخدمه دون اتصال بالشبكة.", color = TextMuted, fontSize = 11.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    if (!localEngineReady) {
                        Button(
                            onClick = {
                                localLoading = true
                                localError = null
                                localProgress = "بدء تنزيل محرك الصوت…"
                                webViewRef?.evaluateJavascript("window.sawtTts?.load()", null)
                            },
                            enabled = localPageReady && !localLoading,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceRaised, contentColor = Gold)
                        ) { Text(if (localLoading) "جارٍ تجهيز الصوت المحلي…" else "تنزيل وتجهيز الصوت المحلي") }
                    } else {
                        Button(
                            onClick = {
                                if (input.isNotBlank()) {
                                    generating = true
                                    lastFile = null
                                    status = "جارٍ إنشاء الصوت محليًا…"
                                    val safeText = org.json.JSONObject.quote(input.trim())
                                    webViewRef?.evaluateJavascript("window.sawtTts.generate($safeText, 1.0)", null)
                                }
                            },
                            enabled = !generating && input.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Background)
                        ) { Text(if (generating) "جارٍ التحويل دون إنترنت…" else "تحويل النص محليًا دون إنترنت") }
                        TextButton(onClick = { webViewRef?.evaluateJavascript("window.sawtTts.removeDownloadedModels()", null) }) {
                            Text("حذف ملفات الصوت المحلية", color = TextMuted)
                        }
                    }
                    if (localLoading || localProgress.isNotBlank()) {
                        Text(localProgress, color = GoldSoft, fontSize = 11.sp)
                        if (localLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gold, trackColor = SurfaceRaised)
                    }
                    if (localError != null) Text(localError.orEmpty(), color = Color(0xFFE0A0A0), fontSize = 11.sp)

                    if (loading) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gold, trackColor = SurfaceRaised)
                    }
                    if (client == null || voices.isEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Surface(color = Color(0xFF211C13), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (client == null) "مكتبة الأصوات الفصحى قيد الربط. لم نفعّل أصوات الهاتف العادية حتى لا نعرضها على أنها فصحى."
                                else "لم يتم تحميل أصوات الفصحى من الخادم. تحقق من إعداد مكتبة الصوت.",
                                color = Color(0xFFE0C995), fontSize = 12.sp, lineHeight = 19.sp,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = {
                            val activeClient = client
                            val activeVoice = selectedVoice
                            if (activeClient == null || activeVoice == null || input.isBlank()) return@Button
                            generating = true
                            lastFile = null
                            status = "جارٍ تجهيز الصوت…"
                            val spokenText = input.trim()
                            scope.launch {
                                try {
                                    val output = withContext(Dispatchers.IO) {
                                        File(context.cacheDir, "sawt-${System.currentTimeMillis()}.mp3").also {
                                            activeClient.synthesizeToFile(spokenText, activeVoice.id, 1f, 1f, it)
                                        }
                                    }
                                    lastFile = output
                                    SpeechPlaybackService.playFile(context, output.absolutePath)
                                    status = "اكتمل الصوت وبدأ التشغيل."
                                } catch (e: Exception) {
                                    status = e.message ?: "تعذر إنشاء الصوت."
                                } finally {
                                    generating = false
                                }
                            }
                        },
                        enabled = !generating && client != null && selectedVoice != null && input.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Gold,
                            contentColor = Background,
                            disabledContainerColor = Color(0xFF3A3428),
                            disabledContentColor = TextMuted
                        )
                    ) {
                        if (generating) CircularProgressIndicator(Modifier.size(21.dp), color = Background, strokeWidth = 2.dp)
                        else Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(9.dp))
                        Text(if (generating) "جارٍ إنشاء الصوت" else "إنشاء الصوت والاستماع", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    if (lastFile != null) {
                        Spacer(Modifier.height(11.dp))
                        OutlinedButton(
                            onClick = {
                                val file = lastFile ?: return@OutlinedButton
                                scope.launch {
                                    try {
                                        val name = withContext(Dispatchers.IO) { saveAudioToDownloads(context, file) }
                                        status = if (Build.VERSION.SDK_INT >= 29) "تم حفظ $name في مجلد التنزيلات." else "تم حفظ $name في مجلد ملفات SAWT AI الخاص بالتطبيق."
                                    } catch (_: Exception) {
                                        status = "تعذر حفظ الملف. حاول مرة أخرى."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GoldSoft)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (file.extension.equals("wav", true)) "حفظ ملف WAV" else "حفظ ملف MP3")
                        }
                    }

                    Spacer(Modifier.height(17.dp))
                    Text(status, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(28.dp))
                    Text("SAWT AI  •  ARABIC VOICE STUDIO", color = Color(0xFF625A4B), fontSize = 9.sp, letterSpacing = 1.2.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(18.dp))
                }
            }
            if (showHelp) {
                AlertDialog(
                    onDismissRequest = { showHelp = false },
                    containerColor = SurfaceDark,
                    title = {
                        Text(
                            "طريقة استخدام SAWT AI",
                            color = Gold,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            "١. اكتب النص أو الصقه في المربع، حتى ٥٠٠٠ حرف.\n\n" +
                                "٢. اختر الصوت الرجالي أو النسائي عندما يظهر أنه متاح.\n\n" +
                                "٣. اضغط «إنشاء الصوت والاستماع» لبدء التحويل.\n\n" +
                                "٤. بعد اكتمال الصوت، اضغط «حفظ ملف MP3» لحفظه في التنزيلات.\n\n" +
                                "تنبيه: يجب أن تكون مكتبة الصوت مفعّلة. عند إضافة حزم محلية معتمدة ستعمل دون إنترنت بعد تنزيلها، أما التوليد السحابي فيحتاج اتصالًا بالإنترنت.",
                            color = TextMain,
                            lineHeight = 23.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showHelp = false }) {
                            Text("حسنًا", color = Gold)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun VoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Gold else Border
    Surface(
        color = if (selected) Color(0xFF211C13) else SurfaceDark,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = modifier
            .height(92.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = if (enabled) TextMain else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(
                if (enabled) subtitle else "غير متاح حاليًا",
                color = if (selected) Gold else TextMuted,
                fontSize = 10.sp
            )
        }
    }
}

private fun saveAudioToDownloads(context: Context, source: File): String {
    val extension = if (source.extension.equals("wav", true)) "wav" else "mp3"
    val mimeType = if (extension == "wav") "audio/wav" else "audio/mpeg"
    val filename = "SAWT-${System.currentTimeMillis()}.$extension"
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SAWT AI")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("تعذر إنشاء ملف التنزيل")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: error("تعذر كتابة ملف الصوت")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    } else {
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SAWT AI").apply { mkdirs() }
        source.copyTo(File(folder, filename), overwrite = true)
    }
    return filename
}
