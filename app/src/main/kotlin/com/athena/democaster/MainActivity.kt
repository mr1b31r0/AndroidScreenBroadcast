// AndroidScreenBroadcast — MainActivity
// Created with CSK · https://nohatHacker.com
package com.athena.democaster

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.athena.democaster.databinding.ActivityMainBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val scanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        val url = result.contents
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "No QR code scanned", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            Toast.makeText(this, "QR must contain a ws:// address", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        startCaster(url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScan.setOnClickListener { launchScanner() }
        binding.btnReverse.setOnClickListener {
            startActivity(Intent(this, ReverseActivity::class.java))
        }
    }

    private fun launchScanner() {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the Athena receiver QR code")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(opts)
    }

    private fun startCaster(wsUrl: String) {
        val intent = Intent(this, CasterActivity::class.java).apply {
            putExtra(CasterActivity.EXTRA_WS_URL, wsUrl)
        }
        startActivity(intent)
    }
}
