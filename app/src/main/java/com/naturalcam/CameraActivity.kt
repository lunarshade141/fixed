package com.naturalcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NaturalCam"
        private const val REQ = 100
    }

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private lateinit var textureView: TextureView
    private lateinit var btnCapture: ImageButton

    private lateinit var processor: NaturalImageProcessor

    private var cameraId = ""
    private var backgroundThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private lateinit var characteristics: CameraCharacteristics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_camera)

        textureView = findViewById(R.id.textureView)
        btnCapture = findViewById(R.id.btnCapture)

        processor = NaturalImageProcessor()

        textureView.surfaceTextureListener = surfaceListener

        btnCapture.setOnClickListener {
            Log.d(TAG, "Capture clicked")
            capturePhoto()
        }

        if (hasPermission()) {
            startThread()
            if (textureView.isAvailable) openCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ)
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        for (id in cameraManager.cameraIdList) {
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                cameraId = id
                characteristics = c
                break
            }
        }

        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return

        val jpegSize = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width * it.height }!!
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.firstOrNull()

        jpegReader = ImageReader.newInstance(
            jpegSize.width,
            jpegSize.height,
            ImageFormat.JPEG,
            2
        )

        jpegReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            bgHandler?.post {
                processor.processAndSaveJpeg(img, this)
                img.close()
            }
        }, bgHandler)

        if (rawSize != null) {
            rawReader = ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                ImageFormat.RAW_SENSOR,
                2
            )

            rawReader!!.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                bgHandler?.post {
                    processor.saveRawDng(img, characteristics, this)
                    img.close()
                }
            }, bgHandler)
        }

        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(1920, 1080)

        val previewSurface = Surface(surfaceTexture)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession(previewSurface)
            }

            override fun onDisconnected(camera: CameraDevice) {}
            override fun onError(camera: CameraDevice, error: Int) {}
        }, bgHandler)
    }

    private fun createSession(preview: Surface) {
        val device = cameraDevice ?: return

        val surfaces = mutableListOf(preview)
        jpegReader?.surface?.let { surfaces.add(it) }
        rawReader?.surface?.let { surfaces.add(it) }

        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                startPreview(preview)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, bgHandler)
    }

    private fun startPreview(preview: Surface) {
        val device = cameraDevice ?: return
        val s = session ?: return

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        req.addTarget(preview)

        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        s.setRepeatingRequest(req.build(), null, bgHandler)
    }

    private fun capturePhoto() {
        val device = cameraDevice ?: return
        val s = session ?: return

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

        jpegReader?.surface?.let { req.addTarget(it) }
        rawReader?.surface?.let { req.addTarget(it) }

        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        s.capture(req.build(), null, bgHandler)
    }

    private fun startThread() {
        backgroundThread = HandlerThread("cam").also { it.start() }
        bgHandler = Handler(backgroundThread!!.looper)
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        startThread()
        if (textureView.isAvailable) openCamera()
    }

    override fun onPause() {
        cameraDevice?.close()
        session?.close()
        backgroundThread?.quitSafely()
        super.onPause()
    }
}
