package com.example.helloworld

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val perms = arrayOf(
        RECORD_AUDIO, READ_CONTACTS, CALL_PHONE, READ_PHONE_STATE,
        READ_CALL_LOG, ANSWER_PHONE_CALLS, CAMERA, POST_NOTIFICATIONS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "জার্ভিস চালু হচ্ছে…"
            textSize = 20f
            setPadding(48, 96, 48, 48)
        })
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        // ব্যাকগ্রাউন্ড থেকে ক্যামেরা/কল অ্যাপ খুলতে "Display over other apps" লাগে
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        startForegroundService(Intent(this, JarvisService::class.java))
    }
}

@SuppressLint("MissingPermission")
class JarvisService : Service() {

    companion object {
        const val API_KEY = "YOUR_API_KEY"   // console.anthropic.com থেকে নেওয়া কী
        const val CITY = "YOUR_CITY"         // ওয়েদারের জন্য আপনার শহর
        val WAKE = listOf("জার্ভিস", "জারভিস", "জার্ভেস", "jarvis")
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var awake = false
    private var askingAboutCall = false
    private var speaking = false
    private var ringing = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val ch = "jarvis"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("জার্ভিস শুনছে")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        tts = TextToSpeech(this) { tts.language = Locale("bn", "BD") }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { done() }
            private fun done() = main.post { speaking = false; listen() }
        })

        ContextCompat.registerReceiver(
            this, callReceiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        listen()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts.shutdown()
        unregisterReceiver(callReceiver)
        super.onDestroy()
    }

    // ---------- শোনা ----------
    private fun listen() {
        if (speaking) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) listen() else handle(t)
                }
                override fun onError(e: Int) { main.postDelayed({ listen() }, 700) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            )
        }
    }

    private fun speak(text: String) {
        speaking = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    // ---------- কথা বোঝা ----------
    private fun handle(raw: String) {
        val t = raw.lowercase()

        if (askingAboutCall) {
            askingAboutCall = false
            val tm = getSystemService(TelecomManager::class.java)
            if (listOf("হ্যাঁ", "হ্যা", "ধরো", "yes").any { t.contains(it) }) tm.acceptRingingCall()
            else if (listOf("না", "কেটে", "no").any { t.contains(it) }) tm.endCall()
            listen()
            return
        }

        if (!awake) {
            val w = WAKE.firstOrNull { t.contains(it) } ?: return listen()
            awake = true
            val rest = t.substringAfter(w).trim()
            if (rest.isEmpty()) speak("জি, বলুন") else command(rest)
        } else {
            command(t)
        }
    }

    private fun command(t: String) {
        awake = false
        val call = Regex("(.+?)\\s*কে\\s*(ফোন|কল)").find(t) ?: Regex("call\\s+(.+)").find(t)
        when {
            call != null -> callContact(call.groupValues[1].trim())
            t.contains("ক্যামেরা") || t.contains("camera") -> {
                startActivity(
                    Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                listen()
            }
            else -> askClaude(t)
        }
    }

    // ---------- ফোন করা ----------
    private fun callContact(name: String) {
        var num: String? = null
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) num = it.getString(0) }

        if (num == null) { speak("$name নামে কাউকে পেলাম না"); return }
        speak("$name কে ফোন করছি")
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- ইনকামিং কল ----------
    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return
                    if (ringing) return
                    ringing = true
                    askingAboutCall = true
                    recognizer?.cancel()
                    speak("${nameOf(num)} ফোন করছে, ধরবো?")
                }
                else -> ringing = false
            }
        }
    }

    private fun nameOf(num: String): String {
        contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return num
    }

    // ---------- ক্লড (প্রশ্নের উত্তর, ওয়েদার, পড়াশোনা) ----------
    private fun askClaude(q: String) {
        speak("একটু অপেক্ষা করুন")
        Thread {
            val answer = try {
                val body = JSONObject()
                    .put("model", "claude-sonnet-5-5")
                    .put("max_tokens", 600)
                    .put(
                        "system",
                        "তুমি জার্ভিস, আনিকের ব্যক্তিগত ভয়েস অ্যাসিস্ট্যান্ট। আনিকের শহর: $CITY। " +
                            "সহজ বাংলায় ছোট উত্তর দাও, মার্কডাউন বা তালিকা ব্যবহার করো না। " +
                            "ওয়েদার বা নতুন তথ্যের জন্য ওয়েব সার্চ ব্যবহার করো। পড়াশোনার প্রশ্নে ধাপে ধাপে বুঝিয়ে দাও।"
                    )
                    .put("tools", JSONArray().put(
                        JSONObject().put("type", "web_search_20250305").put("name", "web_search")
                    ))
                    .put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", q)
                    ))
                val req = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = JSONObject(http.newCall(req).execute().use { it.body!!.string() })
                val parts = res.getJSONArray("content")
                (0 until parts.length()).map { parts.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString(" ") { it.getString("text") }
                    .ifBlank { "উত্তর পেলাম না" }
            } catch (e: Exception) {
                "ইন্টারনেটে সমস্যা হয়েছে"
            }
            main.post { speak(answer) }
        }.start()
    }
}
