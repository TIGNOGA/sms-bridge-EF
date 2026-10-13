package com.example.smsbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
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

    override fun onCreate() {
        super.onCreate()
        running = true
        
        try {
            startForegroundNotification()
            
            server = embeddedServer(Netty, port = 8080) {
                
                // CORS Plugin to allow PC browser to connect
                install(CORS) {
                    anyHost()
                    allowHeader(HttpHeaders.ContentType)
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Options)
                }

                routing {
                    val json = Json { ignoreUnknownKeys = true }

                    get("/status") {
                        call.respond(mapOf("ok" to true, "paired" to paired, "phone" to Build.MODEL))
                    }

                    post("/pair") {
                        try {
                            val body = json.decodeFromString<PairReq>(call.receiveText())
                            if (body.otp == currentOtp) {
                                sessionToken = UUID.randomUUID().toString()
                                paired = true
                                
                                // Send broadcast to notify MainActivity immediately
                                val intent = Intent("SMS_BRIDGE_PAIRED").apply {
                                    setPackage("com.example.smsbridge")
                                }
                                this@BridgeService.sendBroadcast(intent)
                                
                                call.respond(mapOf("ok" to true, "token" to sessionToken))
                            } else {
                                call.respond(mapOf("ok" to false, "reason" to "bad_otp"))
                            }
                        } catch (e: Exception) {
                            call.respond(mapOf("ok" to false, "reason" to (e.message ?: "error")))
                        }
                    }

                    post("/schedule") {
                        try {
                            val body = json.decodeFromString<ScheduleReq>(call.receiveText())
                            if (body.token != sessionToken) {
                                call.respond(mapOf("ok" to false, "reason" to "unauthorized"))
                                return@post
                            }
                            val df = SimpleDateFormat.getTimeInstance()
                            showScheduledNotification(body.messages.size, df.format(Date(body.at)))
                            call.respond(mapOf("ok" to true))
                        } catch (e: Exception) {
                            call.respond(mapOf("ok" to false, "reason" to (e.message ?: "error")))
                        }
                    }

                    post("/send-one") {
                        try {
                            val body = json.decodeFromString<SendOneReq>(call.receiveText())
                            if (body.token != sessionToken) {
                                call.respond(mapOf("ok" to false, "reason" to "unauthorized"))
                                return@post
                            }
                            sendSms(body.to, body.text, body.sim)
                            call.respond(mapOf("ok" to true))
                        } catch (e: Exception) {
                            call.respond(mapOf("ok" to false, "reason" to (e.message ?: "error")))
                        }
                    }
                }
            }.start(wait = false)
        } catch (e: Exception) {
            showCrashNotification(e.message ?: "Unknown service error")
        }
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
            .setContentTitle("Service Failed to Start")
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

    private fun askConfirmation(count: Int, preview: String): Boolean = runBlocking {
        confirmResult = false
        val latch = CountDownLatch(1)
        pendingLatch = latch
        val i = Intent(this@BridgeService, ConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("count", count)
            putExtra("preview", preview.take(200))
        }
        startActivity(i)
        latch.await()
        confirmResult
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
