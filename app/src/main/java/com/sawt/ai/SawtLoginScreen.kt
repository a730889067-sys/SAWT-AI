package com.sawt.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sawt.ai.auth.SawtAuthSession
import com.sawt.ai.auth.SawtFirebaseAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SawtLoginScreen(
    authManager: SawtFirebaseAuthManager,
    onSignedIn: (SawtAuthSession) -> Unit
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
        background = Color(0xFF0D1924),
        surface = Color(0xFF142635),
        primary = Color(0xFFD8B56D),
        onPrimary = Color(0xFF0D1924),
        onBackground = Color(0xFFF5F1E8),
        onSurface = Color(0xFFF5F1E8)
    )) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1924)).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF142635),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF304757))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("SAWT AI", color = Color(0xFFD8B56D), fontSize = 27.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (createAccount) "إنشاء حساب واحد للتطبيق" else "تسجيل الدخول إلى حسابك",
                        color = Color(0xFFF5F1E8),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "بعد تسجيل الدخول مرة واحدة، يستخدم التطبيق محركات الصوت المتاحة دون أن يطلب حسابات الشركات الأخرى.",
                        color = Color(0xFFA0B0BC),
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("البريد الإلكتروني") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("كلمة المرور") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    message?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 11.sp, textAlign = TextAlign.Center) }
                    Button(
                        onClick = {
                            loading = true
                            message = null
                            scope.launch {
                                try {
                                    val session = withContext(Dispatchers.IO) {
                                        if (createAccount) authManager.createAccount(email.trim(), password)
                                        else authManager.signIn(email.trim(), password)
                                    }
                                    onSignedIn(session)
                                } catch (error: Exception) {
                                    message = error.message ?: "تعذر تسجيل الدخول."
                                } finally {
                                    loading = false
                                }
                            }
                        },
                        enabled = !loading && email.isNotBlank() && password.length >= 6,
                        modifier = Modifier.fillMaxWidth().height(51.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD8B56D),
                            contentColor = Color(0xFF0D1924)
                        )
                    ) {
                        if (loading) CircularProgressIndicator(color = Color(0xFF0D1924), strokeWidth = 2.dp)
                        else Text(if (createAccount) "إنشاء الحساب" else "تسجيل الدخول", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { createAccount = !createAccount; message = null }, enabled = !loading) {
                        Text(
                            if (createAccount) "لديك حساب؟ سجّل الدخول" else "مستخدم جديد؟ أنشئ حسابًا",
                            color = Color(0xFFD8B56D),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
