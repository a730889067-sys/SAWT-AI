package com.sawt.ai.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.sawt.ai.MainActivity
import com.sawt.ai.R
import java.util.Locale

class SpeechPlaybackService:Service(),TextToSpeech.OnInitListener {
 private var tts:TextToSpeech?=null
 private var pendingText=""
 private var voiceName:String?=null
 private var rate=1f
 private var pitch=1f

 override fun onCreate(){super.onCreate();createChannel();tts=TextToSpeech(this,this)}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  when(intent?.action) {
   ACTION_STOP->{tts?.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY}
   ACTION_SPEAK->{
    pendingText=intent.getStringExtra(EXTRA_TEXT).orEmpty()
    voiceName=intent.getStringExtra(EXTRA_VOICE)
    rate=intent.getFloatExtra(EXTRA_RATE,1f)
    pitch=intent.getFloatExtra(EXTRA_PITCH,1f)
    startForeground(NOTIFICATION_ID,notification())
    speakIfReady()
   }
  }
  return START_NOT_STICKY
 }
 override fun onInit(status:Int) {
  if(status==TextToSpeech.SUCCESS){tts?.language=Locale("ar");speakIfReady()} else stopSelf()
 }
 private fun speakIfReady() {
  val engine=tts?:return
  if(pendingText.isBlank())return
  voiceName?.let{name->engine.voices?.firstOrNull{it.name==name}?.let{engine.voice=it}}
  engine.setSpeechRate(rate.coerceIn(0.5f,2f));engine.setPitch(pitch.coerceIn(0.5f,2f))
  engine.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
   override fun onStart(utteranceId:String?){}
   override fun onDone(utteranceId:String?){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
   @Deprecated("Deprecated in Java") override fun onError(utteranceId:String?){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
  })
  engine.speak(pendingText,TextToSpeech.QUEUE_FLUSH,null,"sawt-"+System.currentTimeMillis())
  pendingText=""
 }
 private fun notification():Notification {
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val stop=PendingIntent.getService(this,1,Intent(this,SpeechPlaybackService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  return NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_sawt_notification)
   .setContentTitle("SAWT AI").setContentText("جارٍ قراءة النص العربي").setContentIntent(open)
   .addAction(android.R.drawable.ic_media_pause,"إيقاف",stop).setOngoing(true).setOnlyAlertOnce(true).build()
 }
 private fun createChannel() {
  if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)getSystemService(NotificationManager::class.java)
   .createNotificationChannel(NotificationChannel(CHANNEL_ID,"تشغيل الصوت",NotificationManager.IMPORTANCE_LOW))
 }
 override fun onDestroy(){tts?.stop();tts?.shutdown();super.onDestroy()}
 override fun onBind(intent:Intent?):IBinder?=null

 companion object {
  private const val CHANNEL_ID="sawt_playback"
  private const val NOTIFICATION_ID=41
  private const val ACTION_SPEAK="com.sawt.ai.SPEAK"
  private const val ACTION_STOP="com.sawt.ai.STOP"
  private const val EXTRA_TEXT="text"
  private const val EXTRA_VOICE="voice"
  private const val EXTRA_RATE="rate"
  private const val EXTRA_PITCH="pitch"
  fun start(context:Context,text:String,voice:String?,rate:Float,pitch:Float) {
   val i=Intent(context,SpeechPlaybackService::class.java).apply{action=ACTION_SPEAK;putExtra(EXTRA_TEXT,text);putExtra(EXTRA_VOICE,voice);putExtra(EXTRA_RATE,rate);putExtra(EXTRA_PITCH,pitch)}
   if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)context.startForegroundService(i) else context.startService(i)
  }
  fun stop(context:Context){context.startService(Intent(context,SpeechPlaybackService::class.java).setAction(ACTION_STOP))}
 }
}
