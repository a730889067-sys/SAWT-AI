package com.sawt.ai

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sawt.ai.audio.SonexTtsClient
import com.sawt.ai.audio.SonexVoiceOption
import com.sawt.ai.audio.SpeechPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Ink = Color(0xFF080D18)
private val Panel = Color(0xFF111B2C)
private val Panel2 = Color(0xFF17243A)
private val Cyan = Color(0xFF54D6D0)
private val Muted = Color(0xFF9AAAC0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SawtApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SawtApp() {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val settings = remember {
        context.getSharedPreferences("sawt_ai_settings", android.content.Context.MODE_PRIVATE)
    }
    var text by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(settings.getString("sonex_api_key", "").orEmpty()) }
    var savedApiKey by remember { mutableStateOf(apiKey) }
    var settingsOpen by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf<List<SonexVoiceOption>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf<SonexVoiceOption?>(null) }
    var voiceMenu by remember { mutableStateOf(false) }
    var loadingVoices by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var lastGeneratedFile by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("اكتب النص واختر صوتًا عربيًا فصيحًا") }
    val scope = rememberCoroutineScope()
    val client = remember(savedApiKey) {
        savedApiKey.takeIf { it.isNotBlank() }?.let { SonexTtsClient(it) }
    }

    LaunchedEffect(client) {
        voices = emptyList()
        selectedVoice = null
        if (client != null) {
            loadingVoices = true
            try {
                voices = withContext(Dispatchers.IO) { client.fetchArabicVoices() }
                selectedVoice = voices.firstOrNull()
                message = if (voices.isEmpty()) "لا توجد أصوات عربية في حساب الخدمة." else "اختر الصوت واكتب النص."
            } catch (e: Exception) {
                message = e.message ?: "تعذر تحميل الأصوات. تحقق من المفتاح والإنترنت."
            } finally {
                loadingVoices = false
            }
        } else {
            message = "أضف مفتاح الخدمة المجاني مرة واحدة من الإعدادات لبدء الاستخدام."
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Ink, surface = Panel, primary = Cyan,
                onPrimary = Ink, onBackground = Color.White, onSurface = Color.White
            )
        ) {
            Scaffold(containerColor = Ink) { padding ->
                Column(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF101D31), Ink, Ink)))
                        .padding(padding).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(45.dp).clip(RoundedCornerShape(15.dp))
                                    .background(Brush.linearGradient(listOf(Cyan, Color(0xFF6D83FF)))),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.GraphicEq, null, tint = Ink, modifier = Modifier.size(28.dp)) }
                            Spacer(Modifier.width(11.dp))
                            Column {
                                Text("SAWT AI", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                                Text("استوديو الصوت العربي", color = Muted, fontSize = 11.sp)
                            }
                        }
                        IconButton(onClick = { settingsOpen = !settingsOpen }) {
                            Icon(
                                if (settingsOpen) Icons.Default.Close else Icons.Default.Settings,
                                contentDescription = "الإعدادات", tint = Cyan
                            )
                        }
                    }

                    Spacer(Modifier.height(26.dp))
                    if (settingsOpen) {
                        Text("إعداد الخدمة", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "أضف مفتاح Sonex المجاني مرة واحدة. لا نستخدم صوت الهاتف.",
                            color = Muted, fontSize = 13.sp, lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(18.dp))
                        Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("مفتاح API", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = apiKey, onValueChange = { apiKey = it },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    placeholder = { Text("vsk_…") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Cyan, unfocusedBorderColor = Panel2,
                                        focusedContainerColor = Color(0xFF0B1422),
                                        unfocusedContainerColor = Color(0xFF0B1422)
                                    )
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val key = apiKey.trim()
                                        if (key.startsWith("vsk_") && key.length > 12) {
                                            settings.edit().putString("sonex_api_key", key).apply()
                                            savedApiKey = key
                                            settingsOpen = false
                                            message = "تم حفظ المفتاح. جارٍ تحميل الأصوات."
                                        } else message = "المفتاح غير مكتمل. تأكد أنه يبدأ بـ vsk_."
                                    },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                                    shape = RoundedCornerShape(15.dp)
                                ) { Text("حفظ وربط الخدمة", fontWeight = FontWeight.Bold) }
                                TextButton(
                                    onClick = {
                                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sonexlabs.com"))) }
                                        catch (_: Exception) { message = "افتح sonexlabs.com من المتصفح." }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("إنشاء حساب ومفتاح مجاني") }
                                TextButton(
                                    onClick = {
                                        settings.edit().remove("sonex_api_key").apply()
                                        apiKey = ""; savedApiKey = ""; voices = emptyList(); selectedVoice = null
                                        message = "تم حذف مفتاح الخدمة من الهاتف."
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("حذف المفتاح من هذا الهاتف", color = Muted) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "الخطة المجانية المعلنة تشمل 10,000 حرف دون بطاقة بنكية. النص يُرسل إلى Sonex لإنشاء الصوت.",
                            color = Muted, fontSize = 11.sp, lineHeight = 18.sp
                        )
                    } else {
                        Text("حوّل كلماتك إلى صوت", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("أصوات عربية فصحى سحابية · تشغيل وتنزيل MP3", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(19.dp))

                        Surface(color = Panel, shape = RoundedCornerShape(21.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("النص العربي", fontWeight = FontWeight.SemiBold)
                                    Text("${text.length} / 5000", color = Muted, fontSize = 11.sp)
                                }
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { if (it.length <= 5000) text = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp),
                                    placeholder = { Text("اكتب أو الصق النص هنا…", color = Muted) },
                                    shape = RoundedCornerShape(15.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Cyan, unfocusedBorderColor = Panel2,
                                        focusedContainerColor = Color(0xFF0B1422),
                                        unfocusedContainerColor = Color(0xFF0B1422)
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(13.dp))
                        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(15.dp)) {
                                Text("الصوت الفصيح", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Box {
                                    OutlinedButton(
                                        onClick = { voiceMenu = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text(
                                            when {
                                                loadingVoices -> "جارٍ تحميل الأصوات…"
                                                selectedVoice != null -> selectedVoice!!.label
                                                else -> "اختر صوتًا عربيًا"
                                            },
                                            modifier = Modifier.weight(1f), textAlign = TextAlign.Start
                                        )
                                        Icon(Icons.Default.ExpandMore, null)
                                    }
                                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                                        voices.forEach { voice ->
                                            DropdownMenuItem(
                                                text = { Text(voice.label) },
                                                onClick = { selectedVoice = voice; voiceMenu = false }
                                            )
                                        }
                                    }
                                }
                                if (voices.isEmpty() && !loadingVoices) {
                                    Text("اربط الخدمة من زر الإعدادات بالأعلى لعرض الأصوات.", color = Muted, fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Button(
                            enabled = !generating && client != null && selectedVoice != null && text.isNotBlank(),
                            onClick = {
                                val activeClient = client
                                val activeVoice = selectedVoice
                                if (activeClient == null || activeVoice == null) {
                                    message = "اربط الخدمة واختر صوتًا أولًا."
                                } else {
                                    if (Build.VERSION.SDK_INT >= 33 &&
                                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                    ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    generating = true
                                    lastGeneratedFile = null
                                    message = "جارٍ إنشاء الصوت العربي…"
                                    val textToSpeak = text.trim()
                                    scope.launch {
                                        try {
                                            val file = withContext(Dispatchers.IO) {
                                                val folder = File(context.cacheDir, "sawt-audio").apply { mkdirs() }
                                                File(folder, "sawt-${System.currentTimeMillis()}.mp3").also {
                                                    activeClient.synthesizeToFile(textToSpeak, activeVoice.id, 1.0f, it)
                                                }
                                            }
                                            lastGeneratedFile = file
                                            SpeechPlaybackService.playFile(context, file.absolutePath)
                                            message = "الصوت جاهز ويعمل الآن. يمكنك تنزيله إلى الهاتف."
                                        } catch (e: Exception) {
                                            message = e.message ?: "تعذر إنشاء الصوت. حاول مرة أخرى."
                                        } finally {
                                            generating = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)
                        ) {
                            if (generating) CircularProgressIndicator(modifier = Modifier.size(21.dp), color = Ink, strokeWidth = 2.dp)
                            else Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (generating) "جارٍ إنشاء الصوت…" else "إنشاء الصوت والاستماع", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }

                        if (lastGeneratedFile != null) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    val file = lastGeneratedFile
                                    if (file != null) scope.launch {
                                        try {
                                            val saved = withContext(Dispatchers.IO) { saveAudioToDownloads(context, file) }
                                            message = "تم تنزيل $saved داخل مجلد Downloads/SAWT AI."
                                        } catch (_: Exception) {
                                            message = "تعذر حفظ الملف. حاول مرة أخرى."
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text("تنزيل الصوت إلى الهاتف (MP3)")
                            }
                        }
                        Spacer(Modifier.height(13.dp))
                        Text(message, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

private fun saveAudioToDownloads(context: android.content.Context, source: File): String {
    val filename = "SAWT-${System.currentTimeMillis()}.mp3"
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
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
