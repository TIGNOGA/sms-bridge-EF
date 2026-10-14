package com.example.smsbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.Manifest
import androidx.core.app.NotificationCompat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

class BridgeService : Service() {
    companion object {
        var running = false
        var paired = false
        var currentOtp = String.format("%06d", Random.nextInt(1_000_000))
        var sessionToken: String? = null
        var confirmResult: Boolean = false
        var pendingLatch: CountDownLatch? = null
    }

    private var server: ApplicationEngine? = null
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate() {
        super.onCreate()
        running = true
        
        try {
            startForegroundNotification()
            
            server = embeddedServer(Netty, port = 8080) {
                
                // CORS Plugin
                install(CORS) {
                    anyHost()
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Accept)
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Options)
                }

                routing {
                    
                    // STATUS ENDPOINT (Safe with Try/Catch)
                    get("/status") {
                        try {
                            var carrierName = "Unknown"
                            var batteryLevel = -1

                            // Safe Carrier Check
                            if (checkPermission(Manifest.permission.READ_PHONE_STATE)) {
                                try {
                                    val tm = getSystemService(TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                                    if (tm != null) {
                                        carrierName = tm.simOperatorName.ifEmpty { tm.networkOperatorName }.ifEmpty { "Unknown" }
                                    }
                                } catch (e: Exception) { /* Ignore */ }
                            }

                            // Safe Battery Check
                            try {
                                val bm = getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager
                                if (bm != null) {
                                    batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                }
                            } catch (e: Exception) { /* Ignore */ }

                            call.respondText(
                                """{"ok":true, "paired":$paired, "phone":"${Build.MODEL}", "carrier":"$carrierName", "battery":$batteryLevel}""",
                                ContentType.Application.Json
                            )
                        } catch (e: Exception) {
                            call.respondText("""{"ok":false, "error":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    }

                    // PAIR ENDPOINT
                    post("/pair") {
                        try {
                            val bodyText = call.receiveText()
                            val body = json.decodeFromString<PairReq>(bodyText)
                            
                            if (body.otp == currentOtp) {
                                sessionToken = UUID.randomUUID().toString()
                                paired = true
                                
                                // Notify App
                                try {
                                    val intent = Intent("SMS_BRIDGE_PAIRED").apply { setPackage("com.example.smsbridge") }
                                    sendBroadcast(intent)
                                } catch (e: Exception) {}

                                call.respondText("""{"ok":true, "token":"$sessionToken"}""", ContentType.Application.Json)
                            } else {
                                call.respondText("""{"ok":false, "reason":"bad_otp"}""", ContentType.Application.Json)
                            }
                        } catch (e: Exception) {
                            call.respondText("""{"ok":false, "reason":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    }

                    // SCHEDULE ENDPOINT
                    post("/schedule") {
                        try {
                            val bodyText = call.receiveText()
                            val body = json.decodeFromString<ScheduleReq>(bodyText)
                            if (body.token != sessionToken) {
                                call.respondText("""{"ok":false, "reason":"unauthorized"}""", ContentType.Application.Json)
                                return@post
                            }
                            val df = SimpleDateFormat.getTimeInstance()
                            showScheduledNotification(body.messages.size, df.format(Date(body.at)))
                            call.respondText("""{"ok":true}""", ContentType.Application.Json)
                        } catch (e: Exception) {
                            call.respondText("""{"ok":false, "reason":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    }

                    // SEND ONE ENDPOINT
                    post("/send-one") {
                        try {
                            val bodyText = call.receiveText()
                            val body = json.decodeFromString<SendOneReq>(bodyText)
                            if (body.token != sessionToken) {
                                call.respondText("""{"ok":false, "reason":"unauthorized"}""", ContentType.Application.Json)
                                return@post
                            }
                            sendSms(body.to, body.text, body.sim)
                            call.respondText("""{"ok":true}""", ContentType.Application.Json)
                        } catch (e: Exception) {
                            call.respondText("""{"ok":false, "reason":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    }
                }
            }.start(wait = false)
        } catch (e: Exception) {
            showCrashNotification(e.message ?: "Unknown service error")
        }
    }
    
    private fun checkPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() { 
        super.onDestroy()
        try { server?.stop(1000, 2000) } catch (_: Exception) {}
        running = false 
    }
    
    override fun onBind(i: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val ch = "bridge"
        if (Build.VERSION.SDK_INT >= 26) {
            val m = getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(
                NotificationChannel(ch, "SMS Bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("SMS Bridge running")
            .setContentText("OTP: $currentOtp")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun showCrashNotification(errorMsg: String) {
        val ch = "bridge"
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("Service Failed")
            .setContentText(errorMsg)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        getSystemService(NotificationManager::class.java).notify(99, n)
    }

    private fun showScheduledNotification(count: Int, whenStr: String) {
        val n = NotificationCompat.Builder(this, "bridge")
            .setContentTitle("Scheduled campaign")
            .setContentText("$count SMS scheduled for $whenStr")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun sendSms(to: String, text: String, sim: Int) {
        val mgr = if (Build.VERSION.SDK_INT >= 31) {
            if (sim > 0) {
                val sm = getSystemService(SubscriptionManager::class.java)
                val subs = sm.activeSubscriptionInfoList ?: error("no SIM")
                val s = subs.getOrNull(sim - 1) ?: subs.first()
                SmsManager.getSmsManagerForSubscriptionId(s.subscriptionId)
            } else SmsManager.getDefault()
        } else @Suppress("DEPRECATION") SmsManager.getDefault()
        
        val parts = mgr.divideMessage(text)
        mgr.sendMultipartTextMessage(to, null, parts, null, null)
    }

    @Serializable data class PairReq(val otp: String)
    @Serializable data class ScheduleReq(val messages: List<Msg>, val sim: Int, val token: String, val at: Long)
    @Serializable data class SendOneReq(val to: String, val text: String, val sim: Int, val token: String)
    @Serializable data class Msg(val to: String, val text: String)
}
