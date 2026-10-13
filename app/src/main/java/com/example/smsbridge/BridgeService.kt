package com.example.smsbridge

import android.app.*
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
        startForegroundNotification()
        server = embeddedServer(Netty, port = 8080) { routing() }.start(wait = false)
    }
    override fun onDestroy() { super.onDestroy(); server?.stop(1000, 2000); running = false }
    override fun onBind(i: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val ch = "bridge"
        if (Build.VERSION.SDK_INT >= 26) {
            val m = getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(ch, "SMS Bridge", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("SMS Bridge running")
            .setContentText("OTP: $currentOtp")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi).build()
        startForeground(1, n)
    }

    private fun Application.routing() {
        val json = Json { ignoreUnknownKeys = true }

        get("/status") { call.respond(mapOf("ok" to true, "paired" to paired, "phone" to Build.MODEL)) }

        post("/pair") {
            val body = json.decodeFromString<PairReq>(call.receiveText())
            if (body.otp == currentOtp) {
                sessionToken = UUID.randomUUID().toString()
                paired = true
                call.respond(mapOf("ok" to true, "token" to sessionToken))
            } else {
                call.respond(mapOf("ok" to false, "reason" to "bad_otp"))
            }
        }

        post("/schedule") {
            val body = json.decodeFromString<ScheduleReq>(call.receiveText())
            if (body.token != sessionToken) { call.respond(mapOf("ok" to false, "reason" to "unauthorized")); return@post }
            val df = SimpleDateFormat.getTimeInstance()
            showScheduledNotification(body.messages.size, df.format(Date(body.at)))
            call.respond(mapOf("ok" to true))
        }

        post("/send-one") {
            val body = json.decodeFromString<SendOneReq>(call.receiveText())
            if (body.token != sessionToken) { call.respond(mapOf("ok" to false, "reason" to "unauthorized")); return@post }
            try {
                sendSms(body.to, body.text, body.sim)
                call.respond(mapOf("ok" to true))
            } catch (e: Throwable) {
                call.respond(mapOf("ok" to false, "reason" to (e.message ?: "error")))
            }
        }
    }

    private fun showScheduledNotification(count: Int, whenStr: String) {
        val n = NotificationCompat.Builder(this, "bridge")
            .setContentTitle("Scheduled campaign")
            .setContentText("$count SMS scheduled for $whenStr")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
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
