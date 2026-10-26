package com.autodialer.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etNumbers: EditText
    private lateinit var etMaxSeconds: EditText
    private lateinit var etGap: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnImport: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etMessage: EditText
    private lateinit var etSpeakDelay: EditText
    private lateinit var cbSpeak: CompoundButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvCount: TextView
    private lateinit var tvNumberCount: TextView
    private lateinit var statusDot: View
    private lateinit var logo: View
    private val pulseAnimators = mutableListOf<ObjectAnimator>()
    private lateinit var audio: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var savedMusicVolume = -1
    private var speakDelay = 8
    private var speakEnabled = false
    private var messageText = ""

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var telephony: TelephonyManager

    private var queue: List<String> = emptyList()
    private var index = 0
    private var running = false
    private var waitingForCallToStart = false
    private var callActive = false
    private var maxSeconds = 30
    private var gapSeconds = 5

    private var modernCallback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                startRun()
            } else {
                toast("All three phone permissions are required.")
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    etNumbers.setText(text)
                } catch (e: Exception) {
                    toast("Could not read file: ${e.message}")
                }
            }
        }

    private val noCallStartedRunnable = Runnable {
        if (running && waitingForCallToStart) {
            log("No call started for ${queue.getOrNull(index)} - skipping")
            waitingForCallToStart = false
            index++
            scheduleNext()
        }
    }

    private val hangUpRunnable = Runnable {
        if (running && callActive) {
            log("Time limit reached - hanging up")
            endCall()
        }
    }

    private val speakRunnable = Runnable {
        if (running && callActive && speakEnabled) speakMessage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        etNumbers = findViewById(R.id.etNumbers)
        etMaxSeconds = findViewById(R.id.etMaxSeconds)
        etGap = findViewById(R.id.etGap)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnImport = findViewById(R.id.btnImport)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        etMessage = findViewById(R.id.etMessage)
        etSpeakDelay = findViewById(R.id.etSpeakDelay)
        cbSpeak = findViewById(R.id.cbSpeak)

        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { onSpeechDone() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { onSpeechDone() }
                    }
                })
                ttsReady = true
            }
        }

        btnImport.setOnClickListener { importLauncher.launch("*/*") }
        btnStart.setOnClickListener {
            if (hasAllPermissions()) startRun() else permissionLauncher.launch(requiredPermissions)
        }
        btnStop.setOnClickListener { stopRun("Stopped by user") }

        progress = findViewById(R.id.progressBar)
        tvCount = findViewById(R.id.tvCount)
        tvNumberCount = findViewById(R.id.tvNumberCount)
        statusDot = findViewById(R.id.statusDot)
        logo = findViewById(R.id.logo)

        etNumbers.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val n = parseNumbers(s?.toString() ?: "").size
                tvNumberCount.text = if (n == 1) "1 number" else "$n numbers"
            }
        })

        addPressAnimation(btnStart)
        addPressAnimation(btnStop)
        addPressAnimation(btnImport)
        playEntrance()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        unregisterCallStateListener()
        handler.removeCallbacksAndMessages(null)
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun parseNumbers(raw: String): List<String> =
        raw.split('\n', ',', ';')
            .map { it.trim().replace(Regex("[^0-9+]"), "") }
            .filter { it.length >= 3 }

    private fun startRun() {
        if (running) return
        val numbers = parseNumbers(etNumbers.text.toString())
        if (numbers.isEmpty()) {
            toast("Add at least one number.")
            return
        }
        maxSeconds = etMaxSeconds.text.toString().toIntOrNull()?.coerceAtLeast(5) ?: 30
        gapSeconds = etGap.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 5
        speakEnabled = cbSpeak.isChecked
        messageText = etMessage.text.toString().trim()
        speakDelay = etSpeakDelay.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: 8
        if (speakEnabled && (messageText.isEmpty() || !ttsReady)) {
            toast("Voice is not ready or the message is empty.")
            return
        }

        queue = numbers
        index = 0
        running = true
        waitingForCallToStart = false
        callActive = false
        tvLog.text = ""
        updateProgress()
        setRunningUi(true)
        registerCallStateListener()
        log("Starting: ${queue.size} numbers, ${maxSeconds}s max, ${gapSeconds}s gap")
        dialNext()
    }

    private fun stopRun(reason: String) {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        cleanupSpeech()
        if (callActive) endCall()
        callActive = false
        waitingForCallToStart = false
        unregisterCallStateListener()
        setRunningUi(false)
        setStatus(reason)
        updateProgress()
        log(reason)
    }

    private fun dialNext() {
        if (!running) return
        if (index >= queue.size) {
            stopRun("Finished - all ${queue.size} numbers processed")
            return
        }
        val number = queue[index]
        setStatus("Calling ${index + 1}/${queue.size}: $number")
        updateProgress()
        log("Calling $number")
        waitingForCallToStart = true
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
            startActivity(intent)
            handler.postDelayed(noCallStartedRunnable, 20_000)
        } catch (e: SecurityException) {
            log("Permission error: ${e.message}")
            stopRun("Permission error")
        } catch (e: Exception) {
            log("Failed to place call: ${e.message}")
            waitingForCallToStart = false
            index++
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        if (!running) return
        if (index >= queue.size) {
            stopRun("Finished - all ${queue.size} numbers processed")
            return
        }
        setStatus("Next call in ${gapSeconds}s ...")
        updateProgress()
        handler.postDelayed({ dialNext() }, gapSeconds * 1000L)
    }

    private fun endCall() {
        try {
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                telecom.endCall()
            }
        } catch (e: Exception) {
            log("Could not end call: ${e.message}")
        }
    }

    private fun onCallState(state: Int) {
        if (!running) return
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (waitingForCallToStart) {
                    waitingForCallToStart = false
                    callActive = true
                    handler.removeCallbacks(noCallStartedRunnable)
                    val limit = if (speakEnabled) maxOf(maxSeconds, speakDelay + 90) else maxSeconds
                    handler.postDelayed(hangUpRunnable, limit * 1000L)
                    if (speakEnabled) handler.postDelayed(speakRunnable, speakDelay * 1000L)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (callActive) {
                    callActive = false
                    handler.removeCallbacks(hangUpRunnable)
                    cleanupSpeech()
                    log("Call ended: ${queue.getOrNull(index)}")
                    index++
                    scheduleNext()
                }
            }
        }
    }

    private fun registerCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onCallState(state)
            }
            modernCallback = cb
            telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(this), cb)
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
            }
            legacyListener = l
            @Suppress("DEPRECATION")
            telephony.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let { telephony.unregisterTelephonyCallback(it) }
            modernCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { telephony.listen(it, PhoneStateListener.LISTEN_NONE) }
            legacyListener = null
        }
    }

    private fun speakMessage() {
        log("Speaking message")
        enableSpeaker(true)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        savedMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        tts?.speak(messageText, TextToSpeech.QUEUE_FLUSH, null, "msg")
    }

    private fun onSpeechDone() {
        if (!running || !callActive) return
        log("Message finished - ending call")
        handler.postDelayed({ if (running && callActive) endCall() }, 2000)
    }

    private fun cleanupSpeech() {
        handler.removeCallbacks(speakRunnable)
        tts?.stop()
        if (savedMusicVolume >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            savedMusicVolume = -1
        }
        enableSpeaker(false)
    }

    private fun enableSpeaker(on: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    val dev = audio.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (dev != null) audio.setCommunicationDevice(dev)
                } else {
                    audio.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = on
            }
        } catch (e: Exception) {
            log("Speaker error: ${e.message}")
        }
    }

    private fun playEntrance() {
        val ids = intArrayOf(
            R.id.header, R.id.cardStatus, R.id.cardNumbers, R.id.cardMessage,
            R.id.cardSettings, R.id.rowActions, R.id.cardLog
        )
        ids.forEachIndexed { i, id ->
            val v = findViewById<View>(id)
            v.alpha = 0f
            v.translationY = 90f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(100L + i * 90L)
                .setDuration(550)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    private fun startPulse() {
        stopPulse()
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.success))
        val logoPulse = ObjectAnimator.ofPropertyValuesHolder(
            logo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
        ).apply {
            duration = 750
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        val dotPulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.2f).apply {
            duration = 600
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        pulseAnimators.add(logoPulse)
        pulseAnimators.add(dotPulse)
    }

    private fun stopPulse() {
        pulseAnimators.forEach { it.cancel() }
        pulseAnimators.clear()
        logo.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        statusDot.alpha = 1f
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.idle_dot))
    }

    private fun setStatus(text: String) {
        if (tvStatus.text.toString() == text) return
        tvStatus.animate().cancel()
        tvStatus.animate().alpha(0f).translationY(-8f).setDuration(120).withEndAction {
            tvStatus.text = text
            tvStatus.translationY = 8f
            tvStatus.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }.start()
    }

    private fun updateProgress() {
        val total = queue.size
        val done = index.coerceAtMost(total)
        progress.max = maxOf(total, 1)
        progress.setProgressCompat(done, true)
        tvCount.text = "$done / $total"
    }

    @Suppress("ClickableViewAccessibility")
    private fun addPressAnimation(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    private fun setRunningUi(isRunning: Boolean) {
        btnStart.isEnabled = !isRunning
        btnStop.isEnabled = isRunning
        etNumbers.isEnabled = !isRunning
        etMaxSeconds.isEnabled = !isRunning
        etGap.isEnabled = !isRunning
        btnImport.isEnabled = !isRunning
        etMessage.isEnabled = !isRunning
        etSpeakDelay.isEnabled = !isRunning
        cbSpeak.isEnabled = !isRunning
        btnStart.animate().alpha(if (isRunning) 0.4f else 1f).setDuration(250).start()
        btnStop.animate().alpha(if (isRunning) 1f else 0.4f).setDuration(250).start()
        if (isRunning) startPulse() else stopPulse()
    }

    private fun log(msg: String) {
        tvLog.append("$msg\n")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
