package com.example.smsbridge

import android.Manifest
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var tv: TextView
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        tv = TextView(this).apply { textSize = 17f; setPadding(48, 60, 48, 60); lineHeight = 70 }
        setContentView(tv)
        requestPerms()
        startBridge()
        refreshUI()
    }
    override fun onResume() { super.onResume(); refreshUI() }

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
            if (intIp != 0) return String.format("%d.%d.%d.%d",
                intIp and 0xff, intIp shr 8 and 0xff, intIp shr 16 and 0xff, intIp shr 24 and 0xff)
        } catch (_: Throwable) {}
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            ni.inetAddresses.toList().forEach { a ->
                if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: ""
            }
        }
        return "0.0.0.0"
    }
}
