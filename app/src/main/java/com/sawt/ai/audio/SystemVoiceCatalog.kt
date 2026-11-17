package com.sawt.ai.audio

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

data class VoiceOption(val name:String,val label:String,val qualityLabel:String)

class SystemVoiceCatalog(private val context:Context) {
 fun arabicVoices():List<VoiceOption> {
  val tts=TextToSpeech(context) {}
  return try {
   tts.voices.orEmpty().filter { it.locale.language=="ar" && !it.isNetworkConnectionRequired }
    .sortedWith(compareByDescending<Voice>{it.quality}.thenBy{it.name})
    .map { v ->
     val country=v.locale.displayCountry
     VoiceOption(v.name,if(country.isNotBlank()) "العربية الفصحى · $country" else "صوت عربي · "+v.name,
      if(v.quality>=Voice.QUALITY_HIGH) "جودة عالية · مثبت على الجهاز" else "صوت مثبت على الجهاز")
    }.distinctBy{it.name}
  } finally { tts.shutdown() }
 }
 companion object {
  fun openTtsSettings(context:Context) {
   val i=Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   try { context.startActivity(i) } catch (_:Exception) { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  }
 }
}
