// AndroidScreenBroadcast — CasterService
// Created with CSK · https://nohatHacker.com
package com.athena.democaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class CasterService : Service() {

    companion object {
        const val EXTRA_WS_URL      = "ws_url"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "democast"
        private const val NOTIF_ID   = 1
        // Limit frame rate to avoid flooding the connection
        private const val FRAME_INTERVAL_MS = 80L  // ~12 fps
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?         = null
    private var webSocket: WebSocket?             = null
    private var captureThread: HandlerThread?     = null
    private var captureHandler: Handler?          = null
    private var lastFrameMs = 0L
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wsUrl      = intent?.getStringExtra(EXTRA_WS_URL)      ?: return START_NOT_STICKY
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WindowManager::class.java)).defaultDisplay.getRealMetrics(metrics)
        // Downscale for bandwidth — stream at half resolution
        screenW = metrics.widthPixels  / 2
        screenH = metrics.heightPixels / 2

        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)

        setupCapture()
        connectWebSocket(wsUrl)
        return START_NOT_STICKY
    }

    private fun setupCapture() {
        captureThread = HandlerThread("capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < FRAME_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameMs = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane     = image.planes[0]
                val rowStride = plane.rowStride
                val pixStride = plane.pixelStride
                val buffer    = plane.buffer

                val rowPad = rowStride - pixStride * screenW
                val bmp = Bitmap.createBitmap(
                    screenW + rowPad / pixStride, screenH, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)

                val frame = if (rowPad > 0)
                    Bitmap.createBitmap(bmp, 0, 0, screenW, screenH)
                else bmp

                val out = ByteArrayOutputStream(screenW * screenH / 4)
                frame.compress(Bitmap.CompressFormat.JPEG, 55, out)
                if (frame !== bmp) frame.recycle()
                bmp.recycle()

                webSocket?.send(out.toByteArray().toByteString())
            } finally {
                image.close()
            }
        }, captureHandler)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "DemoCast",
            screenW, screenH, 96,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun connectWebSocket(wsUrl: String) {
        // Route through Orbot (Tor SOCKS5 on localhost:9050).
        // createUnresolved prevents Android from resolving the .onion locally.
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // no read timeout — stream is long-lived
            .build()

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // capture already running via ImageReader listener
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (text.trim().uppercase() == "TERMINATE") {
                    shutdown(remote = true)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                shutdown(remote = false)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                shutdown(remote = false)
            }
        })
    }

    private fun shutdown(remote: Boolean) {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread?.quitSafely()

        if (remote) {
            // Server told us to stop — close the app activity too
            sendBroadcast(Intent(CasterActivity.ACTION_STOPPED))
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        webSocket?.close(1000, "service destroyed")
        captureThread?.quitSafely()
    }

    // ── notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Screen cast", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Athena Demo Cast")
            .setContentText("Broadcasting screen via Tor")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
}
