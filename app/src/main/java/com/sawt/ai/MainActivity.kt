package com.sawt.ai

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sawt.ai.audio.SpeechPlaybackService
import com.sawt.ai.audio.SystemVoiceCatalog
import com.sawt.ai.audio.VoiceOption

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
 val context = androidx.compose.ui.platform.LocalContext.current
 val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
 var text by remember { mutableStateOf("") }
 var selectedVoice by remember { mutableStateOf<VoiceOption?>(null) }
 var voices by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
 var rate by remember { mutableFloatStateOf(1f) }
 var pitch by remember { mutableFloatStateOf(1f) }
 var tab by remember { mutableIntStateOf(0) }
 var message by remember { mutableStateOf("اكتب نصك واختر الصوت الذي يناسبك") }

 LaunchedEffect(Unit) {
  voices = SystemVoiceCatalog(context).arabicVoices()
  selectedVoice = voices.firstOrNull()
  if (voices.isEmpty()) message = "لا يوجد صوت عربي مثبت حاليًا. يمكنك تثبيت صوت من إعدادات الجهاز."
 }

 MaterialTheme(colorScheme = darkColorScheme(background=Ink, surface=Panel, primary=Cyan, onPrimary=Ink, onBackground=Color.White, onSurface=Color.White, secondary=Color(0xFF9B8CFF))) {
  Scaffold(containerColor=Ink, bottomBar={
   NavigationBar(containerColor=Panel, contentColor=Cyan) {
    NavigationBarItem(selected=tab==0,onClick={tab=0},icon={Icon(Icons.Default.GraphicEq,null)},label={Text("الاستوديو")})
    NavigationBarItem(selected=tab==1,onClick={tab=1},icon={Icon(Icons.Default.RecordVoiceOver,null)},label={Text("الأصوات")})
    NavigationBarItem(selected=tab==2,onClick={tab=2},icon={Icon(Icons.Default.Settings,null)},label={Text("الإعدادات")})
   }
  }) { padding ->
   Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101D31),Ink,Ink))).padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
     Row(verticalAlignment=Alignment.CenterVertically) {
      Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Cyan,Color(0xFF6D83FF)))),contentAlignment=Alignment.Center) { Icon(Icons.Default.GraphicEq,null,tint=Ink,modifier=Modifier.size(27.dp)) }
      Spacer(Modifier.width(11.dp))
      Column { Text("SAWT AI",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=1.2.sp); Text("استوديو الصوت العربي",color=Muted,fontSize=11.sp) }
     }
     Surface(color=Color(0xFF183A3B),shape=CircleShape) { Text("الفصحى",Modifier.padding(horizontal=12.dp,vertical=8.dp),color=Cyan,fontSize=11.sp,fontWeight=FontWeight.Bold) }
    }
    Spacer(Modifier.height(27.dp))
    when(tab) {
     0 -> {
      Text("حوّل كلماتك إلى صوت",fontSize=25.sp,fontWeight=FontWeight.Bold)
      Spacer(Modifier.height(6.dp)); Text("أصوات عربية فصيحة، بتجربة بسيطة وأنيقة.",color=Muted,fontSize=13.sp)
      Spacer(Modifier.height(20.dp))
      Surface(color=Panel,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
       Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text("النص",fontWeight=FontWeight.SemiBold); Text(text.length.toString()+" حرف",color=Muted,fontSize=11.sp) }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value=text,onValueChange={text=it},modifier=Modifier.fillMaxWidth().heightIn(min=170.dp),placeholder={Text("اكتب أو الصق النص العربي هنا…",color=Muted)},shape=RoundedCornerShape(15.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Cyan,unfocusedBorderColor=Panel2,focusedContainerColor=Color(0xFF0B1422),unfocusedContainerColor=Color(0xFF0B1422)))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
         TextButton(onClick={text=""}) { Icon(Icons.Default.DeleteOutline,null); Text("مسح") }
         TextButton(onClick={text="مرحبًا بك في صوت، حيث تتحول الكلمات العربية إلى تجربة صوتية جميلة وواضحة."}) { Icon(Icons.Default.AutoAwesome,null); Text("تجربة نص") }
        }
       }
      }
      Spacer(Modifier.height(14.dp))
      Surface(color=Panel,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
       Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text("الصوت المختار",fontWeight=FontWeight.SemiBold); TextButton(onClick={tab=1}) { Text("كل الأصوات") } }
        if(selectedVoice!=null) Row(verticalAlignment=Alignment.CenterVertically) {
         Box(Modifier.size(44.dp).clip(CircleShape).background(Panel2),contentAlignment=Alignment.Center) { Icon(Icons.Default.RecordVoiceOver,null,tint=Cyan) }
         Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(selectedVoice!!.label,fontWeight=FontWeight.Bold); Text("صوت عربي مثبت على جهازك",color=Muted,fontSize=11.sp) }; Icon(Icons.Default.CheckCircle,null,tint=Cyan)
        } else Text("لا توجد أصوات عربية مثبتة.",color=Muted,fontSize=12.sp)
       }
      }
      Spacer(Modifier.height(14.dp))
      Surface(color=Panel,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
       Column(Modifier.padding(16.dp)) {
        Text("ضبط الأداء",fontWeight=FontWeight.SemiBold); Spacer(Modifier.height(8.dp))
        Text("سرعة القراءة  "+String.format("%.1f",rate)+"×",color=Muted,fontSize=12.sp)
        Slider(value=rate,onValueChange={rate=it},valueRange=0.7f..1.3f,colors=SliderDefaults.colors(thumbColor=Cyan,activeTrackColor=Cyan))
        Text("طبقة الصوت  "+String.format("%.1f",pitch)+"×",color=Muted,fontSize=12.sp)
        Slider(value=pitch,onValueChange={pitch=it},valueRange=0.8f..1.2f,colors=SliderDefaults.colors(thumbColor=Cyan,activeTrackColor=Cyan))
       }
      }
      Spacer(Modifier.height(17.dp))
      Button(onClick={
       when { text.isBlank()->message="اكتب نصًا أولًا."; selectedVoice==null->message="ثبّت صوتًا عربيًا من إعدادات الجهاز أولًا."; else->{ if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); SpeechPlaybackService.start(context,text,selectedVoice!!.name,rate,pitch); message="بدأ التشغيل؛ يمكنك متابعة الاستماع في الخلفية." } }
      },modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.buttonColors(containerColor=Cyan,contentColor=Ink)) { Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(8.dp)); Text("استمع الآن",fontWeight=FontWeight.Bold,fontSize=16.sp) }
      Spacer(Modifier.height(8.dp))
      OutlinedButton(onClick={SpeechPlaybackService.stop(context);message="تم إيقاف التشغيل."},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(15.dp)) { Icon(Icons.Default.Stop,null); Spacer(Modifier.width(7.dp)); Text("إيقاف الصوت") }
      Spacer(Modifier.height(12.dp)); Text(message,color=Muted,fontSize=11.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
     }
     1 -> {
      Text("مكتبة الأصوات",fontSize=25.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("الأصوات العربية التي يوفرها محرك جهازك؛ قد تختلف جودتها ولهجتها.",color=Muted,fontSize=13.sp); Spacer(Modifier.height(18.dp))
      if(voices.isEmpty()) {
       InfoCard("لا توجد أصوات عربية","ثبّت حزمة صوت عربية من إعدادات محرك تحويل النص إلى كلام في هاتفك.",Icons.Default.CloudDownload)
       Button(onClick={SystemVoiceCatalog.openTtsSettings(context)},modifier=Modifier.fillMaxWidth()) { Text("إدارة وتنزيل أصوات الجهاز") }
      } else {
       voices.forEach { voice ->
        Surface(color=Panel,shape=RoundedCornerShape(17.dp),modifier=Modifier.fillMaxWidth().padding(bottom=10.dp),onClick={selectedVoice=voice;tab=0}) {
         Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically) {
          Icon(Icons.Default.RecordVoiceOver,null,tint=Cyan,modifier=Modifier.size(27.dp)); Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) { Text(voice.label,fontWeight=FontWeight.Bold); Text(voice.qualityLabel,color=Muted,fontSize=11.sp) }
          if(selectedVoice?.name==voice.name) Icon(Icons.Default.CheckCircle,null,tint=Cyan)
         }
        }
       }
       OutlinedButton(onClick={SystemVoiceCatalog.openTtsSettings(context)},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Download,null); Text("تنزيل أصوات إضافية") }
      }
     }
     else -> {
      Text("الإعدادات",fontSize=25.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.height(18.dp))
      InfoCard("مصمم للعربية الفصحى","لن نضيف لهجات عامية إلى مكتبة SAWT AI.",Icons.Default.Translate)
      InfoCard("الخصوصية أولًا","في هذه النسخة تتم القراءة عبر محرك الصوت المثبت على جهازك؛ لا نرسل النص إلى خادم.",Icons.Default.Security)
      InfoCard("التشغيل في الخلفية","يمكن متابعة القراءة بعد مغادرة الشاشة عبر خدمة تشغيل صوت وإشعار تحكم.",Icons.Default.Headphones)
      InfoCard("إصدار التطبيق","SAWT AI 1.0.0 · النسخة التأسيسية",Icons.Default.Info)
     }
    }
    Spacer(Modifier.height(28.dp))
   }
  }
 }
 }
}

@Composable
private fun InfoCard(title:String,description:String,icon:androidx.compose.ui.graphics.vector.ImageVector) {
 Surface(color=Panel,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(bottom=11.dp)) {
  Row(Modifier.padding(16.dp),verticalAlignment=Alignment.Top) {
   Icon(icon,null,tint=Cyan,modifier=Modifier.size(25.dp)); Spacer(Modifier.width(13.dp))
   Column { Text(title,fontWeight=FontWeight.Bold); Spacer(Modifier.height(5.dp)); Text(description,color=Muted,fontSize=12.sp,lineHeight=19.sp) }
  }
 }
}
