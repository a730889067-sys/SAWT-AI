package com.sawt.ai.audio

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class VoiceOption(val name:String,val label:String,val qualityLabel:String)

class SystemVoiceCatalog(private val context:Context) {
 suspend fun arabicVoices():List<VoiceOption> = suspendCancellableCoroutine { continuation ->
  var engine:TextToSpeech? = null
  engine = TextToSpeech(context) { status ->
   val result = if (status == TextToSpeech.SUCCESS) {
    engine?.voices.orEmpty()
     .filter { it.locale.language == "ar" }
     .sortedWith(compareByDescending<Voice>{it.quality}.thenBy{it.name})
     .map { v ->
      val country = v.locale.displayCountry
      val label = if (country.isNotBlank()) "صوت عربي · $country" else "صوت عربي · " + v.name
      val quality = when {
       v.isNetworkConnectionRequired -> "يتطلب اتصالًا بالإنترنت"
       v.quality >= Voice.QUALITY_HIGH -> "جودة عالية · مثبت على الجهاز"
       else -> "مثبت على الجهاز"
      }
      VoiceOption(v.name,label,quality)
     }.distinctBy { it.name }
   } else emptyList()
   engine?.shutdown()
   if (continuation.isActive) continuation.resume(result)
  }
  continuation.invokeOnCancellation { engine?.shutdown() }
 }
 companion object {
  fun openTtsSettings(context:Context) {
   val i=Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   try { context.startActivity(i) }
   catch (_:Exception) { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  }
 }
}
