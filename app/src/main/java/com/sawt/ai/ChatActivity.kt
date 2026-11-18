package com.sawt.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sawt.ai.audio.ChatTurn
import com.sawt.ai.audio.GeminiChatClient
import com.sawt.ai.audio.GeminiKeyVault
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

private val ChatBackground = Color(0xFF070707)
private val ChatSurface = Color(0xFF151515)
private val ChatGold = Color(0xFFD8B56D)
private val ChatText = Color(0xFFF5F1E8)
private val ChatMuted = Color(0xFF9B978E)

private data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String
)

class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SawtAssistantScreen(onBack = { finish() }) }
    }
}

@Composable
private fun SawtAssistantScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { GeminiKeyVault(context) }
    var apiKey by remember { mutableStateOf(vault.read().orEmpty()) }
    var keyDraft by remember { mutableStateOf("") }
    var showKeyDialog by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var speaker by remember { mutableStateOf<TextToSpeech?>(null) }
    var speakerReady by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (apiKey.isBlank()) showKeyDialog = true
    }

    DisposableEffect(context) {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val arabic = instance?.setLanguage(Locale("ar"))
                speakerReady = arabic != null &&
                    arabic != TextToSpeech.LANG_MISSING_DATA &&
                    arabic != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        speaker = instance
        onDispose {
            instance?.stop()
            instance?.shutdown()
            speaker = null
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        if (apiKey.isBlank()) {
            keyDraft = ""
            showKeyDialog = true
            return
        }
        if (text.length > 12_000) {
            errorMessage = "الرسالة طويلة جدًا. أرسلها على أجزاء أقصر."
            return
        }
        val next = messages + ChatMessage(role = "user", text = text)
        messages = next
        draft = ""
        sending = true
        errorMessage = null
        scope.launch {
            try {
                val history = next.takeLast(12).map { ChatTurn(it.role, it.text) }
                val answer = GeminiChatClient.reply(apiKey, history)
                messages = messages + ChatMessage(role = "assistant", text = answer)
            } catch (error: Exception) {
                errorMessage = error.message ?: "تعذر الاتصال بالمساعد. تحقق من الإنترنت والمفتاح."
            } finally {
                sending = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = ChatBackground,
        surface = ChatSurface,
        primary = ChatGold,
        onPrimary = ChatBackground,
        onBackground = ChatText,
        onSurface = ChatText
    )) {
        Scaffold(
            containerColor = ChatBackground,
            bottomBar = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(ChatBackground)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (errorMessage != null) {
                        Text(errorMessage.orEmpty(), color = Color(0xFFE5A0A0), fontSize = 12.sp, modifier = Modifier.padding(bottom = 7.dp))
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = {
                                if (it.length <= 12_000) draft = it
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("اكتب رسالتك أو الصق النص هنا…", color = ChatMuted) },
                            minLines = 1,
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ChatGold,
                                unfocusedBorderColor = Color(0xFF39332A),
                                focusedTextColor = ChatText,
                                unfocusedTextColor = ChatText,
                                cursorColor = ChatGold
                            )
                        )
                        IconButton(
                            onClick = { sendMessage() },
                            enabled = draft.isNotBlank() && !sending && apiKey.isNotBlank(),
                            modifier = Modifier.size(52.dp).background(ChatGold, RoundedCornerShape(16.dp))
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "إرسال", tint = ChatBackground)
                        }
                    }
                    Text("يعمل المساعد عبر الإنترنت. لا ترسل كلمات مرور أو بيانات خاصة.", color = ChatMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .background(ChatBackground)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = ChatGold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("مساعد SAWT الذكي", color = ChatText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Gemini Flash · محادثة عربية", color = ChatMuted, fontSize = 10.sp)
                    }
                    IconButton(onClick = {
                        if (apiKey.isNotBlank()) keyDraft = apiKey
                        showKeyDialog = true
                    }) {
                        Icon(Icons.Default.Key, contentDescription = "إعداد المفتاح", tint = ChatGold)
                    }
                    IconButton(onClick = {
                        messages = emptyList()
                        errorMessage = null
                    }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "مسح المحادثة", tint = ChatMuted)
                    }
                }

                if (messages.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("مرحبًا بك في مساعد SAWT AI", color = ChatGold, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "اسأل عن أي موضوع، أرسل نصًا لشرحه أو تلخيصه أو ترجمته، وتابع الحوار برسائل متتالية.",
                            color = ChatMuted, fontSize = 14.sp, lineHeight = 23.sp, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        Text("يحتفظ بآخر الرسائل لفهم سياق حديثك.", color = ChatMuted, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            val isAssistant = message.role == "assistant"
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End
                            ) {
                                Surface(
                                    color = if (isAssistant) ChatSurface else Color(0xFF342A19),
                                    shape = RoundedCornerShape(
                                        topStart = 18.dp,
                                        topEnd = 18.dp,
                                        bottomEnd = if (isAssistant) 18.dp else 5.dp,
                                        bottomStart = if (isAssistant) 5.dp else 18.dp
                                    ),
                                    modifier = Modifier.widthIn(max = 330.dp)
                                ) {
                                    Column(Modifier.padding(13.dp)) {
                                        Text(if (isAssistant) "SAWT AI" else "أنت", color = if (isAssistant) ChatGold else Color(0xFFE7C98B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(5.dp))
                                        Text(message.text, color = ChatText, fontSize = 14.sp, lineHeight = 22.sp)
                                        if (isAssistant) {
                                            Spacer(Modifier.height(5.dp))
                                            TextButton(
                                                onClick = {
                                                    if (speakerReady) {
                                                        speaker?.speak(message.text, TextToSpeech.QUEUE_FLUSH, null, message.id)
                                                    } else {
                                                        Toast.makeText(context, "لا يتوفر صوت عربي على الهاتف. نزّل بيانات الصوت العربي من إعدادات تحويل النص إلى كلام.", Toast.LENGTH_LONG).show()
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                            ) {
                                                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = ChatGold, modifier = Modifier.size(17.dp))
                                                Spacer(Modifier.width(5.dp))
                                                Text("استمع إلى الرد", color = ChatGold, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (sending) {
                            item {
                                Surface(color = ChatSurface, shape = RoundedCornerShape(16.dp)) {
                                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(16.dp), color = ChatGold, strokeWidth = 2.dp)
                                        Spacer(Modifier.width(9.dp))
                                        Text("يفكر ويكتب الرد…", color = ChatMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { if (apiKey.isNotBlank()) showKeyDialog = false },
            containerColor = ChatSurface,
            title = { Text("تفعيل المساعد الذكي", color = ChatText) },
            text = {
                Column {
                    Text("للتشغيل تحتاج مفتاح Gemini المجاني من Google AI Studio. انسخه والصقه هنا؛ سيُحفظ مشفّرًا على هذا الهاتف فقط.", color = ChatMuted, fontSize = 13.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("مفتاح Gemini API") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ChatGold,
                            unfocusedBorderColor = Color(0xFF514735),
                            focusedTextColor = ChatText,
                            unfocusedTextColor = ChatText,
                            cursorColor = ChatGold
                        )
                    )
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys")))
                        } catch (_: Exception) {
                            Toast.makeText(context, "تعذر فتح Google AI Studio.", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("فتح Google AI Studio للحصول على المفتاح", color = ChatGold) }
                    Text("الخطة المجانية لها حدود استخدام، وقد تستخدم Google محتوى الطلبات لتحسين خدماتها.", color = ChatMuted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        vault.save(keyDraft)
                        apiKey = keyDraft.trim()
                        showKeyDialog = false
                        errorMessage = null
                    } catch (error: Exception) {
                        errorMessage = error.message ?: "تعذر حفظ المفتاح."
                    }
                }, enabled = keyDraft.isNotBlank()) { Text("حفظ وتفعيل", color = ChatGold) }
            },
            dismissButton = {
                Row {
                    if (apiKey.isNotBlank()) {
                        TextButton(onClick = {
                            vault.clear()
                            apiKey = ""
                            keyDraft = ""
                            showKeyDialog = false
                        }) { Text("حذف المفتاح", color = Color(0xFFE5A0A0)) }
                    }
                    TextButton(onClick = { if (apiKey.isNotBlank()) showKeyDialog = false }) { Text("إلغاء", color = ChatMuted) }
                }
            }
        )
    }
}
