package com.naturalcam

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.*
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
        private const val REQUEST_PERMISSIONS = 100
    }

    // Camera core
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var cameraCharacteristics: CameraCharacteristics

    // Image pipelines
    private var imageReaderJpeg: ImageReader? = null
    private var imageReaderRaw: ImageReader? = null

    // Background thread
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // UI
    private lateinit var textureView: TextureView
    private lateinit var btnCapture: ImageButton

    // Processor
    private lateinit var naturalProcessor: NaturalImageProcessor

    // IMPORTANT: store last capture metadata for DNG
    private var lastCaptureResult: TotalCaptureResult? = null

    private var cameraId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_camera)

        textureView = findViewById(R.id.textureView)
        btnCapture = findViewById(R.id.btnCapture)

        naturalProcessor = NaturalImageProcessor()

        btnCapture.setOnClickListener {
            capturePhoto()
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        if (hasPermissions()) {
            startBackgroundThread()
        } else {
            requestPermissions()
        }
    }

    // ---------------- CAMERA OPEN ----------------

    private fun openCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING)
                == CameraCharacteristics.LENS_FACING_BACK
            ) {
                cameraId = id
                cameraCharacteristics = chars
                break
            }
        }

        if (cameraId.isEmpty()) return

        val map = cameraCharacteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return

        val jpegSize = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull {
            it.width * it.height
        } ?: Size(1920, 1080)

        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull {
            it.width * it.height
        }

        imageReaderJpeg = ImageReader.newInstance(
            jpegSize.width,
            jpegSize.height,
            ImageFormat.JPEG,
            2
        )

        imageReaderJpeg!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            backgroundHandler?.post {
                naturalProcessor.processAndSaveJpeg(image, this)
                image.close()
            }
        }, backgroundHandler)

        if (rawSize != null) {
            imageReaderRaw = ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                ImageFormat.RAW_SENSOR,
                2
            )

            imageReaderRaw!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                val result = lastCaptureResult
                if (result != null) {
                    backgroundHandler?.post {
                        naturalProcessor.saveRawDng(
                            image,
                            result,
                            cameraCharacteristics,
                            this
                        )
                        image.close()
                    }
                } else {
                    image.close()
                }
            }, backgroundHandler)
        }

        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(1920, 1080)

        val surface = Surface(surfaceTexture)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession(surface)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
            }
        }, backgroundHandler)
    }

    // ---------------- SESSION ----------------

    private fun createSession(previewSurface: Surface) {
        val device = cameraDevice ?: return

        val surfaces = mutableListOf(previewSurface)
        imageReaderJpeg?.surface?.let { surfaces.add(it) }
        imageReaderRaw?.surface?.let { surfaces.add(it) }

        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                startPreview(previewSurface)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun startPreview(surface: Surface) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        req.addTarget(surface)

        session.setRepeatingRequest(req.build(), captureCallback, backgroundHandler)
    }

    // ---------------- CAPTURE ----------------

    private fun capturePhoto() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

        imageReaderJpeg?.surface?.let { req.addTarget(it) }
        imageReaderRaw?.surface?.let { req.addTarget(it) }

        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                lastCaptureResult = result
            }
        }, backgroundHandler)
    }

    // ---------------- CALLBACK ----------------

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {}

    // ---------------- THREAD ----------------

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("cam").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    // ---------------- PERMISSIONS ----------------

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_PERMISSIONS
        )
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) openCamera()
    }

    override fun onPause() {
        cameraDevice?.close()
        stopBackgroundThread()
        super.onPause()
    }
}
