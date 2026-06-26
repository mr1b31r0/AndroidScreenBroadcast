// AndroidScreenBroadcast — CasterActivity
// Created with CSK · https://nohatHacker.com
package com.athena.democaster

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.athena.democaster.databinding.ActivityCasterBinding

class CasterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WS_URL   = "ws_url"
        const val ACTION_STOPPED = "com.athena.democaster.STOPPED"
        private const val REQ_PROJECTION = 1001
    }

    private lateinit var binding: ActivityCasterBinding
    private lateinit var wsUrl: String

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Server sent TERMINATE — finish this activity cleanly
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCasterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wsUrl = intent.getStringExtra(EXTRA_WS_URL) ?: run {
            finish(); return
        }
        binding.tvStatus.text = "Connecting to:\n$wsUrl"

        ContextCompat.registerReceiver(
            this, stopReceiver,
            IntentFilter(ACTION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        requestProjectionPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stopReceiver)
        stopService(Intent(this, CasterService::class.java))
    }

    private fun requestProjectionPermission() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.tvStatus.text = "Streaming to:\n$wsUrl\n\nWaiting for receiver…"
        val svcIntent = Intent(this, CasterService::class.java).apply {
            putExtra(CasterService.EXTRA_WS_URL,        wsUrl)
            putExtra(CasterService.EXTRA_RESULT_CODE,   resultCode)
            putExtra(CasterService.EXTRA_RESULT_DATA,   data)
        }
        ContextCompat.startForegroundService(this, svcIntent)
    }
}
