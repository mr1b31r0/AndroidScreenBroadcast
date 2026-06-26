// AndroidScreenBroadcast — ReverseActivity
// Created with CSK · https://nohatHacker.com
// NOT SECURE — FOR DEMO PURPOSES ONLY
package com.athena.democaster

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.athena.democaster.databinding.ActivityReverseBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class ReverseActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PROJECTION = 2001
    }

    private lateinit var binding: ActivityReverseBinding

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ReverseService.ACTION_SERVER_READY -> {
                    val url = intent.getStringExtra(ReverseService.EXTRA_SERVER_URL) ?: return
                    showUrl(url)
                }
                ReverseService.ACTION_STOPPED -> finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityReverseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filter = IntentFilter().apply {
            addAction(ReverseService.ACTION_SERVER_READY)
            addAction(ReverseService.ACTION_STOPPED)
        }
        ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        binding.tvStatus.text = "Starting HTTP server…"

        val mgr = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture denied", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        ContextCompat.startForegroundService(this,
            Intent(this, ReverseService::class.java).apply {
                putExtra("result_code", resultCode)
                putExtra("result_data", data)
            }
        )
    }

    private fun showUrl(url: String) {
        binding.tvStatus.text = url
        binding.tvHint.text = "Open any browser on the demo laptop and navigate to this address\n\n⚠ NOT SECURE — local network only"

        val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512)
        val bmp  = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) for (y in 0 until 512)
            bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
        binding.ivQr.setImageBitmap(bmp)
        binding.ivQr.visibility = android.view.View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(eventReceiver)
        stopService(Intent(this, ReverseService::class.java))
    }
}
