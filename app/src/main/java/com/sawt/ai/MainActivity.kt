package com.sawt.ai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.sawt.ai.audio.AudioHistoryEntry
import com.sawt.ai.audio.AudioHistoryStore
import com.sawt.ai.audio.NabraTtsEngine
import com.sawt.ai.audio.PerformanceBenchmark
import com.sawt.ai.audio.PerformanceBenchmarkReport
import com.sawt.ai.audio.SpeechPlaybackService
import com.sawt.ai.audio.SupertonicStorageSnapshot
import com.sawt.ai.audio.SupertonicTtsEngine
import com.sawt.ai.audio.TashkeelEngine
import com.sawt.ai.audio.TashkeelTextProcessor
import com.sawt.ai.audio.VoiceCatalogRepository
import com.sawt.ai.audio.VoicePack
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
    val description: String,
    val engineId: String = "supertonic",
    val catalogId: String = ""
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
    val historyStore = remember(context) {
        AudioHistoryStore(File(context.filesDir, "audio_history"), maxEntries = 100)
    }
    val voiceCatalogRepository = remember(context) { VoiceCatalogRepository(context) }
    val voicePackPrefs = remember(context) {
        context.getSharedPreferences("sawt_voice_packs", Context.MODE_PRIVATE)
    }
    var historyEntries by remember { mutableStateOf(emptyList<AudioHistoryEntry>()) }
    var voicePacks by remember(voiceCatalogRepository) {
        mutableStateOf(voiceCatalogRepository.loadCachedOrBundled())
    }
    var installedPackIds by remember(voicePackPrefs) {
        mutableStateOf(voicePackPrefs.all.filter { (key, value) ->
            key.startsWith("installed_") && value == true
        }.keys.map { it.removePrefix("installed_") }.toSet())
    }
    var nabraEngine by remember { mutableStateOf<NabraTtsEngine?>(null) }
    var requestedNumSteps by remember { mutableIntStateOf(6) }
    var voicePackLoadingId by remember { mutableStateOf<String?>(null) }
    var voiceCatalogRefreshing by remember { mutableStateOf(false) }
    var voiceLibraryStatus by remember { mutableStateOf("مكتبة أصوات قابلة للتحديث من GitHub.") }
    var nabraStatus by remember { mutableStateOf("تنزيل حزمة الفصحى مرة واحدة لتعمل محليًا بعد ذلك.") }
    var showStartup by remember { mutableStateOf(true) }

    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    val input = inputValue.text
    var tashkeelStatus by remember { mutableStateOf("يُضاف التشكيل تلقائيًا بعد التوقف عن الكتابة قليلًا.") }
    var tashkeelWorking by remember { mutableStateOf(false) }
    var lastAutoDiacritizedText by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("ar") }
    var selectedVoice by remember { mutableStateOf(maleVoices.first()) }
    var speed by remember { mutableFloatStateOf(1.0f) }
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
    val controlsLocked = modelLoading || engineWarming || generating || previewing || performanceRunning || voicePackLoadingId != null || voiceCatalogRefreshing
    val selectedVoiceReady = if (selectedVoice.engineId == "nabra") selectedVoice.catalogId in installedPackIds else modelReady
    val selectedVoiceReadyForGeneration = selectedVoiceReady &&
        (selectedVoice.engineId == "nabra" || engineWarmed)
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

    var status by remember { mutableStateOf("اكتب النص، اختر الصوت، ثم أنشئ التسجيل.") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingHistoryDelete by remember { mutableStateOf<AudioHistoryEntry?>(null) }
    var showAllHistory by remember { mutableStateOf(false) }
    fun ensureNabraEngine(): NabraTtsEngine {
        nabraEngine?.let { return it }
        return NabraTtsEngine(context) { message ->
            mainHandler.post {
                nabraStatus = message
                voiceLibraryStatus = message
            }
        }.also { nabraEngine = it }
    }

    suspend fun refreshVoiceStorage() {
        val snapshot = withContext(Dispatchers.IO) { engine.storageSnapshot() }
        val root = File(context.filesDir, "sawt-voice-packs")
        val packBytes = withContext(Dispatchers.IO) { directoryBytes(root) }
        val partialBytes = withContext(Dispatchers.IO) {
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".part") }
                .sumOf { it.length() }
        }
        val historyBytes = withContext(Dispatchers.IO) {
            directoryBytes(File(context.filesDir, "audio_history"))
        }
        voiceStorage = snapshot
        extraVoiceStorageBytes = packBytes
        extraVoicePendingBytes = partialBytes
        recordingsStorageBytes = historyBytes
    }

    fun startPerformanceBenchmark() {
        if (performanceRunning || !modelReady || modelLoading || generating || previewing) return
        performanceRunning = true
        performanceError = null
        performanceReport = null
        performanceStage = "تهيئة الاختبار…"
        scope.launch {
            try {
                performanceReport = PerformanceBenchmark(context, engine).run { stage ->
                    mainHandler.post { performanceStage = stage }
                }
                performanceStage = "اكتمل الاختبار."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                performanceError = error.message ?: "تعذر إكمال اختبار الأداء."
                performanceStage = "توقف الاختبار."
            } finally {
                performanceRunning = false
                refreshVoiceStorage()
            }
        }
    }
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
            val preloaded = engine.warmUp()
            engineWarmed = true
            status = if (preloaded) {
                "محرك الصوت جاهز. اكتب النص وابدأ التحويل."
            } else {
                "سيُجهّز محرك الصوت عند أول تحويل لتخفيف استهلاك الذاكرة."
            }
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
    var showVoiceManager by remember { mutableStateOf(false) }
    var showPerformanceDialog by remember { mutableStateOf(false) }
    var voiceStorage by remember { mutableStateOf(engine.storageSnapshot()) }
    var extraVoiceStorageBytes by remember { mutableLongStateOf(0L) }
    var extraVoicePendingBytes by remember { mutableLongStateOf(0L) }
    var recordingsStorageBytes by remember { mutableLongStateOf(0L) }
    var managerStatus by remember { mutableStateOf("جاهز لإدارة حزم الأصوات.") }
    var pendingVoicePackDelete by remember { mutableStateOf<VoicePack?>(null) }
    var performanceRunning by remember { mutableStateOf(false) }
    var performanceStage by remember { mutableStateOf("لم يبدأ الاختبار بعد.") }
    var performanceError by remember { mutableStateOf<String?>(null) }
    var performanceReport by remember { mutableStateOf<PerformanceBenchmarkReport?>(null) }

    DisposableEffect(engine) {
        onDispose { engine.close() }
    }

    DisposableEffect(tashkeelEngine) {
        onDispose { tashkeelEngine?.close() }
    }
    DisposableEffect(nabraEngine) {
        onDispose { nabraEngine?.close() }
    }
    LaunchedEffect(historyStore, voiceCatalogRepository) {
        historyEntries = withContext(Dispatchers.IO) { historyStore.list() }
        val refreshed = withContext(Dispatchers.IO) {
            runCatching { voiceCatalogRepository.refresh() }.getOrNull()
        }
        if (refreshed != null) {
            voicePacks = refreshed
            voiceLibraryStatus = "تم تحديث مكتبة الأصوات."
        } else {
            voicePacks = voiceCatalogRepository.loadCachedOrBundled()
        }
    }

    LaunchedEffect(input, language, tashkeelEngine) {
        val processor = tashkeelEngine ?: return@LaunchedEffect
        val source = input
        if (language != "ar" || !TashkeelTextProcessor.containsArabic(source)) {
            tashkeelWorking = false
            return@LaunchedEffect
        }
        val baseText = TashkeelTextProcessor.stripDiacritics(source)
        if (baseText.isBlank() || source == lastAutoDiacritizedText) {
            tashkeelWorking = false
            return@LaunchedEffect
        }

        // Lock only audio actions while the 850 ms debounce/inference runs.
        // Keep the editor enabled so the user can continue typing naturally.
        tashkeelWorking = true
        try {
            delay(850L)
            if (input != source) return@LaunchedEffect
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
        } finally {
            tashkeelWorking = false
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
                    nabraEngine?.let { controller ->
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
                            Text("SAWT", color = TextMain, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("قراءة عربية وإنجليزية", color = TextMuted, fontSize = 11.sp)
                        }
                        IconButton(onClick = { showHelp = true }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.HelpOutline, contentDescription = "شرح الاستخدام", tint = Gold, modifier = Modifier.size(23.dp))
                        }
                    }

                    Spacer(Modifier.height(30.dp))
                    Text("حوّل النص إلى تسجيل", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text("تحكّم في النبرة والجودة، واحتفظ بتسجيلاتك", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showVoiceManager = true
                                scope.launch { refreshVoiceStorage() }
                            },
                            enabled = !controlsLocked,
                            modifier = Modifier.weight(1f).height(43.dp),
                            contentPadding = PaddingValues(horizontal = 5.dp),
                            border = BorderStroke(1.dp, GoldSoft),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold)
                        ) {
                            Text("مدير الأصوات", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                performanceReport = null
                                performanceError = null
                                showPerformanceDialog = true
                            },
                            enabled = modelReady && !controlsLocked,
                            modifier = Modifier.weight(1f).height(43.dp),
                            contentPadding = PaddingValues(horizontal = 5.dp),
                            border = BorderStroke(1.dp, Border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                        ) {
                            Text("اختبار الأداء", fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(22.dp))
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
                        LanguageButton("English", language == "en", Modifier.weight(1f), enabled = !controlsLocked && selectedVoice.engineId == "supertonic") {
                            if (selectedVoice.engineId == "nabra") selectedVoice = maleVoices.first()
                            language = "en"
                            inputValue = TextFieldValue("")
                            lastAutoDiacritizedText = ""
                            lastFile = null
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text("٣. النبرات الأساسية", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Text("عشرة أنماط مدمجة: خمسة رجالية وخمسة نسائية.", color = TextMuted, fontSize = 11.sp)
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
                    Spacer(Modifier.height(17.dp))
                    Text("٤. مكتبة الأصوات", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("حزم إضافية من مصادر موثوقة", color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                if (voiceCatalogRefreshing) return@TextButton
                                voiceCatalogRefreshing = true
                                scope.launch {
                                    try {
                                        voicePacks = withContext(Dispatchers.IO) { voiceCatalogRepository.refresh() }
                                        voiceLibraryStatus = "تم تحديث فهرس GitHub."
                                    } catch (error: Exception) {
                                        voiceLibraryStatus = error.message ?: "تعذر تحديث المكتبة. تبقى الحزم المحفوظة متاحة."
                                    } finally {
                                        voiceCatalogRefreshing = false
                                    }
                                }
                            },
                            enabled = !controlsLocked
                        ) {
                            Text(if (voiceCatalogRefreshing) "يُحدّث…" else "تحديث", color = GoldSoft, fontSize = 11.sp)
                        }
                    }
                    voicePacks.forEach { pack ->
                        val isInstalled = pack.id in installedPackIds
                        val isSelected = selectedVoice.engineId == "nabra" && selectedVoice.catalogId == pack.id
                        Surface(
                            color = if (isSelected) Color(0xFF20384A) else SurfaceDark,
                            shape = RoundedCornerShape(17.dp),
                            border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) Gold else Border),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(Modifier.padding(13.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(pack.name, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text(pack.priceLabel, color = Gold, fontSize = 10.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(pack.description, color = TextMuted, fontSize = 10.sp, lineHeight = 17.sp)
                                Spacer(Modifier.height(5.dp))
                                Text("${pack.sizeLabel} · ${pack.license}", color = GoldSoft, fontSize = 9.sp, lineHeight = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (isInstalled) "موجودة على الجهاز" else if (pack.priceType == "free") "حزمة مجانية" else "ترخيص مدفوع",
                                        color = if (isInstalled) Gold else TextMuted,
                                        fontSize = 9.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (pack.downloadable) {
                                        OutlinedButton(
                                            onClick = {
                                                if (voicePackLoadingId != null) return@OutlinedButton
                                                voicePackLoadingId = pack.id
                                                voiceLibraryStatus = "جارٍ تنزيل وتجهيز ${pack.name}…"
                                                scope.launch {
                                                    try {
                                                        ensureNabraEngine().prepare(pack) { name, loaded, total ->
                                                            val progressText = if (total > 0L) {
                                                                "$name · ${(loaded * 100L / total).toInt().coerceIn(0, 100)}%"
                                                            } else {
                                                                "$name · ${loaded / (1024 * 1024)} MB"
                                                            }
                                                            mainHandler.post { voiceLibraryStatus = progressText }
                                                        }
                                                        voicePackPrefs.edit { putBoolean("installed_${pack.id}", true) }
                                                        installedPackIds = installedPackIds + pack.id
                                                        selectedVoice = VoiceStyle(pack.name, -1, "فصحى · تجريبي", "nabra", pack.id)
                                                        language = "ar"
                                                        status = "تم تجهيز ${pack.name}. استمع إلى عينة قبل استخدامه للنصوص المهمة."
                                                        voiceLibraryStatus = "اكتمل تجهيز ${pack.name}."
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (error: Exception) {
                                                        voiceLibraryStatus = error.message ?: "تعذر تنزيل أو تجهيز حزمة الصوت."
                                                        errorMessage = voiceLibraryStatus
                                                    } finally {
                                                        voicePackLoadingId = null
                                                    }
                                                }
                                            },
                                            enabled = voicePackLoadingId == null && !generating && !previewing && !modelLoading,
                                            modifier = Modifier.height(38.dp),
                                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)
                                        ) {
                                            Text(
                                                when {
                                                    voicePackLoadingId == pack.id -> "جارٍ التجهيز…"
                                                    isSelected -> "محدد"
                                                    isInstalled -> "اختيار الصوت"
                                                    else -> "تنزيل وتجربة"
                                                },
                                                color = Gold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pack.sourceUrl)))
                                                }.onFailure {
                                                    voiceLibraryStatus = "تعذر فتح مصدر الصوت."
                                                }
                                            },
                                            modifier = Modifier.height(38.dp),
                                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)
                                        ) {
                                            Text("المصدر والترخيص", color = Gold, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(voiceLibraryStatus, color = TextMuted, fontSize = 10.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
                    if (selectedVoice.engineId == "nabra") {
                        Text("حزمة Nabra الحالية صوت واحد للفصحى الحديثة، وليست مجموعة أصوات رجالية ونسائية.", color = GoldSoft, fontSize = 10.sp, lineHeight = 16.sp)
                    }

                    Spacer(Modifier.height(9.dp))
                    OutlinedButton(
                        onClick = {
                            if (!selectedVoiceReady || generating || previewing) return@OutlinedButton
                            previewing = true
                            lastFile = null
                            errorMessage = null
                            val previewLanguage = language
                            val previewVoice = selectedVoice
                            val previewSpeed = speed
                            val previewSteps = requestedNumSteps
                            val sampleText = if (previewVoice.engineId == "nabra" || previewLanguage == "ar") {
                                "السَّلَامُ عَلَيْكُمْ. هَذَا اخْتِبَارٌ لِلصَّوْتِ العَرَبِيِّ الفَصِيحِ."
                            } else {
                                "Hello, this is a natural voice sample from SAWT."
                            }
                            val previewStartedAt = SystemClock.elapsedRealtime()
                            generationStartedAt = previewStartedAt
                            remainingEstimateBaseMs = estimateGenerationMs(metricsPrefs, previewLanguage, previewSpeed, sampleText.length)
                            generationRemainingMs = remainingEstimateBaseMs
                            remainingEstimateUpdatedAt = previewStartedAt
                            hasRemainingEstimate = generationRemainingMs > 0L
                            generationStage = "جارٍ تجهيز عينة الصوت…"
                            val previewKey = "${previewVoice.engineId}-${previewVoice.catalogId}-${previewLanguage}-${previewVoice.speakerId}-${String.format(Locale.US, "%.1f", previewSpeed)}-$previewSteps"
                            scope.launch {
                                try {
                                    val cachedSample = previewCache[previewKey]
                                        ?.takeIf { it.isFile && it.length() > 44L }
                                    val sample = if (cachedSample != null) cachedSample else if (previewVoice.engineId == "nabra") {
                                        val pack = voicePacks.firstOrNull { it.id == previewVoice.catalogId }
                                            ?: throw IOException("حزمة الصوت غير موجودة في المكتبة.")
                                        tashkeelEngine?.releaseModelMemory()
                                        engine.releaseNativeModel()
                                        ensureNabraEngine().synthesizeToFile(
                                            sampleText,
                                            pack,
                                            previewSpeed,
                                            onProgress = { name, loaded, total ->
                                                val detail = if (total > 0L) "$name · ${(loaded * 100L / total).toInt().coerceIn(0, 100)}%" else name
                                                mainHandler.post { generationStage = detail }
                                            }
                                        )
                                    } else {
                                        nabraEngine?.releaseRuntime()
                                        engine.synthesizeToFile(
                                            text = sampleText,
                                            language = previewLanguage,
                                            speakerId = previewVoice.speakerId,
                                            speed = previewSpeed,
                                            requestedNumSteps = previewSteps,
                                            onProgress = { completed, total ->
                                                updateRemainingEstimate(completed, total, previewStartedAt, previewLanguage, previewSpeed)
                                            }
                                        )
                                    }.also {
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
                                    status = "اكتملت العينة وبدأ تشغيلها."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: "تعذر تجهيز عينة الصوت."
                                } finally {
                                    previewing = false
                                }
                            }
                        },
                        enabled = selectedVoiceReady && !modelLoading && !engineWarming && !generating && !previewing && !tashkeelWorking && voicePackLoadingId == null,
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
                    Text("جودة المعالجة", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                        QualityButton("سريع", 4, requestedNumSteps == 4, Modifier.weight(1f), enabled = !controlsLocked && selectedVoice.engineId == "supertonic") {
                            requestedNumSteps = 4
                        }
                        QualityButton("متوازن", 6, requestedNumSteps == 6, Modifier.weight(1f), enabled = !controlsLocked && selectedVoice.engineId == "supertonic") {
                            requestedNumSteps = 6
                        }
                        QualityButton("أعلى جودة", 8, requestedNumSteps == 8, Modifier.weight(1f), enabled = !controlsLocked && selectedVoice.engineId == "supertonic") {
                            requestedNumSteps = 8
                        }
                    }
                    Text(
                        if (selectedVoice.engineId == "nabra") {
                            "حزمة Nabra تستخدم إعدادات النموذج الأصلية."
                        } else {
                            "أعلى جودة تستخدم حتى 8 خطوات، وقد تستغرق وقتًا أطول. يخفّض التطبيق العدد تلقائيًا عند ضغط الذاكرة."
                        },
                        color = GoldSoft,
                        fontSize = 10.sp,
                        lineHeight = 16.sp
                    )

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
                            if (!selectedVoiceReady || input.isBlank()) return@Button
                            val requestText = input.trim()
                            val requestLanguage = language
                            val requestVoice = selectedVoice
                            val requestSpeed = speed
                            val requestSteps = requestedNumSteps
                            generating = true
                            saving = false
                            lastFile = null
                            errorMessage = null
                            status = "جارٍ إنشاء التسجيل…"
                            generationStartedAt = SystemClock.elapsedRealtime()
                            remainingEstimateBaseMs = estimateGenerationMs(metricsPrefs, requestLanguage, requestSpeed, requestText.length)
                            generationRemainingMs = remainingEstimateBaseMs
                            remainingEstimateUpdatedAt = generationStartedAt
                            hasRemainingEstimate = requestVoice.engineId == "supertonic" && generationRemainingMs > 0L
                            generationStage = "جارٍ تجهيز محرك الصوت…"
                            val startedAt = generationStartedAt
                            scope.launch {
                                try {
                                    val result = if (requestVoice.engineId == "nabra") {
                                        val pack = voicePacks.firstOrNull { it.id == requestVoice.catalogId }
                                            ?: throw IOException("حزمة الصوت غير موجودة في المكتبة.")
                                        generationStage = "جارٍ تجهيز الصوت الفصيح الإضافي…"
                                        tashkeelEngine?.releaseModelMemory()
                                        engine.releaseNativeModel()
                                        ensureNabraEngine().synthesizeToFile(
                                            requestText,
                                            pack,
                                            requestSpeed,
                                            onProgress = { name, loaded, total ->
                                                val detail = if (total > 0L) {
                                                    "$name · ${(loaded * 100L / total).toInt().coerceIn(0, 100)}%"
                                                } else name
                                                mainHandler.post { generationStage = detail }
                                            }
                                        )
                                    } else {
                                        nabraEngine?.releaseRuntime()
                                        if (requestLanguage == "ar" && TashkeelTextProcessor.containsArabic(requestText)) {
                                            generationStage = "جارٍ تحرير ذاكرة التشكيل لتجهيز الصوت…"
                                            tashkeelEngine?.releaseModelMemory()
                                        }
                                        engine.synthesizeToFile(
                                            text = requestText,
                                            language = requestLanguage,
                                            speakerId = requestVoice.speakerId,
                                            speed = requestSpeed,
                                            requestedNumSteps = requestSteps,
                                            onStage = { stage -> mainHandler.post { generationStage = stage } },
                                            onProgress = { completed, total ->
                                                updateRemainingEstimate(completed, total, startedAt, requestLanguage, requestSpeed)
                                            }
                                        )
                                    }
                                    val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                                    val sampleRate = elapsedMs.toFloat() / requestText.length.coerceAtLeast(1)
                                    val prefKey = "${requestVoice.engineId}_${generationRateKey(requestLanguage, requestSpeed)}"
                                    val oldRate = metricsPrefs.getFloat(prefKey, 0f)
                                    val smoothRate = if (oldRate > 0f) oldRate * 0.7f + sampleRate * 0.3f else sampleRate
                                    metricsPrefs.edit { putFloat(prefKey, smoothRate) }

                                    val saved = runCatching {
                                        withContext(Dispatchers.IO) {
                                            val entry = historyStore.add(
                                                source = result,
                                                language = requestLanguage,
                                                voiceName = requestVoice.name,
                                                engineId = requestVoice.engineId,
                                                speed = requestSpeed,
                                                qualitySteps = if (requestVoice.engineId == "nabra") 0 else requestSteps,
                                                text = requestText
                                            )
                                            Triple(entry, historyStore.audioFile(entry), historyStore.list())
                                        }
                                    }.getOrNull()
                                    if (saved != null) {
                                        historyEntries = saved.third
                                        lastFile = saved.second ?: result
                                        status = "اكتمل التسجيل، وحُفظ في مكتبتك الصوتية."
                                    } else {
                                        // A full storage device must not discard a successfully generated recording.
                                        lastFile = result
                                        errorMessage = "تم إنشاء التسجيل، لكن تعذر حفظ نسخة دائمة في المكتبة. نزّله الآن قبل إغلاق التطبيق."
                                        status = "التسجيل جاهز؛ لم تتوفر مساحة كافية لحفظه في السجل."
                                    }
                                    lastVoiceName = requestVoice.name
                                    lastLanguageName = if (requestLanguage == "ar") "العربية" else "English"
                                    SpeechPlaybackService.playFile(context, (lastFile ?: result).absolutePath)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: "تعذر إنشاء الصوت."
                                    status = "لم تكتمل العملية. راجع الملاحظة ثم أعد المحاولة."
                                } finally {
                                    generating = false
                                }
                            }
                        },
                        enabled = selectedVoiceReadyForGeneration && !modelLoading && !engineWarming && !generating && !previewing && !tashkeelWorking && voicePackLoadingId == null && input.isNotBlank(),
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
                                    Text("WAV · تسجيل صوتي", color = TextMuted, fontSize = 10.sp)
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

                    Spacer(Modifier.height(22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("مكتبتي الصوتية", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("${historyEntries.size} تسجيل محفوظ على هذا الجهاز", color = TextMuted, fontSize = 10.sp)
                        }
                        if (historyEntries.size > 5) {
                            TextButton(onClick = { showAllHistory = !showAllHistory }) {
                                Text(if (showAllHistory) "عرض الأقل" else "عرض الكل", color = GoldSoft, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (historyEntries.isEmpty()) {
                        Surface(
                            color = SurfaceDark,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "ستظهر تسجيلاتك هنا بعد إنشاء أول صوت. تبقى محفوظة حتى تحذفها.",
                                color = TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        (if (showAllHistory) historyEntries else historyEntries.take(5)).forEach { entry ->
                            val audioFile = historyStore.audioFile(entry)
                            if (audioFile != null) {
                                Surface(
                                    color = SurfaceDark,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, Border),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${entry.voiceName} · ${if (entry.language == "ar") "العربية" else "English"}", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                Text(formatHistoryDate(entry.createdAtMillis), color = TextMuted, fontSize = 9.sp)
                                            }
                                            Text(if (entry.engineId == "nabra") "Nabra" else "${entry.qualitySteps} خطوات", color = TextMuted, fontSize = 9.sp)
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Text(entry.text.take(120), color = TextMain, fontSize = 11.sp, lineHeight = 17.sp, maxLines = 2)
                                        Spacer(Modifier.height(7.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = { SpeechPlaybackService.playFile(context, audioFile.absolutePath) },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                                            ) { Text("تشغيل", color = Gold, fontSize = 10.sp) }
                                            TextButton(
                                                onClick = {
                                                    inputValue = TextFieldValue(entry.text, TextRange(entry.text.length))
                                                    language = entry.language
                                                    if (entry.engineId == "nabra") {
                                                        voicePacks.firstOrNull { it.id == "nabra-msa-7m-v1" }?.let { pack ->
                                                            selectedVoice = VoiceStyle(pack.name, -1, "فصحى · تجريبي", "nabra", pack.id)
                                                        }
                                                    } else {
                                                        selectedVoice = (maleVoices + femaleVoices).firstOrNull { it.name == entry.voiceName } ?: selectedVoice
                                                    }
                                                    lastAutoDiacritizedText = entry.text
                                                    lastFile = audioFile
                                                    lastVoiceName = entry.voiceName
                                                    lastLanguageName = if (entry.language == "ar") "العربية" else "English"
                                                    status = "تمت استعادة النص والتسجيل من المكتبة."
                                                },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                                            ) { Text("استعادة النص", color = TextMuted, fontSize = 10.sp) }
                                            TextButton(
                                                onClick = {
                                                    if (saving) return@TextButton
                                                    saving = true
                                                    scope.launch {
                                                        try {
                                                            val name = withContext(Dispatchers.IO) { saveAudioToDownloads(context, audioFile) }
                                                            status = "تم حفظ $name داخل التنزيلات / SAWT AI."
                                                        } catch (error: Exception) {
                                                            errorMessage = error.message ?: "تعذر تنزيل التسجيل."
                                                        } finally {
                                                            saving = false
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                                enabled = !saving
                                            ) { Text("تنزيل", color = TextMuted, fontSize = 10.sp) }
                                            TextButton(
                                                onClick = { pendingHistoryDelete = entry },
                                                modifier = Modifier.weight(0.8f),
                                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                                            ) { Text("حذف", color = Color(0xFFE1A7A7), fontSize = 10.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(15.dp))
                    Text(status, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Text("SAWT  ·  مكتبتك الصوتية", color = Color(0xFF708795), fontSize = 9.sp, letterSpacing = 1.2.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(18.dp))
                }
            }

            if (showVoiceManager) {
                AlertDialog(
                    onDismissRequest = { if (!modelLoading && voicePackLoadingId == null) showVoiceManager = false },
                    containerColor = SurfaceDark,
                    title = { Text("مدير الأصوات والتنزيلات", color = Gold, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(
                            modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Text("إدارة الملفات الصوتية واستكمال التنزيلات المتوقفة.", color = TextMain, fontSize = 12.sp)
                            Surface(color = Background, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Border)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("حزمة Supertonic الأساسية", color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(
                                        if (voiceStorage.isInstalled) "مثبتة وجاهزة للاستخدام دون إنترنت."
                                        else if (voiceStorage.pendingBytes > 0L) "يوجد تنزيل غير مكتمل؛ يمكنك استكماله."
                                        else "غير مثبتة على الجهاز.",
                                        color = TextMain,
                                        fontSize = 11.sp
                                    )
                                    Text("الحجم المثبت: ${formatStorageBytes(voiceStorage.installedBytes)}", color = TextMuted, fontSize = 10.sp)
                                    Text("ملفات قيد التنزيل: ${formatStorageBytes(voiceStorage.pendingBytes)}", color = TextMuted, fontSize = 10.sp)
                                    Text("المساحة الفارغة: ${formatStorageBytes(voiceStorage.availableBytes)}", color = TextMuted, fontSize = 10.sp)
                                    if (modelLoading) {
                                        LinearProgressIndicator(
                                            progress = { (modelProgress / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = Gold,
                                            trackColor = SurfaceRaised
                                        )
                                        Text(modelStage, color = GoldSoft, fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = {
                                            if (modelLoading) return@Button
                                            modelLoading = true
                                            modelProgress = 0
                                            managerStatus = "جارٍ تنزيل أو استكمال حزمة Supertonic…"
                                            scope.launch {
                                                try {
                                                    engine.downloadAndInstall(
                                                        onProgress = { loaded, total ->
                                                            mainHandler.post {
                                                                modelProgress = if (total > 0L) {
                                                                    ((loaded * 100L) / total).toInt().coerceIn(0, 100)
                                                                } else 0
                                                            }
                                                        },
                                                        onStage = { stage -> mainHandler.post { modelStage = stage; managerStatus = stage } }
                                                    )
                                                    modelReady = engine.isModelInstalled()
                                                    managerStatus = "حزمة Supertonic جاهزة."
                                                } catch (error: Exception) {
                                                    managerStatus = error.message ?: "تعذر تنزيل حزمة Supertonic."
                                                } finally {
                                                    modelLoading = false
                                                    refreshVoiceStorage()
                                                }
                                            }
                                        },
                                        enabled = !modelLoading && !generating && !previewing && !performanceRunning,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Background)
                                    ) {
                                        Text(
                                            when {
                                                modelLoading -> "جارٍ التنزيل…"
                                                voiceStorage.pendingBytes > 0L -> "استكمال التنزيل"
                                                voiceStorage.isInstalled -> "فحص / تحديث تحسين السرعة"
                                                else -> "تنزيل حزمة Supertonic"
                                            }
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            showVoiceManager = false
                                            showDeleteConfirmation = true
                                        },
                                        enabled = voiceStorage.isInstalled && !modelLoading && !controlsLocked,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("حذف حزمة Supertonic", color = Color(0xFFE1A7A7)) }
                                }
                            }

                            Text("الحزم الصوتية الإضافية", color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            voicePacks.forEach { pack ->
                                val packBytes = directoryBytes(File(context.filesDir, "sawt-voice-packs/${pack.id}"))
                                val packPending = directoryPartialBytes(File(context.filesDir, "sawt-voice-packs/${pack.id}"))
                                val installed = pack.id in installedPackIds
                                Surface(color = Background, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Border)) {
                                    Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(pack.name, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                        Text(pack.description, color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
                                        Text("المساحة المستخدمة: ${formatStorageBytes(packBytes)}", color = TextMuted, fontSize = 10.sp)
                                        if (packPending > 0L) {
                                            Text("تنزيل محفوظ للاستكمال: ${formatStorageBytes(packPending)}", color = GoldSoft, fontSize = 10.sp)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (installed) "مثبتة" else if (packPending > 0L) "تنزيل متوقف" else "غير مثبتة",
                                                color = if (installed) Gold else TextMuted,
                                                fontSize = 10.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (pack.downloadable && !installed) {
                                                TextButton(
                                                    onClick = {
                                                        if (voicePackLoadingId != null) return@TextButton
                                                        voicePackLoadingId = pack.id
                                                        managerStatus = "جارٍ تنزيل أو استكمال ${pack.name}…"
                                                        scope.launch {
                                                            try {
                                                                ensureNabraEngine().prepare(pack) { name, loaded, total ->
                                                                    mainHandler.post {
                                                                        managerStatus = if (total > 0L) {
                                                                            "$name · ${(loaded * 100L / total).toInt().coerceIn(0, 100)}%"
                                                                        } else "$name · ${formatStorageBytes(loaded)}"
                                                                    }
                                                                }
                                                                voicePackPrefs.edit { putBoolean("installed_${pack.id}", true) }
                                                                installedPackIds = installedPackIds + pack.id
                                                                selectedVoice = VoiceStyle(pack.name, -1, "فصحى · إضافي", "nabra", pack.id)
                                                                language = "ar"
                                                                managerStatus = "اكتمل تنزيل ${pack.name} وأصبح متاحًا دون إنترنت."
                                                                refreshVoiceStorage()
                                                            } catch (error: Exception) {
                                                                managerStatus = error.message ?: "تعذر تنزيل الحزمة."
                                                            } finally {
                                                                voicePackLoadingId = null
                                                            }
                                                        }
                                                    },
                                                    enabled = !controlsLocked && !modelLoading
                                                ) {
                                                    Text(if (voicePackLoadingId == pack.id) "جارٍ…" else if (packPending > 0L) "استكمال" else "تنزيل", color = Gold)
                                                }
                                            } else if (installed || packBytes > 0L) {
                                                TextButton(
                                                    onClick = { pendingVoicePackDelete = pack },
                                                    enabled = !controlsLocked && !modelLoading
                                                ) { Text("حذف", color = Color(0xFFE1A7A7)) }
                                            }
                                        }
                                    }
                                }
                            }
                            Divider(color = Border)
                            Text("التسجيلات المحفوظة: ${formatStorageBytes(recordingsStorageBytes)}", color = TextMuted, fontSize = 10.sp)
                            Text("حذف حزمة صوت لا يحذف مكتبة التسجيلات أو ملفات WAV.", color = GoldSoft, fontSize = 10.sp)
                            Text(managerStatus, color = TextMuted, fontSize = 10.sp)
                            TextButton(
                                onClick = { scope.launch { refreshVoiceStorage(); managerStatus = "تم تحديث معلومات التخزين." } },
                                enabled = !modelLoading && voicePackLoadingId == null
                            ) { Text("تحديث معلومات التخزين", color = Gold) }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showVoiceManager = false }, enabled = !modelLoading && voicePackLoadingId == null) {
                            Text("إغلاق", color = Gold)
                        }
                    }
                )
            }

            if (pendingVoicePackDelete != null) {
                AlertDialog(
                    onDismissRequest = { pendingVoicePackDelete = null },
                    containerColor = SurfaceDark,
                    title = { Text("حذف حزمة الصوت؟", color = Gold) },
                    text = {
                        Text(
                            "سيتم حذف ملفات حزمة ${pendingVoicePackDelete?.name} والتنزيلات الجزئية الخاصة بها فقط. لن تُحذف تسجيلاتك المحفوظة.",
                            color = TextMain
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val pack = pendingVoicePackDelete ?: return@TextButton
                            pendingVoicePackDelete = null
                            scope.launch {
                                try {
                                    ensureNabraEngine().removePack(pack.id)
                                    voicePackPrefs.edit { remove("installed_${pack.id}") }
                                    installedPackIds = installedPackIds - pack.id
                                    if (selectedVoice.engineId == "nabra" && selectedVoice.catalogId == pack.id) {
                                        selectedVoice = maleVoices.first()
                                    }
                                    managerStatus = "تم حذف ملفات الحزمة. بقيت التسجيلات كما هي."
                                    refreshVoiceStorage()
                                } catch (error: Exception) {
                                    managerStatus = error.message ?: "تعذر حذف حزمة الصوت."
                                }
                            }
                        }) { Text("حذف", color = Color(0xFFE1A7A7)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingVoicePackDelete = null }) { Text("إلغاء", color = Gold) }
                    }
                )
            }

            if (showPerformanceDialog) {
                AlertDialog(
                    onDismissRequest = { if (!performanceRunning) showPerformanceDialog = false },
                    containerColor = SurfaceDark,
                    title = { Text("اختبار أداء SAWT", color = Gold, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(
                            modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("اختبار محلي على هذا الهاتف باستخدام نص عربي ثابت. لا يستخدم الإنترنت ولا يضيف ملفات إلى مكتبة التسجيلات.", color = TextMain, fontSize = 11.sp, lineHeight = 17.sp)
                            Button(
                                onClick = { startPerformanceBenchmark() },
                                enabled = modelReady && !performanceRunning && !modelLoading && !generating && !previewing,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Background)
                            ) { Text(if (performanceRunning) "جارٍ الاختبار…" else "بدء اختبار الأداء") }
                            if (performanceRunning) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gold, trackColor = SurfaceRaised)
                            }
                            Text(performanceStage, color = GoldSoft, fontSize = 10.sp)
                            performanceError?.let { Text(it, color = Color(0xFFE1A7A7), fontSize = 11.sp) }
                            performanceReport?.let { report ->
                                Divider(color = Border)
                                Text("زمن أول تحويل (يشمل تهيئة المحرك)", color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                BenchmarkResultLine("6 خطوات", report.coldFirstConversion)
                                Text("زمن التحويل بعد التهيئة", color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                BenchmarkResultLine("6 خطوات", report.warmConversion)
                                Text("مقارنة إعدادات الجودة", color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                BenchmarkResultLine("سريع · 4 خطوات", report.qualityComparisons[0])
                                BenchmarkResultLine("متوازن · 6 خطوات", report.qualityComparisons[1])
                                BenchmarkResultLine("أعلى جودة · 8 خطوات", report.qualityComparisons[2])
                                Text(
                                    "الخطوات الفعلية قد تُخفض تلقائيًا لحماية الذاكرة. الاختبار يقيس الزمن والذاكرة، ولا يحكم آليًا على جمال النطق؛ استمع للعينات لمقارنة الجودة.",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showPerformanceDialog = false }, enabled = !performanceRunning) {
                            Text("إغلاق", color = Gold)
                        }
                    }
                )
            }

            if (showHelp) {
                AlertDialog(
                    onDismissRequest = { showHelp = false },
                    containerColor = SurfaceDark,
                    title = {
                        Text("شرح الاستخدام", color = Gold, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 470.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            HelpSection("١. تثبيت الأصوات الأساسية", "نزّل حزمة Supertonic من قسم حزمة الأصوات عند اتصال الهاتف بالإنترنت. حجمها الأولي يقارب 133 ميجابايت، وتتحقق المنظومة من سلامة ملفات النموذج قبل استعمالها. يُفضّل توفر مساحة مؤقتة إضافية لفك الملفات وتثبيتها.")
                            HelpSection("٢. كتابة النص والتشكيل", "اكتب أو الصق النص في المربع. عند الكتابة بالعربية، ينتظر التطبيق توقفك قليلًا ثم يضيف الفتحة والضمة والكسرة والسكون والشدة والتنوين تلقائيًا داخل النص، مع الحفاظ على موضع المؤشر. يلزم الإنترنت لتنزيل نموذج التشكيل أول مرة (نحو 22 ميجابايت)، وبعدها يعمل دون إنترنت. راجع الأسماء وأواخر الإعراب لأن التشكيل الآلي قد يخطئ. الحد الأقصى 2000 حرف في العملية الواحدة.")
                            HelpSection("٣. اختيار اللغة", "اختر «العربية» عندما يكون النص عربيًا، واختر English عندما يكون النص إنجليزيًا. الأفضل أن يكون النص بلغة واحدة في كل عملية حتى تكون القراءة أوضح.")
                            HelpSection("٤. اختيار الصوت", "تتضمن المجموعة الأساسية خمسة أنماط رجالية M1–M5 وخمسة نسائية F1–F5 للعربية والإنجليزية. وتعرض مكتبة الأصوات حزمًا إضافية عند توفر مصدر متوافق. Nabra الحالي صوت عربي واحد للفصحى الحديثة، وهو تجريبي حتى تجربته على جهاز فعلي.")
                            HelpSection("٥. السرعة والجودة", "سرعة القراءة مستقلة عن جودة التوليد. ابدأ بسرعة 1.0× ثم اضبطها بين 0.8× و1.2×. اختر سريعًا (4 خطوات) أو متوازنًا (6) أو أعلى جودة (8). إذا كانت RAM المتاحة قليلة، يخفض التطبيق الحد تلقائيًا لتجنب الضغط على الذاكرة.")
                            HelpSection("٦. إنشاء الصوت والاستماع", "بعد كتابة النص واختيار اللغة والصوت، اضغط «إنشاء الصوت والاستماع». سيُنشئ التطبيق ملف WAV على الهاتف ويبدأ تشغيله تلقائيًا. اضغط زر التشغيل الدائري داخل بطاقة الصوت لسماعه مرة أخرى، أو اضغط سهم التنزيل الموجود بجانبه لحفظ الملف.")
                            HelpSection("٧. مكتبة التسجيلات", "يحفظ التطبيق التسجيلات الناجحة في مكتبة داخلية خاصة بالجهاز، مع النص واللغة والصوت وتاريخ الإنشاء. يمكنك إعادة تشغيل التسجيل أو استعادة نصه إلى المحرر أو تنزيله بصيغة WAV إلى مجلد SAWT AI أو حذفه. تحتفظ المكتبة بآخر 100 تسجيل.")
                            HelpSection("٨. العمل دون إنترنت والخصوصية", "الإنترنت مطلوب أول مرة لتنزيل نموذج الصوت (نحو 133 ميجابايت) ونموذج التشكيل (نحو 22 ميجابايت). بعد اكتمال التنزيلين يعمل التشكيل وإنشاء الصوت محليًا دون اتصال. لا يرسل التطبيق النص إلى خدمة سحابية أثناء المعالجة المحلية.")
                            HelpSection("٩. تحديث مكتبة الأصوات", "اضغط «تحديث» في قسم مكتبة الأصوات لجلب الفهرس المنسق من GitHub. لا يُشغّل التطبيق شيفرات تأتي من الفهرس؛ الحزم المتوافقة فقط مع المحرك المثبّت يمكن تنزيلها. الأصوات التي تتطلب شراءً أو ترخيصًا مدفوعًا تُفتح صفحة مصدرها وترخيصها، ولا توجد عمليات دفع داخل التطبيق.")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showHelp = false }) { Text("فهمت", color = Gold) }
                    }
                )
            }

            if (pendingHistoryDelete != null) {
                AlertDialog(
                    onDismissRequest = { pendingHistoryDelete = null },
                    containerColor = SurfaceDark,
                    title = { Text("حذف التسجيل من المكتبة؟", color = Gold) },
                    text = { Text("سيُحذف ملف WAV المحفوظ لهذا التسجيل فقط. لا يمكن التراجع عن الحذف.", color = TextMain) },
                    confirmButton = {
                        TextButton(onClick = {
                            val selected = pendingHistoryDelete ?: return@TextButton
                            scope.launch {
                                try {
                                    val deleted = withContext(Dispatchers.IO) { historyStore.delete(selected.id) }
                                    historyEntries = withContext(Dispatchers.IO) { historyStore.list() }
                                    if (lastFile?.name == "${selected.id}.wav") lastFile = null
                                    status = if (deleted) "حُذف التسجيل من المكتبة." else "لم يعد التسجيل موجودًا."
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: "تعذر حذف التسجيل."
                                } finally {
                                    pendingHistoryDelete = null
                                }
                            }
                        }) { Text("حذف", color = Color(0xFFE1A7A7)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingHistoryDelete = null }) { Text("إلغاء", color = Gold) }
                    }
                )
            }

            if (showDeleteConfirmation) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmation = false },
                    containerColor = SurfaceDark,
                    title = { Text("حذف حزمة الأصوات؟", color = Gold) },
                    text = { Text("سيتم حذف ملفات Supertonic والتنزيلات الجزئية فقط. ستحتاج إلى الإنترنت لتنزيلها مجددًا. لن تُحذف مكتبة التسجيلات المحفوظة.", color = TextMain) },
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
                                status = "تم حذف حزمة Supertonic والتنزيلات الجزئية. التسجيلات لم تتأثر."
                                scope.launch { refreshVoiceStorage() }
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
            Text("SAWT", color = TextMain, fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text("استوديو تحويل النص إلى صوت", color = Color(0xFFC2D0DA), fontSize = 14.sp)
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
private fun QualityButton(
    label: String,
    steps: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
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

private fun formatHistoryDate(timestamp: Long): String =
    java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()).format(java.util.Date(timestamp))
