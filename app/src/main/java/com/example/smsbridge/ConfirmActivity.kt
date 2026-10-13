package com.example.smsbridge

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AlertDialog

class ConfirmActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val count = intent.getIntExtra("count", 0)
        val preview = intent.getStringExtra("preview") ?: ""
        AlertDialog.Builder(this)
            .setTitle("Confirm campaign?")
            .setMessage("Send $count SMS from your SIM?\n\nPreview:\n$preview")
            .setPositiveButton("✅ Send") { _, _ ->
                BridgeService.confirmResult = true
                BridgeService.pendingLatch?.countDown()
                finish()
            }
            .setNegativeButton("❌ Cancel") { _, _ ->
                BridgeService.confirmResult = false
                BridgeService.pendingLatch?.countDown()
                finish()
            }
            .setCancelable(false).show()
    }
}
