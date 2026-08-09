package org.openautomaticchessboard.mobile.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.io.Closeable

/** Local Camera2 preview plus Android-supported RTSP/HTTP streams, never recorded automatically. */
class CameraController(
    private val context: Context,
    private val view: TextureView,
    private val onStatus: (String) -> Unit,
) : Closeable {
    private val thread = HandlerThread("camera-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var player: MediaPlayer? = null
    private var pendingSource: String? = null

    init {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                pendingSource?.let { source -> pendingSource = null; start(source) }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { stop(); return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    fun start(source: String) {
        stop()
        if (!view.isAvailable) {
            pendingSource = source
            onStatus("Waiting for camera surface…")
            return
        }
        if (source.trim().matches(Regex("\\d+"))) startLocal(source.trim().toInt())
        else startNetwork(source.trim())
    }

    @SuppressLint("MissingPermission")
    private fun startLocal(index: Int) {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = manager.cameraIdList
        if (ids.isEmpty()) { onStatus("No local camera is available"); return }
        val id = ids.getOrElse(index) { ids.first() }
        onStatus("Opening local camera ${ids.indexOf(id)}…")
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val texture = view.surfaceTexture ?: return
                texture.setDefaultBufferSize(view.width.coerceAtLeast(640), view.height.coerceAtLeast(480))
                val surface = Surface(texture)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        session = value
                        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        value.setRepeatingRequest(request.build(), null, handler)
                        onStatus("Local camera is live. Frames stay on this phone.")
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) = onStatus("Camera preview configuration failed")
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); camera = null; onStatus("Camera disconnected") }
            override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; onStatus("Camera failed ($error)") }
        }, handler)
    }

    private fun startNetwork(url: String) {
        if (!(url.startsWith("rtsp://", true) || url.startsWith("http://", true) || url.startsWith("https://", true))) {
            onStatus("Enter 0 for local camera, or an RTSP/HTTP URL")
            return
        }
        onStatus("Connecting to network camera…")
        val media = MediaPlayer()
        player = media
        media.setSurface(Surface(view.surfaceTexture))
        media.setDataSource(context, Uri.parse(url))
        media.setOnPreparedListener { it.isLooping = true; it.start(); onStatus("Network camera is live; URL is never logged") }
        media.setOnErrorListener { _, what, extra -> onStatus("Network camera failed ($what/$extra)"); true }
        media.prepareAsync()
    }

    fun snapshot(): Bitmap? = if (view.isAvailable) view.bitmap else null

    fun stop() {
        pendingSource = null
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        session = null
        camera = null
        player = null
        onStatus("Camera stopped")
    }

    override fun close() {
        stop()
        thread.quitSafely()
    }
}
