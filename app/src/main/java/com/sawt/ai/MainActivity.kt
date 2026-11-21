package com.sawt.ai

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import com.sawt.ai.audio.SpeechPlaybackService
import com.sawt.ai.audio.SupertonicTtsEngine
import com.sawt.ai.audio.TashkeelEngine
import com.sawt.ai.audio.TashkeelTextProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

private val Background = Color(0xFF0D1924)
private val SurfaceDark = Color(0xFF142635)
private val SurfaceRaised = Color(0xFF1D3344)
private val Gold = Color(0xFFD8B56D)
private val GoldSoft = Color(0xFFB99A5C)
private val TextMain = Color(0xFFF5F1E8)
private val TextMuted = Color(0xFFA0B0BC)
private val Border = Color(0xFF304757)
private const val MAX_TEXT_CHARS = 2_000
private const val MAX_PREVIEW_CACHE_ENTRIES = 6

private data class VoiceStyle(
    val name: String,
    val speakerId: Int,
    val description: String
)

private val maleVoices = listOf(
    VoiceStyle("M1", 5, "رجالي 1"),
    VoiceStyle("M2", 6, "رجالي 2"),
    VoiceStyle("M3", 7, "رجالي 3"),
    VoiceStyle("M4", 8, "رجالي 4"),
    VoiceStyle("M5", 9, "رجالي 5")
)
private val femaleVoices = listOf(
    VoiceStyle("F1", 0, "نسائي 1"),
    VoiceStyle("F2", 1, "نسائي 2"),
    VoiceStyle("F3", 2, "نسائي 3"),
    VoiceStyle("F4", 3, "نسائي 4"),
    VoiceStyle("F5", 4, "نسائي 5")
)

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
    val engine = remember(context) { SupertonicTtsEngine(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var showStartup by remember { mutableStateOf(true) }

    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    val input = inputValue.text
    var tashkeelStatus by remember { mutableStateOf("يُضاف التشكيل تلقائيًا بعد التوقف عن الكتابة قليلًا.") }
    var lastAutoDiacritizedText by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("ar") }
    var selectedVoice by remember { mutableStateOf(maleVoices.first()) }
    var speed by remember { mutableFloatStateOf(1.2f) }
    var modelReady by remember { mutableStateOf(engine.isModelInstalled()) }
    var modelLoading by remember { mutableStateOf(false) }
    var engineWarming by remember { mutableStateOf(false) }
    var engineWarmed by remember { mutableStateOf(false) }
    var modelProgress by remember { mutableIntStateOf(0) }
    var modelProgressIndeterminate by remember { mutableStateOf(false) }
    var modelStage by remember { mutableStateOf("الأصوات غير مثبتة بعد") }
    var generating by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var generationStartedAt by remember { mutableLongStateOf(0L) }
    var generationRemainingMs by remember { mutableLongStateOf(0L) }
    var remainingEstimateBaseMs by remember { mutableLongStateOf(0L) }
    var remainingEstimateUpdatedAt by remember { mutableLongStateOf(0L) }
    var hasRemainingEstimate by remember { mutableStateOf(false) }
    var generationStage by remember { mutableStateOf("جارٍ تجهيز الصوت…") }
    var lastVoiceName by remember { mutableStateOf("") }
    var lastLanguageName by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var lastFile by remember { mutableStateOf<File?>(null) }
    // Reuse short preview WAVs during this app session instead of synthesizing
    // the same speaker/language/speed combination repeatedly.
    val previewCache = remember { java.util.LinkedHashMap<String, File>(8, 0.75f, true) }
    val controlsLocked = modelLoading || engineWarming || generating || previewing
    val metricsPrefs = remember(context) {
        context.getSharedPreferences("sawt_generation_metrics", Context.MODE_PRIVATE)
    }
    fun updateRemainingEstimate(
        completedCharacters: Int,
        totalCharacters: Int,
        startedAt: Long,
        requestLanguage: String,
        requestSpeed: Float
    ) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val observedRate = GenerationProgress.observedMsPerCharacter(elapsed, completedCharacters)
        val previousRate = metricsPrefs.getFloat(generationRateKey(requestLanguage, requestSpeed), 0f)
        val blendedRate = when {
            previousRate > 0f && observedRate > 0f -> previousRate * 0.65f + observedRate * 0.35f
            observedRate > 0f -> observedRate
            else -> previousRate
        }
        val estimate = GenerationProgress.estimateRemainingMs(blendedRate, completedCharacters, totalCharacters)
        mainHandler.post {
            remainingEstimateBaseMs = estimate
            generationRemainingMs = estimate
            remainingEstimateUpdatedAt = SystemClock.elapsedRealtime()
            hasRemainingEstimate = estimate > 0L
        }
    }

    var status by remember { mutableStateOf("اكتب نصك، اختر الصوت، ثم أنشئ التسجيل.") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val tashkeelEngine = remember(context, showStartup) {
        if (showStartup) null else TashkeelEngine(context) { message ->
            mainHandler.post { tashkeelStatus = message }
        }
    }

    LaunchedEffect(modelReady, engine) {
        if (!modelReady) {
            engineWarmed = false
            return@LaunchedEffect
        }
        engineWarmed = false
        engineWarming = true
        status = "جارٍ تهيئة محرك الصوت السريع قبل أول تحويل…"
        errorMessage = null
        try {
            engine.warmUp()
            engineWarmed = true
            status = "محرك الصوت جاهز. اكتب النص وابدأ التحويل."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Do not lock the studio permanently if startup warm-up fails.
            // The synthesis action will retry native engine initialization and
            // report a specific error if the device still cannot load the model.
            engineWarmed = true
            errorMessage = error.message ?: "تعذر تهيئة محرك الصوت مسبقًا؛ ستتم إعادة المحاولة عند إنشاء الصوت."
            status = "يمكن تجربة إنشاء الصوت الآن؛ سيعيد التطبيق محاولة تجهيز المحرك."
        } finally {
            engineWarming = false
        }
    }
    var showHelp by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    DisposableEffect(engine) {
        onDispose { engine.close() }
    }

    DisposableEffect(tashkeelEngine) {
        onDispose { tashkeelEngine?.close() }
    }

    LaunchedEffect(input, language, tashkeelEngine) {
        val processor = tashkeelEngine ?: return@LaunchedEffect
        val source = input
        if (language != "ar" || !TashkeelTextProcessor.containsArabic(source)) {
            return@LaunchedEffect
        }
        val baseText = TashkeelTextProcessor.stripDiacritics(source)
        if (baseText.isBlank() || source == lastAutoDiacritizedText) {
            return@LaunchedEffect
        }

        // Debounce inference so typing and keyboard composition stay responsive.
        delay(850L)
        if (input != source) return@LaunchedEffect
        try {
            tashkeelStatus = "جارٍ إضافة الحركات تلقائيًا…"
            val diacritized = processor.diacritize(source)
            if (input == source) {
                if (diacritized != source) {
                    val newSelection = TashkeelTextProcessor.mapSelection(
                        source,
                        diacritized,
                        inputValue.selection
                    )
                    inputValue = TextFieldValue(diacritized, newSelection)
                }
                lastAutoDiacritizedText = diacritized
                tashkeelStatus = "أُضيف التشكيل تلقائيًا. راجع الأسماء وأواخر الإعراب عند الحاجة."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            tashkeelStatus = "لم يكتمل التشكيل التلقائي: ${error.message ?: "تحقق من اتصال الإنترنت لأول تنزيل."}"
        }
    }

    LaunchedEffect(showStartup) {
        if (showStartup) {
            delay(1_050L)
            showStartup = false
        }
    }

    LaunchedEffect(generating, previewing, generationStartedAt) {
        while (generating || previewing) {
            if (hasRemainingEstimate) {
                val sinceLastEstimate = (SystemClock.elapsedRealtime() - remainingEstimateUpdatedAt).coerceAtLeast(0L)
                generationRemainingMs = (remainingEstimateBaseMs - sinceLastEstimate).coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            val staleBefore = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1_000L
            context.cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("sawt-") && it.extension.equals("wav", true) && it.lastModified() < staleBefore }
                ?.forEach { it.delete() }
        }
    }

    if (showStartup) {
        StartupSplash()
        return
    }

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
                        .background(Brush.verticalGradient(listOf(Color(0xFF173044), Background, Background)))
                        .padding(insets)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    tashkeelEngine?.let { controller ->
                        AndroidView(
                            factory = { controller.webView },
                            modifier = Modifier.size(1.dp).alpha(0f)
                        )
                    }
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
                            Text("استوديو تحويل النص إلى صوت", color = TextMuted, fontSize = 11.sp)
                        }
                        IconButton(onClick = { showHelp = true }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.HelpOutline, contentDescription = "شرح الاستخدام", tint = Gold, modifier = Modifier.size(23.dp))
                        }
                    }

                    Spacer(Modifier.height(30.dp))
                    Text("صوتٌ يليق بكلماتك", color = TextMain, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text("أصوات عربية وإنجليزية تعمل على هاتفك", color = TextMuted, fontSize = 13.sp)

                    Spacer(Modifier.height(15.dp))
                    Spacer(Modifier.height(25.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("١. اكتب النص", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("${input.length} / $MAX_TEXT_CHARS حرف", color = TextMuted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(9.dp))
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(21.dp),
                        border = BorderStroke(1.dp, Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = inputValue,
                            enabled = !controlsLocked,
                            onValueChange = { next ->
                                if (next.text.length <= MAX_TEXT_CHARS && next.text.toByteArray(Charsets.UTF_8).size <= 5000) {
                                    inputValue = next
                                    lastFile = null
                                    errorMessage = null
                                } else {
                                    status = "قسّم النص إلى فقرات أقصر، حتى 2000 حرف في المرة."
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 175.dp),
                            placeholder = { Text("اكتب هنا أو الصق النص الذي تريد تحويله إلى صوت…", color = TextMuted, lineHeight = 24.sp) },
                            textStyle = LocalTextStyle.current.copy(color = TextMain, fontSize = 16.sp, lineHeight = 28.sp),
                            shape = RoundedCornerShape(21.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark,
                                cursorColor = Gold
                            )
                        )
                    }
                    Text(
                        tashkeelStatus,
                        color = TextMuted,
                        fontSize = 10.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            val sampleText = if (language == "ar") {
                                "مرحبًا بك في تطبيق ساوت إيه آي. اكتب ما تريد، واختر الصوت الذي يعجبك، ثم استمع إلى النتيجة."
                            } else {
                                "Welcome to SAWT AI. Type your text, choose a voice, and listen to the result."
                            }
                            inputValue = TextFieldValue(sampleText, TextRange(sampleText.length))
                            lastAutoDiacritizedText = ""
                            lastFile = null
                        }, enabled = !controlsLocked) { Text("نص تجريبي", color = GoldSoft) }
                        TextButton(onClick = {
                            inputValue = TextFieldValue("")
                            lastAutoDiacritizedText = ""
                            lastFile = null
                        }, enabled = !controlsLocked) { Text("مسح النص", color = TextMuted) }
                    }

                    Spacer(Modifier.height(17.dp))
                    Text("٢. اختر لغة النص", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        LanguageButton("العربية", language == "ar", Modifier.weight(1f), enabled = !controlsLocked) {
                            language = "ar"
                            inputValue = TextFieldValue("")
                            lastAutoDiacritizedText = ""
                            lastFile = null
                        }
                        LanguageButton("English", language == "en", Modifier.weight(1f), enabled = !controlsLocked) {
                            language = "en"
                            inputValue = TextFieldValue("")
                            lastAutoDiacritizedText = ""
                            lastFile = null
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text("٣. اختر الصوت الذي تريده", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Text("نفس الأصوات العشرة متاحة للعربية والإنجليزية.", color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(11.dp))
                    Text("أصوات رجالية", color = GoldSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        maleVoices.forEach { voice ->
                            VoiceChoice(voice, selectedVoice.name == voice.name, Modifier.weight(1f), enabled = !controlsLocked) {
                                selectedVoice = voice
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("أصوات نسائية", color = GoldSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        femaleVoices.forEach { voice ->
                            VoiceChoice(voice, selectedVoice.name == voice.name, Modifier.weight(1f), enabled = !controlsLocked) {
                                selectedVoice = voice
                            }
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    OutlinedButton(
                        onClick = {
                            if (!modelReady || generating || previewing) return@OutlinedButton
                            previewing = true
                            lastFile = null
                            errorMessage = null
                            status = "جارٍ تجهيز عينة قصيرة للصوت ${selectedVoice.name}…"
                            val previewLanguage = language
                            val previewVoice = selectedVoice
                            val previewSpeed = speed
                            val sampleText = if (previewLanguage == "ar") {
                                "السلام عليكم، هذا صوت ساوت إيه آي."
                            } else {
                                "Hello, this is SAWT AI."
                            }
                            val previewStartedAt = SystemClock.elapsedRealtime()
                            generationStartedAt = previewStartedAt
                            remainingEstimateBaseMs = estimateGenerationMs(metricsPrefs, previewLanguage, previewSpeed, sampleText.length)
                            generationRemainingMs = remainingEstimateBaseMs
                            remainingEstimateUpdatedAt = previewStartedAt
                            hasRemainingEstimate = generationRemainingMs > 0L
                            generationStage = "جارٍ تجهيز عينة الصوت…"
                            val previewKey = "$previewLanguage-${previewVoice.speakerId}-${String.format(Locale.US, "%.1f", previewSpeed)}"
                            scope.launch {
                                try {
                                    val cachedSample = previewCache[previewKey]
                                        ?.takeIf { it.isFile && it.length() > 44L }
                                    val sample = cachedSample ?: engine.synthesizeToFile(
                                        text = sampleText,
                                        language = previewLanguage,
                                        speakerId = previewVoice.speakerId,
                                        speed = previewSpeed,
                                        onStage = { stage -> mainHandler.post { generationStage = stage } },
                                        onProgress = { completed, total ->
                                            updateRemainingEstimate(completed, total, previewStartedAt, previewLanguage, previewSpeed)
                                        }
                                    ).also {
                                        previewCache[previewKey] = it
                                        while (previewCache.size > MAX_PREVIEW_CACHE_ENTRIES) {
                                            val eldest = previewCache.entries.iterator()
                                            if (!eldest.hasNext()) break
                                            eldest.next()
                                            eldest.remove()
                                        }
                                    }
                                    lastFile = sample
                                    lastVoiceName = previewVoice.name
                                    lastLanguageName = if (previewLanguage == "ar") "العربية" else "English"
                                    SpeechPlaybackService.playFile(context, sample.absolutePath)
                                    status = if (cachedSample != null) {
                                        "عينة ${previewVoice.name} جاهزة من الذاكرة. بدأ التشغيل فورًا."
                                    } else {
                                        "تم تجهيز عينة ${previewVoice.name} وبدأ تشغيلها."
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: "تعذّر إكمال عينة الصوت. راجع التفاصيل ثم أعد المحاولة."
                                    status = "يمكنك إعادة تجربة العينة بعد معالجة الملاحظة الموضحة."
                                } finally {
                                    previewing = false
                                }
                            }
                        },
                        enabled = modelReady && engineWarmed && !modelLoading && !engineWarming && !generating && !previewing,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold),
                        border = BorderStroke(1.dp, GoldSoft)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (previewing) "جارٍ تجهيز العينة…" else "استمع إلى عينة الصوت المحدد")
                    }

                    Spacer(Modifier.height(19.dp))
                    Text("٤. اضبط سرعة القراءة والجودة", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("سرعة الصوت", color = TextMuted, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.1f×", speed), color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = speed,
                        onValueChange = { speed = it },
                        valueRange = 0.8f..1.2f,
                        steps = 3,
                        enabled = !controlsLocked,
                        colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = SurfaceRaised)
                    )
                    Text("وضع التحويل", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(7.dp))
                    Surface(
                        color = Color(0xFF21394A),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Gold),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "أولوية السرعة: خطوتان للتوليد، وأنوية أكثر ومقاطع أكبر حسب قدرة الهاتف.",
                            color = Gold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(23.dp))
                    Text("٥. حزمة الأصوات", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, if (modelReady) Color(0xFF75613D) else Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (modelReady) "الأصوات العشرة مثبتة" else "تنزيل الأصوات مرة واحدة", color = if (modelReady) Gold else TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(if (!modelReady) "حوالي 133 MB" else if (engine.needsOptimization()) "66 MB" else "جاهز", color = GoldSoft, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (!modelReady) "تنزيل ملفات الأصوات السبعة مباشرة يحتاج الإنترنت أول مرة. الحجم التقريبي 133 ميجابايت، ويُفضّل توفر 350 ميجابايت فارغة."
                                else if (engine.needsOptimization()) "الأصوات مثبتة بالفعل. نزّل ملف التحسين مرة واحدة لتحسين سرعة التحويل؛ إعدادك الحالي محفوظ أثناء التحديث."
                                else "العربية والإنجليزية، خمسة أصوات رجالية وخمسة نسائية. المحرك المحسّن يعمل دون إنترنت بعد التثبيت.",
                                color = TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 18.sp
                            )
                            if (modelLoading) {
                                Spacer(Modifier.height(12.dp))
                                if (modelProgressIndeterminate) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Gold,
                                        trackColor = SurfaceRaised
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        progress = modelProgress / 100f,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Gold,
                                        trackColor = SurfaceRaised
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    if (modelProgressIndeterminate) modelStage else "$modelStage · $modelProgress%",
                                    color = GoldSoft,
                                    fontSize = 10.sp
                                )
                            } else if (!modelReady || engine.needsOptimization()) {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        modelLoading = true
                                        modelProgress = 0
                                        modelProgressIndeterminate = false
                                        errorMessage = null
                                        status = if (modelReady) "جارٍ تنزيل تحسين سرعة الصوت…" else "جارٍ تنزيل وتثبيت حزمة الأصوات…"
                                        scope.launch {
                                            try {
                                                engine.downloadAndInstall(
                                                    onProgress = { loaded, total ->
                                                        val percent = if (total > 0L) ((loaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                                                        mainHandler.post { modelProgress = percent }
                                                    },
                                                    onStage = { stage ->
                                                        mainHandler.post {
                                                            modelStage = stage
                                                            val step = Regex("""\((\d+)/(\d+)\)""").find(stage)
                                                            if (step != null) {
                                                                val done = step.groupValues[1].toIntOrNull() ?: 0
                                                                val total = step.groupValues[2].toIntOrNull() ?: 1
                                                                modelProgress = (done * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)
                                                                modelProgressIndeterminate = false
                                                            } else if (stage.contains("فك ضغط") || stage.contains("تجهيزها للاستخدام")) {
                                                                modelProgress = 0
                                                                modelProgressIndeterminate = true
                                                            } else if (stage.contains("تنزيل") || stage.contains("فحص سلامة")) {
                                                                modelProgress = 0
                                                                modelProgressIndeterminate = false
                                                            } else if (stage.contains("اكتمل")) {
                                                                modelProgress = 100
                                                                modelProgressIndeterminate = false
                                                            }
                                                        }
                                                    }
                                                )
                                                modelReady = engine.isModelInstalled()
                                                status = "اكتمل تثبيت الأصوات. يمكنك الآن التحويل دون إنترنت."
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                errorMessage = error.message ?: "تعذّر إكمال تجهيز الأصوات. راجع التفاصيل ثم أعد المحاولة."
                                                status = "يمكن متابعة تجهيز الأصوات بعد معالجة الملاحظة الموضحة."
                                            } finally {
                                                modelLoading = false
                                            }
                                        }
                                    },
                                    enabled = !modelLoading && !generating && !previewing,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Background)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(Modifier.width(7.dp))
                                    Text(if (modelReady) "تحسين سرعة التحويل (66 MB)" else "تنزيل وتجهيز الأصوات كلها")
                                }
                            } else {
                                Spacer(Modifier.height(5.dp))
                                TextButton(
                                    onClick = { showDeleteConfirmation = true },
                                    enabled = !controlsLocked && !saving
                                ) {
                                    Text("حذف حزمة الأصوات من الهاتف", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    if (errorMessage != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(errorMessage.orEmpty(), color = Color(0xFFE0BD78), fontSize = 12.sp, lineHeight = 19.sp)
                    }

                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (!modelReady || input.isBlank()) return@Button
                            val requestText = input.trim()
                            val requestLanguage = language
                            val requestVoice = selectedVoice
                            val requestSpeed = speed
                            generating = true
                            saving = false
                            lastFile = null
                            errorMessage = null
                            status = "جارٍ إنشاء الصوت على الهاتف…"
                            generationStartedAt = SystemClock.elapsedRealtime()
                            remainingEstimateBaseMs = estimateGenerationMs(metricsPrefs, requestLanguage, requestSpeed, requestText.length)
                            generationRemainingMs = remainingEstimateBaseMs
                            remainingEstimateUpdatedAt = generationStartedAt
                            hasRemainingEstimate = generationRemainingMs > 0L
                            generationStage = "جارٍ تجهيز محرك الصوت…"
                            val startedAt = generationStartedAt
                            scope.launch {
                                try {
                                    val result = engine.synthesizeToFile(
                                        text = requestText,
                                        language = requestLanguage,
                                        speakerId = requestVoice.speakerId,
                                        speed = requestSpeed,
                                        onStage = { stage -> mainHandler.post { generationStage = stage } },
                                        onProgress = { completed, total ->
                                            updateRemainingEstimate(completed, total, startedAt, requestLanguage, requestSpeed)
                                        }
                                    )
                                    val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                                    val sampleRate = elapsedMs.toFloat() / requestText.length.coerceAtLeast(1)
                                    val prefKey = generationRateKey(requestLanguage, requestSpeed)
                                    val oldRate = metricsPrefs.getFloat(prefKey, 0f)
                                    val smoothRate = if (oldRate > 0f) oldRate * 0.7f + sampleRate * 0.3f else sampleRate
                                    metricsPrefs.edit { putFloat(prefKey, smoothRate) }
                                    lastFile = result
                                    lastVoiceName = requestVoice.name
                                    lastLanguageName = if (requestLanguage == "ar") "العربية" else "English"
                                    SpeechPlaybackService.playFile(context, result.absolutePath)
                                    status = "اكتمل إنشاء الصوت وبدأ تشغيله."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: "تعذر إنشاء الصوت."
                                    status = "تحتاج العملية إلى مراجعة بسيطة؛ يمكنك المحاولة بعد معالجة السبب الموضح."
                                } finally {
                                    generating = false
                                }
                            }
                        },
                        enabled = modelReady && engineWarmed && !modelLoading && !engineWarming && !generating && !previewing && input.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Gold,
                            contentColor = Background,
                            disabledContainerColor = Color(0xFF304657),
                            disabledContentColor = TextMuted
                        )
                    ) {
                        if (generating) {
                            CircularProgressIndicator(Modifier.size(21.dp), color = Background, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                        Spacer(Modifier.width(9.dp))
                        Text(
                            when {
                                generating -> "إنشاء الصوت…"
                                engineWarming -> "تهيئة المحرك السريع…"
                                !engineWarmed && modelReady -> "المحرك قيد التجهيز…"
                                else -> "إنشاء الصوت والاستماع"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    if (generating || previewing) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = SurfaceDark,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (previewing) "تجهيز عينة الصوت" else "إنشاء التسجيل",
                                        color = Gold,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        if (generationRemainingMs > 0L) "المتبقي ${formatDuration(generationRemainingMs)}" else "تقدير الوقت",
                                        color = GoldSoft,
                                        fontSize = 10.sp
                                    )
                                }
                                Spacer(Modifier.height(9.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Gold,
                                    trackColor = SurfaceRaised
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    generationStage,
                                    color = TextMain,
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    when {
                                        generationRemainingMs > 0L ->
                                            "الوقت المتبقي التقريبي: ${formatDuration(generationRemainingMs)}"
                                        generationStage.contains("حفظ") || generationStage.contains("اكتمل إنشاء الصوت") ->
                                            "يجري إنهاء ملف الصوت وحفظه."
                                        hasRemainingEstimate ->
                                            "قد يحتاج الجزء الحالي وقتًا إضافيًا؛ سيتحدّث التقدير بعد اكتماله."
                                        else ->
                                            "جارٍ حساب الوقت المتبقي من أول جزء صوتي."
                                    },
                                    color = GoldSoft,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    if (lastFile != null) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = Color(0xFF142635),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, GoldSoft),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        lastFile?.let { SpeechPlaybackService.playFile(context, it.absolutePath) }
                                    },
                                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Gold)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "استمع إلى الصوت", tint = Background, modifier = Modifier.size(28.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("الصوت جاهز للاستماع", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("$lastVoiceName · $lastLanguageName", color = GoldSoft, fontSize = 11.sp)
                                    Spacer(Modifier.height(7.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth().height(18.dp),
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val bars = listOf(5, 11, 15, 8, 17, 10, 6, 14, 18, 9, 13, 6, 16, 10, 5, 14, 8, 17, 11, 6)
                                        bars.forEach { bar ->
                                            Box(
                                                Modifier.weight(1f).height(bar.dp).clip(RoundedCornerShape(3.dp)).background(GoldSoft.copy(alpha = 0.78f))
                                            )
                                        }
                                    }
                                    Text("يمكنك الاستماع الآن أو حفظ الملف", color = TextMuted, fontSize = 9.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        val file = lastFile ?: return@IconButton
                                        if (saving) return@IconButton
                                        saving = true
                                        scope.launch {
                                            try {
                                                val name = withContext(Dispatchers.IO) { saveAudioToDownloads(context, file) }
                                                status = "تم حفظ $name داخل التنزيلات / SAWT AI."
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                errorMessage = error.message ?: "تعذر حفظ ملف الصوت."
                                            } finally {
                                                saving = false
                                            }
                                        }
                                    },
                                    enabled = !saving,
                                    modifier = Modifier.size(46.dp).clip(CircleShape).background(SurfaceRaised)
                                ) {
                                    if (saving) {
                                        CircularProgressIndicator(Modifier.size(20.dp), color = Gold, strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = "تنزيل الصوت", tint = Gold, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(15.dp))
                    Text(status, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Text("SAWT AI  •  OFFLINE VOICE STUDIO", color = Color(0xFF708795), fontSize = 9.sp, letterSpacing = 1.2.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(18.dp))
                }
            }

            if (showHelp) {
                AlertDialog(
                    onDismissRequest = { showHelp = false },
                    containerColor = SurfaceDark,
                    title = {
                        Text("شرح SAWT AI بالتفصيل", color = Gold, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 470.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            HelpSection("١. تنزيل الأصوات أول مرة", "اضغط «تنزيل وتجهيز الأصوات كلها» والهاتف متصل بالإنترنت. سيُنزل التطبيق ملفات الصوت السبعة مباشرة، ويفحص بصمة كل ملف قبل اعتماده؛ لم يعد يحتاج إلى فك ضغط أرشيف كبير على الهاتف. الحجم التقريبي 133 ميجابايت، ويُفضّل توفر 350 ميجابايت فارغة.")
                            HelpSection("٢. كتابة النص", "المربع الكبير هو مكان الكتابة. اضغط داخله واكتب من لوحة المفاتيح، أو انسخ نصًا من أي مكان ثم اضغط مطولًا داخل المربع واختر «لصق». يمكنك استخدام علامات الترقيم والفواصل وتقسيم النص إلى فقرات. الحد الأقصى 2000 حرف في العملية الواحدة. للأسماء غير الشائعة، أضف التشكيل يدويًا عند الحاجة لتوضيح النطق.")
                            HelpSection("٣. اختيار اللغة", "اختر «العربية» عندما يكون النص عربيًا، واختر English عندما يكون النص إنجليزيًا. الأفضل أن يكون النص بلغة واحدة في كل عملية حتى تكون القراءة أوضح.")
                            HelpSection("٤. اختيار الصوت", "ستجد خمسة أصوات رجالية M1 إلى M5، وخمسة أصوات نسائية F1 إلى F5. اضغط على بطاقة الصوت الذي تريد سماعه. يمكنك تغيير الصوت في أي وقت، وجميعها متاحة للغتين العربية والإنجليزية.")
                            HelpSection("٥. السرعة والجودة", "يبدأ التطبيق بسرعة 1.2×، ويستخدم خطوتين للتوليد لتقليل زمن المعالجة. تقليل الخطوات يعطي أولوية للسرعة وقد يؤثر قليلًا في نعومة الصوت مقارنة بالإعدادات الأعلى. يمكنك خفض سرعة القراءة من شريط التحكم.")
                            HelpSection("٦. إنشاء الصوت والاستماع", "بعد كتابة النص واختيار اللغة والصوت، اضغط «إنشاء الصوت والاستماع». سيُنشئ التطبيق ملف WAV على الهاتف ويبدأ تشغيله تلقائيًا. اضغط زر التشغيل الدائري داخل بطاقة الصوت لسماعه مرة أخرى، أو اضغط سهم التنزيل الموجود بجانبه لحفظ الملف.")
                            HelpSection("٧. حفظ الصوت وإيجاده", "بعد الاستماع إلى النتيجة، اضغط سهم التنزيل الموجود بجانب بطاقة الصوت. سيُحفظ الملف بصيغة WAV في تطبيق الملفات داخل: التنزيلات (Download) ثم مجلد SAWT AI. يمكنك فتحه أو مشاركته من هناك.")
                            HelpSection("٨. العمل دون إنترنت والخصوصية", "الإنترنت مطلوب لتنزيل حزمة النماذج أول مرة فقط. بعد اكتمال التثبيت يعمل إنشاء الصوت محليًا على الهاتف دون اتصال، ولا يحتاج إلى خادم مدفوع. لا يرسل التطبيق النص إلى خدمة سحابية أثناء التحويل المحلي.")
                            HelpSection("٩. استكمال التجهيز", "لأفضل نتيجة، استخدم اتصالًا مستقرًا واترك مساحة فارغة كافية. إذا انقطع الاتصال، أعد تشغيل التنزيل؛ وسيعيد التطبيق التحقق من ملفات الأصوات قبل استخدامها.")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showHelp = false }) { Text("فهمت", color = Gold) }
                    }
                )
            }

            if (showDeleteConfirmation) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmation = false },
                    containerColor = SurfaceDark,
                    title = { Text("حذف حزمة الأصوات؟", color = Gold) },
                    text = { Text("سيتم حذف ملفات النماذج من الهاتف. ستحتاج إلى الإنترنت لتنزيلها مرة أخرى قبل استخدام التحويل المحلي.", color = TextMain) },
                    confirmButton = {
                        TextButton(onClick = {
                            if (controlsLocked || saving) {
                                showDeleteConfirmation = false
                                return@TextButton
                            }
                            try {
                                engine.deleteDownloadedModels()
                                modelReady = false
                                lastFile = null
                                showDeleteConfirmation = false
                                status = "تم حذف حزمة الأصوات من الهاتف."
                            } catch (error: Exception) {
                                errorMessage = error.message ?: "تعذر حذف حزمة الأصوات."
                            }
                        }, enabled = !controlsLocked && !saving) { Text("حذف", color = Color(0xFFE4A3A3)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmation = false }) { Text("إلغاء", color = Gold) }
                    }
                )
            }
        }
    }
}


@Composable
private fun StartupSplash() {
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF1B3549), Color(0xFF0D1924)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(92.dp).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Gold, GoldSoft))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF0D1924), modifier = Modifier.size(54.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("SAWT AI", color = TextMain, fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text("صوتٌ يليق بكلماتك", color = Color(0xFFC2D0DA), fontSize = 14.sp)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(modifier = Modifier.width(142.dp), color = Gold, trackColor = Color(0xFF294052))
            Spacer(Modifier.height(11.dp))
            Text("جارٍ فتح الاستوديو", color = Color(0xFF9AAEBB), fontSize = 11.sp)
        }
    }
}

@Composable
private fun LanguageButton(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xFF21394A) else SurfaceDark,
            contentColor = if (selected) Gold else TextMuted
        ),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Gold else Border),
        shape = RoundedCornerShape(14.dp)
    ) { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun VoiceChoice(voice: VoiceStyle, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        color = if (selected) Color(0xFF21394A) else SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Gold else Border),
        modifier = modifier.height(62.dp).clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(voice.name, color = if (selected) Gold else TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(voice.description, color = TextMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun QualityButton(label: String, steps: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xFF21394A) else SurfaceDark,
            contentColor = if (selected) Gold else TextMuted
        ),
        border = BorderStroke(1.dp, if (selected) Gold else Border),
        shape = RoundedCornerShape(12.dp)
    ) { Text(label, fontSize = 11.sp) }
}

@Composable
private fun HelpSection(title: String, body: String) {
    Text(title, color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Spacer(Modifier.height(3.dp))
    Text(body, color = TextMain, fontSize = 12.sp, lineHeight = 20.sp)
    Spacer(Modifier.height(13.dp))
}

private fun saveAudioToDownloads(context: Context, source: File): String {
    if (!source.isFile || source.length() < 44L) {
        throw IOException("ملف الصوت غير موجود أو غير مكتمل.")
    }
    val filename = "SAWT-${System.currentTimeMillis()}.wav"
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SAWT AI")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("تعذر إنشاء ملف التنزيل.")
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("تعذر فتح ملف التنزيل للكتابة.")
            output.use { stream ->
                source.inputStream().use { input -> input.copyTo(stream) }
            }
            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(uri, ready, null, null) <= 0) {
                throw IOException("تعذر إكمال حفظ ملف الصوت.")
            }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    } else {
        val baseFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("مساحة التنزيلات الخارجية غير متاحة.")
        val folder = File(baseFolder, "SAWT AI")
        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("تعذر إنشاء مجلد SAWT AI.")
        }
        val destination = File(folder, filename)
        try {
            source.copyTo(destination, overwrite = true)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
    return filename
}


private fun formatDuration(milliseconds: Long): String = GenerationProgress.formatDuration(milliseconds)

private fun generationRateKey(language: String, speed: Float): String =
    GenerationProgress.rateKey(language, speed)

private fun estimateGenerationMs(
    contextPrefs: android.content.SharedPreferences,
    language: String,
    speed: Float,
    characterCount: Int
): Long = GenerationProgress.estimateMs(
    contextPrefs.getFloat(generationRateKey(language, speed), 0f),
    characterCount
)
