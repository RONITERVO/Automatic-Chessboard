package org.openautomaticchessboard.mobile.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import java.io.Closeable

/** Local Camera2 or secure network preview. Frames are never recorded automatically. */
class CameraController(
    private val context: Context,
    private val view: TextureView,
    private val onStatus: (String) -> Unit,
) : Closeable {
    private data class Resources(
        val camera: CameraDevice?,
        val session: CameraCaptureSession?,
        val player: MediaPlayer?,
        val previewSurface: Surface?,
        val networkSurface: Surface?,
    )

    private val thread = HandlerThread("camera-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var player: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var networkSurface: Surface? = null
    private var pendingSource: String? = null
    private var generation = 0L
    private var active = false
    private var closed = false

    init {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val source = synchronized(stateLock) {
                    pendingSource.also { pendingSource = null }
                }
                source?.let(::start)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { stop(); return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    fun start(source: String) {
        stop()
        val normalized = source.trim()
        val token = synchronized(stateLock) {
            if (closed) return
            generation++
            active = true
            generation
        }
        view.keepScreenOn = true
        if (!view.isAvailable) {
            synchronized(stateLock) { if (isCurrentLocked(token)) pendingSource = normalized }
            onStatus("Waiting for camera surface…")
            return
        }
        if (normalized.matches(Regex("\\d+"))) startLocal(normalized.toInt(), token)
        else startNetwork(normalized, token)
    }

    @SuppressLint("MissingPermission")
    private fun startLocal(index: Int, token: Long) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            fail(token, "No local camera hardware is available")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail(token, "Camera permission is required for local preview")
            return
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = try {
            manager.cameraIdList
        } catch (_: SecurityException) {
            fail(token, "Camera permission was denied")
            return
        } catch (error: CameraAccessException) {
            fail(token, "Camera list is unavailable (${error.reason})")
            return
        }
        if (ids.isEmpty()) { fail(token, "No local camera is available"); return }
        val id = ids.getOrElse(index) { ids.first() }
        onStatus("Opening local camera ${ids.indexOf(id)}…")
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                val accepted = synchronized(stateLock) {
                    if (isCurrentLocked(token)) { camera = device; true } else false
                }
                if (!accepted) { device.close(); return }
                // TextureView and its dimensions belong to the main thread.
                main.post {
                    val texture = view.surfaceTexture
                    val width = view.width.coerceAtLeast(640)
                    val height = view.height.coerceAtLeast(480)
                    handler.post {
                        if (texture == null) {
                            device.close()
                            fail(token, "Camera surface became unavailable")
                        } else configureLocal(device, texture, width, height, token)
                    }
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                fail(token, "Camera disconnected")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                fail(token, "Camera failed ($error)")
            }
        }
        try {
            manager.openCamera(id, callback, handler)
        } catch (_: SecurityException) {
            fail(token, "Camera permission was denied")
        } catch (error: CameraAccessException) {
            fail(token, "Could not open camera (${error.reason})")
        } catch (error: IllegalStateException) {
            fail(token, "Could not open camera: ${error.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun configureLocal(
        device: CameraDevice,
        texture: SurfaceTexture,
        width: Int,
        height: Int,
        token: Long,
    ) {
        if (!isCurrent(token, device)) { device.close(); return }
        var surface: Surface? = null
        try {
            texture.setDefaultBufferSize(width, height)
            surface = Surface(texture)
            val accepted = synchronized(stateLock) {
                if (isCurrentLocked(token) && camera === device) {
                    previewSurface = surface
                    true
                } else false
            }
            if (!accepted) { surface.release(); device.close(); return }
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    val current = synchronized(stateLock) {
                        if (isCurrentLocked(token) && camera === device) { session = value; true } else false
                    }
                    if (!current) { value.close(); return }
                    try {
                        request.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                        )
                        value.setRepeatingRequest(request.build(), null, handler)
                        onStatus("Local camera is live. Frames stay on this phone.")
                    } catch (error: CameraAccessException) {
                        value.close()
                        fail(token, "Camera preview failed (${error.reason})")
                    } catch (error: IllegalStateException) {
                        value.close()
                        fail(token, "Camera preview stopped: ${error.message}")
                    }
                }

                override fun onConfigureFailed(value: CameraCaptureSession) {
                    value.close()
                    fail(token, "Camera preview configuration failed")
                }
            }, handler)
        } catch (error: CameraAccessException) {
            surface?.release()
            device.close()
            fail(token, "Camera setup failed (${error.reason})")
        } catch (error: IllegalStateException) {
            surface?.release()
            device.close()
            fail(token, "Camera setup failed: ${error.message}")
        }
    }

    private fun startNetwork(url: String, token: Long) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || scheme !in setOf("rtsp", "http", "https")) {
            fail(token, "Enter 0, an HTTPS URL, or trusted-network RTSP")
            return
        }
        if (scheme == "http") {
            val credentialNote = if (!uri.userInfo.isNullOrBlank()) " URLs containing credentials are also rejected." else ""
            fail(token, "Insecure HTTP camera transport is blocked.$credentialNote Use HTTPS, or trusted-network RTSP.")
            return
        }
        val texture = view.surfaceTexture
        if (texture == null) { fail(token, "Camera surface became unavailable"); return }
        val surface = runCatching { Surface(texture) }.getOrElse {
            fail(token, "Could not create network camera surface: ${it.message}")
            return
        }
        val media = MediaPlayer()
        val accepted = synchronized(stateLock) {
            if (isCurrentLocked(token)) {
                player = media
                networkSurface = surface
                true
            } else false
        }
        if (!accepted) { media.release(); surface.release(); return }
        onStatus("Connecting to network camera…")
        try {
            media.setSurface(surface)
            media.setDataSource(context, uri)
            media.setOnPreparedListener { prepared ->
                if (!isCurrent(token, prepared)) { releasePlayer(prepared, surface); return@setOnPreparedListener }
                runCatching { prepared.isLooping = true; prepared.start() }
                    .onSuccess { onStatus("Network camera is live; URL is never logged") }
                    .onFailure { fail(token, "Network camera could not start: ${it.message}") }
            }
            media.setOnErrorListener { failed, what, extra ->
                if (isCurrent(token, failed)) fail(token, "Network camera failed ($what/$extra)")
                true
            }
            media.prepareAsync()
        } catch (error: Exception) {
            fail(token, "Network camera could not start: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun snapshot(): Bitmap? = if (view.isAvailable) view.bitmap else null

    fun stop() {
        view.keepScreenOn = false
        val resources = synchronized(stateLock) {
            generation++
            active = false
            pendingSource = null
            takeResourcesLocked()
        }
        release(resources)
        onStatus("Camera stopped")
    }

    private fun fail(token: Long, message: String) {
        val resources = synchronized(stateLock) {
            if (!isCurrentLocked(token)) return
            active = false
            takeResourcesLocked()
        }
        main.post { view.keepScreenOn = false }
        release(resources)
        onStatus(message)
    }

    private fun takeResourcesLocked(): Resources {
        val resources = Resources(camera, session, player, previewSurface, networkSurface)
        camera = null
        session = null
        player = null
        previewSurface = null
        networkSurface = null
        return resources
    }

    private fun release(resources: Resources) {
        runCatching { resources.session?.close() }
        runCatching { resources.camera?.close() }
        resources.player?.let { releasePlayer(it, resources.networkSurface) }
        if (resources.player == null) runCatching { resources.networkSurface?.release() }
        runCatching { resources.previewSurface?.release() }
    }

    private fun releasePlayer(media: MediaPlayer, surface: Surface?) {
        runCatching { media.stop() }
        runCatching { media.reset() }
        runCatching { media.release() }
        runCatching { surface?.release() }
    }

    private fun isCurrent(token: Long, device: CameraDevice): Boolean =
        synchronized(stateLock) { isCurrentLocked(token) && camera === device }

    private fun isCurrent(token: Long, media: MediaPlayer): Boolean =
        synchronized(stateLock) { isCurrentLocked(token) && player === media }

    private fun isCurrentLocked(token: Long): Boolean = active && !closed && generation == token

    override fun close() {
        synchronized(stateLock) { closed = true }
        stop()
        main.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
