// AndroidScreenBroadcast — ReverseService
// Created with CSK · https://nohatHacker.com
// NOT SECURE — FOR DEMO PURPOSES ONLY
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
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ReverseService : Service() {

    companion object {
        const val ACTION_SERVER_READY = "com.athena.democaster.SERVER_READY"
        const val EXTRA_SERVER_URL    = "server_url"
        const val ACTION_STOPPED      = "com.athena.democaster.REVERSE_STOPPED"
        const val HTTP_PORT           = 8080
        private const val CHANNEL_ID  = "reverse_cast"
        private const val NOTIF_ID    = 2
        private const val FRAME_MS    = 80L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?         = null
    private var captureThread: HandlerThread?     = null

    @Volatile private var latestJpeg: ByteArray?  = null
    private val streamActive  = AtomicBoolean(false)
    private val shutdownOnce  = AtomicBoolean(false)
    private var serverSocket: ServerSocket?       = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("result_code", -1) ?: return START_NOT_STICKY
        val resultData = intent.getParcelableExtra<Intent>("result_data") ?: return START_NOT_STICKY

        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        setupCapture()

        Thread(::runHttpServer, "reverse-http").also { it.isDaemon = true }.start()
        return START_NOT_STICKY
    }

    // ── screen capture ────────────────────────────────────────────────────────

    private fun setupCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels  / 2
        val h = metrics.heightPixels / 2

        captureThread = HandlerThread("rev-capture").also { it.start() }
        var lastMs = 0L

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastMs < FRAME_MS) { reader.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            lastMs = now
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane     = image.planes[0]
                val rowStride = plane.rowStride
                val pixStride = plane.pixelStride
                val rowPad    = rowStride - pixStride * w
                val bmp = Bitmap.createBitmap(w + rowPad / pixStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val frame = if (rowPad > 0) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
                val out = ByteArrayOutputStream(w * h / 4)
                frame.compress(Bitmap.CompressFormat.JPEG, 60, out)
                if (frame !== bmp) frame.recycle()
                bmp.recycle()
                latestJpeg = out.toByteArray()
            } finally { image.close() }
        }, Handler(captureThread!!.looper))

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "RevCast", w, h, 96,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    // ── HTTP server ───────────────────────────────────────────────────────────

    private fun runHttpServer() {
        try {
            serverSocket = ServerSocket(HTTP_PORT, 4)
            val ip  = getLocalIp() ?: "0.0.0.0"
            val url = "http://$ip:$HTTP_PORT"
            sendBroadcast(Intent(ACTION_SERVER_READY).putExtra(EXTRA_SERVER_URL, url))

            while (!shutdownOnce.get()) {
                val client = serverSocket!!.accept()
                Thread({ handleClient(client) }, "rev-client").also { it.isDaemon = true }.start()
            }
        } catch (e: Exception) {
            if (!shutdownOnce.get()) Log.e("ReverseService", "http error", e)
        } finally {
            shutdown()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val out    = client.getOutputStream()
            val line   = reader.readLine() ?: return
            val path   = line.split(" ").getOrElse(1) { "/" }
            while (reader.readLine()?.isNotBlank() == true) {} // drain headers

            when {
                path == "/"              -> serveIndex(out)
                path.startsWith("/stream") -> serveMjpeg(out, client)
                else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun serveIndex(out: OutputStream) {
        val html = HTML.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(html)
        out.flush()
    }

    private fun serveMjpeg(out: OutputStream, client: Socket) {
        // Only one stream client at a time
        if (!streamActive.compareAndSet(false, true)) {
            out.write("HTTP/1.1 503 Busy\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        try {
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace;boundary=frame\r\n" +
                "Connection: keep-alive\r\nCache-Control: no-cache\r\n\r\n")
                .toByteArray()
            )
            var lastFrame: ByteArray? = null
            while (!client.isClosed && !shutdownOnce.get()) {
                val frame = latestJpeg
                if (frame != null && frame !== lastFrame) {
                    lastFrame = frame
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                    out.write(frame)
                    out.write("\r\n".toByteArray())
                    out.flush()
                } else {
                    Thread.sleep(16)
                }
            }
        } catch (_: Exception) {
            // client disconnected
        } finally {
            streamActive.set(false)
            shutdown()   // one viewer, one session — close everything
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun getLocalIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is InetAddress && it.hostAddress?.contains('.') == true }
            ?.hostAddress
    } catch (_: Exception) { null }

    fun shutdown() {
        if (!shutdownOnce.compareAndSet(false, true)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread?.quitSafely()
        sendBroadcast(Intent(ACTION_STOPPED))
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown()
    }

    // ── notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Reverse cast", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Demo Cast — Serving Screen")
            .setContentText("⚠ NOT SECURE — local network demo only")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

    // ── embedded HTML page served to browser ──────────────────────────────────

    private val HTML = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Demo Feed</title>
<style>
  *{margin:0;padding:0;box-sizing:border-box}
  body{background:#000;display:flex;flex-direction:column;align-items:center;
       justify-content:center;height:100vh;font-family:monospace}
  img{max-width:100%;max-height:calc(100vh - 32px);object-fit:contain;display:block}
  .warn{color:#ff4444;font-size:11px;padding:6px 0;text-align:center;width:100%}
</style>
</head>
<body>
<div class="warn">&#9888; NOT SECURE &mdash; FOR DEMO PURPOSES ONLY</div>
<img src="/stream" alt="live screen">
</body>
</html>"""
}
