package com.example.smsbridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var tv: TextView
    private lateinit var btnWake: Button
    private var isScreenAwake = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        
        // CRASH CATCHER
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val errorTv = TextView(this).apply {
                setPadding(48, 60, 48, 60)
                setTextColor(Color.RED)
                text = "APP CRASHED:\n\n${e.message}\n\n${e.stackTraceToString()}"
            }
            setContentView(errorTv)
        }

        try {
            requestPerms()
            startBridge()
            buildUI()
        } catch (e: Exception) {
            val errorTv = TextView(this).apply {
                setPadding(48, 60, 48, 60)
                setTextColor(Color.RED)
                text = "STARTUP ERROR:\n\n${e.message}"
            }
            setContentView(errorTv)
        }
    }

    override fun onResume() { 
        super.onResume()
        try { refreshUI() } catch (_: Exception) {}
    }

    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, p.toTypedArray(), 1)
    }

    private fun startBridge() {
        if (!BridgeService.running) {
            startForegroundService(Intent(this, BridgeService::class.java))
        }
    }

    private fun buildUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60)
            setBackgroundColor(Color.parseColor("#f0f4f8"))
        }

        // Main Status Text
        tv = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#1a1f36"))
            setPadding(0, 0, 0, 40)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        layout.addView(tv)

        // Helper function to create buttons
        fun createBtn(text: String, colorHex: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                setBackgroundColor(Color.parseColor(colorHex))
                setTextColor(Color.WHITE)
                setPadding(0, 30, 0, 30)
                gravity = Gravity.CENTER
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 20, 0, 0)
                layoutParams = params
                setOnClickListener { onClick() }
            }
        }

        // 1. Refresh OTP Button
        val btnRefresh = createBtn("🔄 Refresh OTP", "#6366f1") {
            BridgeService.currentOtp = String.format("%06d", Random.nextInt(1_000_000))
            refreshUI()
            Toast.makeText(this, "New OTP generated!", Toast.LENGTH_SHORT).show()
        }
        layout.addView(btnRefresh)

        // 2. Copy to Clipboard Button
        val btnCopy = createBtn("📋 Copy IP & OTP", "#10b981") {
            val ip = getLocalIp()
            val otp = BridgeService.currentOtp
            val textToCopy = "$ip:8080 | OTP: $otp"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SMS Bridge", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
        layout.addView(btnCopy)

        // 3. Keep Screen Awake Button
        btnWake = createBtn("💡 Keep Screen Awake: OFF", "#f59e0b") {
            isScreenAwake = !isScreenAwake
            if (isScreenAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                btnWake.text = "💡 Keep Screen Awake: ON"
                btnWake.setBackgroundColor(Color.parseColor("#d97706"))
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                btnWake.text = "💡 Keep Screen Awake: OFF"
                btnWake.setBackgroundColor(Color.parseColor("#f59e0b"))
            }
        }
        layout.addView(btnWake)

        // 4. Disconnect PC Button
        val btnReset = createBtn("🔌 Disconnect PC", "#ef4444") {
            BridgeService.paired = false
            BridgeService.sessionToken = null
            refreshUI()
            Toast.makeText(this, "PC disconnected!", Toast.LENGTH_SHORT).show()
        }
        layout.addView(btnReset)

        setContentView(layout)
        refreshUI()
    }

    private fun refreshUI() {
        val ip = getLocalIp()
        val otp = BridgeService.currentOtp
        val paired = BridgeService.paired
        
        tv.text = buildString {
            append("📱 SMS Bridge Pro\n\n")
            append("Connect from PC:\n")
            append("IP:   $ip:8080\n")
            append("OTP:  $otp\n\n")
            append(if (paired) "✅ Paired with PC" else "⏳ Waiting for PC…")
            append("\n\nKeep this screen open while sending.")
        }
    }

    private fun getLocalIp(): String {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val intIp = wm.connectionInfo.ipAddress
            if (intIp != 0) {
                return String.format("%d.%d.%d.%d",
                    intIp and 0xff, 
                    intIp shr 8 and 0xff, 
                    intIp shr 16 and 0xff, 
                    intIp shr 24 and 0xff
                )
            }
        } catch (_: Throwable) {}
        
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            ni.inetAddresses.toList().forEach { a ->
                if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: ""
            }
        }
        return "127.0.0.1"
    }
}
