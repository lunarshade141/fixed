package com.naturalcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NaturalCam"
        private const val REQ_PERMS = 100
    }

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private lateinit var characteristics: CameraCharacteristics

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private lateinit var textureView: TextureView
    private lateinit var btnCapture: ImageButton

    private lateinit var processor: NaturalImageProcessor

    private var cameraId: String = ""

    // 🔥 IMPORTANT BRIDGE (FIX FOR YOUR RAW ERROR)
    private var latestResult: TotalCaptureResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_camera)

        processor = NaturalImageProcessor()
        setupUI()

        if (hasPermissions()) {
            startThread()
            if (textureView.isAvailable) openCamera()
        } else {
            requestPermissions()
        }
    }

    private fun setupUI() {
        textureView = findViewById(R.id.textureView)
        btnCapture = findViewById(R.id.btnCapture)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        btnCapture.setOnClickListener {
            capturePhoto()
        }
    }

    // ========================= CAMERA =========================

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

        val jpegSize = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull {
            it.width * it.height
        } ?: Size(1920, 1080)

        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull {
            it.width * it.height
        }

        jpegReader = ImageReader.newInstance(
            jpegSize.width,
            jpegSize.height,
            ImageFormat.JPEG,
            2
        )

        jpegReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            bgHandler?.post {
                processor.processAndSaveJpeg(image, this)
                image.close()
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
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                val result = latestResult

                if (result != null) {
                    bgHandler?.post {
                        processor.saveRawDng(
                            image,
                            result,
                            characteristics,
                            this
                        )
                        image.close()
                    }
                } else {
                    image.close()
                }
            }, bgHandler)
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
            }
        }, bgHandler)
    }

    private fun createSession() {
        val surface = Surface(textureView.surfaceTexture)

        val targets = mutableListOf(surface, jpegReader!!.surface)
        rawReader?.surface?.let { targets.add(it) }

        cameraDevice!!.createCaptureSession(targets,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, bgHandler)
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val s = session ?: return

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        req.addTarget(Surface(textureView.surfaceTexture))

        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        s.setRepeatingRequest(req.build(), captureCallback, bgHandler)
    }

    private fun capturePhoto() {
        val device = cameraDevice ?: return
        val s = session ?: return

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

        req.addTarget(jpegReader!!.surface)
        rawReader?.surface?.let { req.addTarget(it) }

        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        req.set(CaptureRequest.JPEG_ORIENTATION, 90)

        s.capture(req.build(), null, bgHandler)
    }

    // ========================= CALLBACK =========================

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            latestResult = result
        }
    }

    // ========================= THREAD =========================

    private fun startThread() {
        bgThread = HandlerThread("cam").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopThread() {
        bgThread?.quitSafely()
        bgThread?.join()
    }

    // ========================= PERMISSIONS =========================

    private fun hasPermissions() = arrayOf(
        Manifest.permission.CAMERA
    ).all {
        ContextCompat.checkSelfPermission(this, it) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.CAMERA),
            REQ_PERMS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startThread()
            openCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        cameraDevice?.close()
        stopThread()
    }
}
