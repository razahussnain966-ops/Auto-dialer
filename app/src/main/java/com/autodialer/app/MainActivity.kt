package com.autodialer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etNumbers: EditText
    private lateinit var etMaxSeconds: EditText
    private lateinit var etGap: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnImport: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

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

        btnImport.setOnClickListener { importLauncher.launch("*/*") }
        btnStart.setOnClickListener {
            if (hasAllPermissions()) startRun() else permissionLauncher.launch(requiredPermissions)
        }
        btnStop.setOnClickListener { stopRun("Stopped by user") }
    }

    override fun onDestroy() {
        super.onDestroy()
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

        queue = numbers
        index = 0
        running = true
        waitingForCallToStart = false
        callActive = false
        tvLog.text = ""
        setRunningUi(true)
        registerCallStateListener()
        log("Starting: ${queue.size} numbers, ${maxSeconds}s max, ${gapSeconds}s gap")
        dialNext()
    }

    private fun stopRun(reason: String) {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        if (callActive) endCall()
        callActive = false
        waitingForCallToStart = false
        unregisterCallStateListener()
        setRunningUi(false)
        tvStatus.text = reason
        log(reason)
    }

    private fun dialNext() {
        if (!running) return
        if (index >= queue.size) {
            stopRun("Finished - all ${queue.size} numbers processed")
            return
        }
        val number = queue[index]
        tvStatus.text = "Calling ${index + 1}/${queue.size}: $number"
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
        tvStatus.text = "Next call in ${gapSeconds}s ..."
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
                    handler.postDelayed(hangUpRunnable, maxSeconds * 1000L)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (callActive) {
                    callActive = false
                    handler.removeCallbacks(hangUpRunnable)
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

    private fun setRunningUi(isRunning: Boolean) {
        btnStart.isEnabled = !isRunning
        btnStop.isEnabled = isRunning
        etNumbers.isEnabled = !isRunning
        etMaxSeconds.isEnabled = !isRunning
        etGap.isEnabled = !isRunning
        btnImport.isEnabled = !isRunning
    }

    private fun log(msg: String) {
        tvLog.append("$msg\n")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
